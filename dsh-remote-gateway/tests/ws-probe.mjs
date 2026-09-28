#!/usr/bin/env node
/**
 * ws-probe.mjs —— 极简 RFC 6455 客户端，用来**真实**验证
 * 「手机 → 隧道 → 插件网关 → dsh」这条链路上的 WebSocket 是否真的通。
 *
 * 只回 101 不算通：那只能说明握手头被转发。这里进一步发一帧 `$events`
 * 订阅请求，并等待 dsh 回一个 `ready` 帧 —— 那才证明双向数据真的穿透了。
 *
 * 零依赖：手写握手 + 帧编解码（客户端必须掩码，服务端必须不掩码）。
 *
 * 用法：
 *   node ws-probe.mjs <ws-url> <bearer-token> [endpoint] [timeoutMs]
 * 例：
 *   node ws-probe.mjs ws://127.0.0.1:3092/api/remote.mux "$TOKEN" '$events' 8000
 *
 * 退出码：0 = 收到服务端数据帧；1 = 失败。
 */
import { createHash, randomBytes } from 'node:crypto'
import http from 'node:http'
import https from 'node:https'

const [, , rawUrl, token, endpoint = '$events', timeoutMsRaw = '8000'] = process.argv
const timeoutMs = Number(timeoutMsRaw)

if (!rawUrl || !token) {
  console.error('用法: node ws-probe.mjs <ws-url> <bearer-token> [endpoint] [timeoutMs]')
  process.exit(2)
}

// ws:// → http://, wss:// → https://（Upgrade 请求本身就是 HTTP 请求）
const httpUrl = rawUrl.replace(/^ws:/u, 'http:').replace(/^wss:/u, 'https:')
const target = new URL(httpUrl)
const isTls = target.protocol === 'https:'
const transport = isTls ? https : http

const key = randomBytes(16).toString('base64')

/** 编码一个客户端文本帧（必须掩码）。 */
function encodeTextFrame(text) {
  const payload = Buffer.from(text, 'utf8')
  const length = payload.length
  let header
  if (length < 126) {
    header = Buffer.alloc(6)
    header[1] = 0x80 | length
  } else if (length < 65536) {
    header = Buffer.alloc(8)
    header[1] = 0x80 | 126
    header.writeUInt16BE(length, 2)
  } else {
    header = Buffer.alloc(14)
    header[1] = 0x80 | 127
    header.writeBigUInt64BE(BigInt(length), 2)
  }
  header[0] = 0x81 // FIN + opcode=text
  const mask = randomBytes(4)
  mask.copy(header, header.length - 4)
  const masked = Buffer.alloc(length)
  for (let i = 0; i < length; i++) masked[i] = payload[i] ^ mask[i % 4]
  return Buffer.concat([header, masked])
}

/** 一个增量式的服务端帧解析器。 */
function createFrameParser(onFrame) {
  let buffer = Buffer.alloc(0)
  return (chunk) => {
    buffer = Buffer.concat([buffer, chunk])
    for (;;) {
      if (buffer.length < 2) return
      const fin = (buffer[0] & 0x80) !== 0
      const opcode = buffer[0] & 0x0f
      const masked = (buffer[1] & 0x80) !== 0
      let length = buffer[1] & 0x7f
      let offset = 2
      if (length === 126) {
        if (buffer.length < 4) return
        length = buffer.readUInt16BE(2)
        offset = 4
      } else if (length === 127) {
        if (buffer.length < 10) return
        const big = buffer.readBigUInt64BE(2)
        if (big > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('帧过大')
        length = Number(big)
        offset = 10
      }
      let maskKey = null
      if (masked) {
        if (buffer.length < offset + 4) return
        maskKey = buffer.subarray(offset, offset + 4)
        offset += 4
      }
      if (buffer.length < offset + length) return
      let payload = Buffer.from(buffer.subarray(offset, offset + length))
      if (maskKey) for (let i = 0; i < payload.length; i++) payload[i] ^= maskKey[i % 4]
      buffer = buffer.subarray(offset + length)
      onFrame({ fin, opcode, payload })
    }
  }
}

function fail(message) {
  console.error(`✗ ${message}`)
  process.exit(1)
}

const req = transport.request({
  host: target.hostname,
  port: target.port || (isTls ? 443 : 80),
  path: `${target.pathname}${target.search}`,
  method: 'GET',
  headers: {
    connection: 'Upgrade',
    upgrade: 'websocket',
    'sec-websocket-version': '13',
    'sec-websocket-key': key,
    authorization: `Bearer ${token}`,
  },
})

const timer = setTimeout(() => fail(`超时 ${String(timeoutMs)}ms：未收到服务端数据帧`), timeoutMs)

req.on('upgrade', (res, socket, head) => {
  const status = res.statusCode ?? 0
  if (status !== 101) {
    clearTimeout(timer)
    fail(`期望 101，实际 ${String(status)}`)
    return
  }

  // 校验 Sec-WebSocket-Accept（RFC 6455 §4.1）—— 证明这是真的 WebSocket 握手。
  const expected = createHash('sha1')
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest('base64')
  const actual = res.headers['sec-websocket-accept']
  if (actual !== expected) {
    clearTimeout(timer)
    fail(`Sec-WebSocket-Accept 不匹配（期望 ${expected}，实际 ${String(actual)}）`)
    return
  }
  console.log(`✓ 101 Switching Protocols，Sec-WebSocket-Accept 校验通过`)

  let sawData = false
  const parse = createFrameParser(({ opcode, payload }) => {
    if (opcode === 0x1) {
      sawData = true
      const text = payload.toString('utf8')
      let summary = text
      try {
        const parsed = JSON.parse(text)
        summary = JSON.stringify(parsed).slice(0, 400)
      } catch {
        /* 原样输出 */
      }
      console.log(`✓ 收到文本帧 (${String(payload.length)} 字节): ${summary}`)
      clearTimeout(timer)
      socket.end()
      process.exit(0)
    }
    if (opcode === 0x9) {
      // Ping → 必须回 Pong，否则 dsh 会掐断连接。
      const pong = Buffer.alloc(payload.length + 2)
      pong[0] = 0x8a
      pong[1] = payload.length
      payload.copy(pong, 2)
      socket.write(pong)
      console.log('· 收到 Ping，已回 Pong')
      return
    }
    if (opcode === 0x8) {
      clearTimeout(timer)
      fail('服务端发来 Close 帧')
    }
  })

  if (head && head.length > 0) parse(head)
  socket.on('data', parse)
  socket.on('error', (error) => {
    clearTimeout(timer)
    fail(`socket 错误: ${error.message}`)
  })

  // 订阅：$events 是 dsh 的 Cordis 事件转发通道，连上后会立刻回一个 ready 帧。
  const open = JSON.stringify({
    type: 'open',
    streamId: 'probe1',
    endpoint,
    payload: { args: {} },
  })
  socket.write(encodeTextFrame(open))
  console.log(`→ 已发送 open 帧 (endpoint=${endpoint})`)
  void sawData
})

req.on('response', (res) => {
  clearTimeout(timer)
  let body = ''
  res.on('data', (chunk) => {
    body += chunk.toString('utf8')
  })
  res.on('end', () => {
    fail(`上游未升级，HTTP ${String(res.statusCode)}: ${body.slice(0, 200)}`)
  })
})

req.on('error', (error) => {
  clearTimeout(timer)
  fail(`请求失败: ${error.message}`)
})

req.end()
