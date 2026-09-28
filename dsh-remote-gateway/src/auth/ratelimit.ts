/**
 * 登录限速：滑动窗口，按客户端 IP 计。
 *
 * 只统计**失败**尝试 —— 成功登录会清零该 IP 的计数，避免正常用户被自己
 * 之前的输错拖累。窗口是滑动的（记录每次失败的时间戳），不是固定桶，
 * 这样攻击者无法通过在窗口边界集中尝试来获得双倍额度。
 *
 * 内存安全：桶数量有硬上限，且定期淘汰过期桶。否则海量伪造源 IP 会把
 * 限速器本身变成内存耗尽的入口。
 */
import { errText, warn } from '../log.js'

/** 最多同时跟踪多少个来源桶。 */
const MAX_BUCKETS = 20000

/** 滑动窗口限速器。 */
export class RateLimiter {
  private readonly attempts = new Map<string, number[]>()

  constructor(
    private readonly maxAttempts: number,
    private readonly windowMs: number,
  ) {}

  /** 淘汰时间窗外的记录；桶空了就删掉。 */
  private prune(key: string, now: number): number[] {
    const list = this.attempts.get(key)
    if (list === undefined) return []
    const cutoff = now - this.windowMs
    // 时间戳按写入顺序递增，从头砍即可。
    let drop = 0
    while (drop < list.length && (list[drop] as number) <= cutoff) drop++
    if (drop > 0) list.splice(0, drop)
    if (list.length === 0) {
      this.attempts.delete(key)
      return []
    }
    return list
  }

  /**
   * 当前是否被限速。
   * @returns 0 表示放行；否则为建议的重试等待毫秒数。
   */
  retryAfterMs(key: string, now = Date.now()): number {
    const list = this.prune(key, now)
    if (list.length < this.maxAttempts) return 0
    const oldest = list[0] as number
    return Math.max(1, oldest + this.windowMs - now)
  }

  /** 记一次失败。 */
  recordFailure(key: string, now = Date.now()): void {
    const list = this.prune(key, now)
    list.push(now)
    this.attempts.set(key, list)
    if (this.attempts.size > MAX_BUCKETS) this.evictOldest()
  }

  /** 登录成功后清零。 */
  reset(key: string): void {
    this.attempts.delete(key)
  }

  /** 桶数超限时，按「最后活跃时间」淘汰一批，保证内存有界。 */
  private evictOldest(): void {
    try {
      const entries = [...this.attempts.entries()]
      entries.sort((a, b) => (a[1][a[1].length - 1] ?? 0) - (b[1][b[1].length - 1] ?? 0))
      const removeCount = Math.ceil(entries.length / 4)
      for (let i = 0; i < removeCount; i++) {
        const entry = entries[i]
        if (entry !== undefined) this.attempts.delete(entry[0])
      }
      warn(`限速器桶数超限，已淘汰 ${removeCount} 个最旧来源`)
    } catch (error) {
      warn(`限速器淘汰失败: ${errText(error)}`)
      this.attempts.clear()
    }
  }

  /** 当前跟踪的来源数量（测试用）。 */
  get size(): number {
    return this.attempts.size
  }
}
