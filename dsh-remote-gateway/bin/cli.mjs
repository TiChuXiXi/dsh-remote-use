#!/usr/bin/env node
/**
 * dsh-remote-gateway CLI —— 网关凭据管理。
 *
 * 用法：
 *   npx dsh-remote-gateway set-password [username]   # 交互式设置（推荐）
 *   npx dsh-remote-gateway list-users
 *   npx dsh-remote-gateway remove-user <username>
 *   npx dsh-remote-gateway where
 *
 * 密码不会出现在命令行参数或 shell 历史里 —— 只从 stdin 读。
 * 若你在插件行 config 里改过 `stateDir`，这里要显式传 `--state-dir`。
 */
import { createInterface } from 'node:readline'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const libDir = join(here, '..', 'lib')

if (!existsSync(join(libDir, 'index.js'))) {
  console.error('找不到 lib/ —— 请先在插件目录里执行 `npm run build`。')
  process.exit(2)
}

const { defaultConfig } = await import(join(libDir, 'config.js'))
const { PasswordStore, MIN_PASSWORD_LENGTH, validatePasswordPolicy } = await import(
  join(libDir, 'auth', 'password.js')
)

/** 解析 --state-dir 覆盖与位置参数。 */
function parseArgs(argv) {
  const positional = []
  let stateDir = null
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--state-dir') {
      stateDir = argv[i + 1] ?? null
      i++
      continue
    }
    if (arg.startsWith('--state-dir=')) {
      stateDir = arg.slice('--state-dir='.length)
      continue
    }
    if (arg === '-h' || arg === '--help') {
      positional.push('--help')
      continue
    }
    positional.push(arg)
  }
  return { positional, stateDir: stateDir ?? defaultConfig().stateDir }
}

/** 从 stdin 读一行，echo 可控（用于密码）。 */
function ask(question, { hidden = false } = {}) {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true })
    if (hidden) {
      // 关掉回显，但仍让用户看到提示符。
      const onData = (char) => {
        if (char.toString() === '\r' || char.toString() === '\n') return
        // 重绘提示符，掩盖已输入的字符。
        process.stdout.clearLine?.(0)
        process.stdout.cursorTo?.(0)
        process.stdout.write(question)
      }
      process.stdin.on('data', onData)
      rl.question(question, (answer) => {
        process.stdin.removeListener('data', onData)
        rl.close()
        process.stdout.write('\n')
        resolve(answer)
      })
      return
    }
    rl.question(question, (answer) => {
      rl.close()
      resolve(answer)
    })
  })
}

const USAGE = `dsh-remote-gateway —— dsh 远程网关凭据管理

用法:
  dsh-remote-gateway set-password [username]   交互式设置/修改密码（推荐 username=admin）
  dsh-remote-gateway list-users                列出已配置的用户
  dsh-remote-gateway remove-user <username>    删除一个用户
  dsh-remote-gateway where                     打印状态目录路径

选项:
  --state-dir <path>   覆盖状态目录（默认 $DSH_HOME/dsh-remote-gateway）

环境变量:
  DSH_HOME             决定默认状态目录
  DSH_GATEWAY_PASSWORD 启动 dsh 时直接提供明文密码（首次登录后落库为 scrypt 哈希）
`

async function main() {
  const { positional, stateDir } = parseArgs(process.argv.slice(2))
  const command = positional[0]

  if (command === undefined || command === '--help' || command === '-h' || command === 'help') {
    console.log(USAGE)
    return 0
  }

  const store = new PasswordStore(stateDir)

  if (command === 'where') {
    console.log(`状态目录: ${stateDir}`)
    console.log(`用户库:   ${join(stateDir, 'users.json')}${existsSync(join(stateDir, 'users.json')) ? '' : '（尚未创建）'}`)
    console.log(`会话密钥: ${join(stateDir, 'session.key')}`)
    console.log(`审计日志: ${join(stateDir, 'audit.log')}`)
    return 0
  }

  if (command === 'list-users') {
    const users = store.list()
    if (users.length === 0) {
      console.log('（没有已配置的用户）')
      return 0
    }
    for (const user of users) console.log(user)
    return 0
  }

  if (command === 'remove-user') {
    const username = positional[1]
    if (username === undefined) {
      console.error('用法: dsh-remote-gateway remove-user <username>')
      return 2
    }
    console.log(store.remove(username) ? `已删除用户 ${username}` : `用户 ${username} 不存在`)
    return 0
  }

  if (command === 'set-password') {
    const username = positional[1] ?? 'admin'

    let password = ''
    if (process.env.DSH_GATEWAY_PASSWORD) {
      password = process.env.DSH_GATEWAY_PASSWORD
      console.log('已从环境变量 DSH_GATEWAY_PASSWORD 读取密码。')
    } else {
      if (!process.stdin.isTTY) {
        console.error('stdin 不是终端。请改用环境变量 DSH_GATEWAY_PASSWORD，或在交互式终端里运行。')
        return 2
      }
      password = await ask(`请输入 ${username} 的新密码（至少 ${MIN_PASSWORD_LENGTH} 位）: `, { hidden: true })
      const again = await ask('请再输入一次以确认: ', { hidden: true })
      if (password !== again) {
        console.error('两次输入不一致。')
        return 1
      }
    }

    const policyError = validatePasswordPolicy(password)
    if (policyError !== null) {
      console.error(`密码不符合策略: ${policyError}`)
      return 1
    }

    store.setPassword(username, password)
    console.log(`✓ 已为 ${username} 设置密码（scrypt 哈希，明文不落盘）`)
    console.log(`  用户库: ${join(stateDir, 'users.json')}`)
    console.log('  下次 dsh 启动时网关即会要求登录。')
    return 0
  }

  console.error(`未知命令: ${command}\n`)
  console.log(USAGE)
  return 2
}

process.exitCode = await main()
