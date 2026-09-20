package com.toaster.noad.core.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProcessController] 的 JVM 单测（F7 强制停止）。
 *
 * 重点锁定**冷却语义**（方案 §6.8.2：强停是破坏性操作，
 * 同一应用在冷却窗口内绝不重复执行）与回读验证。
 * 时钟注入（项目惯例），冷却逻辑可确定性断言。
 */
class ProcessControllerTest {

    private var now = 1_000_000L
    private var running = true

    /** 假 exec：force-stop 会把 running 置 false；pidof 反映 running */
    private fun controller(
        cooldownMs: Long = 10_000L,
        ready: Boolean = true,
    ): ProcessController = ProcessController(
        exec = { args ->
            when {
                args.first() == "am" && args.getOrNull(1) == "force-stop" -> {
                    running = false
                    ""
                }
                args.first() == "pidof" -> if (running) "12345" else ""
                else -> null
            }
        },
        isReady = { ready },
        clock = { now },
        cooldownMs = cooldownMs,
    )

    @Test
    fun givenRunningApp_whenForceStop_thenStoppedAndVerified() = runBlocking {
        running = true
        val outcome = controller().forceStop("com.example.app")

        assertEquals(ProcessController.Outcome.Stopped(wasRunning = true), outcome)
        assertFalse(running)
    }

    @Test
    fun givenAlreadyStoppedApp_whenForceStop_thenStoppedWithoutWasRunning() = runBlocking {
        running = false
        val outcome = controller().forceStop("com.example.app")

        assertEquals(ProcessController.Outcome.Stopped(wasRunning = false), outcome)
    }

    @Test
    fun givenSecondCallWithinCooldown_whenForceStop_thenCooldown() = runBlocking {
        running = true
        val controller = controller(cooldownMs = 10_000L)

        assertEquals(
            ProcessController.Outcome.Stopped(wasRunning = true),
            controller.forceStop("com.example.app"),
        )

        now += 3_000L // 冷却窗口内
        val second = controller.forceStop("com.example.app")
        assertTrue(second is ProcessController.Outcome.Cooldown)
        assertEquals(7_000L, (second as ProcessController.Outcome.Cooldown).remainingMs)
    }

    @Test
    fun givenCallAfterCooldown_whenForceStop_thenAllowedAgain() = runBlocking {
        running = true
        val controller = controller(cooldownMs = 10_000L)
        controller.forceStop("com.example.app")

        now += 10_000L // 恰好到达冷却终点（>= 语义放行）
        running = true
        val second = controller.forceStop("com.example.app")
        assertTrue(second is ProcessController.Outcome.Stopped)
    }

    @Test
    fun givenDifferentPackages_whenForceStop_thenCooldownIsPerPackage() = runBlocking {
        running = true
        val controller = controller()

        assertTrue(controller.forceStop("com.a") is ProcessController.Outcome.Stopped)
        // 另一个应用不受前者冷却影响
        assertTrue(controller.forceStop("com.b") is ProcessController.Outcome.Stopped)
    }

    @Test
    fun givenChannelNotReady_whenForceStop_thenUnavailable() = runBlocking {
        val outcome = controller(ready = false).forceStop("com.example.app")
        assertEquals(ProcessController.Outcome.ChannelUnavailable, outcome)
    }

    @Test
    fun givenProcessPersists_whenForceStop_thenVerifyMismatch() = runBlocking {
        // 部分 ROM 强停后立即被自启拉起 —— 回读能识别这种「假成功」
        val controller = ProcessController(
            exec = { args ->
                when {
                    args.first() == "am" -> ""
                    args.first() == "pidof" -> "99999" // 仍在运行
                    else -> null
                }
            },
            isReady = { true },
            clock = { now },
        )
        assertEquals(ProcessController.Outcome.VerifyMismatch, controller.forceStop("com.example.app"))
    }

    @Test
    fun givenPidofEmpty_whenIsRunning_thenFalse() = runBlocking {
        // pidof 无匹配时输出空（rc=1），不能当作命令失败
        running = false
        assertEquals(false, controller().isRunning("com.example.app"))
    }

    @Test
    fun givenExecThrows_whenIsRunning_thenNull() = runBlocking {
        val controller = ProcessController(
            exec = { throw IllegalStateException("binder dead") },
            isReady = { true },
            clock = { now },
        )
        assertNull(controller.isRunning("com.example.app"))
    }

    @Test
    fun givenCooldownCleared_whenForceStop_thenAllowedImmediately() = runBlocking {
        running = true
        val controller = controller()
        controller.forceStop("com.example.app")

        controller.resetCooldown()
        running = true
        assertTrue(controller.forceStop("com.example.app") is ProcessController.Outcome.Stopped)
    }
}
