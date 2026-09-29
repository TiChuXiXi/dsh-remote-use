/**
 * 单元测试 —— 用 Node 内置测试运行器，零测试依赖。
 *
 *   node --test tests/
 *
 * 重点覆盖安全相关场景：路径越界、token 篡改、cookie 伪造、密码策略、限速。
 * 端到端链路另见 scripts/e2e-verify.sh（打真实 dsh，85 项）。
 */
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, describe, it } from 'node:test'

import { normalizeConfig, defaultConfig } from '../lib/config.js'
import { PasswordStore, validatePasswordPolicy } from '../lib/auth/password.js'
import { TokenService } from '../lib/auth/token.js'
import { RateLimiter } from '../lib/auth/ratelimit.js'
import { PathGuard, PathDeniedError } from '../lib/files/guard.js'
import { parseRange } from '../lib/files/routes.js'
import { contentDisposition, mimeFor, shouldForceAttachment } from '../lib/files/mime.js'
import { clientIp, parseCookies, readBody, serializeCookie, PayloadTooLargeError } from '../lib/http-util.js'

const ROOTS = []
function scratch() {
  const dir = mkdtempSync(join(tmpdir(), 'drg-test-'))
  ROOTS.push(dir)
  return dir
}
after(() => {
  for (const dir of ROOTS) rmSync(dir, { recursive: true, force: true })
})

// ─────────────────────────────────────────────────────────────── 配置

describe('normalizeConfig', () => {
  it('对空配置返回默认值', () => {
    const cfg = normalizeConfig(undefined)
    assert.equal(cfg.gatewayPort, 3081)
    assert.equal(cfg.gatewayHost, '127.0.0.1')
    assert.equal(cfg.cookieSecure, true)
    assert.equal(cfg.sessionMaxAgeDays, 30)
    assert.equal(cfg.dshPort, 0)
  })

  it('非对象输入也回退默认而不是抛错', () => {
    for (const bad of [null, 42, 'x', [], true]) {
      assert.equal(normalizeConfig(bad).gatewayPort, 3081)
    }
  })

  it('数值越界被钳制', () => {
    assert.equal(normalizeConfig({ gatewayPort: 99999 }).gatewayPort, 65535)
    assert.equal(normalizeConfig({ gatewayPort: -5 }).gatewayPort, 0)
    // 0 是合法值：表示由操作系统分配端口（测试/嵌入用）
    assert.equal(normalizeConfig({ gatewayPort: 0 }).gatewayPort, 0)
    assert.equal(normalizeConfig({ sessionMaxAgeDays: 0 }).sessionMaxAgeDays, 1)
    assert.equal(normalizeConfig({ loginMaxAttempts: 0 }).loginMaxAttempts, 1)
  })

  it('类型不对的键被忽略', () => {
    const cfg = normalizeConfig({ gatewayPort: 'abc', cookieSecure: 'yes', auditLog: 1 })
    assert.equal(cfg.gatewayPort, 3081)
    assert.equal(cfg.cookieSecure, true)
    assert.equal(cfg.auditLog, true)
  })

  it('未知键被忽略', () => {
    assert.equal(normalizeConfig({ totallyUnknown: 1 }).gatewayPort, 3081)
  })

  it('gatewayHost 只放行回环与具体内网 IPv4，其余强制拉回 127.0.0.1', () => {
    // 允许：回环
    assert.equal(normalizeConfig({ gatewayHost: '127.0.0.1' }).gatewayHost, '127.0.0.1')
    assert.equal(normalizeConfig({ gatewayHost: '127.0.0.53' }).gatewayHost, '127.0.0.53')
    assert.equal(normalizeConfig({ gatewayHost: 'localhost' }).gatewayHost, 'localhost')
    assert.equal(normalizeConfig({ gatewayHost: '::1' }).gatewayHost, '::1')
    // 允许：具体内网 IPv4（隧道 ingress 指向它更好辨认）
    assert.equal(normalizeConfig({ gatewayHost: '192.168.1.182' }).gatewayHost, '192.168.1.182')
    assert.equal(normalizeConfig({ gatewayHost: '10.0.0.7' }).gatewayHost, '10.0.0.7')
    assert.equal(normalizeConfig({ gatewayHost: '172.16.0.1' }).gatewayHost, '172.16.0.1')
    assert.equal(normalizeConfig({ gatewayHost: '172.31.255.254' }).gatewayHost, '172.31.255.254')
    // 拒绝：全接口（会一并挂到 docker0 / VPN 上）
    assert.equal(normalizeConfig({ gatewayHost: '0.0.0.0' }).gatewayHost, '127.0.0.1')
    assert.equal(normalizeConfig({ gatewayHost: '::' }).gatewayHost, '127.0.0.1')
    // 拒绝：公网地址、172.32 越界、CIDR、主机名、非规范写法
    assert.equal(normalizeConfig({ gatewayHost: '8.8.8.8' }).gatewayHost, '127.0.0.1')
    assert.equal(normalizeConfig({ gatewayHost: '172.32.0.1' }).gatewayHost, '127.0.0.1')
    assert.equal(normalizeConfig({ gatewayHost: '192.168.1.0/24' }).gatewayHost, '127.0.0.1')
    assert.equal(normalizeConfig({ gatewayHost: 'example.com' }).gatewayHost, '127.0.0.1')
    assert.equal(normalizeConfig({ gatewayHost: '0x7f.0.0.1' }).gatewayHost, '127.0.0.1')
    assert.equal(normalizeConfig({ gatewayHost: '192.168.1.256' }).gatewayHost, '127.0.0.1')
  })

  it('DSH_GATEWAY_PASSWORD 覆盖 adminPassword', () => {
    const old = process.env.DSH_GATEWAY_PASSWORD
    process.env.DSH_GATEWAY_PASSWORD = 'from-env-12345'
    try {
      assert.equal(normalizeConfig({ adminPassword: 'from-config' }).adminPassword, 'from-env-12345')
    } finally {
      if (old === undefined) delete process.env.DSH_GATEWAY_PASSWORD
      else process.env.DSH_GATEWAY_PASSWORD = old
    }
  })

  it('stateDir 为空时回退默认', () => {
    assert.equal(normalizeConfig({ stateDir: '   ' }).stateDir, defaultConfig().stateDir)
  })
})

// ─────────────────────────────────────────────────────────── 密码

describe('密码策略与存储', () => {
  it('策略拒绝短密码', () => {
    assert.notEqual(validatePasswordPolicy('short'), null)
    assert.notEqual(validatePasswordPolicy('1234567'), null)
    assert.equal(validatePasswordPolicy('12345678'), null)
    assert.notEqual(validatePasswordPolicy(''), null)
  })

  it('setPassword 拒绝不符合策略的密码', () => {
    const store = new PasswordStore(scratch())
    assert.throws(() => store.setPassword('admin', 'short'))
  })

  it('正确密码通过、错误密码失败、未知用户失败', () => {
    const store = new PasswordStore(scratch())
    store.setPassword('admin', 'correct-horse-battery')
    assert.equal(store.verify('admin', 'correct-horse-battery'), true)
    assert.equal(store.verify('admin', 'wrong-password-here'), false)
    assert.equal(store.verify('ghost', 'correct-horse-battery'), false)
    assert.equal(store.verify('', 'correct-horse-battery'), false)
  })

  it('users.json 里不出现明文密码', () => {
    const dir = scratch()
    const store = new PasswordStore(dir)
    store.setPassword('admin', 'SuperSecret123!')
    const raw = readFileSync(join(dir, 'users.json'), 'utf8')
    assert.equal(raw.includes('SuperSecret123!'), false)
    const parsed = JSON.parse(raw)
    assert.equal(parsed.users.admin.N, 16384)
    assert.ok(parsed.users.admin.salt.length > 0)
    assert.ok(parsed.users.admin.hash.length > 0)
  })

  it('同一密码两次哈希不同（salt 随机）', () => {
    const dir = scratch()
    const a = new PasswordStore(dir)
    a.setPassword('u1', 'same-password-123')
    a.setPassword('u2', 'same-password-123')
    const parsed = JSON.parse(readFileSync(join(dir, 'users.json'), 'utf8'))
    assert.notEqual(parsed.users.u1.hash, parsed.users.u2.hash)
  })

  it('进程外改动用户库后无需重启即可生效（mtime 感知）', () => {
    const dir = scratch()
    const running = new PasswordStore(dir)
    assert.equal(running.isEmpty, true)
    // 模拟 CLI 在另一个进程里写入
    const cli = new PasswordStore(dir)
    cli.setPassword('admin', 'set-from-cli-99')
    assert.equal(running.isEmpty, false)
    assert.equal(running.verify('admin', 'set-from-cli-99'), true)
  })

  it('remove 返回是否真的删除', () => {
    const store = new PasswordStore(scratch())
    store.setPassword('admin', 'password-1234')
    assert.equal(store.remove('admin'), true)
    assert.equal(store.remove('admin'), false)
  })
})

// ─────────────────────────────────────────────────────────── token

describe('会话 token（HMAC 签名）', () => {
  const mk = () => new TokenService(scratch(), 30 * 24 * 3600 * 1000)

  it('签发与校验往返', () => {
    const t = mk()
    const { token, payload } = t.mint('admin')
    const back = t.verify(token)
    assert.ok(back)
    assert.equal(back.sub, 'admin')
    assert.equal(back.jti, payload.jti)
    assert.equal(back.exp, payload.exp)
  })

  it('篡改载荷后签名失效', () => {
    const t = mk()
    const { token } = t.mint('admin')
    const [v, , sig] = token.split('.')
    const forged = Buffer.from(JSON.stringify({ sub: 'root', iat: 1, exp: 9e15, jti: 'x' })).toString('base64url')
    assert.equal(t.verify(`${v}.${forged}.${sig}`), null)
  })

  it('篡改签名后失效', () => {
    const t = mk()
    const { token } = t.mint('admin')
    assert.equal(t.verify(`${token.slice(0, -4)}AAAA`), null)
  })

  it('结构非法的 token 一律拒绝且不抛异常', () => {
    const t = mk()
    for (const bad of ['', 'x', 'a.b', 'a.b.c.d', 'v2.a.b', 'v1..', 'v1.a.', '...', 'null', '{}']) {
      assert.equal(t.verify(bad), null, `应拒绝: ${JSON.stringify(bad)}`)
    }
  })

  it('非字符串 / 超长输入被安全拒绝', () => {
    const t = mk()
    assert.equal(t.verify(undefined), null)
    assert.equal(t.verify(12345), null)
    assert.equal(t.verify('v1.' + 'a'.repeat(9000)), null)
  })

  it('另一个密钥签发的同名 token 不被接受', () => {
    const a = new TokenService(scratch(), 1000 * 60)
    const b = new TokenService(scratch(), 1000 * 60)
    assert.equal(b.verify(a.mint('admin').token), null)
  })

  it('过期 token 被拒绝', () => {
    const t = new TokenService(scratch(), 1) // 1ms 有效期
    const { token } = t.mint('admin')
    const start = Date.now()
    while (Date.now() - start < 5) {
      /* 等它过期 */
    }
    assert.equal(t.verify(token), null)
  })

  it('revokeAll 让此前签发的 token 全部失效，但新 token 仍可用', () => {
    const t = mk()
    const old = t.mint('admin').token
    assert.ok(t.verify(old))
    t.revokeAll('admin')
    assert.equal(t.verify(old), null)
    const fresh = t.mint('admin').token
    assert.ok(t.verify(fresh))
  })

  it('revokeAll 只影响目标用户', () => {
    const t = mk()
    const a = t.mint('alice').token
    const b = t.mint('bob').token
    t.revokeAll('alice')
    assert.equal(t.verify(a), null)
    assert.ok(t.verify(b))
  })

  it('吊销状态跨实例持久化（重启后旧 token 仍失效）', () => {
    const dir = scratch()
    const t1 = new TokenService(dir, 30 * 24 * 3600 * 1000)
    const old = t1.mint('admin').token
    t1.revokeAll('admin')
    const t2 = new TokenService(dir, 30 * 24 * 3600 * 1000)
    assert.equal(t2.verify(old), null)
  })
})

// ─────────────────────────────────────────────────────── 路径守卫

describe('PathGuard —— 路径越界防护', () => {
  function setup() {
    const root = join(scratch(), 'workspace')
    const outside = join(root, '..', 'outside')
    mkdirSync(join(root, 'sub', 'deep'), { recursive: true })
    mkdirSync(outside, { recursive: true })
    writeFileSync(join(root, 'ok.txt'), 'ok')
    writeFileSync(join(outside, 'secret.txt'), 'secret')
    symlinkSync(join(outside, 'secret.txt'), join(root, 'escape.txt'))
    symlinkSync(outside, join(root, 'escape-dir'))
    return { guard: new PathGuard(root), root, outside }
  }

  it('接受合法的相对路径', async () => {
    const { guard, root } = setup()
    assert.equal(await guard.resolveForRead('ok.txt'), join(root, 'ok.txt'))
    assert.equal(await guard.resolveForRead('sub/deep'), join(root, 'sub', 'deep'))
    assert.equal(await guard.resolveForRead('.'), root)
    assert.equal(await guard.resolveForRead(''), root)
    assert.equal(await guard.resolveForRead('sub/./deep'), join(root, 'sub', 'deep'))
    assert.equal(await guard.resolveForRead('sub//deep'), join(root, 'sub', 'deep'))
  })

  it('拒绝 .. 穿越的各种写法', async () => {
    const { guard } = setup()
    for (const evil of [
      '..',
      '../',
      '../outside',
      '../outside/secret.txt',
      'sub/../../outside/secret.txt',
      'sub/../../../etc/passwd',
      '..\\..\\outside',
      'sub\\..\\..\\outside',
    ]) {
      await assert.rejects(() => guard.resolveForRead(evil), PathDeniedError, `应拒绝: ${evil}`)
    }
  })

  it('拒绝绝对路径（POSIX 与 Windows 盘符）', async () => {
    const { guard } = setup()
    for (const evil of ['/etc/passwd', '/', '//etc/passwd', 'C:\\Windows\\win.ini', 'c:/windows']) {
      await assert.rejects(() => guard.resolveForRead(evil), PathDeniedError, `应拒绝: ${evil}`)
    }
  })

  it('拒绝含 NUL 字节的路径', async () => {
    const { guard } = setup()
    await assert.rejects(() => guard.resolveForRead('ok.txt\0.png'), PathDeniedError)
  })

  it('拒绝全点号段（Windows 会把 .... 剥成 ..）', async () => {
    const { guard } = setup()
    for (const evil of ['....//etc', '...', 'a/..../b', 'a/.../b']) {
      await assert.rejects(() => guard.resolveForRead(evil), PathDeniedError, `应拒绝: ${evil}`)
    }
  })

  it('拒绝结尾带点或空格的段（Windows 会剥掉）', async () => {
    const { guard } = setup()
    for (const evil of ['ok.txt.', 'dir. /x', 'sub./x']) {
      await assert.rejects(() => guard.resolveForRead(evil), PathDeniedError, `应拒绝: ${evil}`)
    }
  })

  it('拒绝指向工作区外的软链文件（第二重 realpath 校验）', async () => {
    const { guard } = setup()
    await assert.rejects(() => guard.resolveForRead('escape.txt'), PathDeniedError)
  })

  it('拒绝经由软链目录逃逸', async () => {
    const { guard } = setup()
    await assert.rejects(() => guard.resolveForRead('escape-dir/secret.txt'), PathDeniedError)
  })

  it('工作区内的软链是允许的', async () => {
    const { guard, root } = setup()
    symlinkSync(join(root, 'ok.txt'), join(root, 'inner-link.txt'))
    const resolved = await guard.resolveForRead('inner-link.txt')
    assert.equal(resolved, join(root, 'inner-link.txt'))
  })

  it('写路径：允许新建文件，拒绝穿越与软链逃逸', async () => {
    const { guard, root } = setup()
    assert.equal(await guard.resolveForWrite('sub/deep/new.txt'), join(root, 'sub', 'deep', 'new.txt'))
    await assert.rejects(() => guard.resolveForWrite('../pwned.txt'), PathDeniedError)
    await assert.rejects(() => guard.resolveForWrite('escape-dir/pwned.txt'), PathDeniedError)
    await assert.rejects(() => guard.resolveForWrite('nope/../ok.txt'), PathDeniedError)
  })

  it('写路径：父目录不存在时报 not found 而不是越界', async () => {
    const { guard } = setup()
    await assert.rejects(() => guard.resolveForWrite('does/not/exist.txt'), (e) => e.name === 'TargetNotFoundError')
  })

  it('resolveFileForRead 拒绝目录', async () => {
    const { guard } = setup()
    await assert.rejects(() => guard.resolveFileForRead('sub'), (e) => e.name === 'WrongTypeError')
  })

  it('resolveDirForRead 拒绝文件', async () => {
    const { guard } = setup()
    await assert.rejects(() => guard.resolveDirForRead('ok.txt'), (e) => e.name === 'WrongTypeError')
  })

  it('relative() 始终返回 / 分隔且不含前导斜杠', async () => {
    const { guard, root } = setup()
    assert.equal(guard.relative(join(root, 'sub', 'deep')), 'sub/deep')
    assert.equal(guard.relative(root), '')
  })
})

// ──────────────────────────────────────────────── Range / MIME / 工具

describe('parseRange', () => {
  it('无 Range 头返回 null', () => {
    assert.equal(parseRange(undefined, 100), null)
    assert.equal(parseRange('', 100), null)
    assert.equal(parseRange('items=0-5', 100), null)
  })

  it('解析常见形式', () => {
    assert.deepEqual(parseRange('bytes=0-99', 100), { start: 0, end: 99 })
    assert.deepEqual(parseRange('bytes=0-1023', 5000), { start: 0, end: 1023 })
    assert.deepEqual(parseRange('bytes=10-', 100), { start: 10, end: 99 })
    assert.deepEqual(parseRange('bytes=-20', 100), { start: 80, end: 99 })
  })

  it('end 超出总长时被夹到 size-1', () => {
    assert.deepEqual(parseRange('bytes=0-99999', 100), { start: 0, end: 99 })
  })

  it('不可满足的范围返回 unsatisfiable', () => {
    assert.equal(parseRange('bytes=100-', 100), 'unsatisfiable')
    assert.equal(parseRange('bytes=200-300', 100), 'unsatisfiable')
    assert.equal(parseRange('bytes=50-10', 100), 'unsatisfiable')
    assert.equal(parseRange('bytes=-0', 100), 'unsatisfiable')
    assert.equal(parseRange('bytes=-5', 0), 'unsatisfiable')
  })

  it('拒绝多段 Range（回退全量，符合 RFC）', () => {
    assert.equal(parseRange('bytes=0-1,5-9', 100), null)
  })
})

describe('MIME 与 Content-Disposition', () => {
  it('已知扩展名', () => {
    assert.equal(mimeFor('a.apk'), 'application/vnd.android.package-archive')
    assert.equal(mimeFor('a.pdf'), 'application/pdf')
    assert.equal(mimeFor('a.docx'), 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')
    assert.equal(mimeFor('a.PNG'), 'image/png')
    assert.equal(mimeFor('a.tar.gz'), 'application/gzip')
  })

  it('未知扩展名与无扩展名一律 octet-stream（不做启发式猜测）', () => {
    assert.equal(mimeFor('a.weirdext'), 'application/octet-stream')
    assert.equal(mimeFor('noext'), 'application/octet-stream')
  })

  it('活动内容强制附件', () => {
    assert.equal(shouldForceAttachment('text/html; charset=utf-8'), true)
    assert.equal(shouldForceAttachment('image/svg+xml'), true)
    assert.equal(shouldForceAttachment('image/png'), false)
  })

  it('非 ASCII 文件名同时给出 ASCII 兜底与 RFC 5987 编码', () => {
    const cd = contentDisposition('报告 v2.pdf', true)
    assert.ok(cd.startsWith('attachment; filename="'))
    assert.ok(cd.includes("filename*=UTF-8''"))
    assert.ok(cd.includes('%E6%8A%A5%E5%91%8A'))
  })

  it('文件名里的引号不会破坏头部', () => {
    const cd = contentDisposition('a"b.txt', true)
    assert.equal(cd.includes('a"b.txt'), false)
  })
})

describe('RateLimiter', () => {
  it('达到阈值前放行，达到后给出等待时间', () => {
    const rl = new RateLimiter(3, 1000)
    assert.equal(rl.retryAfterMs('ip', 0), 0)
    rl.recordFailure('ip', 0)
    rl.recordFailure('ip', 1)
    assert.equal(rl.retryAfterMs('ip', 2), 0)
    rl.recordFailure('ip', 2)
    assert.ok(rl.retryAfterMs('ip', 2) > 0)
  })

  it('窗口滑动：旧记录过期后重新放行', () => {
    const rl = new RateLimiter(2, 1000)
    rl.recordFailure('ip', 0)
    rl.recordFailure('ip', 10)
    assert.ok(rl.retryAfterMs('ip', 20) > 0)
    assert.equal(rl.retryAfterMs('ip', 1001), 0)
  })

  it('reset 清零（登录成功）', () => {
    const rl = new RateLimiter(2, 1000)
    rl.recordFailure('ip', 0)
    rl.recordFailure('ip', 0)
    assert.ok(rl.retryAfterMs('ip', 0) > 0)
    rl.reset('ip')
    assert.equal(rl.retryAfterMs('ip', 0), 0)
  })

  it('不同来源互不影响', () => {
    const rl = new RateLimiter(1, 1000)
    rl.recordFailure('a', 0)
    assert.ok(rl.retryAfterMs('a', 0) > 0)
    assert.equal(rl.retryAfterMs('b', 0), 0)
  })

  it('桶数量有上限，不会因海量来源耗尽内存', () => {
    const rl = new RateLimiter(5, 60_000)
    for (let i = 0; i < 25000; i++) rl.recordFailure(`ip-${i}`, 1000)
    assert.ok(rl.size <= 20000, `桶数 ${rl.size} 应被限制在 20000 以内`)
  })
})

describe('HTTP 工具', () => {
  it('parseCookies 处理多 cookie 与空值', () => {
    const c = parseCookies('a=1; drg_session=xyz; b=')
    assert.equal(c.get('a'), '1')
    assert.equal(c.get('drg_session'), 'xyz')
    assert.equal(c.get('b'), '')
    assert.equal(c.get('missing'), undefined)
    assert.equal(parseCookies(undefined).size, 0)
  })

  it('serializeCookie 组合属性正确', () => {
    const s = serializeCookie('drg_session', 'tok', { maxAgeSeconds: 60, secure: true, httpOnly: true, sameSite: 'Strict' })
    assert.ok(s.startsWith('drg_session=tok;'))
    assert.ok(s.includes('Path=/'))
    assert.ok(s.includes('Max-Age=60'))
    assert.ok(s.includes('HttpOnly'))
    assert.ok(s.includes('SameSite=Strict'))
    assert.ok(s.includes('Secure'))
  })

  it('serializeCookie 在 secure=false 时不写 Secure（本地 http 调试）', () => {
    assert.equal(serializeCookie('a', 'b', { secure: false }).includes('Secure'), false)
  })

  it('clientIp 只信 X-Forwarded-For 的最右侧（最左可伪造）', () => {
    const req = { headers: { 'x-forwarded-for': '1.2.3.4, 5.6.7.8, 203.0.113.9' }, socket: { remoteAddress: '127.0.0.1' } }
    assert.equal(clientIp(req, true), '203.0.113.9')
    // 不可信转发时忽略该头
    assert.equal(clientIp(req, false), '127.0.0.1')
  })

  it('clientIp 归一化 IPv6 映射地址，避免同一来源落入两个桶', () => {
    const req = { headers: {}, socket: { remoteAddress: '::ffff:127.0.0.1' } }
    assert.equal(clientIp(req, false), '127.0.0.1')
  })

  it('readBody 超过上限抛 PayloadTooLargeError', async () => {
    const { Readable } = await import('node:stream')
    const req = Readable.from([Buffer.alloc(100), Buffer.alloc(100)])
    req.destroy = () => req
    await assert.rejects(() => readBody(req, 150), PayloadTooLargeError)
  })

  it('readBody 正常读取', async () => {
    const { Readable } = await import('node:stream')
    const req = Readable.from([Buffer.from('hello ')])
    assert.equal((await readBody(req, 100)).toString('utf8'), 'hello ')
  })
})

// ─────────────────────────────────────────── 回归：登出必须真的吊销 token

describe('TokenService 单 token 吊销（登出）', () => {
  const mk = () => new TokenService(scratch(), 30 * 24 * 3600 * 1000)

  it('revokeToken 只让目标 token 失效，同用户其它 token 仍可用', () => {
    const t = mk()
    const a = t.mint('admin')
    const b = t.mint('admin')
    assert.ok(t.verify(a.token))
    assert.ok(t.verify(b.token))

    t.revokeToken(a.payload.jti, a.payload.exp)
    assert.equal(t.verify(a.token), null, '被登出的 token 必须失效')
    assert.ok(t.verify(b.token), '另一个 token 不应受影响')
  })

  it('吊销只影响该 token，不影响其它用户', () => {
    const t = mk()
    const a = t.mint('alice')
    const b = t.mint('bob')
    t.revokeToken(a.payload.jti, a.payload.exp)
    assert.equal(t.verify(a.token), null)
    assert.ok(t.verify(b.token))
  })

  it('已过期的 token 无需入库（避免拒绝表无限增长）', () => {
    const t = mk()
    const { payload } = t.mint('admin')
    t.revokeToken(payload.jti, Date.now() - 1000)
    assert.equal(t.revokedCount, 0)
  })

  it('吊销跨实例持久化 —— 重启后登出仍然有效', () => {
    const dir = scratch()
    const t1 = new TokenService(dir, 30 * 24 * 3600 * 1000)
    const a = t1.mint('admin')
    t1.revokeToken(a.payload.jti, a.payload.exp)

    const t2 = new TokenService(dir, 30 * 24 * 3600 * 1000)
    assert.equal(t2.verify(a.token), null)
    assert.equal(t2.revokedCount, 1)
  })

  it('pruneEpochs 清理已自然过期的拒绝条目', () => {
    const dir = scratch()
    const t = new TokenService(dir, 1000)
    const { payload } = t.mint('admin')
    t.revokeToken(payload.jti, Date.now() + 5)
    assert.equal(t.revokedCount, 1)
    const start = Date.now()
    while (Date.now() - start < 12) { /* 等它过期 */ }
    t.pruneEpochs()
    assert.equal(t.revokedCount, 0)
  })

  it('revokeAll 之后 epoch 与 jti 记录同时生效', () => {
    const t = mk()
    const a = t.mint('admin')
    t.revokeToken(a.payload.jti, a.payload.exp)
    t.revokeAll('admin')
    assert.equal(t.verify(a.token), null)
    assert.ok(t.verify(t.mint('admin').token), '新签发的 token 仍可用')
  })

  it('结构非法的 jti 不会污染拒绝表', () => {
    const t = mk()
    t.revokeToken('', Date.now() + 1000)
    t.revokeToken(undefined, Date.now() + 1000)
    assert.equal(t.revokedCount, 0)
  })
})
