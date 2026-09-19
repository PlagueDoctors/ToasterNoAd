package com.toaster.noad.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.core.database.dao.BlockedAppCount
import com.toaster.noad.core.data.repository.LogRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.data.settings.SettingsRepository
import com.toaster.noad.core.model.AccessibilityState
import com.toaster.noad.core.model.InterceptSource
import com.toaster.noad.core.service.AccessibilityStateHolder
import com.toaster.noad.core.service.ProtectionFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 今日拦截排行条目。
 */
data class TopBlockedAppItem(
    val packageName: String,
    val label: String,
    val count: Int,
)

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
    /**
     * S1 无障碍的**真实运行状态**。
     *
     * 与设置里的开关（用户意图）是两件事：
     * 用户可能在设置中授权了服务，但服务尚未被系统连接；
     * 也可能服务正在运行，但用户在应用内关闭了拦截开关。
     */
    val accessibility: AccessibilityState = AccessibilityState(),
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
    application: Application,
    targetAppRepository: TargetAppRepository,
    logRepository: LogRepository,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {

    private val todayCount = logRepository.observeTodayCount()
    private val protectedCount = targetAppRepository.observeAccessibilityEnabledCount()
    private val totalCount = targetAppRepository.observeTotalCount()
    private val topApps = logRepository.observeTopBlockedApps()
    private val settings = settingsRepository.settings
    private val accessibilityState = AccessibilityStateHolder.state

    /**
     * 应用内数据（计数 + 排行），5 路以内。
     *
     * Kotlin 的 `combine` 只提供到 5 个 Flow 的类型化重载，
     * 超过即退化为 `combine(vararg)` 的 `Array<Any>` 版本 ——
     * 那样会丢失全部类型信息。因此这里先合并到 5 路，
     * 再与外部的无障碍状态做二次合并。
     */
    private val appData = combine(
        todayCount,
        protectedCount,
        totalCount,
        topApps,
        settings,
    ) { today, protected_, total, top, settings ->
        AppData(
            todayBlocked = today,
            protectionEnabled = settings.protectionEnabled,
            protectedAppCount = protected_,
            totalAppCount = total,
            topApps = top.map { it.toItem() },
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        appData,
        accessibilityState,
    ) { data, a11y ->
        HomeUiState(
            todayBlocked = data.todayBlocked,
            protectionEnabled = data.protectionEnabled,
            protectedAppCount = data.protectedAppCount,
            totalAppCount = data.totalAppCount,
            isLoading = false,
            topApps = data.topApps,
            accessibility = a11y,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    /** 中间聚合结果，避免 6 路 combine 导致类型退化 */
    private data class AppData(
        val todayBlocked: Int,
        val protectionEnabled: Boolean,
        val protectedAppCount: Int,
        val totalAppCount: Int,
        val topApps: List<TopBlockedAppItem>,
    )

    init {
        // 从系统设置刷新一次真实授权状态。
        // 系统不提供"无障碍服务状态变化"的广播，
        // 因此每次进入首页时主动核对一次，这是最可靠的时机。
        refreshAccessibilityState()
    }

    /** 重新核对系统无障碍设置中的授权状态 */
    fun refreshAccessibilityState() {
        viewModelScope.launch(Dispatchers.IO) {
            // 用 application 上下文：Settings.Secure 查询是跨进程调用，
            // 传 Activity 上下文可能因 Activity 销毁而持有无效引用
            AccessibilityStateHolder.refreshFromSystemSettings(getApplication())
        }
    }

    /**
     * 切换 S1 无障碍拦截开关。
     *
     * 注意：这里切换的是**应用内的拦截开关**，
     * 不是系统设置里的服务授权 —— 后者只能由用户手动开启。
     * 二者是"AND"关系：都开启才真正生效。
     */
    fun setAccessibilityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAccessibilityEnabled(enabled)
            // 同步内存镜像，使无障碍服务在事件回调中立即可见
            ProtectionFlags.setAccessibilityEnabled(enabled)
        }
    }

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
