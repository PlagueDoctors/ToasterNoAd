package com.toaster.noad.feature.logs

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogItem(
    val id: Long,
    val appLabel: String,
    val adType: String,
    val timestamp: Long,
)

data class LogsUiState(
    val logs: List<LogItem> = emptyList(),
    val isLoading: Boolean = false,
)

class LogsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = LogsUiState(
            logs = listOf(
                LogItem(1L, "抖音", "开屏广告", System.currentTimeMillis() - 60_000L),
                LogItem(2L, "淘宝", "信息流广告", System.currentTimeMillis() - 300_000L),
                LogItem(3L, "微博", "开屏广告", System.currentTimeMillis() - 1_800_000L),
                LogItem(4L, "今日头条", "信息流广告", System.currentTimeMillis() - 3_600_000L),
                LogItem(5L, "微信", "横幅广告", System.currentTimeMillis() - 7_200_000L),
                LogItem(6L, "QQ", "开屏广告", System.currentTimeMillis() - 86_400_000L),
            ),
        )
    }
}
