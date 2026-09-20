package com.toaster.noad.core.service.shizuku

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShizukuCapabilityProbe] 的 JVM 单测。
 *
 * 核心契约（方案 §6.2.4）：**能力必须逐项独立** ——
 * 某台设备缺 chain3 时，只该让「应用级断网」降级，
 * 其余能力（Private DNS / 包管理 / AppOps）必须保持可用。
 */
class ShizukuCapabilityProbeTest {

    private val self = "com.toaster.noad"

    /** 构建一个按命令返回不同输出的假 exec */
    private fun probeWith(
        ready: Boolean = true,
        chain3Output: String? = "$self:allow",
        settingsOutput: String? = "null",
        packagesOutput: String? = "package:com.example.disabled",
        appOpsOutput: String? = "No operations.\nDefault mode: allow",
        amOutput: String? = "0",
        throwOn: String? = null,
    ): ShizukuCapabilityProbe = ShizukuCapabilityProbe(
        exec = { args ->
            val joined = args.joinToString(" ")
            if (throwOn != null && joined.contains(throwOn)) throw IllegalStateException("boom")
            when {
                joined.contains("get-package-networking-enabled") -> chain3Output
                joined.contains("settings get global private_dns_mode") -> settingsOutput
                joined.contains("pm list packages") -> packagesOutput
                joined.contains("appops get") -> appOpsOutput
                joined.contains("am get-current-user") -> amOutput
                else -> null
            }
        },
        isReady = { ready },
        selfPackageName = self,
    )

    @Test
    fun givenNotReady_whenProbe_thenAllFalse() = runBlocking {
        val caps = probeWith(ready = false).probe()
        assertFalse(caps.available)
        assertFalse(caps.canControlPerAppNetwork)
        assertFalse(caps.canWriteSecureSettings)
        assertFalse(caps.canManagePackages)
        assertFalse(caps.canSetAppOps)
        assertFalse(caps.canForceStop)
    }

    @Test
    fun givenAllChannelsWorking_whenProbe_thenAllTrue() = runBlocking {
        val caps = probeWith().probe()
        assertTrue(caps.available)
        assertTrue(caps.canControlPerAppNetwork)
        assertTrue(caps.canWriteSecureSettings)
        assertTrue(caps.canManagePackages)
        assertTrue(caps.canSetAppOps)
        assertTrue(caps.canForceStop)
    }

    @Test
    fun givenChain3Unsupported_whenProbe_thenOnlyThatCapabilityFalse() = runBlocking {
        // ★ 核心契约：逐项独立降级
        val caps = probeWith(chain3Output = "Unknown command: connectivity").probe()

        assertFalse(caps.canControlPerAppNetwork)
        // 其余能力不受影响
        assertTrue(caps.canWriteSecureSettings)
        assertTrue(caps.canManagePackages)
        assertTrue(caps.canSetAppOps)
        assertTrue(caps.canForceStop)
    }

    @Test
    fun givenEmptyDisabledList_whenProbe_thenPackageManagerStillAvailable() = runBlocking {
        // 没有已禁用包时 `pm list packages -d` 输出为空 —— 这是合法结果，不是失败
        val caps = probeWith(packagesOutput = "").probe()
        assertTrue(caps.canManagePackages)
    }

    @Test
    fun givenExecThrows_whenProbe_thenNoCrashAndAllFalse() = runBlocking {
        // 通道抛异常（Shizuku 服务器恰好死亡）时探测必须收敛而不是崩溃
        val probe = ShizukuCapabilityProbe(
            exec = { throw IllegalStateException("binder dead") },
            isReady = { true },
            selfPackageName = self,
        )
        val caps = probe.probe()
        assertTrue(caps.available) // ready 仍为 true —— 逐项探测各自失败
        assertFalse(caps.canControlPerAppNetwork)
        assertFalse(caps.canWriteSecureSettings)
        assertFalse(caps.canManagePackages)
        assertFalse(caps.canSetAppOps)
        assertFalse(caps.canForceStop)
    }
}
