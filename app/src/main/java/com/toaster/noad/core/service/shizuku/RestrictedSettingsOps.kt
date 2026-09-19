package com.toaster.noad.core.service.shizuku

/**
 * 侧载「受限设置」解除的**纯决策层**（R11 / 阶段 F2，方案 §6.5）。
 *
 * ## 与方案 §6.5.2 示意的偏差（有意识的设计决策）
 *
 * 方案原文示意 `IAppOpsService` + op 数值（119）的 Binder 直调路径，
 * 并自带两条警告：op 数值随版本漂移、必须运行时探测。本实现把
 * 两条警告都消灭在传输层选择上：
 *
 * 1. **不打包框架 AIDL**。框架接口的事务码按方法声明顺序分配，
 *    framework 跨版本增删方法后，旧 AIDL 会**静默打中别的方法**——
 *    这比 op 数值漂移更隐蔽，且无任何运行时 API 可探测。
 *    改为执行 `cmd appops` 命令，由框架二进制自行完成 binder 调用。
 * 2. **不出现 op 数值**。`cmd appops` 按 **op 字符串名** 解析，
 *    天然免疫数值漂移；名字不被当前 ROM 识别时命令直接报错，
 *    解析层返回 `UNKNOWN`，整条链路自然降级到手动图文引导（§6.5.3）。
 *
 * ## 可测性
 *
 * 本对象不含任何 Android / Shizuku 依赖：命令数组与文本输出进、
 * 决策出，全部逻辑由 JVM 单测锁定。传输层（UserService）与
 * 执行器（[RestrictedSettingsFixer]）只是本层的薄壳。
 */
object RestrictedSettingsOps {

    /**
     * Android 13+「受限设置」对应的 AppOps 字符串名。
     *
     * 注意这是 shell 命令可识别的短名（`opToName` 形态），
     * 不是 `android:access_restricted_settings` 形态的 OPSTR；
     * `cmd appops` 两种都接受，用短名与业界通用 adb 配方一致。
     */
    const val RESTRICTED_SETTINGS_OP_NAME = "ACCESS_RESTRICTED_SETTINGS"

    /** 目标模式：本工具只做「解除限制」一件事 */
    const val MODE_ALLOW = "allow"

    /** `cmd appops get <pkg> [op]` 回读出的 op 状态 */
    enum class OpState {

        /** 显式 allow：限制已解除，无需任何写操作 */
        ALLOWED,

        /** 读到了明确的其他模式（deny / default / ignore / foreground 等） */
        NOT_ALLOWED,

        /** 输出里找不到该 op 的行（命令失败 / ROM 输出格式异常 / op 名不识别） */
        UNKNOWN,
    }

    /** 解除流程的终态 */
    sealed interface FixOutcome {

        /** 回读即已 allow，本次未做任何修改 */
        data object AlreadyAllowed : FixOutcome

        /** 已成功置为 allow（以回读验证为准，而非 set 的退出状态） */
        data object Fixed : FixOutcome

        /** Shizuku 通道未就绪（未运行 / 未授权 / 未连接），不区分细因，UI 按状态机提示 */
        data object NeedsShizuku : FixOutcome

        /** 全部命令变体尝试后仍未验证到 allow */
        data class Failed(val diagnostics: String?) : FixOutcome
    }

    // ---- 命令构造 ----

    /**
     * 构造回读命令。
     *
     * @param uidVariant true = `--uid` 变体（部分 ROM 只认 UID 级模式）
     */
    fun getCommand(packageName: String, uidVariant: Boolean): List<String> = buildList {
        add("cmd")
        add("appops")
        add("get")
        if (uidVariant) add("--uid")
        add(packageName)
        add(RESTRICTED_SETTINGS_OP_NAME)
    }

    /** 构造置 allow 命令（幂等：对已 allow 的 op 重复 set 无副作用） */
    fun setCommand(packageName: String, uidVariant: Boolean): List<String> = buildList {
        add("cmd")
        add("appops")
        add("set")
        if (uidVariant) add("--uid")
        add(packageName)
        add(RESTRICTED_SETTINGS_OP_NAME)
        add(MODE_ALLOW)
    }

    // ---- 输出解析 ----

    /**
     * 解析 `cmd appops get` 的合并输出（stdout+stderr）。
     *
     * 只认「行首为 op 名 + 冒号」的行，随后取第一个模式词：
     * - `ACCESS_RESTRICTED_SETTINGS: allow` → [OpState.ALLOWED]
     * - `ACCESS_RESTRICTED_SETTINGS: deny|default|ignore|...` → [OpState.NOT_ALLOWED]
     * - 找不到该 op 的行（含 op 名不被 ROM 识别时的报错文本）→ [OpState.UNKNOWN]
     *
     * 行匹配是**严格前缀**：`ACCESS_RESTRICTED_SETTINGS_X: allow` 这类
     * 同前缀的其他 op 不会被误认。
     */
    fun parseOpState(output: String): OpState {
        val opName = RESTRICTED_SETTINGS_OP_NAME
        val line = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { candidate ->
                candidate.startsWith(opName) &&
                    (candidate.length == opName.length || candidate[opName.length] == ':')
            }
            ?: return OpState.UNKNOWN

        val mode = line
            .substringAfter(':', "")
            .substringBefore(';')
            .trim()
            .substringBefore(' ')
            .trim()

        // 空模式（例如只有裸 op 名、没有冒号的异常行）视为未允许，
        // 让流程继续走 set + 回读验证，由最终验证决定结果
        return if (mode.equals(MODE_ALLOW, ignoreCase = true)) OpState.ALLOWED else OpState.NOT_ALLOWED
    }

    // ---- 流程决策 ----

    /**
     * 根据初始回读结果决定要依次尝试的命令变体。
     *
     * - 初始即 ALLOWED → 空计划（一次写操作都不做）
     * - 其余 → 先包级（业界通用 adb 配方），后 `--uid` 级（部分 ROM 只认 UID 模式）
     */
    fun buildAttemptPlan(initial: OpState): List<Boolean> =
        if (initial == OpState.ALLOWED) emptyList() else listOf(false, true)

    /**
     * 汇总终态。
     *
     * 成功与否**只看回读验证**（[verified] 中的任一 true），
     * 不信任 set 命令本身的输出 —— set 成功但模式没生效的 ROM 是真实存在的。
     */
    fun decideOutcome(
        initial: OpState,
        verified: List<Boolean>,
        diagnostics: String?,
    ): FixOutcome = when {
        initial == OpState.ALLOWED -> FixOutcome.AlreadyAllowed
        verified.any { it } -> FixOutcome.Fixed
        else -> FixOutcome.Failed(diagnostics)
    }
}
