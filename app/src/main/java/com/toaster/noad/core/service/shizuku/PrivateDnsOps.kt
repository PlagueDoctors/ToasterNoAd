package com.toaster.noad.core.service.shizuku

/**
 * Private DNS 改写的命令序列与输出解析（纯逻辑，JVM 可测）。
 *
 * ## 定位（方案 §6.4）
 *
 * Private DNS 是 S2 的**竞争方案**：改写系统 DoT 设置即可获得
 * 「加密 + 不占用 VPN」的 DNS 过滤，代价是只能用服务商规则
 * （AdGuard DNS / NextDNS 等）而非 NoAd 自定义规则。
 *
 * ## 实机事实（NX789J / Android 15）
 *
 * `settings get global private_dns_mode` → `null`（未设置过）。
 * 三个合法取值：`off` / `opportunistic`（自动）/ `hostname`（指定主机名）。
 * 设置主机名时必须**同时**写 `private_dns_mode` 与 `private_dns_specifier`
 * —— 只写 specifier 不会生效，只写 mode 会指向空主机名。
 */
object PrivateDnsOps {

    enum class DnsMode {
        /** 关闭 DoT */
        OFF,

        /** 自动（机会式）：仅当解析到可用的 DoT 服务器时启用 */
        AUTO,

        /** 指定主机名（严格模式） */
        HOSTNAME,

        /** 无法识别（未知值或读取失败） */
        UNKNOWN,
    }

    /** 数据键（系统 Settings.Global） */
    const val KEY_MODE = "private_dns_mode"
    const val KEY_SPECIFIER = "private_dns_specifier"

    /** 设为「指定主机名」：两条命令必须成对下发 */
    fun setHostnameCommands(hostname: String): List<List<String>> = listOf(
        listOf("settings", "put", "global", KEY_MODE, "hostname"),
        listOf("settings", "put", "global", KEY_SPECIFIER, hostname),
    )

    fun setOffCommands(): List<List<String>> =
        listOf(listOf("settings", "put", "global", KEY_MODE, "off"))

    fun setAutoCommands(): List<List<String>> =
        listOf(listOf("settings", "put", "global", KEY_MODE, "opportunistic"))

    fun queryModeCommand(): List<String> = listOf("settings", "get", "global", KEY_MODE)

    fun querySpecifierCommand(): List<String> = listOf("settings", "get", "global", KEY_SPECIFIER)

    /**
     * 解析模式输出。
     *
     * `null` 字面量（settings 未设置时的输出）与读取失败（输出为 null）
     * 都归为 [DnsMode.OFF]？—— **不**：前者是「系统未设置 = 实际处于关闭」，
     * 后者是「不知道」。因此读取失败由调用方先行判定（输出为 null 直接放弃），
     * 本函数只处理「拿到了输出」的情形。
     */
    fun parseMode(output: String?): DnsMode = when (output?.trim()?.lowercase()) {
        null, "", "null", "off" -> DnsMode.OFF
        "opportunistic" -> DnsMode.AUTO
        "hostname" -> DnsMode.HOSTNAME
        else -> DnsMode.UNKNOWN
    }

    /** 解析主机名输出；`null` 字面量表示未设置 */
    fun parseSpecifier(output: String?): String? {
        val text = output?.trim() ?: return null
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return null
        return text
    }

    /**
     * 主机名校验（写系统设置前必须过这道门）。
     *
     * 拒绝：空白、含空格、过长、不含点（DoT 主机名必为域名）、
     * 含协议前缀/端口/路径（用户可能粘贴 URL）。
     */
    fun isValidHostname(hostname: String): Boolean {
        val h = hostname.trim()
        if (h.isEmpty() || h.length > MAX_HOSTNAME_LENGTH) return false
        if (h.any { it.isWhitespace() }) return false
        if (h.contains("://") || h.contains('/') || h.contains(':')) return false
        if (!h.contains('.')) return false
        return true
    }

    /** DoT 主机名的合理上限（RFC 1035 域名 253 字符） */
    private const val MAX_HOSTNAME_LENGTH = 253
}
