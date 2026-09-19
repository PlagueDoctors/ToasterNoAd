package com.toaster.noad.feature.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.core.data.repository.RuleRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.model.GLOBAL_RULE_PACKAGE
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.SkipRuleSource
import com.toaster.noad.core.model.TargetType
import com.toaster.noad.core.service.event.SkipDiagnostics
import com.toaster.noad.core.service.event.SkipDiagnosticsState
import com.toaster.noad.core.service.event.SkipReason
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
 * @param globalRules 通用规则条数（伪包名 `"*"`，作用于任意已纳管应用）
 * @param diagnostics S1 实时诊断（绕开被 ROM 抑制的 logcat）
 */
data class RulesUiState(
    val query: String = "",
    val groups: List<RuleGroup> = emptyList(),
    val totalRules: Int = 0,
    val totalApps: Int = 0,
    val globalRules: Int = 0,
    val diagnostics: DiagnosticsItem = DiagnosticsItem.EMPTY,
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
 * 诊断信息的展示模型。
 *
 * ## 为什么必须有这个视图
 *
 * 实测设备的 ROM 把 `log.tag.NoAdAccessibility` 设为 Silent，
 * 应用日志完全不可见。用户报告"没效果"时，若只能回答
 * 「请连 adb 看日志」，排障就卡死了。
 *
 * 因此把引擎的判定结果直接呈现在规则页 —— 用户自己就能看出卡在哪一步。
 */
data class DiagnosticsItem(
    /** 事件最终归属的应用包名 */
    val packageName: String?,
    /** 引擎给出的结论（中文，直接可读） */
    val summary: String,
    /** 该应用当时可用的规则条数。为 0 是"规则没加载"的强信号 */
    val ruleCount: Int,
    /** 后续该做什么（仅在能给出明确动作时非空） */
    val advice: String?,
    /** 是否已经收到过任何事件 */
    val hasReceivedEvent: Boolean,
    /** 本次会话累计事件数 / 点击数 */
    val totalEvents: Int,
    val totalClicks: Int,
    /** 最近一次事件占用主线程的毫秒数 */
    val lastCostMs: Long = 0L,
    /** 本次会话单次事件处理的最大耗时（毫秒） */
    val maxCostMs: Long = 0L,
) {
    /**
     * 是否存在可感知的主线程卡顿。
     *
     * 判据用 `maxCostMs` 而非 `lastCostMs`：偶发尖峰同样会让用户
     * 感到"有时卡一下"，只看最近一次会漏掉。
     */
    val isSlow: Boolean get() = maxCostMs > SkipDiagnosticsState.FRAME_BUDGET_MS

    companion object {
        val EMPTY = DiagnosticsItem(
            packageName = null,
            summary = "尚未收到任何界面事件。请确认无障碍服务已授权，然后打开任意应用试试。",
            ruleCount = 0,
            advice = null,
            hasReceivedEvent = false,
            totalEvents = 0,
            totalClicks = 0,
        )
    }
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
            matchMode == MatchMode.PREFIX -> "前缀"
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
        SkipDiagnostics.state,
    ) { rules, targetApps, query, diagnostics ->
        buildState(rules, targetApps, query, diagnostics)
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
        diagnostics: SkipDiagnosticsState,
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
                    appLabel = if (packageName == GLOBAL_RULE_PACKAGE) {
                        GLOBAL_GROUP_LABEL
                    } else {
                        labelByPackage[packageName] ?: packageName
                    },
                    // 通用规则组不参与"是否纳管"的标注：
                    // 它作用于任意已纳管应用，本身不是一个应用。
                    managed = packageName == GLOBAL_RULE_PACKAGE ||
                        packageName in managedPackages,
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
            // 通用规则组固定置顶：它决定了所有应用的兜底能力，
            // 排在应用组之间会让人以为它只对某个应用生效
            .sortedWith(
                compareByDescending<RuleGroup> { it.packageName == GLOBAL_RULE_PACKAGE }
                    .thenBy { it.appLabel },
            )

        return RulesUiState(
            query = query,
            groups = groups,
            // 摘要始终反映全量，不随搜索变化 —— 否则用户会以为规则被删了。
            // 通用规则计入 totalRules 但不计入 totalApps（它不是"一个应用"）。
            totalRules = rules.size,
            totalApps = rules.asSequence()
                .map { it.packageName }
                .filter { it != GLOBAL_RULE_PACKAGE }
                .distinct()
                .count(),
            globalRules = rules.count { it.packageName == GLOBAL_RULE_PACKAGE },
            diagnostics = diagnostics.toItem(labelByPackage),
            isLoading = false,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** 通用规则组在列表中的显示名 */
        const val GLOBAL_GROUP_LABEL = "通用规则（适用于所有已纳管应用）"
    }
}

/**
 * 诊断状态 → 展示模型。
 *
 * 把「引擎结论 → 用户可读文案 + 可执行动作」的翻译集中在这里，
 * 避免 Composable 里堆 when 分支。
 */
private fun SkipDiagnosticsState.toItem(labelByPackage: Map<String, String>): DiagnosticsItem {
    if (!hasReceivedEvent) return DiagnosticsItem.EMPTY

    val appName = lastPackageName?.let { labelByPackage[it] ?: it }

    val (summary, advice) = when {
        // 总开关未开 / 服务状态类描述
        lastReason == null && lastOutcome != null -> lastOutcome to null

        lastReason == SkipReason.APP_NOT_MANAGED ->
            "最近事件来自「${appName ?: "未知应用"}」，但该应用**未被纳管**，事件被丢弃。" to
                "去「应用管理」页勾选该应用"

        lastReason == SkipReason.NO_RULE_FOR_PACKAGE ->
            "最近事件来自「${appName ?: "未知应用"}」，该应用已纳管但**没有任何可用规则**。" to
                "该应用不在内置规则覆盖范围内，可在本页为其新增规则"

        lastReason == SkipReason.ACTIVITY_MISMATCH ->
            "最近事件来自「${appName ?: "未知应用"}」，但规则的界面限定与当前界面不匹配。" to
                "检查相关规则的 activity 限定是否过窄"

        lastReason == SkipReason.NO_NODE_MATCH ->
            "最近事件来自「${appName ?: "未知应用"}」（可用规则 $availableRuleCount 条），" +
                "遍历了界面但**没有任何节点命中规则**。" to
                "该应用的跳过按钮可能已改版，需要更新规则定位值"

        lastReason == SkipReason.PRE_FILTERED ->
            "最近事件来自「${appName ?: "未知应用"}」，事件自带的信息不可能匹配任何规则，" +
                "已**在遍历界面前丢弃**。" to null

        lastReason == SkipReason.NO_ROOT_NODE || lastReason == SkipReason.EMPTY_NODE_TREE ->
            "最近事件来自「${appName ?: "未知应用"}」，但**读不到界面节点树**。" to
                "可能是系统权限限制或界面正在切换，可稍后重试"

        lastReason == SkipReason.IRRELEVANT_EVENT ->
            "最近事件来自「${appName ?: "未知应用"}」，事件类型与拦截无关，正常跳过。" to null

        lastReason == SkipReason.UNKNOWN_PACKAGE ->
            "最近事件没有携带包名，无法判断归属。" to null

        else -> (lastOutcome ?: "已收到事件，等待下一次判定") to null
    }

    return DiagnosticsItem(
        packageName = lastPackageName,
        summary = summary,
        ruleCount = availableRuleCount,
        advice = advice,
        hasReceivedEvent = true,
        totalEvents = totalEvents,
        totalClicks = totalClicks,
        lastCostMs = lastCostMs,
        maxCostMs = maxCostMs,
    )
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
