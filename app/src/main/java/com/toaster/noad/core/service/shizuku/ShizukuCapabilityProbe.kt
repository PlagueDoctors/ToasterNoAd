package com.toaster.noad.core.service.shizuku

/**
 * Shizuku 能力探测（方案 §6.2.4：一切增强能力的入口闸门）。
 *
 * ## 设计原则：逐项、运行时、可失败
 *
 * shell 权限在不同 Android 版本与厂商 ROM 上差异很大。
 * 静态假设「有 Shizuku 就有一切」会做出脆弱的实现 ——
 * 某台设备上 `cmd connectivity` 不存在时，不该让整个 S4 崩掉，
 * 而应只是「应用级断网不可用」这一项静默降级。
 *
 * 因此探测返回**每项能力各自的布尔值**，且每项探测都只发**只读命令**。
 *
 * ## 探测用命令（全部只读，实机验证过）
 *
 * | 能力 | 探测命令 |
 * |---|---|
 * | Chain-3 断网 | `cmd connectivity get-package-networking-enabled <self>` |
 * | 设置写入 | `settings get global private_dns_mode` |
 * | 包管理 | `pm list packages -d` |
 * | AppOps | `cmd appops get <self> RUN_IN_BACKGROUND` |
 * | 强制停止 | `am get-current-user` |
 *
 * 全部注入 [exec]，因此探测逻辑在 JVM 上可测（无需 Shizuku）。
 */
class ShizukuCapabilityProbe(
    private val exec: suspend (List<String>) -> String?,
    /** Shizuku 服务是否就绪（由 [ShizukuAvailabilityHolder] 提供） */
    private val isReady: () -> Boolean,
    /** 本应用包名（探测需要一个真实存在的包，避免 NameNotFoundException 混淆） */
    private val selfPackageName: String,
) {

    data class Capabilities(
        val available: Boolean,
        val canControlPerAppNetwork: Boolean,
        val canWriteSecureSettings: Boolean,
        val canManagePackages: Boolean,
        val canSetAppOps: Boolean,
        val canForceStop: Boolean,
    ) {
        companion object {
            /** 未就绪时的全 false —— 调用方无需区分「不可用」与「探测失败」 */
            val NONE = Capabilities(
                available = false,
                canControlPerAppNetwork = false,
                canWriteSecureSettings = false,
                canManagePackages = false,
                canSetAppOps = false,
                canForceStop = false,
            )
        }
    }

    suspend fun probe(): Capabilities {
        if (!isReady()) return Capabilities.NONE

        return Capabilities(
            available = true,
            canControlPerAppNetwork = probeChain3(),
            canWriteSecureSettings = probeSecureSettings(),
            canManagePackages = probePackageManager(),
            canSetAppOps = probeAppOps(),
            canForceStop = probeForceStop(),
        )
    }

    /** Chain-3：能查到一个可解析的结果即视为支持 */
    suspend fun probeChain3(): Boolean = runCatching {
        val output = exec(AppFirewallOps.queryCommand(selfPackageName))
        AppFirewallOps.probeSupported(output)
    }.getOrDefault(false)

    /** 设置读取：能读到（哪怕值是 null 字面量）即说明 `settings` 通道可用 */
    suspend fun probeSecureSettings(): Boolean = runCatching {
        val output = exec(PrivateDnsOps.queryModeCommand())
        output != null && !AppFirewallOps.isFailureOutput(output)
    }.getOrDefault(false)

    /** 包管理：`pm list packages -d` 能返回 package: 行即可用 */
    suspend fun probePackageManager(): Boolean = runCatching {
        val output = exec(listOf("pm", "list", "packages", "-d")) ?: return@runCatching false
        if (AppFirewallOps.isFailureOutput(output)) {
            // 没有已禁用包时输出为空是合法的 —— 此时无行但无错误文本
            output.contains("error", ignoreCase = true).not() && output.isBlank()
        } else {
            output.contains("package:") || output.isBlank()
        }
    }.getOrDefault(false)

    /** AppOps：能查询即支持（"No operations." 也是合法结果） */
    suspend fun probeAppOps(): Boolean = runCatching {
        val output = exec(listOf("cmd", "appops", "get", selfPackageName, OPS_PROBE_OP))
        output != null && !AppFirewallOps.isFailureOutput(output)
    }.getOrDefault(false)

    /**
     * 强制停止：`am` 命令恒存在于 shell（无需真正执行 force-stop ——
     * 那有副作用），改用只读的 `am get-current-user` 验证通道。
     */
    suspend fun probeForceStop(): Boolean = runCatching {
        val output = exec(listOf("am", "get-current-user"))
        output != null && !AppFirewallOps.isFailureOutput(output) &&
            output.trim().isNotEmpty()
    }.getOrDefault(false)

    private companion object {
        /** 探测用的 AppOps 操作名（字符串名，绝不硬编码数值 —— 项目铁律） */
        const val OPS_PROBE_OP = "RUN_IN_BACKGROUND"
    }
}
