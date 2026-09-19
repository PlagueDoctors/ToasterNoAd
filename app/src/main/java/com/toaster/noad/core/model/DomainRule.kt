package com.toaster.noad.core.model

/**
 * 域名规则的匹配方式。
 */
enum class DomainMatchType {
    /** 精确匹配：仅 domain == pattern */
    EXACT,

    /**
     * 后缀匹配：domain == pattern 或 domain 以 ".pattern" 结尾。
     *
     * ⚠️ 实现约束：**不可**直接用 domain.endsWith(pattern)，
     * 否则 "badexample.com" 会被 pattern "example.com" 误匹配。
     * 正确写法见 [com.toaster.noad.core.engine.DomainRuleEngine]。
     */
    SUFFIX,
}

/**
 * 域名归类，用于统计与 UI 分组。
 */
enum class DomainCategory(val label: String) {
    /** 广告域名 */
    AD("广告"),

    /** 追踪/统计域名 */
    TRACKER("追踪"),

    /** 探针/遥测域名 */
    ANALYTICS("统计"),

    /** 用户自定义 */
    CUSTOM("自定义"),
}

/**
 * 单条域名规则。
 */
data class DomainRule(
    val id: Long = 0L,

    /** 匹配方式 */
    val matchType: DomainMatchType,

    /**
     * 匹配图案。
     * 约定为**已归一化**的小写、无尾点、无协议前缀的域名，例如 "doubleclick.net"。
     */
    val pattern: String,

    /** 归类 */
    val category: DomainCategory = DomainCategory.AD,

    /** 说明（可选），用于展示规则来源 */
    val note: String? = null,

    /** 是否启用 */
    val enabled: Boolean = true,
)

/**
 * 域名策略：黑白名单集合。
 *
 * 设计要点：
 * 1. 白名单**优先于**黑名单。若黑名单有 `example.com`、白名单有 `login.example.com`，
 *    黑名单优先会误伤关键登录域名。
 * 2. 该结构是不可变快照，由 [com.toaster.noad.core.engine.DomainRuleEngine] 编译为
 *    哈希集合以支持 O(1) 匹配。
 */
data class DomainPolicy(
    val blacklist: List<DomainRule> = emptyList(),
    val whitelist: List<DomainRule> = emptyList(),
) {
    val isEmpty: Boolean get() = blacklist.isEmpty() && whitelist.isEmpty()

    companion object {
        val EMPTY = DomainPolicy()
    }
}

/**
 * 域名匹配结果。
 */
sealed interface DomainVerdict {
    /** 命中白名单：明确放行，即使同时命中黑名单 */
    data class Allowed(val rule: DomainRule?) : DomainVerdict

    /** 命中黑名单：应拦截 */
    data class Blocked(val rule: DomainRule) : DomainVerdict

    /** 未命中任何规则：默认放行 */
    data object NoMatch : DomainVerdict

    /** 应拦截的简写判断，供包处理循环快速分支 */
    val shouldBlock: Boolean get() = this is Blocked
}
