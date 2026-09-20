package com.toaster.noad.core.service.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppOpsOps] 的 JVM 单测。
 *
 * 🔴 最重要的两条契约：
 * 1. **命令必须使用字符串操作名，绝不含数值 opCode**
 *    （项目 R11 铁律：数值随版本漂移会静默改错权限）；
 * 2. **回读解析必须精确到目标 Op 行** —— 松散匹配会把别的 Op 的模式
 *    当成本 Op 的结果，产出「假成功」。
 */
class AppOpsOpsTest {

    @Test
    fun givenDenied_whenBuildSetCommand_thenStringOpAndIgnore() {
        val command = AppOpsOps.setModeCommand(
            "com.example.app",
            AppOpsOps.Op.RUN_IN_BACKGROUND,
            denied = true,
        )
        assertEquals(
            listOf("cmd", "appops", "set", "com.example.app", "RUN_IN_BACKGROUND", "ignore"),
            command,
        )
    }

    @Test
    fun givenAllowed_whenBuildSetCommand_thenAllow() {
        val command = AppOpsOps.setModeCommand(
            "com.example.app",
            AppOpsOps.Op.SYSTEM_ALERT_WINDOW,
            denied = false,
        )
        assertEquals(
            listOf("cmd", "appops", "set", "com.example.app", "SYSTEM_ALERT_WINDOW", "allow"),
            command,
        )
    }

    @Test
    fun givenAnyOpCommand_thenNeverContainsNumericOpCode() {
        // 🔴 铁律断言：所有 Op 的命令参数里不得出现纯数字参数
        AppOpsOps.Op.entries.forEach { op ->
            val command = AppOpsOps.setModeCommand("com.example.app", op, denied = true)
            val numericArgs = command.drop(3).filter { arg -> arg.toIntOrNull() != null }
            assertTrue(
                "op ${op.opName} 的命令不得含数值参数（实际: $command）",
                numericArgs.isEmpty(),
            )
        }
    }

    @Test
    fun givenAllOps_whenInspectNames_thenExpectedStrings() {
        assertEquals("RUN_IN_BACKGROUND", AppOpsOps.Op.RUN_IN_BACKGROUND.opName)
        assertEquals("SYSTEM_ALERT_WINDOW", AppOpsOps.Op.SYSTEM_ALERT_WINDOW.opName)
        assertEquals("GET_DEVICE_ID", AppOpsOps.Op.GET_DEVICE_ID.opName)
        assertEquals("READ_PHONE_STATE", AppOpsOps.Op.READ_PHONE_STATE.opName)
        assertEquals("REQUEST_INSTALL_PACKAGES", AppOpsOps.Op.REQUEST_INSTALL_PACKAGES.opName)
    }

    @Test
    fun givenNoOperationsOutput_whenParse_thenNotSet() {
        // 实机实测（未设置过时）：
        //   No operations.
        //   Default mode: allow
        val output = "No operations.\nDefault mode: allow"
        assertEquals(
            AppOpsOps.ModeResult.NotSet,
            AppOpsOps.parseMode(output, AppOpsOps.Op.RUN_IN_BACKGROUND),
        )
    }

    @Test
    fun givenIgnoreOutput_whenParse_thenResolvedDenied() {
        val output = "RUN_IN_BACKGROUND: ignore"
        assertEquals(
            AppOpsOps.ModeResult.Resolved(denied = true),
            AppOpsOps.parseMode(output, AppOpsOps.Op.RUN_IN_BACKGROUND),
        )
    }

    @Test
    fun givenAllowOutput_whenParse_thenResolvedAllowed() {
        val output = "RUN_IN_BACKGROUND: allow"
        assertEquals(
            AppOpsOps.ModeResult.Resolved(denied = false),
            AppOpsOps.parseMode(output, AppOpsOps.Op.RUN_IN_BACKGROUND),
        )
    }

    @Test
    fun givenOtherOpLineOnly_whenParse_thenUnknown() {
        // 精确到目标 Op 行：别的 Op 的模式不能当作本 Op 的结果
        val output = "SYSTEM_ALERT_WINDOW: ignore"
        assertEquals(
            AppOpsOps.ModeResult.Unknown,
            AppOpsOps.parseMode(output, AppOpsOps.Op.RUN_IN_BACKGROUND),
        )
    }

    @Test
    fun givenMultiLineOutput_whenParse_thenPicksTargetOpLine() {
        val output = """
            SYSTEM_ALERT_WINDOW: allow
            RUN_IN_BACKGROUND: ignore
            READ_PHONE_STATE: allow
        """.trimIndent()
        assertEquals(
            AppOpsOps.ModeResult.Resolved(denied = true),
            AppOpsOps.parseMode(output, AppOpsOps.Op.RUN_IN_BACKGROUND),
        )
    }

    @Test
    fun givenNullOutput_whenParse_thenUnknown() {
        assertEquals(
            AppOpsOps.ModeResult.Unknown,
            AppOpsOps.parseMode(null, AppOpsOps.Op.RUN_IN_BACKGROUND),
        )
    }

    @Test
    fun givenIgnoreOutput_whenCheckIsDenied_thenTrue() {
        assertTrue(AppOpsOps.isDenied("RUN_IN_BACKGROUND: ignore", AppOpsOps.Op.RUN_IN_BACKGROUND))
        assertFalse(AppOpsOps.isDenied("RUN_IN_BACKGROUND: allow", AppOpsOps.Op.RUN_IN_BACKGROUND))
        assertFalse(AppOpsOps.isDenied("No operations.", AppOpsOps.Op.RUN_IN_BACKGROUND))
    }
}
