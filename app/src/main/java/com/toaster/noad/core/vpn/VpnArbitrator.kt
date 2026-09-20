package com.toaster.noad.core.vpn

import com.toaster.noad.core.model.NetworkFilterMode

/**
 * 启动决策的原因（与 [com.toaster.noad.core.model.VpnYieldReason] 分工）：
 * 本枚举描述「启动前的仲裁结论」，供日志与 UI 文案映射；
 * `VpnYieldReason` 描述「让位之后的持续状态」。
 */
enum class VpnStartReason {
    /** 用户总开关关闭 */
    USER_DISABLED,

    /** 请求的模式为 OFF，无需启动任何网络组件 */
    MODE_OFF,

    /** 按请求的模式正常启动 */
    PROCEED,

    /** 检测到其他 VPN，但具备 S4 能力，降级为应用级断网（不占 VPN） */
    DEGRADED_TO_APP_FIREWALL,

    /** 检测到其他 VPN 且无法降级，让位 */
    YIELD_TO_OTHER_VPN,
}

/**
 * 启动仲裁的终态。
 *
 * [mode] 是**实际将采用**的模式：正常路径等于请求模式；
 * 降级路径（[VpnStartReason.DEGRADED_TO_APP_FIREWALL]）会被改写为
 * `APP_FIREWALL` —— 调用方必须使用本字段而非原始请求发起后续动作。
 */
data class VpnStartDecision(
    val start: Boolean,
    val mode: NetworkFilterMode,
    val reason: VpnStartReason,
)

/**
 * VPN 让位仲裁（阶段 C，方案 §2.2 / §6.9）。
 *
 * ## 为什么它是阶段 C 的最高优先
 *
 * Android 的最硬约束：**同一时刻只允许一个 VpnService 运行**。
 * 若本应用在其他 VPN 活跃时强行 `establish()`，会**抢占**对方的 TUN；
 * 若双方都自动重连，就是「无限抢占循环」—— VPN 反复掉线，
 * 这是 Android 生态里最典型的负面案例。因此任何 VPN 动作之前
 * 必须先经过本仲裁。
 *
 * ## 决策矩阵（方案 §6.9）
 *
 * | 用户开关 | 请求模式 | 其他 VPN | S4 可用 | 结果 |
 * |---|---|---|---|---|
 * | 关 | 任意 | - | - | 不启动（USER_DISABLED） |
 * | 开 | OFF | - | - | 不启动（MODE_OFF） |
 * | 开 | 不占 VPN（APP_FIREWALL） | 任意 | - | 启动（S4 天然共存） |
 * | 开 | 占 VPN | 有 | 有 | **降级 APP_FIREWALL** |
 * | 开 | 占 VPN | 有 | 无 | 不启动（让位） |
 * | 开 | 占 VPN | 无 | - | 启动 |
 *
 * 「让位即让位」：不启动、不排队、不轮询等待对方退出 ——
 * 恢复只能由用户显式触发（方案决策四），这与 S1 授权恢复的
 * 自动化（R14）是两个不同的问题：VPN 让位涉及与其他应用的
 * 接口争抢，自动化会造成抢占循环。
 *
 * ## 可测性
 *
 * 两个探测均为注入函数：`isOtherVpnActive`（ConnectivityManager 的
 * TRANSPORT_VPN 检测 + 排除自身 handle）与 `canControlPerAppNetwork`
 * （S4 Chain-3 能力）都依赖 Android 框架，注入后本类的**决策矩阵**
 * ——整个阶段 C 最值得锁定的行为——可以在 JVM 上全量验证。
 */
class VpnArbitrator(
    private val isOtherVpnActive: () -> Boolean,
    private val canControlPerAppNetwork: () -> Boolean,
) {

    fun resolveStartDecision(
        userEnabled: Boolean,
        requestedMode: NetworkFilterMode,
    ): VpnStartDecision = when {
        !userEnabled ->
            VpnStartDecision(start = false, mode = requestedMode, reason = VpnStartReason.USER_DISABLED)

        requestedMode == NetworkFilterMode.OFF ->
            VpnStartDecision(start = false, mode = requestedMode, reason = VpnStartReason.MODE_OFF)

        // S4 不建立 TUN，与他人 VPN 天然共存 —— 不参与让位仲裁
        !requestedMode.occupiesVpn ->
            VpnStartDecision(start = true, mode = requestedMode, reason = VpnStartReason.PROCEED)

        isOtherVpnActive() && canControlPerAppNetwork() ->
            VpnStartDecision(
                start = true,
                mode = NetworkFilterMode.APP_FIREWALL,
                reason = VpnStartReason.DEGRADED_TO_APP_FIREWALL,
            )

        isOtherVpnActive() ->
            VpnStartDecision(start = false, mode = requestedMode, reason = VpnStartReason.YIELD_TO_OTHER_VPN)

        else ->
            VpnStartDecision(start = true, mode = requestedMode, reason = VpnStartReason.PROCEED)
    }
}
