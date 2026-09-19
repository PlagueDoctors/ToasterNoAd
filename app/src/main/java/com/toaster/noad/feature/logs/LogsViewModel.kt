package com.toaster.noad.feature.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.core.data.repository.LogRepository
import com.toaster.noad.core.model.InterceptLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 拦截日志页 UI 状态。
 */
data class LogsUiState(
    val logs: List<InterceptLog> = emptyList(),
    val isLoading: Boolean = true,
    val totalToday: Int = 0,
) {
    val isEmpty: Boolean get() = !isLoading && logs.isEmpty()
}

/**
 * 拦截日志 ViewModel。
 *
 * 直接消费 [LogRepository] 的 Flow：日志写入数据库后 UI 自动刷新，
 * 既不需要手动刷新按钮，也不需要轮询。
 */
class LogsViewModel(
    private val logRepository: LogRepository,
) : ViewModel() {

    /** 仅作为「清空」操作的加载态标记；不承载业务状态 */
    private val isBusy = MutableStateFlow(false)

    val uiState: StateFlow<LogsUiState> = combine(
        logRepository.observeRecent(),
        logRepository.observeTodayCount(),
        isBusy,
    ) { logs, todayCount, _ ->
        LogsUiState(
            logs = logs,
            isLoading = false,
            totalToday = todayCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = LogsUiState(),
    )

    /** 清空全部日志 */
    fun clearLogs() {
        viewModelScope.launch {
            isBusy.value = true
            runCatching { logRepository.clear() }
            isBusy.value = false
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
