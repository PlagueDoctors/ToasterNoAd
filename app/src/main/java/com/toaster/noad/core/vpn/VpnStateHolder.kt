package com.toaster.noad.core.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VPN 运行状态发布器（进程内单例，模式同 AccessibilityStateHolder）。
 *
 * ## 为什么需要独立 holder
 *
 * VPN 是否真的在跑只有 [com.toaster.noad.core.service.NoAdVpnService]
 * 自己知道（establish 成功/被抢占/被停止），而首页策略状态卡需要
 * 如实展示这一事实 —— 双方解耦的唯一共享点是进程内单例。
 *
 * ## 真值边界
 *
 * `running == true` 的含义严格限定为「TUN 已建立且包循环在跑」，
 * 由服务在 establish 成功后置位、在 onDestroy/onRevoke/失败时清零。
 * 它不代表「拦截规则生效」（那是规则库的事），也不代表「系统已授权」
 * （那发生在 establish 之前）。
 *
 * ## 与 DataStore 的分工
 *
 * `networkFilterMode` 是**用户意图**（落盘，重启可恢复）；
 * 本 holder 是**运行事实**（进程内，随服务生死）。
 * 「首页显示 DNS 过滤运行中」= 意图选中且事实在跑，两者不可互替。
 */
object VpnStateHolder {

    private val _running = MutableStateFlow(false)

    /** VPN TUN 是否已建立且包循环在跑 */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 由服务在 establish 成功、包循环启动后调用 */
    fun onEstablished() {
        _running.value = true
    }

    /** 由服务在 onDestroy / onRevoke / establish 失败路径调用 */
    fun onStopped() {
        _running.value = false
    }
}
