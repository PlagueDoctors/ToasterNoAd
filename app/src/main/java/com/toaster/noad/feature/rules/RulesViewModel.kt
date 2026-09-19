package com.toaster.noad.feature.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.core.data.repository.RuleRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.SkipRuleSource
import com.toaster.noad.core.model.TargetType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 规则页 UI 状态。
 *
 * @param groups 按应用分组的规则列表（已是过滤后的结果）
 * @param totalRules 全部规则数（不受搜索影响），用于顶部摘要
 * @param totalApps 覆盖的应用数（不受搜索影响）
 */
data class RulesUiState(
    val query: String = "",
    val groups: List<RuleGroup> = emptyList(),
    val totalRules: Int = 0,
    val totalApps: Int = 0,
    val isLoading: Boolean = true,
) {
    /** 是否处于"搜索引擎无结果"状态（与"一条规则都没有"不同） */
    val isEmptySearch: Boolean
        get() = !isLoading && query.isNotBlank() && groups.isEmpty()

    /** 是否完全没有任何规则 */
    val isEmpty: Boolean
        get() = !isLoading && totalRules == 0
}

/**
 * 一个应用下的规则集合。
 *
 * 按应用分组而非平铺列表：跳过规则天然归属于某个应用，
 * 用户排查"某个 App 的广告没跳过"时，第一反应就是找这个 App。
 */
data class RuleGroup(
    val packageName: String,
    val appLabel: String,
    val rules: List<RuleItem>,
    /** 该应用是否已被纳管（未纳管时规则不会生效） */
    val managed: Boolean,
) {
    val enabledCount: Int get() = rules.count { it.enabled }
}

/**
 * 单条规则的展示模型。
 *
 * 把展示所需的派生字段（定位方式的中文名、匹配模式标签）
 * 在 ViewModel 中算好，避免 Composable 里堆积 when 分支 ——
 * 那会让 UI 代码同时承担"展示"与"翻译枚举"两种职责。
 */
data class RuleItem(
    val id: Long,
    val name: String,
    val activityName: String?,
    val targetType: TargetType,
    val targetValue: String,
    val matchMode: MatchMode,
    val priority: Int,
    val enabled: Boolean,
    val source: SkipRuleSource,
) {
    val isBuiltin: Boolean get() = source == SkipRuleSource.BUILTIN

    /**
     * 定位方式的可读描述。
     *
     * 不用 `stringResource` 是因为这里不是 Composable；
     * 文案固定且不与语言强相关（都是技术术语），
     * 直接给中文标签比把资源 id 传进 UI 再解析更直接。
     */
    val targetTypeLabel: String
        get() = when (targetType) {
            TargetType.VIEW_ID -> "控件 ID"
            TargetType.TEXT -> "按钮文字"
            TargetType.DESCRIPTION -> "按钮描述"
            TargetType.COORDINATE -> "屏幕坐标"
        }

    /** 匹配模式标签。控件 ID / 坐标是精确匹配，不展示模式以免误导 */
    val matchModeLabel: String?
        get() = when {
            targetType == TargetType.VIEW_ID || targetType == TargetType.COORDINATE -> null
            matchMode == MatchMode.EXACT -> "精确"
            matchMode == MatchMode.CONTAINS -> "包含"
            else -> "正则"
        }
}

/**
 * 规则页 ViewModel。
 *
 * ## 同时订阅两条数据流的原因
 *
 * 规则的**可用性**取决于两件事：规则本身存在，且所属应用已被纳管。
 * 只有规则没有纳管 = 规则不会生效，用户会困惑"规则明明在列表里却没作用"。
 * 因此需要 `targetApp` 一起判断，并在 UI 上标注出来。
 */
class RulesViewModel(
    private val ruleRepository: RuleRepository,
    targetAppRepository: TargetAppRepository,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")

    val uiState: StateFlow<RulesUiState> = combine(
        ruleRepository.observeRules(),
        targetAppRepository.observeTargetApps(),
        queryFlow,
    ) { rules, targetApps, query ->
        buildState(rules, targetApps, query)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = RulesUiState(),
    )

    fun onQueryChange(query: String) {
        queryFlow.value = query
    }

    /** 启停一条规则 */
    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { ruleRepository.setEnabled(id, enabled) }
    }

    /**
     * 删除一条规则。
     *
     * 不做"内置规则不可删"的限制：用户删掉不想要的内置规则后，
     * 在这个版本内就不再受其干扰。若该规则在后续版本仍存在，
     * 会重新出现 —— 这一行为已在删除确认弹窗中说明。
     */
    fun delete(id: Long) {
        viewModelScope.launch { ruleRepository.deleteById(id) }
    }

    private fun buildState(
        rules: List<SkipRule>,
        targetApps: List<com.toaster.noad.core.model.TargetApp>,
        query: String,
    ): RulesUiState {
        val managedPackages = targetApps.mapTo(HashSet(targetApps.size)) { it.packageName }

        // 应用名从纳管数据取；未纳管的应用没有 label，退回包名
        val labelByPackage = targetApps.associate { it.packageName to it.label }

        val keyword = query.trim()

        val filtered = if (keyword.isEmpty()) {
            rules
        } else {
            rules.filter { rule ->
                rule.name.contains(keyword, ignoreCase = true) ||
                    rule.packageName.contains(keyword, ignoreCase = true) ||
                    rule.targetValue.contains(keyword, ignoreCase = true) ||
                    (labelByPackage[rule.packageName]?.contains(keyword, ignoreCase = true) == true)
            }
        }

        val groups = filtered
            .groupBy { it.packageName }
            .map { (packageName, groupRules) ->
                RuleGroup(
                    packageName = packageName,
                    appLabel = labelByPackage[packageName] ?: packageName,
                    managed = packageName in managedPackages,
                    rules = groupRules
                        .sortedWith(
                            // 内置优先（用户需要看到"系统已覆盖哪些"），
                            // 再按优先级降序，与引擎的尝试顺序一致
                            compareByDescending<SkipRule> { it.source == SkipRuleSource.BUILTIN }
                                .thenByDescending { it.priority }
                                .thenBy { it.id },
                        )
                        .map(SkipRule::toItem),
                )
            }
            .sortedBy { it.appLabel }

        return RulesUiState(
            query = query,
            groups = groups,
            // 摘要始终反映全量，不随搜索变化 —— 否则用户会以为规则被删了
            totalRules = rules.size,
            totalApps = rules.mapTo(HashSet()) { it.packageName }.size,
            isLoading = false,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * 领域模型 → 展示模型。
 *
 * 声明为**文件级私有扩展函数**（而非 ViewModel 的成员扩展）：
 * Kotlin 不允许通过 `SkipRule::toItem` 这样的可调用引用指向
 * "既是成员又是扩展"的函数，而 `map(SkipRule::toItem)` 正是这里想要的写法。
 */
private fun SkipRule.toItem(): RuleItem = RuleItem(
    id = id,
    name = name,
    activityName = activityName,
    targetType = targetType,
    targetValue = targetValue,
    matchMode = matchMode,
    priority = priority,
    enabled = enabled,
    source = source,
)
