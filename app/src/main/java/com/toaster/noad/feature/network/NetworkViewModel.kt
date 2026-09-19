package com.toaster.noad.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.core.data.repository.DomainRuleRepository
import com.toaster.noad.core.data.settings.SettingsRepository
import com.toaster.noad.core.model.NetworkFilterMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

/**
 * 网络过滤 ViewModel。
 *
 * ## 模式选择的约束表达
 *
 * S2（DNS_ONLY）与 S3（FULL_TRAFFIC）互斥，因为 Android 只允许一个 VpnService。
 * S4（APP_FIREWALL，Shizuku Chain-3）不占用 VPN，可与 S2 叠加。
 *
 * 这些约束由 [NetworkFilterMode] 的属性表达，UI 据此决定哪些选项可选、
 * 哪些需要额外提示（如「将占用系统 VPN」）。
 */
class NetworkViewModel(
    private val settingsRepository: SettingsRepository,
    domainRuleRepository: DomainRuleRepository,
) : ViewModel() {

    private val _yielded = MutableStateFlow(false)

    val uiState: StateFlow<NetworkUiState> = combine(
        settingsRepository.networkFilterMode,
        domainRuleRepository.observeTotalCount(),
        domainRuleRepository.observeWhitelistCount(),
        _yielded,
    ) { mode, total, whitelist, yielded ->
        NetworkUiState(
            mode = mode,
            yielded = yielded,
            blacklistCount = (total - whitelist).coerceAtLeast(0),
            whitelistCount = whitelist,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = NetworkUiState(),
    )

    /** 切换网络过滤模式。真实的 TUN 建立/拆除在阶段 C/D/E 接入 */
    fun setMode(mode: NetworkFilterMode) {
        viewModelScope.launch {
            settingsRepository.setNetworkFilterMode(mode)
            // 用户主动切换模式时清除让位状态（表示用户重新表达了启用意图）
            settingsRepository.setVpnYielded(false)
            _yielded.value = false
        }
    }

    /** 用户确认「已了解让位原因」 */
    fun acknowledgeYield() {
        viewModelScope.launch {
            _yielded.value = false
            settingsRepository.setVpnYielded(false)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
