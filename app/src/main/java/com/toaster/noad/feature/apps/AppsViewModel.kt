package com.toaster.noad.feature.apps

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppItem(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
)

data class AppsUiState(
    val query: String = "",
    val apps: List<AppItem> = emptyList(),
    val isLoading: Boolean = false,
)

class AppsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppsUiState())
    val uiState: StateFlow<AppsUiState> = _uiState.asStateFlow()

    private val allApps = listOf(
        AppItem("com.tencent.mm", "微信", true),
        AppItem("com.tencent.mobileqq", "QQ", false),
        AppItem("com.ss.android.ugc.aweme", "抖音", true),
        AppItem("com.taobao.taobao", "淘宝", false),
        AppItem("com.eg.android.AlipayGphone", "支付宝", true),
        AppItem("com.sina.weibo", "微博", false),
        AppItem("com.netease.cloudmusic", "网易云音乐", true),
        AppItem("com.ss.android.article.news", "今日头条", false),
    )

    init {
        _uiState.value = AppsUiState(apps = allApps)
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(
            query = query,
            apps = if (query.isBlank()) allApps else allApps.filter { it.label.contains(query) },
        )
    }

    fun toggleApp(packageName: String) {
        val updated = _uiState.value.apps.map {
            if (it.packageName == packageName) it.copy(enabled = !it.enabled) else it
        }
        _uiState.value = _uiState.value.copy(apps = updated)
    }
}
