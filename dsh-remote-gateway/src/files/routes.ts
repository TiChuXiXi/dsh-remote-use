/**
 * 工作区文件服务：`/fs/list`、`/fs/download`、`/fs/upload`。
 *
 * 设计取舍：
 *   - 直接用 node:fs 而**不**经过 dsh 的工作区服务。理由：网关要服务的是工作区里
 *     的任意成品（刚构建出来的 APK、导出的 PDF 等），需要精确控制 Range、流式
 *     读写和原子落盘；这些都是 node:fs 的直接能力。安全性由 PathGuard 的双重
 *     校验保证，且网关本身已经过认证。
 *   - 三种上传模式，客户端按需选：
 *       A) multipart/form-data     —— 浏览器 / curl 方便
 *       B) 分块（X-Chunk-*）        —— 可续传，大文件首选
 *       C) 原始体（?path= + octet-stream）—— 最简单，一次性
 *     三种都走「临时文件 → rename」的原子落盘，落盘完成前目标路径绝不出现半成品。
 */
import { createHash } from 'node:crypto'
import { once } from 'node:events'
import { createReadStream, createWriteStream } from 'node:fs'
import { copyFile, mkdir, readdir, rename, rm, stat, statfs } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { basename, dirname, join } from 'node:path'
import { pipeline } from 'node:stream/promises'
import { randomBytes } from 'node:crypto'
import type { GatewayConfig } from '../config.js'
import type { AuditLog } from '../log.js'
import { errText, warn } from '../log.js'
import { clientIp, sendError, sendJson } from '../http-util.js'
import { PathDeniedError, PathGuard, TargetNotFoundError, WrongTypeError } from './guard.js'
import { contentDisposition, mimeFor, shouldForceAttachment } from './mime.js'

/** 目录列表上限。 */
const MAX_LIST_ENTRIES = 5000

/** multipart 里普通字段（非文件）的大小上限。 */
const MAX_FIELD_BYTES = 64 * 1024

/** 分块上传的临时会话保留时间。 */
const CHUNK_SESSION_TTL_MS = 24 * 60 * 60 * 1000

interface FsRoutesDeps {
  config: GatewayConfig
  guard: PathGuard
  audit: AuditLog
}

/** 由 Content-Range / Range 解析出的单段范围。 */
interface ByteRange {
  start: number
  end: number
}

/**
 * 解析单段 Range 头。
 * 只支持单段（多段返回 null，调用方回退为 200 全量 —— 这是 RFC 允许的行为）。
 */
export function parseRange(header: string | undefined, size: number): ByteRange | 'unsatisfiable' | null {
  if (typeof header !== 'string' || header.length === 0) return null
  const match = /^bytes=(\d*)-(\d*)$/u.exec(header.trim())
  if (match === null) return null

  const rawStart = match[1] ?? ''
  const rawEnd = match[2] ?? ''
  if (rawStart === '' && rawEnd === '') return null

  if (rawStart === '') {
    // 后缀形式：bytes=-N 表示最后 N 字节。
    const suffix = Number(rawEnd)
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return 'unsatisfiable'
    if (size === 0) return 'unsatisfiable'
    const start = Math.max(0, size - suffix)
    return { start, end: size - 1 }
  }

  const start = Number(rawStart)
  if (!Number.isSafeInteger(start) || start < 0) return 'unsatisfiable'
  if (start >= size) return 'unsatisfiable'

  if (rawEnd === '') return { start, end: size - 1 }

  const end = Number(rawEnd)
  if (!Number.isSafeInteger(end) || end < start) return 'unsatisfiable'
  return { start, end: Math.min(end, size - 1) }
}

/** multipart 分段的头信息。 */
interface PartInfo {
  name: string
  filename: string | null
}

/** 解析 Content-Disposition 里的 name / filename。 */
function parseContentDisposition(value: string): { name: string; filename: string | null } {
  const nameMatch = /;\s*name="((?:[^"\\]|\\.)*)"/u.exec(value)
  const fileMatch = /;\s*filename="((?:[^"\\]|\\.)*)"/u.exec(value)
  const name = nameMatch?.[1]?.replace(/\\(.)/gu, '$1') ?? ''
  const filename = fileMatch?.[1]?.replace(/\\(.)/gu, '$1') ?? null
  return { name, filename }
}

/** 一个分段接收器。 */
interface PartSink {
  onData(chunk: Buffer): Promise<void> | void
  onEnd(): Promise<void> | void
}

/** 分段起始回调。 */
type PartStart = (info: PartInfo, headers: Map<string, string>) => PartSink | null

/** multipart 解析状态。 */
type MultipartState = 'preamble' | 'headers' | 'body' | 'epilogue'

/**
 * 流式 multipart/form-data 状态机。
 *
 * 用推模型逐块扫描边界，并且**保留尾部一段**（长度为分隔符长度减一）不往下发 ——
 * 否则一个恰好跨 chunk 边界的分隔符会被当成正文写进文件。这是流式 multipart
 * 解析最容易出错的地方。
 *
 * 写成类而不是闭包里的局部变量：TypeScript 的控制流分析不跟踪在闭包里被改写的
 * 局部变量，会让状态判断退化到 `never`。
 */
class MultipartParser {
  private static readonly CRLFCRLF = Buffer.from('\r\n\r\n', 'utf8')

  private pending = Buffer.alloc(0)
  private state: MultipartState = 'preamble'
  private sink: PartSink | null = null
  private totalBytes = 0
  private aborted = false

  private readonly first: Buffer
  private readonly delim: Buffer

  constructor(
    boundary: string,
    private readonly totalLimit: number,
    private readonly onPartStart: PartStart,
  ) {
    this.first = Buffer.from(`--${boundary}`, 'utf8')
    this.delim = Buffer.from(`\r\n--${boundary}`, 'utf8')
  }

  /** 已经结束（正常收尾或超限中止）。 */
  get done(): boolean {
    return this.aborted || this.state === 'epilogue'
  }

  /** 投入一块数据，尽可能推进状态机。 */
  async push(chunk: Buffer): Promise<void> {
    this.pending = this.pending.length === 0 ? Buffer.from(chunk) : Buffer.concat([this.pending, chunk])
    let progress = true
    while (progress && !this.aborted) progress = await this.consume()
  }

  /** 收尾，返回统计。 */
  async finish(): Promise<{ totalBytes: number; aborted: boolean }> {
    if (!this.aborted && this.state === 'body' && this.sink !== null) {
      const sink = this.sink
      this.sink = null
      await sink.onEnd()
    }
    return { totalBytes: this.totalBytes, aborted: this.aborted }
  }

  /** 推进一步。返回 true 表示还有进展，需要再次调用。 */
  private async consume(): Promise<boolean> {
    if (this.state === 'preamble') {
      const at = this.pending.indexOf(this.first)
      if (at === -1) {
        // 前导内容丢弃，但保留可能的半个分隔符。
        const keep = this.first.length
        if (this.pending.length > keep) this.pending = this.pending.subarray(this.pending.length - keep)
        return false
      }
      this.pending = this.pending.subarray(at + this.first.length)
      if (this.pending.subarray(0, 2).toString('latin1') === '--') {
        this.state = 'epilogue'
        return false
      }
      if (this.pending.length < 2) return false
      this.pending = this.pending.subarray(2)
      this.state = 'headers'
      return true
    }

    if (this.state === 'headers') {
      const at = this.pending.indexOf(MultipartParser.CRLFCRLF)
      if (at === -1) return false
      const rawHeaders = this.pending.subarray(0, at).toString('utf8')
      this.pending = this.pending.subarray(at + MultipartParser.CRLFCRLF.length)

      const headers = new Map<string, string>()
      for (const line of rawHeaders.split('\r\n')) {
        const colon = line.indexOf(':')
        if (colon === -1) continue
        headers.set(line.slice(0, colon).trim().toLowerCase(), line.slice(colon + 1).trim())
      }
      const info = parseContentDisposition(headers.get('content-disposition') ?? '')
      this.sink = this.onPartStart({ name: info.name, filename: info.filename }, headers)
      this.state = 'body'
      return true
    }

    if (this.state === 'body') {
      const at = this.pending.indexOf(this.delim)
      if (at === -1) {
        // 边界可能跨 chunk：留出 delim.length - 1 字节不发。
        const keep = this.delim.length - 1
        if (this.pending.length > keep) {
          const emit = this.pending.subarray(0, this.pending.length - keep)
          this.pending = this.pending.subarray(this.pending.length - keep)
          if (this.sink !== null && emit.length > 0) {
            this.totalBytes += emit.length
            if (this.totalBytes > this.totalLimit) {
              this.aborted = true
              return false
            }
            await this.sink.onData(Buffer.from(emit))
          }
        }
        return false
      }

      if (this.sink !== null) {
        const sink = this.sink
        this.sink = null
        if (at > 0) {
          const emit = this.pending.subarray(0, at)
          this.totalBytes += emit.length
          if (this.totalBytes > this.totalLimit) {
            this.aborted = true
            return false
          }
          await sink.onData(Buffer.from(emit))
        }
        await sink.onEnd()
      }

      this.pending = this.pending.subarray(at + this.delim.length)
      if (this.pending.length < 2) return false
      if (this.pending.subarray(0, 2).toString('latin1') === '--') {
        this.state = 'epilogue'
        return false
      }
      this.pending = this.pending.subarray(2)
      this.state = 'headers'
      return true
    }

    return false
  }
}

/** 驱动 {@link MultipartParser} 读完整个请求体。 */
async function parseMultipart(
  body: AsyncIterable<Buffer>,
  boundary: string,
  totalLimit: number,
  onPartStart: PartStart,
): Promise<{ totalBytes: number; aborted: boolean }> {
  const parser = new MultipartParser(boundary, totalLimit, onPartStart)
  for await (const chunk of body) {
    if (parser.done) break
    await parser.push(chunk)
  }
  return parser.finish()
}

/** 生成一个同目录内的唯一临时文件名。 */
function tempNameFor(targetDir: string, label: string): string {
  const token = randomBytes(8).toString('hex')
  return join(targetDir, `.dsh-upload-${label}-${token}.part`)
}

/** 工作区文件路由。 */
export class FsRoutes {
  private readonly stagingDir: string
  private readonly chunkRoot: string

  constructor(private readonly deps: FsRoutesDeps) {
    this.stagingDir = join(deps.config.stateDir, 'uploads')
    this.chunkRoot = join(this.stagingDir, 'chunks')
  }

  /** 处理 `/fs/*`。 */
  async handle(req: IncomingMessage, res: ServerResponse, url: URL, user: { sub: string }): Promise<boolean> {
    try {
      switch (url.pathname) {
        case '/fs/list':
          await this.handleList(req, res, url)
          return true
        case '/fs/download':
          await this.handleDownload(req, res, url, user.sub)
          return true
        case '/fs/upload':
          await this.handleUpload(req, res, url, user.sub)
          return true
        default:
          sendError(res, 404, 'not_found', `未知的文件路由 ${url.pathname}`)
          return true
      }
    } catch (error) {
      this.replyForError(res, url, error)
      return true
    }
  }

  /** 把守卫抛出的错误翻译成统一响应。 */
  private replyForError(res: ServerResponse, url: URL, error: unknown): void {
    if (res.headersSent) {
      res.destroy()
      return
    }
    if (error instanceof PathDeniedError) {
      this.deps.audit.record({ action: 'fs.denied', path: url.searchParams.get('path') ?? '', status: 403, detail: errText(error) })
      sendError(res, 403, 'path_denied', '路径越界或非法')
      return
    }
    if (error instanceof TargetNotFoundError) {
      sendError(res, 404, 'not_found', '目标不存在')
      return
    }
    if (error instanceof WrongTypeError) {
      sendError(res, 400, 'bad_request', errText(error))
      return
    }
    warn(`文件路由失败 ${url.pathname}: ${errText(error)}`)
    sendError(res, 500, 'internal', '文件服务内部错误')
  }

  // ------------------------------------------------------------------ 列表

  private async handleList(req: IncomingMessage, res: ServerResponse, url: URL): Promise<void> {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      sendError(res, 405, 'method_not_allowed')
      return
    }
    const rel = url.searchParams.get('path') ?? ''
    const dir = await this.deps.guard.resolveDirForRead(rel)

    const dirents = await readdir(dir, { withFileTypes: true })
    const truncated = dirents.length > MAX_LIST_ENTRIES
    const slice = truncated ? dirents.slice(0, MAX_LIST_ENTRIES) : dirents

    const entries = await Promise.all(
      slice.map(async (dirent) => {
        const abs = join(dir, dirent.name)
        // lstat 语义：符号链接报告自身信息，不跟随（避免泄露链接指向的外部文件大小）。
        const info = await stat(abs).catch(() => null)
        const isDir = dirent.isDirectory()
        const isLink = dirent.isSymbolicLink()
        const type = isDir ? 'directory' : isLink && info?.isDirectory() === true ? 'directory' : 'file'
        return {
          name: dirent.name,
          path: this.deps.guard.relative(abs),
          type,
          size: type === 'file' ? (info?.size ?? 0) : 0,
          mtimeMs: info === null ? 0 : Math.floor(info.mtimeMs),
          mime: type === 'file' ? mimeFor(dirent.name) : null,
          symlink: isLink,
        }
      }),
    )

    entries.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'directory' ? -1 : 1
      return a.name.localeCompare(b.name, 'en')
    })

    this.deps.audit.record({ action: 'fs.list', path: this.deps.guard.relative(dir), status: 200 })
    sendJson(res, 200, {
      ok: true,
      path: this.deps.guard.relative(dir),
      root: this.deps.guard.root,
      entries,
      ...(truncated ? { truncated: true } : {}),
    })
  }

  // ------------------------------------------------------------------ 下载

  private async handleDownload(req: IncomingMessage, res: ServerResponse, url: URL, user: string): Promise<void> {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      sendError(res, 405, 'method_not_allowed')
      return
    }
    const rel = url.searchParams.get('path')
    if (rel === null || rel.length === 0) {
      sendError(res, 400, 'bad_request', '缺少 path 参数')
      return
    }

    const abs = await this.deps.guard.resolveFileForRead(rel)
    const info = await stat(abs)
    const size = info.size
    const name = basename(abs)
    const mime = mimeFor(name)
    const etag = `"${String(size)}-${String(Math.floor(info.mtimeMs))}"`

    const baseHeaders: Record<string, string> = {
      'content-type': mime,
      'accept-ranges': 'bytes',
      etag,
      'last-modified': new Date(info.mtimeMs).toUTCString(),
      'content-disposition': contentDisposition(name, shouldForceAttachment(mime) || true),
      'cache-control': 'private, no-store',
      'x-content-type-options': 'nosniff',
    }

    if (req.headers['if-none-match'] === etag) {
      res.writeHead(304, { etag, 'cache-control': 'private, no-store' })
      res.end()
      return
    }

    const range = parseRange(req.headers['range'], size)
    if (range === 'unsatisfiable') {
      res.writeHead(416, { ...baseHeaders, 'content-range': `bytes */${String(size)}` })
      res.end()
      return
    }

    const start = range === null ? 0 : range.start
    const end = range === null ? Math.max(0, size - 1) : range.end
    const length = size === 0 ? 0 : end - start + 1
    const status = range === null ? 200 : 206

    this.deps.audit.record({
      action: 'fs.download',
      user,
      path: this.deps.guard.relative(abs),
      bytes: length,
      status,
    })

    if (req.method === 'HEAD') {
      res.writeHead(status, {
        ...baseHeaders,
        'content-length': String(length),
        ...(range === null ? {} : { 'content-range': `bytes ${String(start)}-${String(end)}/${String(size)}` }),
      })
      res.end()
      return
    }

    res.writeHead(status, {
      ...baseHeaders,
      'content-length': String(length),
      ...(range === null ? {} : { 'content-range': `bytes ${String(start)}-${String(end)}/${String(size)}` }),
    })

    if (length === 0 || size === 0) {
      res.end()
      return
    }

    const stream = createReadStream(abs, { start, end })
    stream.on('error', (error) => {
      warn(`读取 ${abs} 失败: ${errText(error)}`)
      res.destroy()
    })
    // 客户端提前断开时释放文件句柄。
    res.on('close', () => {
      stream.destroy()
    })
    stream.pipe(res)
  }

  // ------------------------------------------------------------------ 上传

  private async handleUpload(req: IncomingMessage, res: ServerResponse, url: URL, user: string): Promise<void> {
    if (req.method !== 'POST' && req.method !== 'PUT') {
      sendError(res, 405, 'method_not_allowed')
      return
    }
    const contentType = String(req.headers['content-type'] ?? '').toLowerCase()

    if (contentType.startsWith('multipart/form-data')) {
      await this.uploadMultipart(req, res, user)
      return
    }
    if (req.headers['x-upload-id'] !== undefined) {
      await this.uploadChunk(req, res, user)
      return
    }
    // 原始体模式：?path=<rel> + application/octet-stream（或任意非 multipart 类型）
    await this.uploadRaw(req, res, url, user)
  }

  /** 走「目录 → 追加 basename」规则后再准备落盘。 */
  private async prepareTargetFor(rel: string, rawName: string): Promise<{ target: string; temp: string }> {
    const target = await this.resolveUploadTarget(rel, rawName)
    const targetDir = dirname(target)
    await mkdir(targetDir, { recursive: true })
    return { target, temp: tempNameFor(targetDir, 'single') }
  }

  /**
   * 解析上传的最终目标路径。
   *
   * 契约规定：**目标若是一个已存在的目录，则文件名取上传文件自身的 basename**。
   * 于是 `path=.` 表示「丢进工作区根目录」，`path=docs` 表示「丢进 docs/」。
   *
   * 这一步必须在写校验**之前**判断，因为对「根目录自身」做写校验时，它的父目录
   * 本来就在工作区之外，会被正确地判为越界 —— 但用户的意思显然不是要往根目录的
   * 父目录写东西。
   */
  private async resolveUploadTarget(rel: string, rawName: string): Promise<string> {
    // basename() 会剥掉任何目录成分，客户端无法用 "../../x" 当文件名来逃逸。
    const name = basename(rawName.trim()) || 'upload.bin'
    const trimmed = rel.trim().replace(/\/+$/u, '')

    if (trimmed.length === 0 || trimmed === '.') {
      return this.deps.guard.resolveForWrite(name)
    }

    // 已存在的目录 → 落成 <dir>/<name>。
    const asDir = await this.deps.guard.resolveDirForRead(trimmed).catch(() => null)
    if (asDir !== null) return this.deps.guard.resolveForWrite(join(trimmed, name))

    return this.deps.guard.resolveForWrite(trimmed)
  }

  /** 原子落盘：把临时文件 rename 到目标。 */
  private async commit(temp: string, target: string): Promise<void> {
    try {
      await rename(temp, target)
    } catch (error) {
      await rm(temp, { force: true }).catch(() => undefined)
      throw error
    }
  }

  /** 模式 C：原始请求体作为整个文件。 */
  private async uploadRaw(req: IncomingMessage, res: ServerResponse, url: URL, user: string): Promise<void> {
    const rel = url.searchParams.get('path')
    if (rel === null || rel.length === 0) {
      sendError(res, 400, 'bad_request', '原始体上传必须提供 ?path=<相对路径>')
      return
    }
    const rawName = String(req.headers['x-file-name'] ?? 'upload.bin')
    const { target, temp } = await this.prepareTargetFor(rel, rawName)

    const hash = createHash('sha256')
    let written = 0
    let aborted = false
    const limit = this.deps.config.maxUploadBytes

    try {
      await pipeline(
        req,
        async function* (source: AsyncIterable<Buffer>) {
          for await (const chunk of source) {
            written += chunk.length
            if (written > limit) {
              aborted = true
              throw new Error('payload too large')
            }
            hash.update(chunk)
            yield chunk
          }
        },
        createWriteStream(temp, { mode: 0o600 }),
      )
    } catch (error) {
      await rm(temp, { force: true }).catch(() => undefined)
      if (aborted) {
        sendError(res, 413, 'payload_too_large', `超过 maxUploadBytes=${String(limit)}`)
        return
      }
      this.replyForError(res, url, error)
      return
    }

    const digest = hash.digest('hex')
    const expected = req.headers['x-file-sha256']
    if (typeof expected === 'string' && expected.toLowerCase() !== digest) {
      await rm(temp, { force: true }).catch(() => undefined)
      sendError(res, 400, 'checksum_mismatch', 'X-File-SHA256 与服务端计算结果不一致')
      return
    }

    await this.commit(temp, target)
    this.deps.audit.record({
      action: 'fs.upload',
      user,
      path: this.deps.guard.relative(target),
      bytes: written,
      status: 200,
      detail: 'raw',
    })
    sendJson(res, 200, {
      ok: true,
      completed: true,
      path: this.deps.guard.relative(target),
      size: written,
      sha256: digest,
    })
  }

  /** 模式 A：multipart/form-data（流式解析，`path` 字段与 `file` 字段顺序无关）。 */
  private async uploadMultipart(req: IncomingMessage, res: ServerResponse, user: string): Promise<void> {
    const boundaryMatch = /boundary=(?:"([^"]+)"|([^;]+))/u.exec(String(req.headers['content-type'] ?? ''))
    const boundary = boundaryMatch?.[1] ?? boundaryMatch?.[2]?.trim()
    if (boundary === undefined || boundary.length === 0) {
      sendError(res, 400, 'bad_request', 'multipart 缺少 boundary')
      return
    }

    // 先落盘到 stateDir 暂存区（能拿到 path 字段之前无法确定目标目录），
    // 最后再复制到目标同目录并 rename。顺序无关，代价是一次额外拷贝。
    await mkdir(this.stagingDir, { recursive: true, mode: 0o700 })
    const staged = join(this.stagingDir, `mp-${randomBytes(8).toString('hex')}.part`)

    // 累加器刻意用对象而不是局部变量：这些字段在 parseMultipart 的回调里被赋值，
    // 而 TypeScript 的控制流分析看不到闭包内的赋值，会把它们一直窄化成初始值。
    const acc = {
      pathField: null as string | null,
      uploadedName: null as string | null,
      written: 0,
      sawFile: false,
      hash: null as ReturnType<typeof createHash> | null,
      out: null as ReturnType<typeof createWriteStream> | null,
    }

    const cleanupStaged = async (): Promise<void> => {
      await rm(staged, { force: true }).catch(() => undefined)
    }

    try {
      const result = await parseMultipart(req, boundary, this.deps.config.maxUploadBytes, (info) => {
        if (info.filename !== null || info.name === 'file') {
          acc.sawFile = true
          acc.uploadedName = info.filename ?? 'upload.bin'
          acc.hash = createHash('sha256')
          acc.out = createWriteStream(staged, { mode: 0o600 })
          return {
            onData: (chunk) => {
              acc.written += chunk.length
              acc.hash?.update(chunk)
              const out = acc.out
              if (out !== null && !out.write(chunk)) {
                return new Promise<void>((resolve) => {
                  out.once('drain', () => {
                    resolve()
                  })
                })
              }
              return undefined
            },
            // end() 的回调在 'finish' 触发，也就是数据真的落盘之后才 resolve，
            // 因此这里返回即代表临时文件已经完整。
            onEnd: () =>
              new Promise<void>((resolve, reject) => {
                const out = acc.out
                if (out === null) {
                  resolve()
                  return
                }
                out.on('error', reject)
                out.end(() => {
                  resolve()
                })
              }),
          }
        }
        // 普通字段：只收 path，且大小有上限。
        const chunks: Buffer[] = []
        let fieldBytes = 0
        return {
          onData: (chunk) => {
            fieldBytes += chunk.length
            if (fieldBytes > MAX_FIELD_BYTES) throw new Error(`字段 ${info.name} 过大`)
            chunks.push(chunk)
          },
          onEnd: () => {
            if (info.name === 'path') acc.pathField = Buffer.concat(chunks).toString('utf8').trim()
          },
        }
      })

      if (result.aborted) {
        await cleanupStaged()
        sendError(res, 413, 'payload_too_large', `超过 maxUploadBytes=${String(this.deps.config.maxUploadBytes)}`)
        return
      }
      if (!acc.sawFile) {
        await cleanupStaged()
        sendError(res, 400, 'bad_request', 'multipart 中缺少文件字段')
        return
      }

      const digest = acc.hash === null ? '' : acc.hash.digest('hex')
      const expected = req.headers['x-file-sha256']
      if (typeof expected === 'string' && expected.toLowerCase() !== digest) {
        await cleanupStaged()
        sendError(res, 400, 'checksum_mismatch', 'X-File-SHA256 与服务端计算结果不一致')
        return
      }

      const finalName = acc.uploadedName ?? 'upload.bin'
      // 契约：path 指向已存在目录时（含 "." 与缺省），文件名取上传文件自身的 basename。
      const target = await this.resolveUploadTarget(acc.pathField ?? '', finalName)
      await mkdir(dirname(target), { recursive: true })
      const temp = tempNameFor(dirname(target), 'mp')
      await copyFile(staged, temp)
      await cleanupStaged()
      await this.commit(temp, target)

      this.deps.audit.record({
        action: 'fs.upload',
        user,
        path: this.deps.guard.relative(target),
        bytes: acc.written,
        status: 200,
        detail: 'multipart',
      })
      sendJson(res, 200, {
        ok: true,
        completed: true,
        path: this.deps.guard.relative(target),
        size: acc.written,
        sha256: digest,
      })
    } catch (error) {
      if (acc.out !== null) {
        try {
          acc.out.destroy()
        } catch {
          /* ignore */
        }
      }
      await cleanupStaged()
      this.replyForError(res, new URL('http://x/fs/upload'), error)
    }
  }

  /** 模式 B：分块上传。 */
  private async uploadChunk(req: IncomingMessage, res: ServerResponse, user: string): Promise<void> {
    const uploadId = String(req.headers['x-upload-id'] ?? '')
    const indexPath = String(req.headers['x-chunk-index'] ?? '')
    const totalPath = String(req.headers['x-total-chunks'] ?? '')
    const filePath = String(req.headers['x-file-path'] ?? '')

    if (!/^[A-Za-z0-9_-]{1,128}$/u.test(uploadId)) {
      sendError(res, 400, 'bad_request', 'X-Upload-Id 缺失或格式非法')
      return
    }
    const index = Number(indexPath)
    const total = Number(totalPath)
    if (!Number.isSafeInteger(index) || index < 0 || !Number.isSafeInteger(total) || total < 1 || index >= total) {
      sendError(res, 400, 'bad_request', 'X-Chunk-Index / X-Total-Chunks 非法')
      return
    }
    if (filePath.length === 0) {
      sendError(res, 400, 'bad_request', 'X-File-Path 缺失')
      return
    }
    // 先校验路径，再落任何字节。
    const chunkName = String(req.headers['x-file-name'] ?? 'upload.bin')
    const target = await this.resolveUploadTarget(decodeURIComponent(filePath), chunkName)

    const sessionDir = join(this.chunkRoot, uploadId)
    await mkdir(sessionDir, { recursive: true, mode: 0o700 })
    const chunkFile = join(sessionDir, `${String(index).padStart(8, '0')}.part`)

    let written = 0
    let aborted = false
    const limit = this.deps.config.maxUploadBytes
    try {
      await pipeline(
        req,
        async function* (source: AsyncIterable<Buffer>) {
          for await (const chunk of source) {
            written += chunk.length
            if (written > limit) {
              aborted = true
              throw new Error('chunk too large')
            }
            yield chunk
          }
        },
        createWriteStream(chunkFile, { mode: 0o600 }),
      )
    } catch (error) {
      await rm(chunkFile, { force: true }).catch(() => undefined)
      if (aborted) {
        sendError(res, 413, 'payload_too_large')
        return
      }
      this.replyForError(res, new URL('http://x/fs/upload'), error)
      return
    }

    this.deps.audit.record({
      action: 'fs.upload.chunk',
      user,
      path: decodeURIComponent(filePath),
      bytes: written,
      status: 200,
      detail: `${String(index + 1)}/${String(total)}`,
    })

    // 统计已收到的分块。
    const present = (await readdir(sessionDir)).filter((n) => n.endsWith('.part'))
    if (present.length < total) {
      sendJson(res, 200, { ok: true, completed: false, receivedChunks: present.length, totalChunks: total })
      return
    }

    // 全部到齐：按序合并 → 校验哈希 → 原子落盘。
    const temp = tempNameFor(dirname(target), 'chunks')
    await mkdir(dirname(target), { recursive: true })
    const hash = createHash('sha256')
    try {
      // 流式合并：逐块读取并写盘，带背压；绝不把一整块读进内存
      // （单块上限就是 maxUploadBytes，可能非常巨大）。
      const out = createWriteStream(temp, { mode: 0o600 })
      const outError = new Promise<never>((_resolve, reject) => {
        out.on('error', reject)
      })
      try {
        for (let i = 0; i < total; i++) {
          const part = join(sessionDir, `${String(i).padStart(8, '0')}.part`)
          const source = createReadStream(part)
          for await (const chunk of source) {
            hash.update(chunk)
            if (!out.write(chunk)) await once(out, 'drain')
          }
        }
      } finally {
        await new Promise<void>((resolve, reject) => {
          out.on('error', reject)
          out.end(() => {
            resolve()
          })
        })
        void outError.catch(() => undefined)
      }
      const digest = hash.digest('hex')
      const expected = req.headers['x-file-sha256']
      if (typeof expected === 'string' && expected.toLowerCase() !== digest) {
        await rm(temp, { force: true }).catch(() => undefined)
        await rm(sessionDir, { recursive: true, force: true }).catch(() => undefined)
        sendError(res, 400, 'checksum_mismatch', 'X-File-SHA256 与服务端计算结果不一致')
        return
      }
      await this.commit(temp, target)
      const finalInfo = await stat(target)
      await rm(sessionDir, { recursive: true, force: true }).catch(() => undefined)
      this.deps.audit.record({
        action: 'fs.upload',
        user,
        path: this.deps.guard.relative(target),
        bytes: finalInfo.size,
        status: 200,
        detail: 'chunked',
      })
      sendJson(res, 200, {
        ok: true,
        completed: true,
        receivedChunks: total,
        totalChunks: total,
        path: this.deps.guard.relative(target),
        size: finalInfo.size,
        sha256: digest,
      })
    } catch (error) {
      await rm(temp, { force: true }).catch(() => undefined)
      this.replyForError(res, new URL('http://x/fs/upload'), error)
    }
  }

  /** 清理过期的分块暂存目录。 */
  async sweepStaleChunkSessions(): Promise<number> {
    let removed = 0
    try {
      const entries = await readdir(this.chunkRoot, { withFileTypes: true }).catch(() => [])
      const cutoff = Date.now() - CHUNK_SESSION_TTL_MS
      for (const entry of entries) {
        if (!entry.isDirectory()) continue
        const dir = join(this.chunkRoot, entry.name)
        const info = await stat(dir).catch(() => null)
        if (info === null || info.mtimeMs >= cutoff) continue
        await rm(dir, { recursive: true, force: true }).catch(() => undefined)
        removed += 1
      }
    } catch (error) {
      warn(`清理过期分块会话失败: ${errText(error)}`)
    }
    return removed
  }

  /** 暂存区剩余空间检查（供 /gateway/health 诊断用）。 */
  async stagingFreeBytes(): Promise<number | null> {
    try {
      const info = await statfs(this.stagingDir)
      return info.bavail * info.bsize
    } catch {
      return null
    }
  }

  /** 供测试与诊断：暂存目录。 */
  get staging(): string {
    return this.stagingDir
  }
}

/** 供外部使用的清理入口（删除一个未完成的分块会话）。 */
export async function dropChunkSession(root: string, uploadId: string): Promise<void> {
  if (!/^[A-Za-z0-9_-]{1,128}$/u.test(uploadId)) return
  await rm(join(root, uploadId), { recursive: true, force: true }).catch(() => undefined)
}

/** 客户端 IP（复用于审计）。 */
export { clientIp }

