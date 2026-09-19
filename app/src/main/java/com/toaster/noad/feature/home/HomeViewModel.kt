package com.toaster.noad.feature.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首页 UI 状态
 */
data class HomeUiState(
    val todayBlocked: Int = 0,
    val diffYesterdayPercent: Int = 0,
    val progress: Float = 0f,
    val protectionEnabled: Boolean = false,
    val protectedAppCount: Int = 0,
    val totalAppCount: Int = 0,
    val protectedApps: List<ProtectedAppItem> = emptyList(),
)

data class ProtectedAppItem(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
)

class HomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // 占位假数据：实际接入广告拦截服务后替换
        _uiState.value = HomeUiState(
            todayBlocked = 1247,
            diffYesterdayPercent = 18,
            progress = 0.75f,
            protectionEnabled = true,
            protectedAppCount = 6,
            totalAppCount = 8,
            protectedApps = listOf(
                ProtectedAppItem("com.tencent.mm", "微信", true),
                ProtectedAppItem("com.ss.android.ugc.aweme", "抖音", true),
                ProtectedAppItem("com.taobao.taobao", "淘宝", false),
                ProtectedAppItem("com.eg.android.AlipayGphone", "支付宝", true),
                ProtectedAppItem("com.tencent.mobileqq", "QQ", true),
                ProtectedAppItem("com.sina.weibo", "微博", false),
            ),
        )
    }

    fun toggleProtection() {
        _uiState.value = _uiState.value.copy(protectionEnabled = !_uiState.value.protectionEnabled)
    }
}
