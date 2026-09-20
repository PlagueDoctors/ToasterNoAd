package com.toaster.noad.core.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccessibilityAutoRestorer] 自动恢复入口的 JVM 单测。
 *
 * 通道判定与恢复动作均为注入函数（记录调用到 [executed]），
 * 节流策略用注入时钟构造。锁定四条契约：
 *
 * 1. 通道不在位 → 返回 null 且**绝不**调用恢复；
 * 2. 策略拒绝 → 返回 null 且不调用恢复；
 * 3. 通道缺席**不消耗**策略记账（没写任何东西就不算拉锯一方）；
 * 4. 真实执行时，节流在恢复动作**之前**记账，且结果透传给调用方。
 */
class AccessibilityAutoRestorerTest {

    private var securePermission = true
    private var now = 0L
    private val executed = mutableListOf<String>()

    private val outcomes =
        ArrayDeque(listOf(AccessibilityRecoveryController.RestoreOutcome.RESTORED))

    private fun restorer(): AccessibilityAutoRestorer = AccessibilityAutoRestorer(
        policy = AutoRestorePolicy(clock = { now }),
        hasSecureWritePermission = { securePermission },
        secureRestore = {
            executed.add("restore")
            outcomes.removeFirstOrNull()
                ?: AccessibilityRecoveryController.RestoreOutcome.RESTORED
        },
    )

    @Test
    fun givenNoSecurePermission_whenMaybeRestore_thenNullWithoutRestore() = runBlocking {
        securePermission = false

        assertNull(restorer().maybeRestore())
        assertTrue(executed.isEmpty())
    }

    @Test
    fun givenPolicyDenies_whenMaybeRestore_thenNullWithoutRestore() = runBlocking {
        val r = restorer()
        r.maybeRestore() // t=0：首次执行并记账
        now += 1_000L    // 间隔内

        executed.clear()
        assertNull(r.maybeRestore())
        assertTrue(executed.isEmpty())
    }

    @Test
    fun givenChannelAndPolicyAllow_whenMaybeRestore_thenRestoresAndReturnsOutcome() =
        runBlocking {
            val outcome = restorer().maybeRestore()

            assertEquals(
                AccessibilityRecoveryController.RestoreOutcome.RESTORED,
                outcome,
            )
            assertEquals(listOf("restore"), executed)
        }

    @Test
    fun givenChannelMissingCalls_whenMaybeRestore_thenPolicyNotConsumed() = runBlocking {
        val r = restorer()
        // 通道缺席连续被短路多次（例如权限被撤销后反复回前台）
        securePermission = false
        repeat(3) { assertNull(r.maybeRestore()) }
        // 通道恢复在位（用户重新 adb 授权）后第一次即放行：
        // 若通道缺席路径消耗过记账，此处 30s 间隔内会被拒绝
        securePermission = true
        now += 1L
        assertEquals(
            AccessibilityRecoveryController.RestoreOutcome.RESTORED,
            r.maybeRestore(),
        )
        assertEquals(1, executed.size)
    }

    @Test
    fun givenAlreadyPresentOutcome_whenMaybeRestore_thenPassedThrough() = runBlocking {
        outcomes.clear()
        outcomes.addLast(AccessibilityRecoveryController.RestoreOutcome.ALREADY_PRESENT)

        assertEquals(
            AccessibilityRecoveryController.RestoreOutcome.ALREADY_PRESENT,
            restorer().maybeRestore(),
        )
    }

    @Test
    fun givenExecuted_whenMaybeRestoreAgainImmediately_thenThrottledToSingleExecution() =
        runBlocking {
            val r = restorer()
            r.maybeRestore()

            executed.clear()
            now += 1_000L
            assertNull(r.maybeRestore())
            // 记账发生在恢复动作之前：即使恢复抛异常，同一间隔内
            // 也不会被再次发起 —— 这是防拉锯的关键顺序
            assertTrue(executed.isEmpty())
        }
}
