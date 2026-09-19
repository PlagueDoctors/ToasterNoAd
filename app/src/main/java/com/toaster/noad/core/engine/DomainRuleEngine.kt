package com.toaster.noad.core.engine

import com.toaster.noad.core.model.DomainMatchType
import com.toaster.noad.core.model.DomainPolicy
import com.toaster.noad.core.model.DomainRule
import com.toaster.noad.core.model.DomainVerdict

/**
 * 域名规则匹配引擎（S2 / S3 / S4 共享）。
 *
 * ## 为什么需要独立引擎
 *
 * S2（DNS 过滤）、S3（全流量）与 S4（应用级断网）都需要判断
 * 「这个域名该不该拦」。如果把匹配逻辑散落在各策略实现里，
 * 一旦规则语义调整（例如新增通配类型）就会多处遗漏。
 *
 * ## 线程安全
 *
 * 本类**不可变**：所有索引在构造时一次性编译完成，之后无任何写入。
 * 因此可以被 S2 的包处理线程与 S3 的转发线程并发读取，无需加锁。
 * 规则变更时，由 Repository 重新构造一个新实例并整体替换。
 *
 * ## 匹配顺序（白名单优先）
 *
 * ```
 * 1. 白名单精确匹配  -> Allowed
 * 2. 白名单后缀匹配  -> Allowed
 * 3. 黑名单精确匹配  -> Blocked
 * 4. 黑名单后缀匹配  -> Blocked
 * 5. 默认            -> NoMatch（放行）
 * ```
 *
 * 白名单优先的原因：若黑名单有 `example.com`、白名单有 `login.example.com`，
 * 黑名单优先会误伤关键登录域名，导致用户无法登录 —— 这是最严重的误杀形态。
 */
class DomainRuleEngine private constructor(
    private val whitelistExact: Set<String>,
    private val whitelistSuffix: Set<String>,
    private val blacklistExact: Set<String>,
    private val blacklistSuffix: Set<String>,
    private val blacklistRules: Map<String, DomainRule>,
    private val whitelistRules: Map<String, DomainRule>,
) {

    /**
     * 判断域名是否应被拦截。
     *
     * @param rawDomain 原始域名，可含大小写、尾点、端口；内部会先归一化
     */
    fun evaluate(rawDomain: String): DomainVerdict {
        val domain = normalize(rawDomain)
        if (domain.isEmpty()) return DomainVerdict.NoMatch

        // 1 & 2. 白名单优先
        if (whitelistExact.contains(domain)) {
            return DomainVerdict.Allowed(whitelistRules[domain])
        }
        matchSuffix(domain, whitelistSuffix)?.let { matched ->
            return DomainVerdict.Allowed(whitelistRules[matched])
        }

        // 3 & 4. 黑名单
        if (blacklistExact.contains(domain)) {
            return DomainVerdict.Blocked(blacklistRules.getValue(domain))
        }
        matchSuffix(domain, blacklistSuffix)?.let { matched ->
            return DomainVerdict.Blocked(blacklistRules.getValue(matched))
        }

        return DomainVerdict.NoMatch
    }

    /** 便捷判断：是否需要拦截 */
    fun shouldBlock(rawDomain: String): Boolean = evaluate(rawDomain).shouldBlock

    /**
     * 后缀匹配。
     *
     * ⚠️ 关键正确性要求：
     * 不能用 `domain.endsWith(suffix)` —— 那会让 `badexample.com`
     * 被后缀 `example.com` 误匹配，导致误杀。
     *
     * 正确语义是「domain 等于 suffix，或 domain 是 suffix 的**子域**」，
     * 因此必须校验分隔点：`domain.endsWith(".$suffix")`。
     */
    private fun matchSuffix(domain: String, suffixes: Set<String>): String? {
        if (suffixes.isEmpty()) return null

        // 逐级剥离子域：a.b.example.com -> b.example.com -> example.com -> com
        var cursor = domain
        while (true) {
            if (suffixes.contains(cursor)) return cursor
            val dot = cursor.indexOf('.')
            if (dot < 0) return null
            cursor = cursor.substring(dot + 1)
        }
    }

    /** 已编译的规则条数（用于 UI 展示与断言） */
    val whitelistSize: Int get() = whitelistExact.size + whitelistSuffix.size

    val blacklistSize: Int get() = blacklistExact.size + blacklistSuffix.size

    val totalSize: Int get() = whitelistSize + blacklistSize

    val isEmpty: Boolean get() = totalSize == 0

    companion object {

        /**
         * 域名归一化。
         *
         * 处理内容：
         * - 转小写（DNS 大小写不敏感）
         * - 去除首尾空白
         * - 去除末尾的点（FQDN 写法 `example.com.`）
         * - 去除可能的端口（`example.com:443`）
         * - 去除可能残留的协议前缀
         */
        fun normalize(raw: String): String {
            var s = raw.trim().lowercase()
            if (s.isEmpty()) return ""

            // 去协议前缀
            val schemeIndex = s.indexOf("://")
            if (schemeIndex >= 0) s = s.substring(schemeIndex + 3)

            // 去路径 / 查询串
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')

            // 去端口（注意 IPv6 字面量含多个冒号，这类输入直接放弃归一化）
            if (!s.contains('[') && s.count { it == ':' } == 1) {
                s = s.substringBefore(':')
            }

            // 去尾点
            while (s.endsWith('.')) s = s.dropLast(1)

            return s
        }

        /** 归一化规则图案，并拒绝明显无效的输入 */
        private fun normalizePattern(pattern: String): String? {
            val p = normalize(pattern)
            if (p.isEmpty()) return null
            // 单个字符无法构成有效域名
            if (p == ".") return null
            return p
        }

        /**
         * 由策略构建引擎实例。
         *
         * 一次性把规则拆进四个哈希集合，使匹配为 O(域名层级数) 而非 O(规则数)。
         * 同时保留 pattern -> Rule 的映射，以便返回命中的具体规则用于日志。
         */
        fun from(policy: DomainPolicy): DomainRuleEngine {
            val whitelistExact = HashSet<String>()
            val whitelistSuffix = HashSet<String>()
            val blacklistExact = HashSet<String>()
            val blacklistSuffix = HashSet<String>()
            val whitelistRules = HashMap<String, DomainRule>()
            val blacklistRules = HashMap<String, DomainRule>()

            fun classify(rule: DomainRule, isWhitelist: Boolean) {
                if (!rule.enabled) return
                val pattern = normalizePattern(rule.pattern) ?: return

                if (isWhitelist) {
                    // 同图案先到先得，避免后导入的规则覆盖既有语义
                    whitelistRules.putIfAbsent(pattern, rule)
                    when (rule.matchType) {
                        DomainMatchType.EXACT -> whitelistExact.add(pattern)
                        DomainMatchType.SUFFIX -> whitelistSuffix.add(pattern)
                    }
                } else {
                    blacklistRules.putIfAbsent(pattern, rule)
                    when (rule.matchType) {
                        DomainMatchType.EXACT -> blacklistExact.add(pattern)
                        DomainMatchType.SUFFIX -> blacklistSuffix.add(pattern)
                    }
                }
            }

            policy.whitelist.forEach { rule -> classify(rule, isWhitelist = true) }
            policy.blacklist.forEach { rule -> classify(rule, isWhitelist = false) }

            return DomainRuleEngine(
                whitelistExact = whitelistExact,
                whitelistSuffix = whitelistSuffix,
                blacklistExact = blacklistExact,
                blacklistSuffix = blacklistSuffix,
                whitelistRules = whitelistRules,
                blacklistRules = blacklistRules,
            )
        }
    }
}
