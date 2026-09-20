package com.toaster.noad.core.service.shizuku

/**
 * Chain-3 应用级断网的命令序列与输出解析（纯逻辑，JVM 可测）。
 *
 * ## 为什么命令与解析独立成 Ops
 *
 * 与 [AccessibilityRestoreOps] 同源的理由：**命令序列是唯一权威**，
 * 门面（`AppFirewallController`）只负责执行与回读验证，不重写序列。
 * 这样「禁用/恢复的每条命令」都能在 JVM 上被断言，
 * 无需 Shizuku 环境（项目无 Robolectric）。
 *
 * ## 命令来自实机实测（NX789J / Android 15）
 *
 * | 命令 | 实测结果 |
 * |---|---|
 * | `cmd connectivity set-chain3-enabled true` | rc=0，无输出 |
 * | `cmd connectivity set-package-networking-enabled false <pkg>` | 包不存在时 rc=255 + NameNotFoundException |
 * | `cmd connectivity get-package-networking-enabled <pkg>` | rc=0，输出 `<pkg>:allow` |
 *
 * **解析必须容错**：失败时输出含 `Exception`/`Error`/`Unknown command`，
 * 绝不能把这些误判为「已阻断」或「未阻断」。
 */
object AppFirewallOps {

    /** 开启 Chain-3 防火墙链（无规则时对网络无影响，是使用该能力的前置开关） */
    fun enableChain3Command(): List<String> =
        listOf("cmd", "connectivity", "set-chain3-enabled", "true")

    /**
     * 设置某应用的联网开关。
     *
     * 注意取反：本应用语义是「阻断」，而命令参数是「联网是否可用」——
     * blocked=true → networking=false。这个反转极易写错，
     * 因此由本函数统一承担，调用方不接触命令参数。
     */
    fun setBlockedCommand(packageName: String, blocked: Boolean): List<String> =
        listOf(
            "cmd",
            "connectivity",
            "set-package-networking-enabled",
            if (blocked) "false" else "true",
            packageName,
        )

    /** 回读某应用当前的联网开关 */
    fun queryCommand(packageName: String): List<String> =
        listOf("cmd", "connectivity", "get-package-networking-enabled", packageName)

    /** 回读结果 */
    sealed interface QueryResult {
        /** 查询成功：[blocked] 为真表示该应用已被断网 */
        data class Resolved(val blocked: Boolean) : QueryResult

        /** 命令在这台设备/这个 Android 版本上不可用 */
        data object Unsupported : QueryResult

        /** 输出无法解析（不可信）—— 不能据此下任何结论 */
        data object Unknown : QueryResult
    }

    /**
     * 解析回读输出。
     *
     * 实机格式为 `<pkg>:allow`；不同版本可能返回 `true/false` 或 `deny`。
     * 因此判据是「关键词包含」而非精确匹配，且**宁可 Unknown 也不猜** ——
     * 把 Unknown 猜成「未阻断」会让上层重复下发命令，
     * 猜成「已阻断」会让上层误以为成功。
     */
    fun parseQuery(output: String?): QueryResult {
        if (output == null) return QueryResult.Unsupported
        val text = output.trim()
        if (text.isEmpty()) return QueryResult.Unknown
        if (isFailureOutput(text)) return QueryResult.Unsupported

        // 三种已知形态都要覆盖：
        //   实机格式 `<pkg>:allow` / `<pkg>:deny`（NX789J / Android 15 实测）
        //   裸布尔 `true` / `false`（部分版本的输出）
        val lower = text.lowercase()
        return when {
            lower == "false" || lower.contains(":deny") || lower.endsWith(":false") ->
                QueryResult.Resolved(blocked = true)

            lower == "true" || lower.contains(":allow") || lower.endsWith(":true") ->
                QueryResult.Resolved(blocked = false)

            else -> QueryResult.Unknown
        }
    }

    /**
     * 判断输出是否表示「命令失败/不支持」。
     *
     * 用于所有走 shell 通道的能力：失败必须以 `null` 或异常文本回来，
     * 上层据此判「未产生可信结果」而不是「操作已完成」。
     */
    fun isFailureOutput(output: String?): Boolean {
        if (output == null) return true
        val lower = output.lowercase()
        return lower.contains("exception") ||
            lower.contains("error") ||
            lower.contains("unknown command") ||
            lower.contains("bad operation") ||
            lower.contains("unknown operation") ||
            lower.contains("permission denial") ||
            lower.contains("securityexception")
    }

    /**
     * 解析「能力探测」的输出：仅当查询返回可解析结果时，Chain-3 才可用。
     *
     * 探测用一个真实存在的包名（本应用自身），避免 NameNotFoundException
     * 把「命令不支持」与「包不存在」混为一谈。
     */
    fun probeSupported(output: String?): Boolean =
        parseQuery(output) is QueryResult.Resolved
}
