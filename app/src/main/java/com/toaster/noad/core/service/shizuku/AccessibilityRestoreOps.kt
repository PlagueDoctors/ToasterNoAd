package com.toaster.noad.core.service.shizuku

/**
 * 无障碍授权恢复的纯决策层（R12，方案 §6.3.4「逐项、可失败」原则的复用）。
 *
 * ## 解决的问题
 *
 * 激进 ROM 的「一键清理 / 上划清除」按 **force-stop 语义**处理应用：
 * 系统会随之撤销该应用的无障碍授权记录（`Settings.Secure` 中的条目消失）。
 * 这是系统行为，应用侧**无法阻止**，只能事后恢复 —— 而恢复恰好需要
 * shell 权限，R11 已铺好的 Shizuku 通道正好覆盖。
 *
 * ## 为什么必须 read-merge-write 而不是直接覆盖
 *
 * `enabled_accessibility_services` 是**全局共享**的设置：
 * 其他应用（如无障碍工具、自动化工具）的条目也在里面。
 * 直接写「只有自己」会把别人的授权一并抹掉 —— 影响面超出本应用，
 * 属于不可接受的行为。因此：先 `settings get` 读出当前值，
 * 解析条目后**只追加/确保自己**，其余条目原样保留。
 *
 * ## 为什么成功判定仍是「回读验证」
 *
 * 与 F2 的 appops 同理：`settings put` 不报错不代表生效
 * （SELinux 拒绝、ROM 裁剪 settings 命令等都会产生静默失败）。
 * 只有重新 get 读到自己的条目才能报告「已恢复」。
 */
object AccessibilityRestoreOps {

    /** 系统存储该设置的分隔符（带条目前导 `:` 的写法也存在，解析必须容忍） */
    const val SERVICE_SEPARATOR = ":"

    /** `settings get` 在键不存在时返回的字面量（不是空串） */
    const val SETTING_VALUE_NULL = "null"

    const val NAMESPACE_SECURE = "secure"
    const val ENABLED_SERVICES_KEY = "enabled_accessibility_services"
    const val ACCESSIBILITY_ENABLED_KEY = "accessibility_enabled"

    /** 无障碍总开关的「开」值（settings 存的是字符串） */
    const val MASTER_SWITCH_ON = "1"

    /** 恢复动作的终态（纯层视角，不含「Shizuku 未授权」等通道态） */
    sealed interface RestoreOutcome {
        /** 之前不在列表中，本次写入后回读验证通过 */
        data object Restored : RestoreOutcome

        /** 之前就在列表中（可能只是总开关/重绑问题），本次未改条目 */
        data object AlreadyPresent : RestoreOutcome

        /** 写入或回读未通过验证；[diagnostics] 为回读输出的末行 */
        data class Failed(val diagnostics: String?) : RestoreOutcome
    }

    /** 读取当前启用服务列表 */
    fun readEnabledServicesCommand(): List<String> =
        listOf("settings", "get", NAMESPACE_SECURE, ENABLED_SERVICES_KEY)

    /** 写入合并后的服务列表（调用方必须传入 [mergeEnabledServices] 的结果） */
    fun writeEnabledServicesCommand(merged: String): List<String> =
        listOf("settings", "put", NAMESPACE_SECURE, ENABLED_SERVICES_KEY, merged)

    /** 写入无障碍总开关（条目写入后显式置 1，避免「条目在但总开关关」的死态） */
    fun writeMasterSwitchCommand(): List<String> =
        listOf("settings", "put", NAMESPACE_SECURE, ACCESSIBILITY_ENABLED_KEY, MASTER_SWITCH_ON)

    /**
     * 解析 `settings get` 的原始输出为条目列表。
     *
     * 容忍三种脏形态：`null` 字面量（键不存在）、空/全空白、
     * 条目前后的多余分隔符与空白。解析结果是后续合并的唯一依据。
     */
    fun parseEntries(raw: String?): List<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals(SETTING_VALUE_NULL, ignoreCase = true)) {
            return emptyList()
        }
        return trimmed
            .split(SERVICE_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** 当前列表中是否已含 [flat]（大小写不敏感：组件扁平串的书写大小写不保证一致） */
    fun containsService(raw: String?, flat: String): Boolean =
        parseEntries(raw).any { it.equals(flat, ignoreCase = true) }

    /**
     * 把 [flat] 合并进当前列表：**其余条目原样保留**，自己不存在才追加到末尾。
     *
     * 已存在（含大小写差异）时返回去除了脏分隔符的规范化列表 ——
     * 这意味着「已存在」时写回同一内容是幂等的，不会产生副作用。
     */
    fun mergeEnabledServices(raw: String?, flat: String): String {
        val entries = parseEntries(raw).toMutableList()
        val exists = entries.any { it.equals(flat, ignoreCase = true) }
        if (!exists) {
            entries += flat
        }
        return entries.joinToString(SERVICE_SEPARATOR)
    }

    /**
     * 恢复序列（read → merge → put → put master → read 验证）。
     *
     * ## 为什么 exec 是注入的函数参数而不是类依赖
     *
     * 序列逻辑（先读后写、条件跳过、验证判定）是**最值得测的部分**，
     * 而 `ShizukuShellClient` 是持有 binder 的具体类，JVM 上无法伪造。
     * 把「怎么执行一条命令」收敛为 `suspend (List<String>) -> String?`，
     * 生产侧传 `shell::exec`，测试侧传一个记录调用的 lambda ——
     * 与 `S1RuleCache` 收 Flow 而非 Repository 的可测性改造同一思路。
     *
     * @param exec 执行一条命令并返回合并输出；返回 `null` 表示通道不可用
     * @param flat 本应用无障碍服务的组件扁平串（`包名/类名`）
     */
    suspend fun restore(
        exec: suspend (List<String>) -> String?,
        flat: String,
    ): RestoreOutcome {
        val before = exec(readEnabledServicesCommand())
            ?: return RestoreOutcome.Failed(null)

        val alreadyPresent = containsService(before, flat)
        if (!alreadyPresent) {
            exec(writeEnabledServicesCommand(mergeEnabledServices(before, flat)))
        }
        exec(writeMasterSwitchCommand())

        val after = exec(readEnabledServicesCommand())
            ?: return RestoreOutcome.Failed(diagnosticTail(before))

        return decideRestoreOutcome(
            alreadyPresent = alreadyPresent,
            verified = containsService(after, flat),
            diagnostics = diagnosticTail(after),
        )
    }

    /** 验证通过与否决定终态；[alreadyPresent] 区分「本次写入」与「原本就在」 */
    fun decideRestoreOutcome(
        alreadyPresent: Boolean,
        verified: Boolean,
        diagnostics: String?,
    ): RestoreOutcome = when {
        !verified -> RestoreOutcome.Failed(diagnostics)
        alreadyPresent -> RestoreOutcome.AlreadyPresent
        else -> RestoreOutcome.Restored
    }

    /** 诊断只保留末行（错误信息通常在最后），截断防长输出 —— 与 F2 同一口径 */
    private fun diagnosticTail(output: String): String? =
        output.lineSequence().lastOrNull { it.isNotBlank() }?.takeLast(200)
}
