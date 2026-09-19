package com.toaster.noad.core.model

/**
 * 网络过滤模式。
 *
 * ## 为什么这样设计
 *
 * Android 的硬约束：**同一时刻只允许一个 VpnService 运行**。
 * 因此 [DNS_ONLY] 与 [FULL_TRAFFIC] 不能同时启用，必须互斥。
 *
 * [APP_FIREWALL] 是 Shizuku 增强路径（Chain-3 应用级断网），
 * 它**不建立 TUN、不占用系统 VPN**，因此可以与用户自己的 VPN 共存，
 * 也可以与 [DNS_ONLY] 叠加使用。
 *
 * 参见：`reference/THREE_STRATEGY_PLAN.md`、`reference/SHIZUKU_ENHANCEMENT.md`
 */
enum class NetworkFilterMode(val label: String) {
    /** 关闭：不接管网络，仅 S1 无障碍生效 */
    OFF("关闭"),

    /** S2：只接管 DNS 查询，性能影响极小 */
    DNS_ONLY("DNS 过滤"),

    /** S3：接管全部流量，能力最强但独占系统 VPN */
    FULL_TRAFFIC("全流量过滤"),

    /** S4：Shizuku Chain-3 按应用断网，不占用 VPN */
    APP_FIREWALL("应用级断网"),

    /** S4 与 S2 叠加：应用级断网 + DNS 域名过滤 */
    HYBRID("混合模式");

    /** 该模式是否需要占用系统 VPN（决定与「让位逻辑」的关系） */
    val occupiesVpn: Boolean
        get() = this == DNS_ONLY || this == FULL_TRAFFIC || this == HYBRID

    /** 该模式是否需要 Shizuku 特权通道 */
    val requiresShizuku: Boolean
        get() = this == APP_FIREWALL || this == HYBRID
}

/**
 * VPN 让位原因。
 */
enum class VpnYieldReason(val label: String) {
    /** 非让位状态 */
    NONE(""),

    /** 用户主动关闭 */
    USER_DISABLED("用户已关闭"),

    /** 检测到其他 VPN 正在运行，主动让位 */
    OTHER_VPN_ACTIVE("检测到其他 VPN，已让位"),

    /** 被其他 VPN 抢占（收到 onRevoke） */
    REVOKED("已被其他 VPN 接管"),

    /** 缺少 VPN 授权 */
    NOT_PREPARED("尚未授予 VPN 权限"),
}

/**
 * 无障碍服务与系统之间的连接状态（S1）。
 *
 * ## 三个字段为什么要分开
 *
 * 「无障碍生效」实际上是**三个独立条件的与**：
 *
 * 1. 用户在系统设置里授权了 NoAd 的服务（[serviceEnabledInSettings]）
 * 2. 系统真的把服务连上了（[serviceRunning]）——
 *    授权后服务可能因内存压力被系统解绑
 * 3. 用户在 NoAd 应用内没有关掉 S1 开关（[appSwitchEnabled]）
 *
 * 把它们混成一个布尔值会产生误导：用户看到"已开启"却在拦截日志里
 * 一条记录都没有，却不知道是哪一环断了。
 *
 * ## ⚠️ 授权 ≠ 运行（理解本状态机的前提）
 *
 * Android 的无障碍是**授权模型**：用户在设置里勾选后，系统把这一条写进
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，然后**自行决定何时**
 * 连接、何时解绑服务。官方文档对 `AccessibilityService` 的描述是
 * "由系统绑定/解绑"，没有任何 API 能让应用要求系统"保持连接"。
 *
 * 息屏、切换应用、内存压力都可能导致**服务实例被解绑**，但设置里的
 * 那条授权记录**依然存在**——这正是它能被系统自动重连的原因。
 *
 * 因此本状态机的正确读法是：
 *
 * - 「断开」= `serviceEnabledInSettings && !serviceRunning` → **可自愈**
 * - 「未授权」= `!serviceEnabledInSettings` → 需要用户去设置
 *
 * 前者不需要用户做任何事，系统或本应用的对策会恢复它；
 * 把它误显示成"权限被关了"会让用户反复跑去设置里确认，是纯粹的误导。
 */
data class AccessibilityState(
    /** 系统是否已连接本服务（运行时事实，最可靠的信号） */
    val serviceRunning: Boolean = false,

    /** 用户是否已在系统设置中授权本服务 */
    val serviceEnabledInSettings: Boolean = false,

    /** 用户在 NoAd 应用内是否开启了 S1 拦截开关 */
    val appSwitchEnabled: Boolean = false,

    /**
     * 是否被 Android 13+ 的「受限设置」阻止开启。
     * 侧载应用默认为 true，需要用户手动放行或借助 Shizuku 解除。
     */
    val restrictedBySideload: Boolean = false,

    /**
     * 最近一次观察到服务断开的时间戳（`SystemClock.elapsedRealtime()`，毫秒）。
     *
     * 用单调时钟而非墙上时钟：它不受用户改系统时间、时区切换影响，
     * 而这里只关心"过了多久"，不关心"是几点"。
     *
     * `null` 表示本进程内尚未观察到断开（或已重新连接后清空）。
     */
    val lastDisconnectedAtMillis: Long? = null,

    /**
     * 最近一次断开的原因。`null` 表示无断开记录。
     *
     * 区分原因的意义在于**让用户知道要不要动手**：
     * 系统解绑只需等待自愈，用户手动关闭则必须自己去设置。
     */
    val disconnectReason: DisconnectReason? = null,
) {
    /**
     * S1 是否**真正在生效**。
     *
     * 要求服务正在运行且应用内开关已开。
     * 注意不以 [serviceEnabledInSettings] 为必要条件：
     * 服务能跑起来就说明系统已授权，该字段只是兜底信号
     * （系统设置读取有缓存延迟，不应反过来否认真实运行状态）。
     */
    val isEffectivelyActive: Boolean
        get() = serviceRunning && appSwitchEnabled

    /**
     * 是否需要引导用户去系统设置。
     *
     * 授权已存在时不提示，避免用户已经开好了还被反复引导。
     */
    val needsSystemPermission: Boolean
        get() = !serviceRunning && !serviceEnabledInSettings

    /**
     * ⭐ 是否处于「已授权但当前未连接」—— 本状态机里最需要自愈的一态。
     *
     * 此时用户**什么都不用做**：系统会在下一次界面事件时重新连接服务，
     * 应用侧的自愈监听也会在息屏/解锁/切回前台时主动核对。
     *
     * UI 必须把它与 [needsSystemPermission] 区分开：前者是"等恢复"，
     * 后者是"去设置"。混在一起会让用户以为自己没开好。
     */
    val isDisconnectedButAuthorized: Boolean
        get() = !serviceRunning && serviceEnabledInSettings

    /**
     * 是否连"授权"都没有。
     *
     * 与 [needsSystemPermission] 同义，保留为语义更直白的别名供 UI 使用。
     */
    val isNotAuthorized: Boolean
        get() = !serviceRunning && !serviceEnabledInSettings
}

/**
 * 无障碍服务断开的原因。
 *
 * ## 为什么需要这个枚举
 *
 * 断开是**必然会发生**的（系统在息屏/内存压力/窗口切换时都会解绑），
 * 但用户看到的现象只有一句"不拦截了"，无法判断该不该干预。
 * 把原因摆出来，用户才能知道"是等一等"还是"去设置里看看"。
 *
 * ## 取值来源的诚实性
 *
 * Android **不提供**"服务被解绑的原因"这一 API。因此这里不做推测：
 * 只区分**我们确实观察到的**两种回调路径，其余一律归为 [UNKNOWN]。
 * 猜一个"大概是内存不足"写进 UI，是拿可信度换好看。
 */
enum class DisconnectReason(val label: String) {
    /** 系统解绑（`onUnbind` 被调用）。最常见，通常会自动恢复 */
    SYSTEM_UNBOUND("系统解绑"),

    /** 服务被销毁（`onDestroy`，进程结束或系统回收） */
    SERVICE_DESTROYED("服务被回收"),

    /** 用户在系统设置中关闭了本服务 */
    USER_DISABLED_IN_SETTINGS("已在系统设置中关闭"),

    /** 无法归因（例如本进程启动时服务就没连着，没有观察到断开瞬间） */
    UNKNOWN("原因不明");

    companion object {
        /**
         * 从持久化名还原。
         *
         * 未知值退回 [UNKNOWN] 而非抛异常：这是诊断信息，
         * 版本升级导致的枚举变化不该让整个状态读取失败。
         */
        fun fromPersistedName(name: String?): DisconnectReason? {
            if (name.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
        }
    }
}


/**
 * 统一保护状态。
 *
 * 由各 Service / Repository 的状态汇聚而成，供首页与网络页消费。
 * 这是 UI 观察保护能力的**唯一权威来源**，避免各页面各自拼装状态导致不一致。
 */
data class ProtectionState(
    /** 总开关（用户意图），不代表实际生效 */
    val userEnabled: Boolean = false,

    /** S1 无障碍 */
    val accessibility: AccessibilityState = AccessibilityState(),

    /** 网络过滤模式（用户选择） */
    val networkFilterMode: NetworkFilterMode = NetworkFilterMode.OFF,

    /** 网络过滤是否实际生效 */
    val networkFilterActive: Boolean = false,

    /** 让位原因，[VpnYieldReason.NONE] 表示未让位 */
    val yieldReason: VpnYieldReason = VpnYieldReason.NONE,

    /** Shizuku 特权通道是否可用（增强能力） */
    val shizukuAvailable: Boolean = false,
) {
    /** S1 是否实际生效 */
    val isAccessibilityActive: Boolean
        get() = accessibility.serviceRunning

    /** 是否存在让位状态（UI 需要提示用户） */
    val isYielded: Boolean
        get() = yieldReason != VpnYieldReason.NONE

    /** 是否有任何一策略在实际生效 */
    val isAnyStrategyActive: Boolean
        get() = isAccessibilityActive || networkFilterActive
}
