package com.toaster.noad.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.core.data.repository.DomainRuleRepository
import com.toaster.noad.core.data.repository.RuleRepository
import com.toaster.noad.core.data.settings.AppSettings
import com.toaster.noad.core.data.settings.SettingsRepository
import com.toaster.noad.core.model.AccessibilityState
import com.toaster.noad.core.service.AccessibilityStateHolder
import com.toaster.noad.core.service.ProtectionFlags
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 UI 状态。
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    /** S1 无障碍规则启用条数 */
    val skipRuleCount: Int = 0,
    /** 域名规则总数 */
    val domainRuleCount: Int = 0,
    /** 域名白名单条数 */
    val domainWhitelistCount: Int = 0,
    /** S1 无障碍服务的真实运行状态 */
    val accessibility: AccessibilityState = AccessibilityState(),
    val isLoading: Boolean = true,
) {
    /** 域名黑名单条数（总数减去白名单） */
    val domainBlacklistCount: Int
        get() = (domainRuleCount - domainWhitelistCount).coerceAtLeast(0)
}

/**
 * 设置 ViewModel。
 *
 * ## 与旧实现的区别
 *
 * 旧实现 `init` 里直接把状态覆盖为固定值（`autostart = true`），
 * 这会让用户上次的选择在每次进入设置页时被丢弃。
 * 现在全部读写 DataStore：**持久化、不回退**。
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    domainRuleRepository: DomainRuleRepository,
    ruleRepository: RuleRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        ruleRepository.observeEnabledCount(),
        domainRuleRepository.observeTotalCount(),
        domainRuleRepository.observeWhitelistCount(),
        AccessibilityStateHolder.state,
    ) { settings, skipCount, domainCount, whitelistCount, a11y ->
        SettingsUiState(
            settings = settings,
            skipRuleCount = skipCount,
            domainRuleCount = domainCount,
            domainWhitelistCount = whitelistCount,
            accessibility = a11y,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsUiState(),
    )

    fun setAutostart(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutostart(enabled) }
    }

    fun setShowNotification(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowNotification(enabled) }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkTheme(enabled) }
    }

    fun setShizukuEnhancementsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShizukuEnhancementsEnabled(enabled) }
    }

    /**
     * 切换 S1 无障碍拦截开关。
     *
     * 同时写 DataStore（持久化）与内存镜像（热路径），
     * 后者让无障碍服务在事件回调中立即可见，无需等待 Flow 派发。
     */
    fun setAccessibilityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAccessibilityEnabled(enabled)
            ProtectionFlags.setAccessibilityEnabled(enabled)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
