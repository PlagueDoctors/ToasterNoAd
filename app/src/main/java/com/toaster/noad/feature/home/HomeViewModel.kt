package com.toaster.noad.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.core.database.dao.BlockedAppCount
import com.toaster.noad.core.data.repository.LogRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.data.settings.SettingsRepository
import com.toaster.noad.core.model.InterceptSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 UI 状态。
 */
data class HomeUiState(
    val todayBlocked: Int = 0,
    val protectionEnabled: Boolean = false,
    val protectedAppCount: Int = 0,
    val totalAppCount: Int = 0,
    val isLoading: Boolean = true,
    /** 今日拦截最多的应用（前 N） */
    val topApps: List<TopBlockedAppItem> = emptyList(),
)

data class TopBlockedAppItem(
    val packageName: String,
    val label: String,
    val count: Int,
)

/**
 * 首页 ViewModel。
 *
 * ## 数据来源
 *
 * 全部来自 Repository 的真实 Flow。原先的硬编码假数据已移除。
 *
 * ## 关于「较昨日对比」
 *
 * 该指标需要昨日区间的计数，而当前 DAO 只提供「自某时刻起」的计数。
 * 与其用近似值给出可能误导的数字，此处**先不展示该指标** ——
 * 待 DAO 补齐区间查询后再恢复（记录在待办中）。
 */
class HomeViewModel(
    targetAppRepository: TargetAppRepository,
    logRepository: LogRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val todayCount = logRepository.observeTodayCount()
    private val protectedCount = targetAppRepository.observeAccessibilityEnabledCount()
    private val totalCount = targetAppRepository.observeTotalCount()
    private val topApps = logRepository.observeTopBlockedApps()
    private val settings = settingsRepository.settings
    val uiState: StateFlow<HomeUiState> = combine(
        todayCount,
        protectedCount,
        totalCount,
        topApps,
        settings,
    ) { today, protected_, total, top, settings ->
        HomeUiState(
            todayBlocked = today,
            protectionEnabled = settings.protectionEnabled,
            protectedAppCount = protected_,
            totalAppCount = total,
            isLoading = false,
            topApps = top.map { it.toItem() },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    /** 按来源分解的统计（S1 / S2 / S3 / S4 各自拦截了多少） */
    val blockedBySource: StateFlow<Map<InterceptSource, Int>> = combine(
        logRepository.observeTodayCountBySource(InterceptSource.ACCESSIBILITY),
        logRepository.observeTodayCountBySource(InterceptSource.DNS),
        logRepository.observeTodayCountBySource(InterceptSource.VPN),
        logRepository.observeTodayCountBySource(InterceptSource.APP_FIREWALL),
    ) { accessibility, dns, vpn, firewall ->
        mapOf(
            InterceptSource.ACCESSIBILITY to accessibility,
            InterceptSource.DNS to dns,
            InterceptSource.VPN to vpn,
            InterceptSource.APP_FIREWALL to firewall,
        ).filterValues { it > 0 }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyMap(),
    )

    /** 切换总开关。真实的服务启停逻辑在后续阶段接入 */
    fun toggleProtection() {
        viewModelScope.launch {
            settingsRepository.setProtectionEnabled(!uiState.value.protectionEnabled)
        }
    }

    private fun BlockedAppCount.toItem() = TopBlockedAppItem(
        packageName = packageName,
        label = appLabel,
        count = count,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
