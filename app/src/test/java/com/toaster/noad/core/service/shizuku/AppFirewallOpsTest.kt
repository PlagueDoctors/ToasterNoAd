package com.toaster.noad.core.service.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppFirewallOps] 的 JVM 单测。
 *
 * 锁定三类契约：
 * 1. **取反语义**（最容易写错的一处）：本应用语义是「阻断」，
 *    而命令参数是「联网是否可用」—— blocked=true 必须映射为 networking=false；
 * 2. **实机输出格式解析**（`<pkg>:allow`，来自 NX789J / Android 15 实测）；
 * 3. **失败识别**：任何异常文本都不能被误判为「已阻断/未阻断」。
 */
class AppFirewallOpsTest {

    @Test
    fun givenBlockedTrue_whenBuildSetCommand_thenNetworkingFalse() {
        val command = AppFirewallOps.setBlockedCommand("tv.danmaku.bili", blocked = true)
        assertEquals(
            listOf("cmd", "connectivity", "set-package-networking-enabled", "false", "tv.danmaku.bili"),
            command,
        )
    }

    @Test
    fun givenBlockedFalse_whenBuildSetCommand_thenNetworkingTrue() {
        val command = AppFirewallOps.setBlockedCommand("tv.danmaku.bili", blocked = false)
        assertEquals(
            listOf("cmd", "connectivity", "set-package-networking-enabled", "true", "tv.danmaku.bili"),
            command,
        )
    }

    @Test
    fun givenEnableChain3_whenBuildCommand_thenExpectedArgs() {
        assertEquals(
            listOf("cmd", "connectivity", "set-chain3-enabled", "true"),
            AppFirewallOps.enableChain3Command(),
        )
    }

    @Test
    fun givenQuery_whenBuildCommand_thenExpectedArgs() {
        assertEquals(
            listOf("cmd", "connectivity", "get-package-networking-enabled", "com.tencent.mm"),
            AppFirewallOps.queryCommand("com.tencent.mm"),
        )
    }

    @Test
    fun givenRealDeviceAllowOutput_whenParse_thenResolvedNotBlocked() {
        // 实机实测输出格式（NX789J / Android 15）
        val result = AppFirewallOps.parseQuery("tv.danmaku.bili:allow")
        assertEquals(AppFirewallOps.QueryResult.Resolved(blocked = false), result)
    }

    @Test
    fun givenDenyOutput_whenParse_thenResolvedBlocked() {
        assertEquals(
            AppFirewallOps.QueryResult.Resolved(blocked = true),
            AppFirewallOps.parseQuery("tv.danmaku.bili:deny"),
        )
    }

    @Test
    fun givenBooleanOutput_whenParse_thenResolved() {
        // 部分版本可能返回裸布尔值
        assertEquals(
            AppFirewallOps.QueryResult.Resolved(blocked = false),
            AppFirewallOps.parseQuery("true"),
        )
        assertEquals(
            AppFirewallOps.QueryResult.Resolved(blocked = true),
            AppFirewallOps.parseQuery("false"),
        )
    }

    @Test
    fun givenExceptionOutput_whenParse_thenUnsupported() {
        // 实机实测的失败输出（包不存在）
        val failure = "android.content.pm.PackageManager\$NameNotFoundException: com.example.test"
        assertEquals(AppFirewallOps.QueryResult.Unsupported, AppFirewallOps.parseQuery(failure))
    }

    @Test
    fun givenNullOutput_whenParse_thenUnsupported() {
        assertEquals(AppFirewallOps.QueryResult.Unsupported, AppFirewallOps.parseQuery(null))
    }

    @Test
    fun givenGarbageOutput_whenParse_thenUnknown() {
        // 宁可 Unknown 也不猜 —— 猜错会让上层重复下发或误判成功
        assertEquals(AppFirewallOps.QueryResult.Unknown, AppFirewallOps.parseQuery("something odd"))
    }

    @Test
    fun givenFailureMarkers_thenIsFailureOutputTrue() {
        assertTrue(AppFirewallOps.isFailureOutput(null))
        assertTrue(AppFirewallOps.isFailureOutput("Unknown command: connectivity"))
        assertTrue(AppFirewallOps.isFailureOutput("java.lang.SecurityException"))
        assertTrue(AppFirewallOps.isFailureOutput("bad operation"))
        assertFalse(AppFirewallOps.isFailureOutput("tv.danmaku.bili:allow"))
    }

    @Test
    fun givenParsableOutput_whenProbeSupported_thenTrue() {
        assertTrue(AppFirewallOps.probeSupported("com.toaster.noad:allow"))
        assertFalse(AppFirewallOps.probeSupported("Unknown command"))
        assertFalse(AppFirewallOps.probeSupported(null))
    }
}
