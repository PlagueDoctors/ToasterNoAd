package com.toaster.noad.core.service.event

import android.os.SystemClock
import android.util.Log
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
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetType
import com.toaster.noad.core.repository.S1RuleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

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

    /**
     * 事件被[廉价预筛][EventProcessor.eventMayMatch]丢弃。
     *
     * ## 与 [NO_NODE_MATCH] 的区别（这是本项存在的全部意义）
     *
     * 两者现象都是"没点"，但成本与含义完全不同：
     *
     * - [PRE_FILTERED]：**没有遍历节点树**就丢了。事件自带的文本
     *   不可能匹配任何规则。这是绝大多数事件的正常归宿，
     *   属于**优化命中**，用户不需要做任何事。
     * - [NO_NODE_MATCH]：**遍历了整棵节点树**仍没找到。
     *   这意味着规则真的过期了，需要更新定位值。
     *
     * 合并成一个值会让排障者误以为"规则失效"，
     * 从而去改本来正确的规则。必须分开。
     */
    PRE_FILTERED("事件预筛未通过"),

    /** 拿不到界面根节点（权限不足或界面正在切换） */
    NO_ROOT_NODE("取不到界面节点"),

    /** 节点树为空或全部为跨应用节点 */
    EMPTY_NODE_TREE("节点树为空"),

    /** 遍历到节点了，但没有一个符合规则条件 */
    NO_NODE_MATCH("未命中任何节点"),
}

/**
 * 一次事件处理的结果，供日志与调试使用。
 *
 * ## 为什么每个分支都要带包名与规则数
 *
 * 排障时要回答两个问题：「是哪个应用的事件」和「当时它有几条可用规则」。
 * 如果靠调用方自己去查，在多个 return 路径上很容易漏；
 * 把这两个字段编入结果类型，可以让 [SkipDiagnostics] 在任何路径下
 * 都能拿到完整信息。
 */
sealed interface ProcessOutcome {

    /** 携带包名与规则数，供诊断记录使用 */
    interface WithPackage {
        val packageName: String?
    }

    /** 额外携带"当时可用规则数"（仅规则已查出的路径有意义） */
    interface WithRuleCount {
        val ruleCount: Int
    }

    /**
     * 事件与本次保护无关，被快速丢弃。
     *
     * 用 [SkipReason] 而非单一无参对象，见其文档说明的排障理由。
     */
    data class Ignored(
        val reason: SkipReason,
        override val packageName: String? = null,
        override val ruleCount: Int = 0,
    ) : ProcessOutcome, WithPackage, WithRuleCount

    /** 命中规则但被防误点闸门拦下 */
    data class Throttled(
        val ruleName: String,
        override val packageName: String? = null,
        override val ruleCount: Int = 0,
    ) : ProcessOutcome, WithPackage, WithRuleCount

    /**
     * 已命中规则、点击已投递到后台线程。
     *
     * ## 为什么不能直接复用 [Clicked]
     *
     * [Clicked] 表示"点击已经执行且有结果"，是**终态**；
     * 本类型表示"点击已排队但尚未执行"，是**中间态**。
     *
     * 二者合并会让诊断显示"已点击"，而实际上点击可能还没执行、
     * 甚至执行失败 —— 那是比"没显示"更糟的虚假成功。
     * 拦截日志由异步任务在真正成功后写入，统计口径以日志为准。
     */
    data class ClickScheduled(
        val match: MatchResult,
        override val packageName: String? = null,
        override val ruleCount: Int = 0,
    ) : ProcessOutcome, WithPackage, WithRuleCount

    /**
     * 已命中规则，但**没有可用于投递点击的作用域**，因此未执行。
     *
     * 显式区分而不是静默不点：缺少执行环境属于接线错误，
     * 若表现为"什么都没发生"，会与"规则没命中"混在一起无法归因。
     */
    data class Deferred(
        val ruleName: String,
        override val packageName: String? = null,
        override val ruleCount: Int = 0,
    ) : ProcessOutcome, WithPackage, WithRuleCount

    /** 命中并成功派发点击 */
    data class Clicked(
        val match: MatchResult,
        val method: ClickMethod,
        override val packageName: String? = null,
        override val ruleCount: Int = 0,
    ) : ProcessOutcome, WithPackage, WithRuleCount

    /** 命中但点击失败 */
    data class ClickFailed(
        val match: MatchResult,
        val reason: String,
        override val packageName: String? = null,
        override val ruleCount: Int = 0,
    ) : ProcessOutcome, WithPackage, WithRuleCount
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
    /**
     * 提供"当下有效"的界面根节点。
     *
     * 点击被投递到后台线程执行，因此执行时必须**重新取一次**根节点 ——
     * 同步阶段拿到的节点在等待调度期间可能已失效。
     *
     * 声明为 `() -> AccessibilityNodeInfo?` 而非传值：无障碍 API
     * （`rootInActiveWindow`）可在任意线程调用，届时再取即可。
     */
    private val rootProvider: () -> AccessibilityNodeInfo? = { null },
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

    /** 慢事件累计数，供诊断展示；仅由主线程写入，无需同步 */
    private var slowEventCount = 0

    /**
     * 用于点击任务的默认调度器。
     *
     * 从 [scope] 取而非新建：作用域的生命周期（进程级）才是正确的，
     * 点击任务必须能活过单次事件回调。
     */
    private val handoffContext: CoroutineContext get() = scope.coroutineContext

    /**
     * 处理一个无障碍事件（**主线程必须尽快返回**）。
     *
     * ## ⚠️ 不在本方法内执行点击
     *
     * 本方法由 `onAccessibilityEvent` 同步调用，运行在**主线程**。
     * 而 [`ClickExecutor.execute`] 内部会：
     *
     * 1. `findByIndex` —— 从头 BFS 回查真实节点（数百次 `getChild` IPC）
     * 2. `ACTION_CLICK` / `getParent` 链 —— 又是若干次 IPC
     * 3. 失败时 `dispatchGesture` —— 手势需要**约 40ms 播放时间**
     *
     * 这些全部累加在主线程上。实测用户报告的"启动任何应用都有半秒延迟"
     * 正是由此而来：`onAccessibilityEvent` 占用主线程期间，
     * 系统无法把输入与绘制交给刚启动的前台应用。
     *
     * 因此本方法只做"判定 + 投递"，点击交给 [handoffScope] 上的异步任务。
     *
     * @param handoffScope 点击任务的作用域。为 null 时**不执行点击**而是
     *        返回 [ProcessOutcome.Deferred] —— 让"缺少执行环境"表现为
     *        可观测的显式结果，而不是静默不点。
     */
    fun process(
        event: AccessibilityEvent,
        rootProvider: () -> AccessibilityNodeInfo?,
        handoffScope: CoroutineScope? = null,
    ): ProcessOutcome {
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = processInternal(event, rootProvider, handoffScope)
        val costMs = SystemClock.elapsedRealtime() - startedAt

        // 慢事件计数在引擎侧维护：诊断对象只做无状态存取，
        // 避免"阈值判断"这种策略散落到展示层。
        if (costMs >= SkipDiagnosticsState.SLOW_EVENT_THRESHOLD_MS) {
            slowEventCount++
        }

        // 记录诊断供 UI 展示。放在这里而非各 return 点，
        // 是为了保证**任何**返回路径都被覆盖 —— 漏记一条就等于
        // 用户看到"没反应"却查不出原因。
        SkipDiagnostics.record(
            packageName = outcome.packageNameOf(event),
            eventType = event.eventType,
            outcome = outcome,
            ruleCount = outcome.ruleCountOf(),
            costMs = costMs,
            slowEventCount = slowEventCount,
        )
        return outcome
    }

    /** 从事件取包名，供诊断记录使用（与 [processInternal] 的判定口径一致） */
    private fun ProcessOutcome.packageNameOf(event: AccessibilityEvent): String? =
        (this as? ProcessOutcome.WithPackage)?.packageName ?: event.packageName?.toString()

    private fun ProcessOutcome.ruleCountOf(): Int = when (this) {
        is ProcessOutcome.WithRuleCount -> ruleCount
        else -> 0
    }

    private fun processInternal(
        event: AccessibilityEvent,
        rootProvider: () -> AccessibilityNodeInfo?,
        handoffScope: CoroutineScope?,
    ): ProcessOutcome {
        val packageName = event.packageName?.toString()
        if (packageName.isNullOrBlank()) {
            return ProcessOutcome.Ignored(SkipReason.UNKNOWN_PACKAGE)
        }

        // ---- 第 1 道闸：包名 ----
        // 绝大多数事件在这一步返回，这是性能的关键。
        // 注意这里区分了两种"没规则"：应用本身没被纳管，还是纳管了但没有规则。
        if (!ruleCache.isManaged(packageName)) {
            return ProcessOutcome.Ignored(SkipReason.APP_NOT_MANAGED, packageName)
        }
        val rules = ruleCache.rulesForPackage(packageName)
        if (rules.isEmpty()) {
            return ProcessOutcome.Ignored(SkipReason.NO_RULE_FOR_PACKAGE, packageName)
        }

        // ---- 第 2 道闸：事件类型 ----
        if (!isRelevantEventType(event.eventType)) {
            return ProcessOutcome.Ignored(SkipReason.IRRELEVANT_EVENT, packageName, rules.size)
        }

        val activityName = event.className?.toString()

        // ---- 第 2.5 道闸：廉价预筛（★ 消除"启动应用卡一下"的关键）----
        // 走到这里意味着应用已纳管且有规则，于是每次内容变化都要遍历整棵
        // 节点树 —— 一个 500 节点的界面会产生数百次 getChild IPC，
        // 单次轻松超过 100ms，叠加在一次启动过程中就是用户感知的延迟。
        //
        // 但事件本身通常**已经携带了目标文本**（跳过按钮的
        // TYPE_WINDOW_CONTENT_CHANGED 事件 text 即「跳过 1」）。
        // 先用这份廉价信息判断"这次事件有没有可能命中"，
        // 不做任何 IPC 就能丢弃绝大多数无关事件。
        if (!eventMayMatch(rules, event, activityName)) {
            return ProcessOutcome.Ignored(SkipReason.PRE_FILTERED, packageName, rules.size)
        }

        // ---- 第 3 道闸：Activity 限定 ----
        val applicable = filterByActivity(rules, activityName)
        if (applicable.isEmpty()) {
            return ProcessOutcome.Ignored(SkipReason.ACTIVITY_MISMATCH, packageName, rules.size)
        }

        // ---- 遍历节点树 ----
        val root = rootProvider()
            ?: return ProcessOutcome.Ignored(SkipReason.NO_ROOT_NODE, packageName, rules.size)
        val nodes: List<NodeSnapshot> = try {
            UiTreeScanner.scan(root, packageName)
        } finally {
            // 调用方（服务）分配的 root 由本处负责回收
            NodeRecycler.recycle(root)
        }

        if (nodes.isEmpty()) {
            return ProcessOutcome.Ignored(SkipReason.EMPTY_NODE_TREE, packageName, rules.size)
        }

        // ---- 匹配 ----
        val best = matcher.matchBest(applicable, nodes)
            ?: return ProcessOutcome.Ignored(SkipReason.NO_NODE_MATCH, packageName, rules.size)

        // ---- 第 4 道闸：防误点 ----
        val ruleId = best.rule.id.takeIf { it != 0L } ?: best.rule.name.hashCode().toLong()
        val nodeKey = AntiMisclickGate.nodeKey(best.node)
        val now = System.currentTimeMillis()
        if (!gate.tryAcquire(ruleId, nodeKey, now)) {
            return ProcessOutcome.Throttled(best.rule.name, packageName, rules.size)
        }

        // ---- 投递点击（★ 绝不在主线程执行）----
        // 见 process 的文档：点击包含二次 BFS + 多次 IPC + 40ms 手势播放，
        // 全部累加在主线程上就是用户感知的启动延迟。
        val scope = handoffScope
            ?: return ProcessOutcome.Deferred(best.rule.name, packageName, rules.size)

        scope.launch(handoffContext) {
            performClick(best, packageName, activityName, rules.size)
        }

        return ProcessOutcome.ClickScheduled(best, packageName, rules.size)
    }

    /**
     * 在后台线程执行点击，并在落地后补发拦截日志。
     *
     * ## 为什么整个方法都要脱离主线程
     *
     * `AccessibilityNodeInfo` 的操作（`performAction` / `getChild`）
     * 本身是跨进程调用，**不受"必须在主线程"约束** ——
     * 无障碍服务只要求"调用发生在服务存活期间"。
     *
     * 因此把整段放到 [Dispatchers.Default] 执行：
     * 二次 BFS 与 IPC 全部脱离主线程，主线程在匹配完成的那一刻就释放。
     *
     * ## 失败不回写诊断
     *
     * 本方法异步执行，其失败原因无法回填到"本次事件"的诊断记录上
     * （会被后续事件覆盖，造成错乱）。因此失败只写日志，
     * 由 [LogRepository] 侧如实记录。
     */
    private suspend fun performClick(
        match: MatchResult,
        packageName: String,
        activityName: String?,
        ruleCount: Int,
    ) {
        val result = withContext(Dispatchers.Default) {
            // 重新取根节点：同步阶段拿到的那个在等待调度期间可能已失效
            val gestureRoot = runCatching { rootProvider() }.getOrNull()
            try {
                clickExecutor.execute(gestureRoot, match)
            } finally {
                NodeRecycler.recycle(gestureRoot)
            }
        }

        when (result) {
            is ClickOutcome.Success -> emitLog(match, packageName, activityName, result.method)
            is ClickOutcome.Failed -> debugLog("点击失败: ${result.reason}（$packageName）")
        }
    }

    /**
     * 廉价预筛：判断事件本身是否**有可能**命中任一规则。
     *
     * 判据集中在 [EventPreFilter]（无 Android 依赖，可在 JVM 上测试）。
     * 这里只负责把 `AccessibilityEvent` 翻译成它需要的入参。
     *
     * ## 为什么这一层能大幅降低卡顿
     *
     * 应用启动时会连续产生数十次 `TYPE_WINDOW_CONTENT_CHANGED`。
     * 修改前每一次都要遍历整棵节点树（`rootProvider()` + `scan()`），
     * 而其中绝大多数与"跳过按钮出现"无关。一个 500 节点的界面
     * 单次遍历就是数百次 `getChild` IPC，轻松超过 100ms ——
     * 这些耗时全部累加在主线程上，用户感知即"启动应用卡一下"。
     *
     * ⚠️ **只放宽、不收紧**：预筛一旦比 [UiMatcher] 严格，就会出现
     * "事件被预筛丢弃、永远走不到匹配"的静默漏拦，其现象与
     * "规则写错"完全一致。判据与理由详见 [EventPreFilter]。
     */
    private fun eventMayMatch(
        rules: List<SkipRule>,
        event: AccessibilityEvent,
        activityName: String?,
    ): Boolean = EventPreFilter.mayMatch(
        rules = rules,
        candidates = eventCandidates(event),
        activityName = activityName,
    )

    /**
     * 读取事件自带的文本候选（不产生任何 IPC）。
     *
     * ## 能读到什么、读不到什么
     *
     * `AccessibilityEvent` 携带的是**事件自身的描述**，不是节点属性：
     * - `event.text` → `List<CharSequence>`，内容变化事件常在此携带节点文本
     * - `event.contentDescription` → 单个描述
     *
     * **没有** viewId —— 那是 `AccessibilityNodeInfo` 才有的属性，
     * 必须遍历节点树才能取得。因此 `VIEW_ID` 型规则无法靠事件预筛，
     * 由 [EventPreFilter] 统一放行。
     */
    private fun eventCandidates(event: AccessibilityEvent): List<String> = buildList {
        event.text?.forEach { item ->
            item?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
        }
        event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
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

    private fun debugLog(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "NoAdEventProcessor"
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
