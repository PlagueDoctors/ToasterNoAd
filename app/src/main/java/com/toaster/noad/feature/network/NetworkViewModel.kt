package com.toaster.noad.feature.network

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.R
import com.toaster.noad.core.data.repository.DomainRuleRepository
import com.toaster.noad.core.data.settings.SettingsRepository
import com.toaster.noad.core.model.NetworkFilterMode
import com.toaster.noad.core.service.NoAdVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 网络过滤页 UI 状态。
 */
data class NetworkUiState(
    val mode: NetworkFilterMode = NetworkFilterMode.OFF,
    val yielded: Boolean = false,
    /** 当前生效的域名规则条数 */
    val blacklistCount: Int = 0,
    val whitelistCount: Int = 0,
    val isLoading: Boolean = true,
    /**
     * 非 null 时表示系统 VPN 授权尚未授予，UI 应以
     * `startActivityForResult` 启动它并回传结果（阶段 D）。
     */
    val vpnPermissionIntent: Intent? = null,
)

/**
 * 网络过滤 ViewModel（阶段 D 接通 S2 真实启动链路）。
 *
 * ## 模式选择 → 启动链路
 *
 * ```
 * setMode(DNS_ONLY)
 *   → VpnArbitrator 仲裁（其他 VPN / S4 降级）
 *       ├─ 不启动 → vpnYielded=true（让位横幅）
 *       ├─ prepare 非 null → vpnPermissionIntent（UI 弹系统授权对话框）
 *       └─ 通过 → startService(NoAdVpnService, mode)
 * ```
 *
 * ## mode 落盘时机即 UI 真相
 *
 * `networkFilterMode` 只在**启动动作确实发出**后才写入 DataStore；
 * 仲裁让位 / 用户拒绝授权时不写 —— 单选框的状态永远反映
 * 「实际发生了什么」，绝不让 UI 显示一个并不存在的运行中模式。
 *
 * ## yielded 走 DataStore 单一源
 *
 * 让位标记由服务（onRevoke）与 VM（仲裁失败）两处写入，
 * 因此 UI 直接订阅 DataStore 的 `vpnYielded` 而非维护内存副本 ——
 * 否则服务端写入的让位状态对页面不可见（两处真相）。
 */
class NetworkViewModel(
    application: Application,
    private val settingsRepository: SettingsRepository,
    domainRuleRepository: DomainRuleRepository,
) : AndroidViewModel(application) {

    private val _vpnPermissionIntent = MutableStateFlow<Intent?>(null)

    private val settingsFlow = settingsRepository.settings

    val uiState: StateFlow<NetworkUiState> = combine(
        settingsFlow,
        domainRuleRepository.observeTotalCount(),
        domainRuleRepository.observeWhitelistCount(),
        _vpnPermissionIntent,
    ) { settings, total, whitelist, permissionIntent ->
        NetworkUiState(
            mode = settings.networkFilterMode,
            yielded = settings.vpnYielded,
            blacklistCount = (total - whitelist).coerceAtLeast(0),
            whitelistCount = whitelist,
            isLoading = false,
            vpnPermissionIntent = permissionIntent,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = NetworkUiState(),
    )

    /**
     * 切换网络过滤模式。
     *
     * OFF 立即落盘并停服务；DNS_ONLY 走完整启动链路；
     * FULL_TRAFFIC / APP_FIREWALL 在后续阶段接线前**不落盘**
     * （落盘会让 UI 显示一个并不存在的运行中模式）。
     */
    fun setMode(mode: NetworkFilterMode) {
        viewModelScope.launch {
            when (mode) {
                NetworkFilterMode.OFF -> {
                    settingsRepository.setNetworkFilterMode(NetworkFilterMode.OFF)
                    settingsRepository.setVpnYielded(false)
                    stopVpnService()
                }

                NetworkFilterMode.DNS_ONLY -> startDnsVpn()

                NetworkFilterMode.FULL_TRAFFIC ->
                    toast(R.string.network_s3_not_available)

                NetworkFilterMode.APP_FIREWALL ->
                    toast(R.string.network_s4_not_available)

                // HYBRID = S4 + S2，其 S4 部分在阶段 F 接线前不完整；
                // 只跑 S2 会让用户误以为应用断网已生效 —— 同样提示未接入
                NetworkFilterMode.HYBRID ->
                    toast(R.string.network_s4_not_available)
            }
        }
    }

    /** VPN 系统授权对话框完成（成功）后由 UI 回传 */
    fun onVpnPermissionGranted() {
        _vpnPermissionIntent.value = null
        viewModelScope.launch { launchVpnService() }
    }

    /** VPN 系统授权对话框被取消后由 UI 回传 */
    fun onVpnPermissionDenied() {
        _vpnPermissionIntent.value = null
        viewModelScope.launch {
            settingsRepository.setVpnYielded(true)
            toast(R.string.network_vpn_permission_denied)
        }
    }

    /**
     * S2 启动链路：仲裁 → 授权检查 → 启动服务 → 落盘。
     *
     * 任何一步失败都不落盘：mode 单选保持原状，让位横幅说明原因。
     */
    private suspend fun startDnsVpn() {
        val decision = container().vpnArbitrator.resolveStartDecision(
            userEnabled = true,
            requestedMode = NetworkFilterMode.DNS_ONLY,
        )
        if (!decision.start) {
            settingsRepository.setVpnYielded(true)
            return
        }

        val prepareIntent = VpnService.prepare(getApplication())
        if (prepareIntent != null) {
            // 尚未授予系统 VPN 权限：交给 UI 启动系统授权对话框
            _vpnPermissionIntent.value = prepareIntent
            return
        }
        launchVpnService()
    }

    private fun launchVpnService() {
        val context = getApplication<Application>()
        runCatching {
            context.startService(NoAdVpnService.startIntent(context, NetworkFilterMode.DNS_ONLY))
        }.onSuccess {
            viewModelScope.launch {
                settingsRepository.setNetworkFilterMode(NetworkFilterMode.DNS_ONLY)
                settingsRepository.setVpnYielded(false)
            }
        }.onFailure {
            toast(R.string.network_vpn_start_failed)
        }
    }

    /**
     * 停止 VPN 服务。
     *
     * 用显式 ACTION_STOP 指令（startService）而非 `stopService`：
     * 前者确定执行 closeTun（撤销 TUN 的唯一可靠途径）并留日志；
     * 后者对 VpnService 的销毁回调在部分 ROM/时序下不可靠 ——
     * 曾导致「选关闭后状态栏 VPN 图标残留」。
     */
    private fun stopVpnService() {
        val context = getApplication<Application>()
        runCatching { context.startService(NoAdVpnService.stopIntent(context)) }
    }

    /** 用户确认「已了解让位原因」 */
    fun acknowledgeYield() {
        viewModelScope.launch {
            settingsRepository.setVpnYielded(false)
        }
    }

    private fun container() = (getApplication<Application>() as com.toaster.noad.NoAdApplication).container

    private fun toast(resId: Int) {
        Toast.makeText(getApplication(), resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
