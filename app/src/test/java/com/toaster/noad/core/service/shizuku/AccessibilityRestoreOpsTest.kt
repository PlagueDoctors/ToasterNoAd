package com.toaster.noad.core.service.shizuku

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccessibilityRestoreOps] 纯层测试。
 *
 * 覆盖三类行为：输出解析（脏形态容忍）、列表合并（保护他人条目）、
 * 恢复序列（read → merge → put → put master → read 验证的顺序与分支）。
 * 序列测试用注入的 exec lambda 记录调用，验证「命令顺序」与「条件跳过」——
 * 这是本链路最容易写错的部分（写错顺序会把别人的条目覆盖掉）。
 *
 * 诚实边界：`settings get/put` 的真实行为属 Android 平台侧，
 * 由用户实机验证；此处只锁定命令构造与序列决策。
 */
class AccessibilityRestoreOpsTest {

    // ---------- 解析：parseEntries ----------

    @Test
    fun givenNullRaw_whenParseEntries_thenEmptyList() {
        assertTrue(AccessibilityRestoreOps.parseEntries(null).isEmpty())
    }

    @Test
    fun givenLiteralNull_whenParseEntries_thenEmptyList() {
        // settings get 对不存在的键返回字面量 "null"，不是空串
        assertTrue(AccessibilityRestoreOps.parseEntries("null").isEmpty())
    }

    @Test
    fun givenLiteralNullMixedCase_whenParseEntries_thenEmptyList() {
        assertTrue(AccessibilityRestoreOps.parseEntries("NULL").isEmpty())
    }

    @Test
    fun givenBlankRaw_whenParseEntries_thenEmptyList() {
        assertTrue(AccessibilityRestoreOps.parseEntries("   ").isEmpty())
    }

    @Test
    fun givenLeadingColon_whenParseEntries_thenNoEmptyEntries() {
        assertEquals(
            listOf("a/App", "b/Tool"),
            AccessibilityRestoreOps.parseEntries(":a/App:b/Tool"),
        )
    }

    @Test
    fun givenTrailingColon_whenParseEntries_thenNoEmptyEntries() {
        assertEquals(
            listOf("a/App"),
            AccessibilityRestoreOps.parseEntries("a/App:"),
        )
    }

    @Test
    fun givenWhitespaceAroundEntries_whenParseEntries_thenTrimmed() {
        assertEquals(
            listOf("a/App", "b/Tool"),
            AccessibilityRestoreOps.parseEntries(" a/App : b/Tool "),
        )
    }

    @Test
    fun givenMultipleEntries_whenParseEntries_thenAllInOrder() {
        assertEquals(
            listOf("a/App", "b/Tool", "c/Helper"),
            AccessibilityRestoreOps.parseEntries("a/App:b/Tool:c/Helper"),
        )
    }

    // ---------- 包含判断：containsService ----------

    @Test
    fun givenExactMatch_whenContainsService_thenTrue() {
        val flat = "com.toaster.noad/com.toaster.noad.core.service.NoAdAccessibilityService"
        assertTrue(AccessibilityRestoreOps.containsService(":$flat", flat))
    }

    @Test
    fun givenCaseDifference_whenContainsService_thenTrue() {
        assertTrue(
            AccessibilityRestoreOps.containsService(
                ":com.Toaster.Noad/com.toaster.noad.core.service.noadaccessibilityservice",
                "com.toaster.noad/com.toaster.noad.core.service.NoAdAccessibilityService",
            ),
        )
    }

    @Test
    fun givenOnlyOtherServices_whenContainsService_thenFalse() {
        assertTrue(
            !AccessibilityRestoreOps.containsService(
                ":a/App:b/Tool",
                "com.toaster.noad/com.toaster.noad.core.service.NoAdAccessibilityService",
            ),
        )
    }

    @Test
    fun givenEmptyRaw_whenContainsService_thenFalse() {
        assertTrue(!AccessibilityRestoreOps.containsService(null, "a/App"))
        assertTrue(!AccessibilityRestoreOps.containsService("null", "a/App"))
        assertTrue(!AccessibilityRestoreOps.containsService("", "a/App"))
    }

    // ---------- 合并：mergeEnabledServices ----------

    @Test
    fun givenNullRaw_whenMerge_thenReturnFlatOnly() {
        assertEquals("a/App", AccessibilityRestoreOps.mergeEnabledServices(null, "a/App"))
    }

    @Test
    fun givenLiteralNull_whenMerge_thenReturnFlatOnly() {
        assertEquals("a/App", AccessibilityRestoreOps.mergeEnabledServices("null", "a/App"))
    }

    @Test
    fun givenOtherServices_whenMerge_thenPreservedAndFlatAppendedLast() {
        assertEquals(
            "a/App:b/Tool:com.toaster.noad/com.toaster.noad.core.service.NoAdAccessibilityService",
            AccessibilityRestoreOps.mergeEnabledServices(
                ":a/App:b/Tool",
                "com.toaster.noad/com.toaster.noad.core.service.NoAdAccessibilityService",
            ),
        )
    }

    @Test
    fun givenFlatAlreadyPresent_whenMerge_thenNoDuplicate() {
        val flat = "com.toaster.noad/com.toaster.noad.core.service.NoAdAccessibilityService"
        assertEquals(flat, AccessibilityRestoreOps.mergeEnabledServices(flat, flat))
    }

    @Test
    fun givenFlatPresentWithCaseDifference_whenMerge_thenOriginalEntryPreservedUnchanged() {
        // 合并对「已存在的条目」零改写：不追加、也不把大小写变体规范化成自己的写法。
        // 对系统既有状态做计划外变更属于超出必要的行为；大小写差异不影响系统匹配
        //（containsService 的契约本身就是大小写不敏感）。
        val flat = "com.toaster.noad/com.toaster.noad.core.service.NoAdAccessibilityService"
        val upper = "COM.TOASTER.NOAD/com.toaster.noad.core.service.NoAdAccessibilityService"
        assertEquals(upper, AccessibilityRestoreOps.mergeEnabledServices(upper, flat))
    }

    @Test
    fun givenDirtySeparators_whenMerge_thenNormalized() {
        // 前导冒号 + 空白被清洗，产出可直接写回的规范化值
        assertEquals(
            "a/App:b/Tool",
            AccessibilityRestoreOps.mergeEnabledServices(" a/App : :b/Tool:", "b/Tool"),
        )
    }

    // ---------- 命令构造 ----------

    @Test
    fun whenBuildReadCommand_thenSettingsGetSecure() {
        assertEquals(
            listOf("settings", "get", "secure", "enabled_accessibility_services"),
            AccessibilityRestoreOps.readEnabledServicesCommand(),
        )
    }

    @Test
    fun whenBuildWriteCommand_thenMergedValueIsSingleLastArg() {
        // 合并值必须作为单个参数传入（UserService 走 ProcessBuilder，
        // 不经 shell 分词 —— 含 : 与 / 的值不会被错误拆开）
        assertEquals(
            listOf("settings", "put", "secure", "enabled_accessibility_services", "a/App:b/Tool"),
            AccessibilityRestoreOps.writeEnabledServicesCommand("a/App:b/Tool"),
        )
    }

    @Test
    fun whenBuildMasterSwitchCommand_thenAccessibilityEnabledOne() {
        assertEquals(
            listOf("settings", "put", "secure", "accessibility_enabled", "1"),
            AccessibilityRestoreOps.writeMasterSwitchCommand(),
        )
    }

    @Test
    fun givenAnyConstructedCommand_whenInspectArgs_thenNoBlankArg() {
        val all = AccessibilityRestoreOps.readEnabledServicesCommand() +
            AccessibilityRestoreOps.writeEnabledServicesCommand("a/App") +
            AccessibilityRestoreOps.writeMasterSwitchCommand()
        assertTrue(all.all { it.isNotBlank() })
    }

    // ---------- 终态决策：decideRestoreOutcome ----------

    @Test
    fun givenNotVerified_whenDecide_thenFailedWithDiagnostics() {
        val outcome = AccessibilityRestoreOps.decideRestoreOutcome(
            alreadyPresent = false,
            verified = false,
            diagnostics = "Error",
        )
        assertEquals(AccessibilityRestoreOps.RestoreOutcome.Failed("Error"), outcome)
    }

    @Test
    fun givenVerifiedAndAlreadyPresent_whenDecide_thenAlreadyPresent() {
        val outcome = AccessibilityRestoreOps.decideRestoreOutcome(
            alreadyPresent = true,
            verified = true,
            diagnostics = null,
        )
        assertEquals(AccessibilityRestoreOps.RestoreOutcome.AlreadyPresent, outcome)
    }

    @Test
    fun givenVerifiedAndNotPresentBefore_whenDecide_thenRestored() {
        val outcome = AccessibilityRestoreOps.decideRestoreOutcome(
            alreadyPresent = false,
            verified = true,
            diagnostics = null,
        )
        assertEquals(AccessibilityRestoreOps.RestoreOutcome.Restored, outcome)
    }

    // ---------- 恢复序列：restore（exec 注入） ----------

    /** 记录调用顺序并按脚本吐输出的假 exec */
    private class ScriptedExec(vararg script: String?) {
        val commands = mutableListOf<List<String>>()
        private val outputs = script.toList()
        private var index = 0

        val exec: suspend (List<String>) -> String? = { command ->
            commands += command
            val result = outputs.getOrNull(index)
            index++
            result
        }
    }

    private val flat = "com.toaster.noad/com.toaster.noad.core.service.NoAdAccessibilityService"

    @Test
    fun givenChannelUnavailableOnFirstRead_whenRestore_thenFailedWithoutAnyWrite() {
        val exec = ScriptedExec(null)

        val outcome = runBlocking { AccessibilityRestoreOps.restore(exec.exec, flat) }

        assertTrue(outcome is AccessibilityRestoreOps.RestoreOutcome.Failed)
        // 通道不可用时绝不写任何东西 —— 拿不到当前值就无法保护他人条目
        assertEquals(1, exec.commands.size)
    }

    @Test
    fun givenHappyPath_whenRestore_thenFourCommandsInExpectedOrder() {
        val read = AccessibilityRestoreOps.readEnabledServicesCommand()
        val write = AccessibilityRestoreOps.writeEnabledServicesCommand(flat)
        val master = AccessibilityRestoreOps.writeMasterSwitchCommand()
        val exec = ScriptedExec("null", "ok", "ok", ":$flat")

        val outcome = runBlocking { AccessibilityRestoreOps.restore(exec.exec, flat) }

        assertEquals(AccessibilityRestoreOps.RestoreOutcome.Restored, outcome)
        assertEquals(listOf(read, write, master, read), exec.commands)
    }

    @Test
    fun givenOtherServicesPresent_whenRestore_thenWritePreservesThem() {
        val exec = ScriptedExec(":a/App:b/Tool", "ok", "ok", ":a/App:b/Tool:$flat")

        val outcome = runBlocking { AccessibilityRestoreOps.restore(exec.exec, flat) }

        assertEquals(AccessibilityRestoreOps.RestoreOutcome.Restored, outcome)
        // 序列固定：commands[1] 必是服务条目写（read → write → master → read）；
        // read 与 write 的第 4 参都是键名，不能按键名匹配命令
        assertEquals(
            AccessibilityRestoreOps.writeEnabledServicesCommand("a/App:b/Tool:$flat"),
            exec.commands[1],
        )
    }

    @Test
    fun givenAlreadyPresent_whenRestore_thenSkipsServicesWriteButStillPutsMaster() {
        val exec = ScriptedExec(":$flat", "ok", ":$flat")

        val outcome = runBlocking { AccessibilityRestoreOps.restore(exec.exec, flat) }

        assertEquals(AccessibilityRestoreOps.RestoreOutcome.AlreadyPresent, outcome)
        // 只剩 read / master put / read 三条 —— 服务条目写被跳过
        assertEquals(
            listOf(
                AccessibilityRestoreOps.readEnabledServicesCommand(),
                AccessibilityRestoreOps.writeMasterSwitchCommand(),
                AccessibilityRestoreOps.readEnabledServicesCommand(),
            ),
            exec.commands,
        )
    }

    @Test
    fun givenVerifyReadUnavailable_whenRestore_thenFailedWithBeforeOutputAsDiagnostics() {
        // 首读输出 "null"（键不存在）→ 失败诊断取「首读输出的末行」，即字面量 "null"
        val exec = ScriptedExec("null", "ok", "ok", null)

        val outcome = runBlocking { AccessibilityRestoreOps.restore(exec.exec, flat) }

        assertEquals(AccessibilityRestoreOps.RestoreOutcome.Failed("null"), outcome)
    }

    @Test
    fun givenWriteSilentlyRejected_whenRestore_thenFailedWithEmptyReadDiagnostics() {
        // 写被 SELinux/ROM 静默拒绝：不抛异常、输出无关文本 —— 回读验证是唯一防线。
        // 回读输出为空 → 诊断尾行为 null（无可展示明细）
        val exec = ScriptedExec("null", "Error: permission denied", "ok", "")

        val outcome = runBlocking { AccessibilityRestoreOps.restore(exec.exec, flat) }

        assertEquals(AccessibilityRestoreOps.RestoreOutcome.Failed(null), outcome)
    }
}
