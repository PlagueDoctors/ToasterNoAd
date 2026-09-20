package com.toaster.noad.core.service

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 自动恢复的「防拉锯」节流策略（R14）。
 *
 * ## 它防的是什么
 *
 * 自动恢复（检测到授权丢失后不经用户点击直接写 `Settings.Secure`）
 * 一旦失控就会退化成 R8 明确否决的「周期性后台写」：如果某个 ROM
 * 检测到设置被改又再次撤销，应用再改、ROM 再撤……双方进入无限拉锯，
 * 用户设备白白耗电。
 *
 * 因此任何一次自动尝试都必须先过本策略：
 *
 * - **最小间隔** [minIntervalMs]：两次自动尝试之间至少隔这么久，
 *   防止 ON_RESUME 高频触发（用户快速来回切应用）造成写风暴；
 * - **进程内上限** [maxAttemptsPerProcess]：单次进程生命周期内
 *   自动尝试最多这么多次，超过后本进程内不再自动尝试
 *   （手动按钮不受限——用户显式点击总是允许的）。
 *
 * ## 为什么状态放在进程内、不持久化
 *
 * 上限与间隔的目的是「防拉锯」，不是「记账」。进程死亡意味着
 * 自动恢复的执行者本身也死了（force-stop 后没有任何代码在跑）；
 * 进程被用户重新打开时，内存态清零、允许立即尝试一次 ——
 * 这正是期望行为（用户打开应用 = 明确想用 = 应该尽快恢复）。
 * 持久化反而会在进程重启后错误地沿用旧账，让用户白等。
 *
 * ## 时钟为什么注入
 *
 * 与 [AccessibilityStateHolder.clock] 同一理由：`SystemClock` 在
 * JVM 单测中会抛 "not mocked"，直接调用会让节流逻辑无法验证。
 * 这里只关心「过了多久」，用单调时钟（不受改时间/时区影响）。
 */
class AutoRestorePolicy(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxAttemptsPerProcess: Int = DEFAULT_MAX_ATTEMPTS,
) {

    /** 从未尝试过。用哨兵值而不是 0：elapsedRealtime 理论上可能接近 0。 */
    private val lastAttemptAt = AtomicLong(NEVER)

    private val attempts = AtomicInteger(0)

    /**
     * 此刻是否允许发起一次自动尝试。
     *
     * 注意「允许」不等于「会执行」：调用方还要过通道检查与
     * 用户意图判断（应用内开关、授权确实丢失）。
     */
    fun shouldAttempt(now: Long = clock()): Boolean {
        if (attempts.get() >= maxAttemptsPerProcess) return false
        val last = lastAttemptAt.get()
        if (last == NEVER) return true
        return now - last >= minIntervalMs
    }

    /**
     * 记录一次**实际发起**的尝试（无论结果成败）。
     *
     * 只允许在真正触发恢复动作前调用 —— 被「通道不可用」挡下的
     * 调用不算尝试（那不是拉锯的一方，没有写任何东西）。
     */
    fun recordAttempt(now: Long = clock()) {
        lastAttemptAt.set(now)
        attempts.incrementAndGet()
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val DEFAULT_MIN_INTERVAL_MS = 30_000L
        const val DEFAULT_MAX_ATTEMPTS = 10
    }
}
