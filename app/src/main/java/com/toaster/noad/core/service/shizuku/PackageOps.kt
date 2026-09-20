package com.toaster.noad.core.service.shizuku

/**
 * 包与组件管理命令（F5：应用停用 / 组件停用，方案 §6.6）。
 *
 * ## 为什么值得做
 *
 * 很多 App 的开屏广告由**独立 Activity** 承载（如 `SplashAdActivity`）。
 * S1 无障碍只能「等它出现再点掉」；而停用该组件**从根上不会出现**。
 * 这是 S1 的强化选项，不是替代品。
 *
 * ## 边界（不越界成应用管理器）
 *
 * - ✅ 停用**用户逐个确认的**广告载体组件
 * - ✅ 冻结**用户明确指定的**广告 App
 * - ❌ 批量停用系统应用（由门面按 `isSystem` 拦截）
 * - ❌ 静默安装/卸载
 *
 * ## 命令与回读（实机验证）
 *
 * | 命令 | 实测 |
 * |---|---|
 * | `pm list packages -d` | 输出 `package:<pkg>` 行（已禁用包列表）|
 * | `dumpsys package <pkg>` | 含 `enabled=N`（0 默认/2 禁用/3 用户禁用）与 `enabledComponents:` 段 |
 */
object PackageOps {

    /** 应用级停用（等价「冻结」：应用完全无法运行，用户可在系统设置恢复） */
    fun disableAppCommand(packageName: String): List<String> =
        listOf("pm", "disable-user", "--user", "0", packageName)

    /** 应用级恢复 */
    fun enableAppCommand(packageName: String): List<String> =
        listOf("pm", "enable", "--user", "0", packageName)

    /** 组件级停用（只停广告 Activity/Service，应用其余功能保留） */
    fun disableComponentCommand(packageName: String, component: String): List<String> =
        listOf("pm", "disable", "$packageName/$component")

    /** 组件级恢复 */
    fun enableComponentCommand(packageName: String, component: String): List<String> =
        listOf("pm", "enable", "$packageName/$component")

    /** 查询应用是否处于禁用列表 */
    fun listDisabledPackagesCommand(): List<String> =
        listOf("pm", "list", "packages", "-d")

    /** 查询包详情（用于解析 enabled 状态与已变更组件） */
    fun dumpPackageCommand(packageName: String): List<String> =
        listOf("dumpsys", "package", packageName)

    /**
     * 从 `pm list packages -d` 输出中提取被禁用的包集合。
     * 行格式：`package:<pkg>`（实机验证）。
     */
    fun parseDisabledPackages(output: String?): Set<String> {
        if (output == null) return emptySet()
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(PREFIX_PACKAGE) }
            .map { it.removePrefix(PREFIX_PACKAGE).trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * 从 `dumpsys package <pkg>` 输出解析应用级启用状态。
     *
     * `enabled=` 的取值：0=DEFAULT（跟随 manifest）、1=ENABLED、
     * 2=DISABLED、3=DISABLED_USER、4=DISABLED_UNTIL_USED。
     * 只把 2/3/4 视为「已被停用」—— 0 与 1 都是正常可用状态。
     *
     * 注意要取 **User 0** 那一行（输出里还有 User 999 工作资料行）。
     */
    fun parseAppEnabled(output: String?): Boolean? {
        if (output == null) return null
        val userLine = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("User 0:") && it.contains("enabled=") }
            ?: return null

        val value = Regex("enabled=(\\d+)").find(userLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        return value in 0..1
    }

    /** 判断某组件是否出现在 `enabledComponents:` 段（出现即被显式改动过） */
    fun parseComponentMentioned(output: String?, packageName: String, component: String): Boolean {
        if (output == null) return false
        val target = "$packageName/$component"
        val simple = component.substringAfterLast('.')
        return output.lineSequence()
            .map { it.trim() }
            .any { line -> line.contains(target) || line.contains("$packageName/$simple") }
    }

    /**
     * 拒绝对系统应用执行停用（方案 §6.6.3 的硬约束）。
     *
     * 系统应用停用可能导致系统功能异常，属于明确的「不做」范围。
     * 判定依据由调用方提供（`ApplicationInfo.FLAG_SYSTEM`），
     * 本节只提供纯逻辑判定，保持可测。
     */
    fun isOperationAllowed(isSystemApp: Boolean): Boolean = !isSystemApp

    private const val PREFIX_PACKAGE = "package:"
}
