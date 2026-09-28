/**
 * 路径越界防护 —— 双重校验。
 *
 * 第一重（字符串层）：拒绝绝对路径、含 NUL、含 `..` 段；`resolve()` 归一化后
 *   必须仍落在根目录之下。这一重挡 `..` 穿越、URL 编码穿越、以及各种
 *   `....//` 之类的绕过写法。
 *
 * 第二重（文件系统层）：对**已存在**的目标路径（读）或**其父目录**（写）做
 *   `realpath()`，再检查是否仍在真实根目录之下。这一重挡软链接逃逸 ——
 *   工作区里一个指向 `/etc` 的软链能骗过纯字符串校验。
 *
 * 两重都过才放行。任何一重失败一律 403，不区分原因（不向调用方泄露
 * 「是穿越还是软链」这类信息）。
 */
import { constants } from 'node:fs'
import { access, realpath, stat } from 'node:fs/promises'
import { isAbsolute, join, resolve, sep } from 'node:path'

/** 路径被拒绝（越界、非法）。 */
export class PathDeniedError extends Error {
  constructor(message = 'path denied') {
    super(message)
    this.name = 'PathDeniedError'
  }
}

/** 目标不存在。 */
export class TargetNotFoundError extends Error {
  constructor(message = 'not found') {
    super(message)
    this.name = 'TargetNotFoundError'
  }
}

/** 目标是目录但需要文件（或反之）。 */
export class WrongTypeError extends Error {
  constructor(message = 'wrong type') {
    super(message)
    this.name = 'WrongTypeError'
  }
}

/**
 * 工作区路径守卫。
 */
export class PathGuard {
  /** 字符串归一化后的根目录（绝对、无尾斜杠）。 */
  readonly root: string
  private realRoot: string | null = null

  constructor(root: string) {
    this.root = resolve(root)
  }

  /** 解析真实根目录（缓存），顺带确认根目录存在。 */
  private async resolveRealRoot(): Promise<string> {
    if (this.realRoot !== null) return this.realRoot
    const real = await realpath(this.root)
    this.realRoot = real
    return real
  }

  /** 丢弃缓存的真实根（工作区被重建时用）。 */
  invalidate(): void {
    this.realRoot = null
  }

  /**
   * 第一重校验：把相对路径解析成根目录下的绝对路径。
   * @throws {PathDeniedError} 路径非法或越界。
   */
  private resolveLexical(rel: string): string {
    if (typeof rel !== 'string') throw new PathDeniedError('path must be a string')
    if (rel.includes('\0')) throw new PathDeniedError('path contains NUL byte')
    // Windows 风格绝对路径与 POSIX 绝对路径都拒绝（"/x" 与 "C:\x"）。
    if (isAbsolute(rel)) throw new PathDeniedError('absolute path not allowed')
    if (/^[a-zA-Z]:[\\/]/u.test(rel)) throw new PathDeniedError('drive-absolute path not allowed')

    const normalizedInput = rel.replaceAll('\\', '/')
    const segments = normalizedInput.split('/').filter((segment) => segment.length > 0 && segment !== '.')
    for (const segment of segments) {
      if (segment === '..') throw new PathDeniedError('parent traversal not allowed')
      // 全点号段（'..'、'...'、'....'）：在 POSIX 上是合法的字面目录名，但 Windows
      // 的文件系统会剥掉结尾的点，使 `....` 退化成 `..` 从而变成真正的穿越。
      // 本项目明确支持 Windows，所以这类段一律拒绝 —— 拒绝一个罕见目录名，
      // 换掉一个跨平台的穿越面，是划算的。
      if (/^\.{2,}$/u.test(segment)) throw new PathDeniedError('dot-only path segment not allowed')
      // Windows 同样会剥掉结尾的空格与点：`foo.` / `foo ` 会解析成 `foo`，
      // 这可能让校验的路径与实际打开的路径不一致。一并拒绝。
      if (segment.endsWith('.') || segment.endsWith(' ')) {
        throw new PathDeniedError('path segment with trailing dot or space not allowed')
      }
    }

    const abs = resolve(this.root, ...segments)
    if (!this.isInside(abs, this.root)) throw new PathDeniedError('resolved path escapes workspace root')
    return abs
  }

  /** 路径 containment 判定：相等或以 `root + sep` 开头。 */
  private isInside(candidate: string, root: string): boolean {
    if (candidate === root) return true
    return candidate.startsWith(root.endsWith(sep) ? root : root + sep)
  }

  /** 第二重校验：真实路径必须在真实根之下。 */
  private async assertRealInside(abs: string): Promise<void> {
    const realRoot = await this.resolveRealRoot()
    let real: string
    try {
      real = await realpath(abs)
    } catch {
      throw new TargetNotFoundError()
    }
    if (!this.isInside(real, realRoot)) throw new PathDeniedError('real path escapes workspace root (symlink?)')
  }

  /**
   * 解析一个**读**目标：路径必须存在、必须是普通文件或目录、真实路径必须在根内。
   * 不跟随最终符号链接到根外。
   */
  async resolveForRead(rel: string): Promise<string> {
    const abs = this.resolveLexical(rel)
    await this.assertRealInside(abs)
    return abs
  }

  /** 解析一个读目标并确认它是常规文件。 */
  async resolveFileForRead(rel: string): Promise<string> {
    const abs = await this.resolveForRead(rel)
    const info = await stat(abs).catch(() => null)
    if (info === null) throw new TargetNotFoundError()
    if (!info.isFile()) throw new WrongTypeError('not a regular file')
    return abs
  }

  /** 解析一个读目标并确认它是目录。 */
  async resolveDirForRead(rel: string): Promise<string> {
    const abs = await this.resolveForRead(rel)
    const info = await stat(abs).catch(() => null)
    if (info === null) throw new TargetNotFoundError()
    if (!info.isDirectory()) throw new WrongTypeError('not a directory')
    return abs
  }

  /**
   * 解析一个**写**目标。
   *
   * 目标本身可以不存在，但**父目录**必须存在、必须是目录、且真实路径必须在根内。
   * 若目标已存在，它自身的真实路径也必须在根内（否则会顺着软链写到外面）。
   */
  async resolveForWrite(rel: string): Promise<string> {
    const abs = this.resolveLexical(rel)
    const realRoot = await this.resolveRealRoot()

    // 父目录必须真实存在且在根内。
    let parentReal: string
    try {
      parentReal = await realpath(resolve(abs, '..'))
    } catch {
      throw new TargetNotFoundError('parent directory does not exist')
    }
    if (!this.isInside(parentReal, realRoot)) {
      throw new PathDeniedError('parent real path escapes workspace root')
    }

    // 目标已存在时，跟着软链检查它的真实位置。
    const existing = await stat(abs).catch(() => null)
    if (existing !== null) {
      const target = await realpath(abs).catch(() => null)
      if (target === null || !this.isInside(target, realRoot)) {
        throw new PathDeniedError('existing target real path escapes workspace root')
      }
    }
    return abs
  }

  /** 目标路径是否已存在。 */
  async exists(rel: string): Promise<boolean> {
    try {
      const abs = await this.resolveLexical(rel)
      await access(abs, constants.F_OK)
      return true
    } catch {
      return false
    }
  }

  /** 计算相对根的展示路径（用于响应体），始终用 `/` 分隔。 */
  relative(abs: string): string {
    if (abs === this.root) return ''
    const prefix = this.root.endsWith(sep) ? this.root : this.root + sep
    return abs.startsWith(prefix) ? abs.slice(prefix.length).split(sep).join('/') : abs
  }

  /** 把相对路径拼成绝对路径（**不再校验**，仅供调用方在已校验基础上拼接）。 */
  joinRoot(rel: string): string {
    return join(this.root, rel)
  }
}
