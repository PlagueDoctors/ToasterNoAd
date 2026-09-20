package com.toaster.noad.core.service.shizuku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shizuku 能力的响应式发布者（让位降级链路的判定源）。
 *
 * ## 为什么需要 Holder 而不是每次现探
 *
 * [VpnArbitrator] 的决策是**同步**的（事件/启动路径上调用），
 * 而能力探测是 suspend（要发 shell 命令）。因此把探测结果缓存为
 * [StateFlow]，仲裁侧同步读 `.value`，探测在后台按事件刷新。
 *
 * ## 刷新时机（保守而确定）
 *
 * - 应用启动（容器构造后）
 * - Shizuku binder 可用/死亡（由 [ShizukuAvailabilityHolder] 驱动）
 * - 用户进入「增强能力」设置页（UI 主动刷新）
 *
 * **不做定时轮询**：能力在运行期几乎不变（要变化必然是 Shizuku 生死，
 * 已有事件驱动），轮询只增加 binder 负载。
 *
 * ## 保守默认值
 *
 * 初始值与探测失败时都是 [ShizukuCapabilityProbe.Capabilities.NONE]
 * —— 「未探测到能力」绝不能表现为「有能力」：
 * 降级链路据它决定是否对用户的应用断网，虚报会造成误操作。
 */
class ShizukuCapabilityHolder(
    private val probe: ShizukuCapabilityProbe,
    private val scope: CoroutineScope,
) {

    private val _capabilities =
        MutableStateFlow(ShizukuCapabilityProbe.Capabilities.NONE)

    val capabilities: StateFlow<ShizukuCapabilityProbe.Capabilities> = _capabilities.asStateFlow()

    /** 后台刷新一次（幂等，可重复调用） */
    fun refresh() {
        scope.launch {
            runCatching { _capabilities.value = probe.probe() }
        }
    }

    /** Shizuku 死亡时立即归零（不等下一次探测） */
    fun onShizukuUnavailable() {
        _capabilities.value = ShizukuCapabilityProbe.Capabilities.NONE
    }
}
