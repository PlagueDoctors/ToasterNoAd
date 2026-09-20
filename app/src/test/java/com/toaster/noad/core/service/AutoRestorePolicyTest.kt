package com.toaster.noad.core.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutoRestorePolicy] 节流策略的 JVM 单测。
 *
 * 时钟为注入的计数器（`now` 变量），所有时间边界都可精确构造。
 * 策略没有 Android 依赖，这里验证的是纯时间逻辑：
 * 首次放行、间隔拒绝/放行、进程内上限。
 */
class AutoRestorePolicyTest {

    private var now = 0L

    private fun policy(
        minIntervalMs: Long = 30_000L,
        maxAttempts: Int = 10,
    ): AutoRestorePolicy = AutoRestorePolicy(
        clock = { now },
        minIntervalMs = minIntervalMs,
        maxAttemptsPerProcess = maxAttempts,
    )

    @Test
    fun givenNeverAttempted_whenShouldAttempt_thenAllowed() {
        assertTrue(policy().shouldAttempt())
    }

    @Test
    fun givenJustRecorded_whenShouldAttemptWithinInterval_thenDenied() {
        val p = policy()
        p.recordAttempt()
        now += 29_999L
        assertFalse(p.shouldAttempt())
    }

    @Test
    fun givenExactIntervalElapsed_whenShouldAttempt_thenAllowed() {
        val p = policy()
        p.recordAttempt()
        now += 30_000L
        // >= 语义：恰好到达间隔即放行（自动恢复是低频动作，不差这一毫秒）
        assertTrue(p.shouldAttempt())
    }

    @Test
    fun givenMaxAttemptsReached_whenShouldAttempt_thenDeniedEvenAfterLongTime() {
        val p = policy(maxAttempts = 2)
        p.recordAttempt()
        now += 1_000_000L
        p.recordAttempt()
        now += 1_000_000L
        assertFalse(p.shouldAttempt())
        // 间隔再久也不会重置上限：进程生命周期内封顶
        now += 10_000_000L
        assertFalse(p.shouldAttempt())
    }

    @Test
    fun givenDeniedByMaxAttempts_whenCheckedRepeatedly_thenAttemptCountUnchanged() {
        val p = policy(maxAttempts = 1)
        p.recordAttempt()
        now += 1_000_000L
        // 被上限拒绝的查询不记账（shouldAttempt 是纯查询，无副作用），
        // 因此间隔到达后它仍然拒绝，状态一致
        assertFalse(p.shouldAttempt())
        assertFalse(p.shouldAttempt())
    }

    @Test
    fun givenRecordAttempt_thenLastAttemptTimeIsRecordedForInterval() {
        val p = policy()
        now = 100_000L
        p.recordAttempt()
        now = 100_000L + 29_999L
        assertFalse(p.shouldAttempt())
        now = 100_000L + 30_000L
        assertTrue(p.shouldAttempt())
    }

    @Test
    fun givenCustomLimits_whenConstructed_thenRespected() {
        // 上限给 2：留出本次 recordAttempt 后仍有余量，
        // 使拒绝/放行只由自定义间隔决定，不与上限判定混淆
        val p = policy(minIntervalMs = 5L, maxAttempts = 2)
        p.recordAttempt()
        now += 4L
        assertFalse(p.shouldAttempt())
        now += 1L
        assertTrue(p.shouldAttempt())
    }

    @Test
    fun givenMultipleAttempts_thenIntervalMeasuredFromMostRecent() {
        val p = policy()
        p.recordAttempt() // t=0
        now = 40_000L
        assertTrue(p.shouldAttempt())
        p.recordAttempt() // t=40_000：以最近一次为基准
        now = 40_000L + 29_999L
        // 若错误地以第一次(t=0)为基准，此刻距它已 69_999ms > 间隔，
        // 会误放行 —— 断言拒绝即锁死「以最近一次为基准」
        assertFalse(p.shouldAttempt())
    }
}
