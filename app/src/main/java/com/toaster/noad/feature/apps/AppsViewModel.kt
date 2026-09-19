package com.toaster.noad.feature.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.core.data.repository.ManagedApp
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.model.TargetApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * 应用管理页 UI 状态。
 */
data class AppsUiState(
    val query: String = "",
    val apps: List<ManagedApp> = emptyList(),
    val isLoading: Boolean = true,
    val includeSystemApps: Boolean = false,
    val errorMessage: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && apps.isEmpty()

    /** 已纳管应用数 */
    val managedCount: Int get() = apps.count { it.managed }
}

/**
 * 应用管理 ViewModel。
 *
 * ## 与旧实现的区别
 *
 * 旧实现用 8 条硬编码应用做过滤。现在改为：
 * - 从 [TargetAppRepository.merge] 读取**真实已安装应用**（依赖 `QUERY_ALL_PACKAGES`）
 * - 叠加数据库中用户的纳管状态
 * - 搜索按应用名与包名双匹配，并做防抖（避免每次按键都触发 PackageManager 查询）
 *
 * ## 响应式更新
 *
 * 通过 `observeTargetApps()` 作为触发源：数据库任何变更都会驱动列表重组，
 * 因此切换开关后 UI 会自动刷新，无需手动 reload。
 *
 * ## 纳管语义
 *
 * 开关打开 = 写入纳管记录；开关关闭 = **删除**该记录（而非置为 disabled），
 * 这样「受保护应用数」才等于真正被纳管的应用数量。
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AppsViewModel(
    private val targetAppRepository: TargetAppRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppsUiState())
    val uiState: StateFlow<AppsUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    private val includeSystemAppsFlow = MutableStateFlow(false)

    init {
        observeApps()
    }

    private fun observeApps() {
        viewModelScope.launch {
            combine(
                queryFlow.debounce(SEARCH_DEBOUNCE_MS),
                // StateFlow 本身已做去重（Operator Fusion），无需再调 distinctUntilChanged
                includeSystemAppsFlow,
                // 数据库纳管状态作为第三个触发源
                targetAppRepository.observeTargetApps(),
            ) { query, includeSystem, _ -> query to includeSystem }
                .flatMapLatest { (query, includeSystem) ->
                    val loading = MutableStateFlow(
                        AppsUiState(
                            query = query,
                            includeSystemApps = includeSystem,
                            isLoading = true,
                        ),
                    )
                    kotlinx.coroutines.flow.flow {
                        emit(loading.value)
                        val merged = runCatching {
                            targetAppRepository.merge(
                                query = query,
                                includeSystemApps = includeSystem,
                            )
                        }
                        emit(
                            merged.fold(
                                onSuccess = { apps ->
                                    AppsUiState(
                                        query = query,
                                        apps = apps,
                                        includeSystemApps = includeSystem,
                                        isLoading = false,
                                    )
                                },
                                onFailure = { error ->
                                    AppsUiState(
                                        query = query,
                                        includeSystemApps = includeSystem,
                                        isLoading = false,
                                        errorMessage = error.message ?: "读取应用列表失败",
                                    )
                                },
                            ),
                        )
                    }
                }
                .collect { _uiState.value = it }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        queryFlow.value = query
    }

    fun setIncludeSystemApps(include: Boolean) {
        includeSystemAppsFlow.value = include
    }

    /**
     * 切换应用的纳管状态。
     *
     * 打开：写入纳管记录（S1 与网络层默认开启，S4 默认关闭）
     * 关闭：删除纳管记录
     */
    fun toggleApp(packageName: String) {
        viewModelScope.launch {
            val app = _uiState.value.apps
                .firstOrNull { it.packageName == packageName } ?: return@launch
            if (app.managed) {
                targetAppRepository.remove(packageName)
            } else {
                targetAppRepository.upsert(
                    TargetApp(
                        packageName = app.packageName,
                        label = app.label,
                        accessibilityEnabled = true,
                        networkFilterEnabled = true,
                        appFirewallEnabled = false,
                    ),
                )
            }
        }
    }

    /** 批量纳管当前列表中的全部未纳管应用 */
    fun manageAllVisible() {
        viewModelScope.launch {
            _uiState.value.apps
                .filterNot { it.managed }
                .forEach { app ->
                    targetAppRepository.upsert(
                        TargetApp(
                            packageName = app.packageName,
                            label = app.label,
                            accessibilityEnabled = true,
                            networkFilterEnabled = true,
                        ),
                    )
                }
        }
    }

    /** 取消纳管当前列表中的全部应用 */
    fun clearAllVisible() {
        viewModelScope.launch {
            _uiState.value.apps
                .filter { it.managed }
                .forEach { targetAppRepository.remove(it.packageName) }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

