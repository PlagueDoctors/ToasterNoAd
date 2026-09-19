package com.toaster.noad.core.service.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧载「受限设置」解除纯决策层测试（R11 / 阶段 F2）。
 *
 * ## 测试范围与诚实边界
 *
 * 覆盖三块**可以在 JVM 上验证**的逻辑：
 * 1. 命令构造 —— op 名必须是字符串形式（§6.5.2 禁止数值硬编码的落实），
 *    `--uid` 变体的参数位置必须正确
 * 2. 输出解析 —— `cmd appops get` 在不同 ROM 上的真实输出形态，
 *    以及 op 名不被识别时的报错文本不得被误解析
 * 3. 流程决策 —— 初始已允许时不做任何写操作；成功判定只认回读验证
 *
 * **不覆盖**：Shizuku 绑定、UserService 进程内执行、真实 ROM 的
 * appops 行为 —— 那些需要真机 + Shizuku 环境，由用户实机验证
 * （与本项目的交付验证标准一致）。
 */
class RestrictedSettingsOpsTest {

    private val pkg = "com.toaster.noad"

    // ---- 命令构造 ----

    @Test
    fun givenPackageVariant_whenBuildGetCommand_thenArgsExact() {
        val command = RestrictedSettingsOps.getCommand(pkg, uidVariant = false)

        assertEquals(
            listOf("cmd", "appops", "get", pkg, "ACCESS_RESTRICTED_SETTINGS"),
            command,
        )
    }

    @Test
    fun givenUidVariant_whenBuildGetCommand_thenUidFlagBeforePackage() {
        val command = RestrictedSettingsOps.getCommand(pkg, uidVariant = true)

        assertEquals(
            listOf("cmd", "appops", "get", "--uid", pkg, "ACCESS_RESTRICTED_SETTINGS"),
            command,
        )
    }

    @Test
    fun givenPackageVariant_whenBuildSetCommand_thenModeIsStringAllow() {
        val command = RestrictedSettingsOps.setCommand(pkg, uidVariant = false)

        assertEquals(
            listOf("cmd", "appops", "set", pkg, "ACCESS_RESTRICTED_SETTINGS", "allow"),
            command,
        )
    }

    @Test
    fun givenUidVariant_whenBuildSetCommand_thenUidFlagBeforePackage() {
        val command = RestrictedSettingsOps.setCommand(pkg, uidVariant = true)

        assertEquals(
            listOf("cmd", "appops", "set", "--uid", pkg, "ACCESS_RESTRICTED_SETTINGS", "allow"),
            command,
        )
    }

    @Test
    fun givenAnyCommand_whenInspectArgs_thenNoNumericOpCodeAnywhere() {
        // §6.5.2 硬约束的守护测试：命令里绝不允许出现 op 数值（如 119）
        val allCommands = listOf(
            RestrictedSettingsOps.getCommand(pkg, uidVariant = false),
            RestrictedSettingsOps.getCommand(pkg, uidVariant = true),
            RestrictedSettingsOps.setCommand(pkg, uidVariant = false),
            RestrictedSettingsOps.setCommand(pkg, uidVariant = true),
        )

        allCommands.forEach { command ->
            assertTrue(
                "命令 $command 不应包含纯数字参数（op 数值禁止硬编码）",
                command.none { it.matches(Regex("\\d+")) },
            )
        }
    }

    // ---- 输出解析 ----

    @Test
    fun givenAllowLine_whenParse_thenAllowed() {
        val state = RestrictedSettingsOps.parseOpState("ACCESS_RESTRICTED_SETTINGS: allow\n")

        assertEquals(RestrictedSettingsOps.OpState.ALLOWED, state)
    }

    @Test
    fun givenAllowWithExtras_whenParse_thenAllowed() {
        // 部分 ROM 在模式后附加 "; time=..." 等字段
        val state = RestrictedSettingsOps.parseOpState(
            "ACCESS_RESTRICTED_SETTINGS: allow; time=\"2026-09-19 12:00:00\"\n",
        )

        assertEquals(RestrictedSettingsOps.OpState.ALLOWED, state)
    }

    @Test
    fun givenDenyLine_whenParse_thenNotAllowed() {
        val state = RestrictedSettingsOps.parseOpState("ACCESS_RESTRICTED_SETTINGS: deny\n")

        assertEquals(RestrictedSettingsOps.OpState.NOT_ALLOWED, state)
    }

    @Test
    fun givenDefaultLine_whenParse_thenNotAllowed() {
        // 未被显式设置时的常见形态：default（此时实际受限与否由 uid 态决定，
        // 必须继续尝试 set + 回读验证，不能当作已解除）
        val state = RestrictedSettingsOps.parseOpState("ACCESS_RESTRICTED_SETTINGS: default\n")

        assertEquals(RestrictedSettingsOps.OpState.NOT_ALLOWED, state)
    }

    @Test
    fun givenIgnoreLine_whenParse_thenNotAllowed() {
        val state = RestrictedSettingsOps.parseOpState("ACCESS_RESTRICTED_SETTINGS: ignore\n")

        assertEquals(RestrictedSettingsOps.OpState.NOT_ALLOWED, state)
    }

    @Test
    fun givenOtherOpLineOnly_whenParse_thenUnknown() {
        val state = RestrictedSettingsOps.parseOpState("WAKE_LOCK: allow\nRUN_IN_BACKGROUND: ignore\n")

        assertEquals(RestrictedSettingsOps.OpState.UNKNOWN, state)
    }

    @Test
    fun givenEmptyOutput_whenParse_thenUnknown() {
        val state = RestrictedSettingsOps.parseOpState("")

        assertEquals(RestrictedSettingsOps.OpState.UNKNOWN, state)
    }

    @Test
    fun givenBareOpNameWithoutColon_whenParse_thenNotAllowed() {
        // 裸 op 名行（无冒号）是异常形态：当作未允许继续走 set + 验证
        val state = RestrictedSettingsOps.parseOpState("ACCESS_RESTRICTED_SETTINGS\n")

        assertEquals(RestrictedSettingsOps.OpState.NOT_ALLOWED, state)
    }

    @Test
    fun givenSimilarPrefixedOpName_whenParse_thenUnknown() {
        // 严格前缀边界：同前缀的其他 op 不得被误认
        val state = RestrictedSettingsOps.parseOpState("ACCESS_RESTRICTED_SETTINGS_X: allow\n")

        assertEquals(RestrictedSettingsOps.OpState.UNKNOWN, state)
    }

    @Test
    fun givenIndentedLine_whenParse_thenStillMatched() {
        // `cmd appops get --uid` 的输出可能带缩进/UID 节头
        val state = RestrictedSettingsOps.parseOpState(
            "Uid uid=10123:\n  ACCESS_RESTRICTED_SETTINGS: deny\n",
        )

        assertEquals(RestrictedSettingsOps.OpState.NOT_ALLOWED, state)
    }

    @Test
    fun givenBadOperationError_whenParse_thenUnknown() {
        // op 名不被 ROM 识别时命令报错——错误行不以 op 名开头，
        // 必须解析为 UNKNOWN（整条链路据此降级到手动引导）
        val state = RestrictedSettingsOps.parseOpState(
            "Error: java.lang.IllegalArgumentException: Bad operation name ACCESS_RESTRICTED_SETTINGS\n",
        )

        assertEquals(RestrictedSettingsOps.OpState.UNKNOWN, state)
    }

    // ---- 流程决策 ----

    @Test
    fun givenInitiallyAllowed_whenBuildPlan_thenNoWriteAttempts() {
        val plan = RestrictedSettingsOps.buildAttemptPlan(RestrictedSettingsOps.OpState.ALLOWED)

        assertTrue("初始已允许时不该尝试任何写操作", plan.isEmpty())
    }

    @Test
    fun givenInitiallyNotAllowed_whenBuildPlan_thenPackageThenUidVariants() {
        val plan = RestrictedSettingsOps.buildAttemptPlan(RestrictedSettingsOps.OpState.NOT_ALLOWED)

        assertEquals(listOf(false, true), plan)
    }

    @Test
    fun givenInitiallyUnknown_whenBuildPlan_thenPackageThenUidVariants() {
        val plan = RestrictedSettingsOps.buildAttemptPlan(RestrictedSettingsOps.OpState.UNKNOWN)

        assertEquals(listOf(false, true), plan)
    }

    @Test
    fun givenInitiallyAllowed_whenDecideOutcome_thenAlreadyAllowed() {
        val outcome = RestrictedSettingsOps.decideOutcome(
            initial = RestrictedSettingsOps.OpState.ALLOWED,
            verified = emptyList(),
            diagnostics = null,
        )

        assertEquals(RestrictedSettingsOps.FixOutcome.AlreadyAllowed, outcome)
    }

    @Test
    fun givenUidVariantVerified_whenDecideOutcome_thenFixed() {
        val outcome = RestrictedSettingsOps.decideOutcome(
            initial = RestrictedSettingsOps.OpState.NOT_ALLOWED,
            verified = listOf(false, true),
            diagnostics = null,
        )

        assertEquals(RestrictedSettingsOps.FixOutcome.Fixed, outcome)
    }

    @Test
    fun givenPackageVariantVerified_whenDecideOutcome_thenFixed() {
        val outcome = RestrictedSettingsOps.decideOutcome(
            initial = RestrictedSettingsOps.OpState.UNKNOWN,
            verified = listOf(true),
            diagnostics = null,
        )

        assertEquals(RestrictedSettingsOps.FixOutcome.Fixed, outcome)
    }

    @Test
    fun givenAllVariantsFailed_whenDecideOutcome_thenFailedWithDiagnostics() {
        val outcome = RestrictedSettingsOps.decideOutcome(
            initial = RestrictedSettingsOps.OpState.NOT_ALLOWED,
            verified = listOf(false, false),
            diagnostics = "Error: permission denied",
        )

        assertEquals(RestrictedSettingsOps.FixOutcome.Failed("Error: permission denied"), outcome)
    }

    @Test
    fun givenNoVerificationResult_whenDecideOutcome_thenFailed() {
        // 通道中途死亡（verify exec 返回 null 被 continue 跳过）的防御分支
        val outcome = RestrictedSettingsOps.decideOutcome(
            initial = RestrictedSettingsOps.OpState.UNKNOWN,
            verified = emptyList(),
            diagnostics = null,
        )

        assertEquals(RestrictedSettingsOps.FixOutcome.Failed(null), outcome)
    }
}
