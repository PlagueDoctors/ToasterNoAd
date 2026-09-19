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
 * 文本匹配方式。
 */
enum class MatchMode {
    /** 完全相等 */
    EXACT,

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
