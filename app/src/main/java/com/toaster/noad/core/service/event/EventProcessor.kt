package com.toaster.noad.core.service.event

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.toaster.noad.core.engine.ui.AntiMisclickGate
import com.toaster.noad.core.engine.ui.ClickExecutor
import com.toaster.noad.core.engine.ui.ClickMethod
import com.toaster.noad.core.engine.ui.ClickOutcome
import com.toaster.noad.core.engine.ui.MatchResult
import com.toaster.noad.core.engine.ui.NodeRecycler
import com.toaster.noad.core.engine.ui.NodeSnapshot
import com.toaster.noad.core.engine.ui.UiMatcher
import com.toaster.noad.core.engine.ui.UiTreeScanner
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.repository.S1RuleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 事件未被处理的原因。
 *
 * ## 为什么需要区分「哪一步丢弃的」
 *
 * 这类原因曾经只有一个 `Ignored`。后果是线上出问题时**完全不可观测**：
 *
 * - 规则表为空 → 所有事件被丢弃
 * - 规则表正常但文案变了 → 所有事件走到匹配后落空，**同样表现为被丢弃**
 * - 应用没纳管 → 同样
 *
 * 三者现象完全相同（无拦截、无日志、统计恒 0），
 * 但修复动作完全不同（导入规则 / 更新规则 / 去应用管理页开启）。
 * 用一个笼统的 `Ignored` 掩盖它们，等于把排障成本转嫁给用户。
 *
 * 因此这里按**闸门位置**细分。每一项都对应一个明确、可执行的用户动作。
 */
enum class SkipReason(val label: String) {
    /** 事件没有包名，无法判断归属 */
    UNKNOWN_PACKAGE("事件无包名"),

    /**
     * 该应用未被纳管（不在 `target_app` 表中）。
     *
     * 对应的用户动作：去「应用管理」页添加该应用。
     */
    APP_NOT_MANAGED("应用未纳管"),

    /**
     * 应用已纳管，但没有匹配当前界面的规则。
     *
     * 这是**内置规则集不覆盖该应用**时的典型状态。
     * 与 [APP_NOT_MANAGED] 必须区分：后者是用户没开，前者是规则没有。
     */
    NO_RULE_FOR_PACKAGE("无可用规则"),

    /** Activity 限定不匹配（规则只对特定界面生效） */
    ACTIVITY_MISMATCH("界面不匹配"),

    /** 事件类型不在监听范围内（如用户点击、滚动） */
    IRRELEVANT_EVENT("事件类型无关"),

    /** 拿不到界面根节点（权限不足或界面正在切换） */
    NO_ROOT_NODE("取不到界面节点"),

    /** 节点树为空或全部为跨应用节点 */
    EMPTY_NODE_TREE("节点树为空"),

    /** 遍历到节点了，但没有一个符合规则条件 */
    NO_NODE_MATCH("未命中任何节点"),
}

/**
 * 一次事件处理的结果，供日志与调试使用。
 */
sealed interface ProcessOutcome {

    /**
     * 事件与本次保护无关，被快速丢弃。
     *
     * 用 [SkipReason] 而非单一无参对象，见其文档说明的排障理由。
     */
    data class Ignored(val reason: SkipReason) : ProcessOutcome

    /** 命中规则但被防误点闸门拦下 */
    data class Throttled(val ruleName: String) : ProcessOutcome

    /** 命中并成功派发点击 */
    data class Clicked(
        val match: MatchResult,
        val method: ClickMethod,
    ) : ProcessOutcome

    /** 命中但点击失败 */
    data class ClickFailed(val match: MatchResult, val reason: String) : ProcessOutcome
}

/**
 * S1 事件处理器。
 *
 * ## 执行位置与性能约束
 *
 * `onAccessibilityEvent` 运行在**主线程**，且调用极其频繁
 * （一次界面变化可产生数次回调）。因此本处理器的原则是：
 *
 * 1. **最快失败**：包名不在纳管集合内立刻返回，不遍历节点树
 * 2. **不查数据库**：规则查询走内存缓存（[S1RuleCache]）
 * 3. **不写数据库**：命中后只派发点击并投递日志到协程，写库在 IO 线程
 * 4. **不过度匹配**：一次事件最多点击一个节点
 *
 * ## 线程模型
 *
 * - 匹配与点击：主线程（无障碍 API 要求）
 * - 日志写库：IO 线程（通过 [scope]）
 */
class EventProcessor(
    private val ruleCache: S1RuleCache,
    private val clickExecutor: ClickExecutor,
    private val scope: CoroutineScope,
    private val matcher: UiMatcher = UiMatcher(),
    private val gate: AntiMisclickGate = AntiMisclickGate(),
    /**
     * 拦截回调。
     *
     * 声明为 `suspend` 而非普通 lambda：回调内部要写数据库，
     * 而写库本身是挂起操作。若声明成普通函数，实现方就不得不
     * 在内部使用 `runBlocking` —— 那会把 IO 阻塞带进调用链，
     * 一旦调用方忘记切线程就会卡主线程。
     *
     * 由本类负责在 [scope] 中启动协程调用它，调用方无需关心调度。
     */
    private val onIntercepted: suspend (InterceptRecord) -> Unit = {},
) {

    /**
     * 处理一个无障碍事件。
     *
     * @param event 原始事件
     * @param rootProvider 提供当前界面根节点。
     *        用 lambda 而非直接传节点，是因为**只有确实需要遍历时才取根节点** ——
     *        取根节点本身有一定开销，而绝大多数事件在包名过滤阶段就被丢弃了。
     */
    fun process(
        event: AccessibilityEvent,
        rootProvider: () -> AccessibilityNodeInfo?,
    ): ProcessOutcome {
        val packageName = event.packageName?.toString()
        if (packageName.isNullOrBlank()) {
            return ProcessOutcome.Ignored(SkipReason.UNKNOWN_PACKAGE)
        }

        // ---- 第 1 道闸：包名 ----
        // 绝大多数事件在这一步返回，这是性能的关键。
        // 注意这里区分了两种"没规则"：应用本身没被纳管，还是纳管了但没有规则。
        if (!ruleCache.isManaged(packageName)) {
            return ProcessOutcome.Ignored(SkipReason.APP_NOT_MANAGED)
        }
        val rules = ruleCache.rulesForPackage(packageName)
        if (rules.isEmpty()) {
            return ProcessOutcome.Ignored(SkipReason.NO_RULE_FOR_PACKAGE)
        }

        // ---- 第 2 道闸：事件类型 ----
        if (!isRelevantEventType(event.eventType)) {
            return ProcessOutcome.Ignored(SkipReason.IRRELEVANT_EVENT)
        }

        // ---- 第 3 道闸：Activity 限定 ----
        val activityName = event.className?.toString()
        val applicable = filterByActivity(rules, activityName)
        if (applicable.isEmpty()) {
            return ProcessOutcome.Ignored(SkipReason.ACTIVITY_MISMATCH)
        }

        // ---- 遍历节点树 ----
        val root = rootProvider() ?: return ProcessOutcome.Ignored(SkipReason.NO_ROOT_NODE)
        val nodes: List<NodeSnapshot> = try {
            UiTreeScanner.scan(root, packageName)
        } finally {
            // 调用方（服务）分配的 root 由本处负责回收
            NodeRecycler.recycle(root)
        }

        if (nodes.isEmpty()) return ProcessOutcome.Ignored(SkipReason.EMPTY_NODE_TREE)

        // ---- 匹配 ----
        val best = matcher.matchBest(applicable, nodes)
            ?: return ProcessOutcome.Ignored(SkipReason.NO_NODE_MATCH)

        // ---- 第 4 道闸：防误点 ----
        val ruleId = best.rule.id.takeIf { it != 0L } ?: best.rule.name.hashCode().toLong()
        val nodeKey = AntiMisclickGate.nodeKey(best.node)
        val now = System.currentTimeMillis()
        if (!gate.tryAcquire(ruleId, nodeKey, now)) {
            return ProcessOutcome.Throttled(best.rule.name)
        }

        // ---- 执行点击 ----
        // 注意：此处重新取根节点。`root` 已在上面回收，
        // 且点击需要"当下有效"的节点引用（快照可能已过期）。
        val gestureRoot = rootProvider()
        val outcome = try {
            clickExecutor.execute(gestureRoot, best)
        } finally {
            NodeRecycler.recycle(gestureRoot)
        }

        return when (outcome) {
            is ClickOutcome.Success -> {
                emitLog(best, packageName, activityName, outcome.method)
                ProcessOutcome.Clicked(best, outcome.method)
            }
            is ClickOutcome.Failed -> {
                ProcessOutcome.ClickFailed(best, outcome.reason)
            }
        }
    }

    /**
     * 界面发生窗口切换时重置防误点记账。
     *
     * 不重置会导致"上一个界面的冷却时间"影响新界面 ——
     * 用户从应用 A 切到应用 B，B 的开屏广告因 A 的残留冷却而点不掉。
     * 这类问题在实机调试中表现为"偶发失效"，极难定位，因此必须主动清理。
     */
    fun onWindowChanged() {
        gate.reset()
    }

    /**
     * 事件类型白名单。
     *
     * 刻意**不**监听 `TYPE_VIEW_CLICKED` 等交互事件：
     * 那些事件由用户操作产生，监听它们既无必要（广告出现不依赖用户点击），
     * 又会在用户正常使用应用时产生大量无效回调。
     */
    private fun isRelevantEventType(eventType: Int): Boolean =
        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

    /**
     * 按 Activity 过滤规则。
     *
     * 规则的 `activityName` 为空表示"该应用内所有界面均可触发"。
     * 注意部分 ROM 的 `event.className` 可能是简名或带前缀，
     * 因此采用「完整相等 或 简名相等」判定，避免因厂商差异导致规则全失效。
     */
    private fun filterByActivity(rules: List<SkipRule>, activityName: String?): List<SkipRule> =
        rules.filter { rule ->
            val expected = rule.activityName
            if (expected.isNullOrBlank()) return@filter true
            if (activityName.isNullOrBlank()) return@filter false

            val simpleActual = activityName.substringAfterLast('.')
            val simpleExpected = expected.substringAfterLast('.')

            activityName == expected || simpleActual == simpleExpected
        }

    /** 投递拦截日志到 IO 线程，不阻塞事件回调 */
    private fun emitLog(
        match: MatchResult,
        packageName: String,
        activityName: String?,
        method: ClickMethod,
    ) {
        val record = InterceptRecord(
            packageName = packageName,
            ruleName = match.rule.name,
            ruleDetail = match.node.describe(),
            activityName = activityName,
            method = method,
            confidence = match.confidence,
        )
        // 在 IO 作用域启动协程：主线程只做一次协程创建，不做任何 IO
        scope.launch { onIntercepted(record) }
    }
}

/**
 * 一次成功拦截的原始记录。
 *
 * 与 [com.toaster.noad.core.model.InterceptLog] 的区别：
 * 后者是**持久化模型**（字段精简、无调试信息），
 * 本类携带调试字段（点击方式、匹配置信度、命中节点描述），
 * 由 Service 侧决定写库与日志输出。
 */
data class InterceptRecord(
    val packageName: String,
    val ruleName: String,
    val ruleDetail: String,
    val activityName: String?,
    val method: ClickMethod,
    val confidence: Int,
)
