/**
 * 反向代理的「请求重放」集成测试 —— 起一个假 dsh 上游，让网关真的去代理。
 *
 * 为什么单独写这一份：大文件上传失败往往不是上传通道本身的问题，而是
 * **dsh 重启后会话失效**这条路径上的重放逻辑。旧实现在流式转发（请求体超过
 * 重放上限）时也会去「重放」，但流已经被消费掉了 —— 再 pipe 一次只会向
 * 上游发出一个**空体**，上游收到残缺请求，比直接报错更糟。这里把两种体
 * 都真实跑一遍，锁死正确行为。
 *
 *   node --test tests/proxy-replay.test.mjs
 */
import assert from 'node:assert/strict'
import http from 'node:http'
import { after, describe, it } from 'node:test'

import { normalizeConfig } from '../lib/config.js'
import { GatewayServer } from '../lib/proxy/gateway.js'
import { DshSessionCookie } from '../lib/proxy/dsh-cookie.js'

/** 假 dsh 上游收到的每一次非兑换请求。 */
function makeUpstream() {
  const seen = []
  let force401Once = false

  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://x')

    // launch token 兑换端点：dsh 的 authorizeIndex 行为
    if (url.pathname === '/' && url.searchParams.has('token')) {
      res.writeHead(303, {
        'set-cookie': 'dsh-auth-fake=cookie-v1; Max-Age=100; Path=/; HttpOnly; SameSite=Strict',
        location: './',
      })
      res.end()
      return
    }

    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const body = Buffer.concat(chunks)
      seen.push({
        url: req.url,
        cookie: req.headers['cookie'],
        bytes: body.byteLength,
        sha: body.toString('base64').slice(0, 24),
      })

      if (force401Once) {
        force401Once = false
        res.writeHead(401)
        res.end()
        return
      }
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: true, got: body.byteLength }))
    })
  })

  return {
    server,
    seen,
    failNextOnce() {
      force401Once = true
    },
  }
}

/** 起一个网关，指向假上游。 */
async function startGateway({ replayBodyLimitBytes }) {
  const upstream = makeUpstream()
  await new Promise((r) => upstream.server.listen(0, '127.0.0.1', r))
  const dshPort = upstream.server.address().port

  const config = normalizeConfig({
    gatewayHost: '127.0.0.1',
    gatewayPort: 0,
    replayBodyLimitBytes,
    adminPassword: 'x',
    auditLog: false,
  })

  const audits = []
  const cookie = new DshSessionCookie(dshPort, (baseUrl) => {
    const u = new URL(baseUrl)
    u.searchParams.set('token', 'fake-launch-token')
    return u.href
  })

  const server = new GatewayServer({
    config,
    dshPort,
    auth: {
      isConfigured: () => true,
      handle: () => Promise.resolve(false),
      authenticate: () => ({ ok: true, payload: { sub: 'admin' } }),
    },
    files: { handle: () => Promise.resolve(false) },
    cookie,
    audit: { record: (e) => audits.push(e) },
  })

  const port = await server.start()

  return {
    port,
    upstream,
    audits,
    cookie,
    async close() {
      await server.stop()
      await new Promise((r) => upstream.server.close(r))
    },
  }
}

/** 向网关发一个 POST。 */
function post(port, path, body, timeoutMs = 15_000) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port, method: 'POST', path, headers: { 'content-length': String(body.byteLength) } },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }))
      },
    )
    req.setTimeout(timeoutMs, () => {
      req.destroy(new Error(`POST ${path} 超时（${String(timeoutMs)}ms）`))
    })
    req.on('error', reject)
    req.end(body)
  })
}

const OPEN = []
after(async () => {
  for (const g of OPEN) await g.close()
})

async function gateway(opts) {
  const g = await startGateway(opts)
  OPEN.push(g)
  return g
}

describe('反向代理重放', () => {
  it('小请求体（可重放）：401 后换 cookie 重放，且重放带完整请求体', async () => {
    const g = await gateway({ replayBodyLimitBytes: 1024 * 1024 })
    const body = Buffer.alloc(4096, 0x41) // 4 KiB，低于上限 → 缓冲

    g.upstream.failNextOnce()
    const res = await post(g.port, '/api/session/list', body)

    assert.equal(res.status, 200, '重放后应当成功')

    // 上游应当收到两次业务请求：第一次 401，第二次带完整体。
    const calls = g.upstream.seen
    assert.equal(calls.length, 2, `上游应收到 2 次请求，实际 ${String(calls.length)}`)
    assert.equal(calls[0].bytes, body.byteLength, '第一次应带完整体')
    assert.equal(calls[1].bytes, body.byteLength, '重放必须带完整体（这正是旧实现的缺陷点）')
    assert.equal(calls[1].sha, calls[0].sha, '重放的字节内容必须与原请求完全一致')

    // 审计里应当有 reauth 记录，而不是 unreplayable
    assert.ok(
      g.audits.some((a) => a.action === 'upstream.reauth'),
      '应当记录 upstream.reauth',
    )
  })

  it('大请求体（流式、不可重放）：401 后不得发出空体重放，而是明确报错', async () => {
    const g = await gateway({ replayBodyLimitBytes: 1024 }) // 上限 1 KiB
    const body = Buffer.alloc(64 * 1024, 0x42) // 64 KiB，超过上限 → 流式

    g.upstream.failNextOnce()
    const res = await post(g.port, '/api/fs/upload', body)

    // 旧实现会在这里发出第二个「空体」请求并把它当成正常重放。
    const business = g.upstream.seen
    assert.equal(
      business.length,
      1,
      `流式请求体不可重放，上游不应收到第二次请求（旧实现会发一个空体）。实际 ${String(business.length)} 次`,
    )
    assert.equal(business[0].bytes, body.byteLength, '第一次必须完整送达')

    assert.equal(res.status, 502, '应当返回 502 而不是假装成功')
    assert.match(res.body, /重试|重传/, '错误信息应告诉客户端重传')

    assert.ok(
      g.audits.some((a) => a.action === 'proxy.unreplayable_401'),
      '应当记录 proxy.unreplayable_401',
    )
    assert.ok(
      !g.audits.some((a) => a.action === 'upstream.reauth'),
      '流式路径不应记录成普通 reauth',
    )
  })

  it('大请求体在出发前会先把 cookie 换新（避免走到 401 死路）', async () => {
    const g = await gateway({ replayBodyLimitBytes: 1024 })
    const body = Buffer.alloc(64 * 1024, 0x43)

    // 先做一次小请求把 cookie 灌进缓存
    await post(g.port, '/api/session/list', Buffer.from('{}'))
    const before = g.cookie.exchanges

    // 把 cookie 的「年龄」推到超过阈值
    const realNow = Date.now
    Date.now = () => realNow() + 60_000
    try {
      const res = await post(g.port, '/api/fs/upload', body)
      assert.equal(res.status, 200, '大请求应当成功')
      assert.ok(g.cookie.exchanges > before, '流式请求出发前应当重新兑换了 cookie')
    } finally {
      Date.now = realNow
    }

    // 关键：上游收到的大请求带的是**新**cookie，且体完整
    const last = g.upstream.seen.at(-1)
    assert.equal(last.bytes, body.byteLength, '大请求体必须完整送达')
    assert.ok(last.cookie?.startsWith('dsh-auth-'), '应当带着 dsh 会话 cookie')
  })

  it('重放上限可配置：调大后原本流式的请求变成可重放', async () => {
    const g = await gateway({ replayBodyLimitBytes: 8 * 1024 * 1024 })
    const body = Buffer.alloc(64 * 1024, 0x44) // 64 KiB，现在在上限之内

    g.upstream.failNextOnce()
    const res = await post(g.port, '/api/fs/upload', body)

    assert.equal(res.status, 200)
    assert.equal(g.upstream.seen.length, 2, '现在应当走重放路径')
    assert.equal(g.upstream.seen[1].bytes, body.byteLength, '重放带完整体')
  })
})
