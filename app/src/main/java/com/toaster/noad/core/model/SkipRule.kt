package com.toaster.noad.core.model

/**
 * 节点定位方式（S1 无障碍规则）。
 *
 * 优先级：由高到低为 [VIEW_ID] > [TEXT] > [DESCRIPTION] > [COORDINATE]。
 * 高优先级定位更稳定，坐标定位仅作为最后的兜底手段。
 */
enum class TargetType {
    /** 通过 viewIdResourceName 定位（最稳定，需 FLAG_REPORT_VIEW_IDS） */
    VIEW_ID,

    /** 通过节点可见文本定位 */
    TEXT,

    /** 通过 contentDescription 定位（常见于图片按钮） */
    DESCRIPTION,

    /** 通过相对坐标定位（兜底，跨分辨率可能失效） */
    COORDINATE,
}

/**
 * 跳过规则的来源。
 *
 * ## 为什么必须区分来源
 *
 * 内置规则与用户规则的**生命周期完全不同**：
 *
 * - **内置规则**随应用版本演进，可被整批替换；但用户对它的修改
 *   （停用/删除）应当在新版本导入时被保留，而不是被覆盖回去。
 * - **用户规则**是用户手工创建的资产，任何自动流程都不得删除。
 *
 * 若不区分来源，应用升级时只剩两种坏选择：要么全量重建
 * （抹掉用户的删除意图），要么永不更新（内置规则的错误无法通过升级修复）。
 *
 * > 与 `DomainRuleEntity.SOURCE_*` 常量保持同名同义，便于记忆与统一处理。
 */
enum class SkipRuleSource(
    /**
     * 持久化名称。
     *
     * 刻意**不使用** `enum.name`（即 `"BUILTIN"`）：数据库里存的是这个值，
     * 而 `SkipRuleEntity.SOURCE_*` 常量与 v1→v2 迁移的 `DEFAULT 'user'`
     * 都是小写。若存枚举名，迁移补的默认值 `'user'` 与代码写入的
     * `"USER"` 会变成两个不同的取值，`WHERE source = 'user'` 查不到新数据 ——
     * 这类缺陷在迁移测试与查询中才会暴露，且现象是"规则莫名消失"。
     */
    val persistedName: String,
) {
    /** 随应用内置分发（`assets/rules/builtin_skip_rules.json`） */
    BUILTIN("builtin"),

    /** 用户导入（后续版本支持） */
    IMPORTED("imported"),

    /** 用户在应用内手工创建 */
    USER("user"),
    ;

    companion object {
        const val NAME_BUILTIN = "builtin"
        const val NAME_IMPORTED = "imported"
        const val NAME_USER = "user"

        /**
         * 字符串 → 枚举，未知值退回 [USER]。
         *
         * 退回 USER 而非 BUILTIN：把"来源不明"当作最需要保护的用户规则对待，
         * 避免某次导入意外删除它们。
         */
        fun fromName(raw: String?): SkipRuleSource =
            when (raw?.trim()?.lowercase()) {
                NAME_BUILTIN -> BUILTIN
                NAME_IMPORTED -> IMPORTED
                else -> USER
            }
    }
}

/**
 * 通用规则的伪包名。
 *
 * 广告 SDK 的跳过按钮 id 在所有接入该 SDK 的应用中相同（如穿山甲的
 * `tt_splash_skip_btn`、快手的 `ksad_splash_circle_skip_view`），
 * 但按钮 id 的**包名前缀**随宿主而异。用伪包名承载这类跨应用规则，
 * 使其作用于任意已纳管应用，无需逐应用重复登记。
 *
 * 使用位置（**必须保持一致**）：
 * - `builtin_skip_rules.json` 中的 `"package": "*"` 条目
 * - [com.toaster.noad.core.repository.S1RuleCache] 的快照构建与查询
 *
 * 安全性：通用规则同样受"应用必须被用户纳管"约束 ——
 * 用户没勾选的应用，通用规则也不会作用于它。
 */
const val GLOBAL_RULE_PACKAGE: String = "*"

/**
 * 定位值前缀，表示按 viewId **后缀**匹配。
 *
 * 用法：`"*tt_splash_skip_btn"` 可匹配
 * `com.byted.pangle:id/tt_splash_skip_btn`（SDK 自有资源）
 * 与 `com.cainiao.wireless:id/tt_splash_skip_btn`（宿主覆写资源）。
 *
 * 定义在 model 层而非 `UiMatcher` 内部：规则文件编写者与规则页
 * 都需要知道这个约定，放在匹配器里会让它在 UI 层不可见。
 */
const val VIEW_ID_SUFFIX_PREFIX: String = "*"

/**
 * 文本匹配方式。
 *
 * ## 为什么需要 [PREFIX]
 *
 * 开屏广告的「跳过」按钮**几乎都带倒计时**，真机抓包实证（B站）：
 *
 * ```
 * tv.danmaku.bili:id/count_down  →  text = "跳过 1"
 * ```
 *
 * 这类文案的正确刻画是「**以『跳过』开头**且总长度很短」：
 *
 * - 用 [EXACT]（`"跳过" == "跳过 1"`）→ **必然落空**，这是本应用曾完全失效的直接原因
 * - 用 [CONTAINS]（`"跳过 1".contains("跳过")`）→ 能命中，
 *   但也会命中正文里的「跳过此步可在设置中重新开启」等引导文案
 * - 用 [PREFIX] → 既命中带倒计时的按钮，又不会命中正文（正文不以「跳过」开头）
 *
 * 该模式对应李跳跳规则语法中的 `+` 前缀修饰符
 * （官方文档：「+检测到 表示匹配以检测到开头的文字」）。
 */
enum class MatchMode {
    /** 完全相等 */
    EXACT,

    /**
     * 前缀匹配（忽略大小写与首尾空白）。
     *
     * 专用于「跳过 1」「跳过广告 5s」这类**带动态后缀**的按钮文案。
     */
    PREFIX,

    /** 包含子串 */
    CONTAINS,

    /** 正则匹配 */
    REGEX,
}

/**
 * S1 跳过规则：描述「在某个应用的某个界面上，如何找到并点击关闭按钮」。
 *
 * 该模型同时被规则编辑 UI、规则持久化层与 S1 引擎消费。
 */
data class SkipRule(
    /** 规则唯一标识。新建时为 0，落库后由 Room 回填 */
    val id: Long = 0L,

    /** 规则名（展示用），例如「抖音开屏跳过」 */
    val name: String,

    /** 生效的目标应用包名 */
    val packageName: String,

    /**
     * 限定生效的 Activity 类名。
     * 为空表示该应用内所有界面均可触发，精度较低，建议尽量填。
     */
    val activityName: String? = null,

    /** 节点定位方式 */
    val targetType: TargetType,

    /** 定位值。含义随 [targetType] 变化：viewId / 文本 / 描述 / "x,y" 坐标 */
    val targetValue: String,

    /** 文本匹配模式，仅 [TargetType.TEXT] 与 [TargetType.DESCRIPTION] 生效 */
    val matchMode: MatchMode = MatchMode.EXACT,

    /**
     * 点击后的等待时间（毫秒）。
     * 部分应用在广告关闭后有动画或二次弹窗，适当延迟可提高成功率。
     */
    val clickDelayMs: Long = 0L,

    /** 规则是否启用 */
    val enabled: Boolean = true,

    /** 排序权重，数值越大越先尝试 */
    val priority: Int = 0,

    /**
     * 规则来源。决定该规则在「内置规则集重新导入」时的去留策略
     * （见 [SkipRuleSource]），同时供规则页区分展示。
     */
    val source: SkipRuleSource = SkipRuleSource.USER,
) {
    /**
     * 坐标型规则的解析结果。
     * 仅在 [targetType] 为 [TargetType.COORDINATE] 时有值。
     */
    val coordinate: Pair<Int, Int>?
        get() = if (targetType != TargetType.COORDINATE) {
            null
        } else {
            val parts = targetValue.split(',')
            val x = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val y = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (x != null && y != null) x to y else null
        }
}

/**
 * 广告类型，用于归类拦截记录与统计。
 */
enum class AdType(val label: String) {
    /** 应用启动时的全屏广告 */
    SPLASH("开屏广告"),

    /** 应用内弹窗广告 */
    POPUP("弹窗广告"),

    /** 页面内横幅 */
    BANNER("横幅广告"),

    /** 信息流内嵌广告（技术边界内通常无法拦截） */
    FEED("信息流广告"),

    /** 网页内广告 */
    WEB("网页广告"),

    /** 仅追踪/统计，不展示 */
    TRACKER("追踪域名"),

    /** 未能归类的其他类型 */
    OTHER("其他"),
}

/**
 * 拦截来源策略，对应三策略（+ Shizuku 增强）分工。
 */
enum class InterceptSource(val label: String) {
    /** S1 无障碍模拟点击 */
    ACCESSIBILITY("无障碍"),

    /** S2 本地 DNS 过滤 */
    DNS("DNS 过滤"),

    /** S3 本地 VPN 全流量过滤 */
    VPN("VPN 过滤"),

    /** S4 Shizuku Chain-3 应用级断网 */
    APP_FIREWALL("应用断网"),
}

/**
 * 一条拦截记录。
 *
 * 注意：该模型被写入频繁（S1 在事件回调中产生、S2/S3 在包处理线程产生），
 * 因此字段保持精简，避免大对象分配。
 */
data class InterceptLog(
    val id: Long = 0L,

    /** 产生广告的应用包名（DNS/VPN 层可能无法确定，允许为空） */
    val packageName: String? = null,

    /** 产生广告的应用名（冗余存储，便于日志页直接展示，避免联表查询） */
    val appLabel: String,

    /** 广告类型 */
    val adType: AdType,

    /** 命中该次拦截的策略来源 */
    val source: InterceptSource,

    /** 命中的规则摘要（如 viewId、域名），便于排查 */
    val ruleDetail: String? = null,

    /** 事件发生时间戳（毫秒） */
    val timestamp: Long,
)

/**
 * 受保护的目标应用。
 *
 * 与「已安装应用」不同：本模型只记录用户在 NoAd 中显式纳管的应用，
 * 因此是稀疏集合，而非全量应用列表。
 */
data class TargetApp(
    /** 包名，作为主键 */
    val packageName: String,

    /** 应用展示名（冗余存储，避免每次读取 PackageManager） */
    val label: String,

    /** 是否启用 S1 无障碍拦截 */
    val accessibilityEnabled: Boolean = true,

    /** 是否启用 S2/S3 网络层拦截 */
    val networkFilterEnabled: Boolean = true,

    /** 是否纳入 S4 应用级断网（Shizuku增强，默认关闭） */
    val appFirewallEnabled: Boolean = false,

    /** 排序权重，越大越靠前 */
    val sortOrder: Int = 0,
)
