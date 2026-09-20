package com.toaster.noad.core.service.event

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.toaster.noad.core.engine.ui.AntiMisclickGate
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
     * 已通过全部廉价闸门，扫描任务已投递到后台（性能重构新增）。
     *
     * ## 为什么必须有这个中间态
     *
     * 重构后主线程只做零 IPC 的闸门并投递请求，真正的扫描/匹配/点击
     * 都在后台单消费者中完成。若投递后返回 [Ignored]，诊断会显示
     * "被丢弃"；若返回 [ClickScheduled] 则更是撒谎 —— 此时**还没有
     * 扫描过任何节点**。
     *
     * 因此本类型表达：「闸门已过、结果未定、已排队」。
     * 后台完成后会以真实结果覆盖诊断（见 [SkipDiagnostics.record]）。
     */
    data class Queued(
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
 * ## 执行位置与性能约束（★ 性能重构的核心）
 *
 * `onAccessibilityEvent` 运行在**主线程**，一次应用启动会连续产生
 * 数十次 `TYPE_WINDOW_CONTENT_CHANGED`。重构前的处理链在主线程上
 * 完成了**节点树遍历**：一次扫描按节点逐个读取 12 个字段
 * （viewId/text/className/clickable/visible/bounds…），**每个字段都是
 * 一次跨进程 IPC** —— 一个 500 节点的界面就是数千次 IPC、数百毫秒。
 * 数十个事件排队等待，用户感知就是「启动后要等两秒才跳过广告」。
 *
 * 重构后的两段式流水线：
 *
 * ```
 * 主线程（零 IPC，必须尽快返回）
 *   闸门1 包名/纳管/规则数（内存哈希查表）
 *   → 闸门2 事件类型
 *   → 闸门2.5 廉价预筛（事件自带文本，纯字符串）
 *   → 提取纯数据 ScanRequest，投递到 CONFLATED 队列
 *   → return Queued
 *
 * 后台单消费者（扫描的重活全在这里）
 *   取最新请求（连续事件自动合并）→ 窗口重置（如有）→ 闸门3 Activity
 *   → 扫描节点树 → 匹配 → 防误点闸门 → 投递点击（独立协程）→ 记录诊断
 * ```
 *
 * ## 为什么「合并」是等价而非降级
 *
 * 扫描的对象是**当前界面快照**（`rootInActiveWindow`），不是事件对象。
 * 因此连续 N 个事件所对应的最后一次扫描**包含前 N-1 次能看到的一切**。
 * 合并只是免除了对同一界面状态的重复扫描，**不丢任何可观测信息**。
 * 这是把「启动期数十次全树扫描」压成「1~2 次」的关键。
 *
 * ## 线程契约
 *
 * - [process]：主线程；只读内存 + 纯字符串运算 + 一次 `trySend`
 * - 消费者协程：构造时传入的 [scope]（生产为 IO 调度器），串行处理
 * - 点击执行：消费者内再 `launch` 一个任务（手势 40ms 播放不阻塞后续扫描）
 * - [AntiMisclickGate] 为**非线程安全**的普通 Map：只被消费者线程访问；
 *   [onWindowChanged] 不直接 `reset()` 而是置原子标记，消费者读取后
 *   在本线程执行 reset，消除跨线程风险。
 */
class EventProcessor(
    private val ruleCache: S1RuleCache,
    /**
     * 点击执行函数。
     *
     * 注入函数而非 `ClickExecutor` 实例：后者构造需要
     * `AccessibilityService`，会让整个事件流水线（合并、投递、
     * 闸门顺序、线程契约）永远无法在 JVM 上被验证。
     */
    private val clickAction: suspend (AccessibilityNodeInfo?, MatchResult) -> ClickOutcome,
    private val scope: CoroutineScope,
    /**
     * 提供"当下有效"的界面根节点。
     *
     * **可在任意线程调用**：无障碍 API 只要求"调用发生在服务存活期间"。
     * 生产实现为 `{ rootInActiveWindow }`；消费者线程调用。
     */
    private val rootProvider: () -> AccessibilityNodeInfo? = { null },
    private val matcher: UiMatcher = UiMatcher(),
    private val gate: AntiMisclickGate = AntiMisclickGate(),
    /**
     * 单调时钟（毫秒）。
     *
     * 注入而非直接调用 `SystemClock.elapsedRealtime()`：
     * 后者是 Android API，在 JVM 单测中会抛 "not mocked" ——
     * 而它位于消费者线程的第一行，异常会被 `runCatching` 吞掉，
     * 表现为「后台扫描从未发生」的诡异现象（本轮实测踩到）。
     * 注入后整个事件流水线在 JVM 上完全可测（项目既有时钟注入惯例）。
     */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * 日志出口（注入以便单测静音；生产默认走 logcat）。
     */
    private val log: (String) -> Unit = { Log.d(TAG, it) },
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
     * 慢事件累计数（后台处理耗时 ≥ 阈值）。
     * 消费者线程写、任意线程读展示，因此用原子类型。
     */
    private val slowEventCount = AtomicInteger(0)

    /**
     * 待处理请求槽位：**最新覆盖**（合并语义见 [LatestRequestQueue]）。
     *
     * 用「只保留最新」替代 FIFO 队列 —— 扫描对象是当前界面快照，
     * 连续 N 个请求中只有最后一个是「信息最全」的。
     */
    private val pendingRequests = LatestRequestQueue<ScanRequest>()

    /** 消费者是否正在运行（保证同一时刻只有一个 drain 循环，即串行消费） */
    private val workerActive = AtomicBoolean(false)

    /**
     * 窗口切换重置标记。
     *
     * 不能用请求字段携带：连续的窗口切换请求可能被合并覆盖，
     * 而**任何一次**窗口切换都必须执行重置，否则会残留上一个界面的
     * 防误点冷却（历史 bug：新界面的广告点不掉）。
     * 因此用原子标记累积（OR 语义），由消费者读取并清除。
     */
    private val pendingWindowReset = AtomicBoolean(false)

    /** 一次待扫描请求（纯数据，主线程提取，不含任何 Android 引用） */
    private class ScanRequest(
        val packageName: String,
        val eventType: Int,
        val activityName: String?,
        val rules: List<SkipRule>,
    )

    /**
     * 调度一次后台处理。
     *
     * 「单消费者 + 最新覆盖」的实现：
     * - [pendingRequests] 覆盖式保存最新请求（合并）
     * - [workerActive] 保证同一时刻只有一个 drain 循环在跑（串行，
     *   这也让非线程安全的 [AntiMisclickGate] 无需加锁）
     * - drain 取空槽位后做一次「收尾重查」：清空与置位之间刚到达的
     *   新请求不会被漏掉
     *
     * ⚠️ **消费循环运行在 [scope] 上（生产为 IO 调度器）**，而不是在
     * 本方法（主线程）内 —— 这是「扫描脱离主线程」的落点。
     */
    private fun schedule(request: ScanRequest) {
        pendingRequests.offer(request)
        if (workerActive.compareAndSet(false, true)) {
            scope.launch { drain() }
        }
    }

    /** 后台单消费者：串行消化槽位中的最新请求，直到无待处理项 */
    private suspend fun drain() {
        try {
            while (true) {
                val request = pendingRequests.poll() ?: break
                runCatching { consume(request) }
                    .onFailure { log("后台处理异常: ${it.javaClass.simpleName}") }
            }
        } finally {
            workerActive.set(false)
            // 收尾重查：poll 返回 null 与置位清零之间到达的请求
            if (pendingRequests.hasPending && workerActive.compareAndSet(false, true)) {
                scope.launch { drain() }
            }
        }
    }

    /**
     * 处理一个无障碍事件（**主线程必须尽快返回**）。
     *
     * 本方法**不遍历节点树**（重构前的主要瓶颈）：只做零 IPC 的闸门
     * 判定，把「扫描请求」投递到后台队列后立即返回 [ProcessOutcome.Queued]。
     *
     * @param handoffScope 执行环境存在性标记。为 null 时返回
     *        [ProcessOutcome.Deferred]（接线错误显式化，见其文档）；
     *        非 null 时实际执行由消费者协程完成。
     */
    fun process(
        event: AccessibilityEvent,
        rootProvider: () -> AccessibilityNodeInfo?,
        handoffScope: CoroutineScope? = null,
    ): ProcessOutcome {
        val startedAt = clock()
        val outcome = submit(
            packageName = event.packageName?.toString(),
            eventType = event.eventType,
            activityName = event.className?.toString(),
            candidates = eventCandidates(event),
            hasHandoff = handoffScope != null,
        )
        val costMs = clock() - startedAt

        // 主线程记录的是「闸门耗时」——它应当恒为个位数毫秒。
        // 若这里持续出现几十毫秒，说明闸门层退化（如误加了 IPC）。
        SkipDiagnostics.record(
            packageName = outcome.packageNameOf(event),
            eventType = event.eventType,
            outcome = outcome,
            ruleCount = outcome.ruleCountOf(),
            costMs = costMs,
            slowEventCount = slowEventCount.get(),
        )
        return outcome
    }

    /** 从事件取包名，供诊断记录使用（与 [submit] 的判定口径一致） */
    private fun ProcessOutcome.packageNameOf(event: AccessibilityEvent): String? =
        (this as? ProcessOutcome.WithPackage)?.packageName ?: event.packageName?.toString()

    /** 取本次判定携带的规则数（未走到规则查询的路径为 0） */
    private fun ProcessOutcome.ruleCountOf(): Int = when (this) {
        is ProcessOutcome.WithRuleCount -> ruleCount
        else -> 0
    }

    /**
     * 纯数据闸门入口（**JVM 可测的核心**）。
     *
     * 与 [process] 的关系：后者只是把 `AccessibilityEvent` 的字段
     * 提取出来（`packageName` / `eventType` / `className` / 文本候选），
     * 真正的判定逻辑全部在本方法内 —— 因此整套闸门顺序、预筛语义、
     * 合并投递都能在 `EventProcessorTest` 中被直接验证，
     * 无需（也无法）在 JVM 上构造 Android 的 `AccessibilityEvent`。
     *
     * 【重要】从工程角度，把「可测内核」与「框架适配层」分开是特意为之：
     * 适配层只做字段提取（机械翻译，无逻辑），内核承载全部决策。
     */
    internal fun submit(
        packageName: String?,
        eventType: Int,
        activityName: String?,
        candidates: List<String>,
        hasHandoff: Boolean,
    ): ProcessOutcome {
        if (packageName.isNullOrBlank()) {
            return ProcessOutcome.Ignored(SkipReason.UNKNOWN_PACKAGE)
        }

        // ---- 第 1 道闸：包名 ----
        // 绝大多数事件在这一步返回（用户正常使用时事件几乎全部来自未纳管应用）。
        if (!ruleCache.isManaged(packageName)) {
            return ProcessOutcome.Ignored(SkipReason.APP_NOT_MANAGED, packageName)
        }
        val rules = ruleCache.rulesForPackage(packageName)
        if (rules.isEmpty()) {
            return ProcessOutcome.Ignored(SkipReason.NO_RULE_FOR_PACKAGE, packageName)
        }

        // ---- 第 2 道闸：事件类型 ----
        if (!isRelevantEventType(eventType)) {
            return ProcessOutcome.Ignored(SkipReason.IRRELEVANT_EVENT, packageName, rules.size)
        }

        // ---- 第 2.5 道闸：廉价预筛 ----
        // 纯字符串判定（事件自带文本），不做任何 IPC。
        // ⚠️ 只放宽、不收紧：判据见 [EventPreFilter]。
        if (!EventPreFilter.mayMatch(rules, candidates, activityName)) {
            return ProcessOutcome.Ignored(SkipReason.PRE_FILTERED, packageName, rules.size)
        }

        // ---- 执行环境检查（接线错误的显式化）----
        if (!hasHandoff) {
            return ProcessOutcome.Deferred(NO_HANDOFF_PLACEHOLDER, packageName, rules.size)
        }

        // ---- 投递后台（最新覆盖合并）----
        schedule(
            ScanRequest(
                packageName = packageName,
                eventType = eventType,
                activityName = activityName,
                rules = rules,
            ),
        )
        return ProcessOutcome.Queued(packageName, rules.size)
    }

    /**
     * 消费者：后台串行处理一次扫描请求。
     *
     * 串行（单消费者）的三个理由：
     * 1. [AntiMisclickGate] 是非线程安全的普通 Map，串行天然安全；
     * 2. 扫描本身是数百次 IPC，并发扫描只会互相争抢 binder 通道；
     * 3. 合并语义下队列长度恒为 0 或 1，串行不存在吞吐瓶颈。
     */
    private fun consume(request: ScanRequest) {
        val startedAt = clock()

        // 窗口切换重置：必须在本次扫描前完成
        if (pendingWindowReset.getAndSet(false)) {
            gate.reset()
        }

        val outcome = scanAndMatch(request)
        val costMs = clock() - startedAt

        if (costMs >= SkipDiagnosticsState.SLOW_EVENT_THRESHOLD_MS) {
            slowEventCount.incrementAndGet()
        }

        SkipDiagnostics.record(
            packageName = request.packageName,
            eventType = request.eventType,
            outcome = outcome,
            ruleCount = request.rules.size,
            costMs = costMs,
            slowEventCount = slowEventCount.get(),
        )
    }

    /** 扫描 + 匹配 + 闸门 + 投递点击（消费者线程） */
    private fun scanAndMatch(request: ScanRequest): ProcessOutcome {
        // ---- 第 3 道闸：Activity 限定 ----
        val applicable = filterByActivity(request.rules, request.activityName)
        if (applicable.isEmpty()) {
            return ProcessOutcome.Ignored(
                SkipReason.ACTIVITY_MISMATCH, request.packageName, request.rules.size,
            )
        }

        // ---- 遍历节点树（唯一昂贵步骤，已脱离主线程）----
        val root = rootProvider()
            ?: return ProcessOutcome.Ignored(
                SkipReason.NO_ROOT_NODE, request.packageName, request.rules.size,
            )
        val nodes: List<NodeSnapshot> = try {
            UiTreeScanner.scan(root, request.packageName)
        } finally {
            NodeRecycler.recycle(root)
        }

        if (nodes.isEmpty()) {
            return ProcessOutcome.Ignored(
                SkipReason.EMPTY_NODE_TREE, request.packageName, request.rules.size,
            )
        }

        // ---- 匹配 ----
        val best = matcher.matchBest(applicable, nodes)
            ?: return ProcessOutcome.Ignored(
                SkipReason.NO_NODE_MATCH, request.packageName, request.rules.size,
            )

        // ---- 第 4 道闸：防误点 ----
        val ruleId = best.rule.id.takeIf { it != 0L } ?: best.rule.name.hashCode().toLong()
        val nodeKey = AntiMisclickGate.nodeKey(best.node)
        val now = System.currentTimeMillis()
        if (!gate.tryAcquire(ruleId, nodeKey, now)) {
            return ProcessOutcome.Throttled(best.rule.name, request.packageName, request.rules.size)
        }

        // ---- 点击独立协程：手势 40ms 播放 + 二次 BFS 不阻塞下一次扫描 ----
        scope.launch {
            performClick(best, request.packageName, request.activityName, request.rules.size)
        }
        return ProcessOutcome.ClickScheduled(best, request.packageName, request.rules.size)
    }

    /**
     * 执行点击并在落地后补发拦截日志。
     *
     * `AccessibilityNodeInfo` 的操作是跨进程调用，**不受"必须在主线程"
     * 约束** —— 无障碍服务只要求"调用发生在服务存活期间"。
     *
     * ## 失败不回写诊断
     *
     * 本方法异步执行，其失败原因无法回填到"本次事件"的诊断记录上
     * （会被后续事件覆盖，造成错乱）。因此失败只写日志，
     * 由 LogRepository 侧如实记录。
     */
    private suspend fun performClick(
        match: MatchResult,
        packageName: String,
        activityName: String?,
        ruleCount: Int,
    ) {
        // 重新取根节点：同步阶段拿到的那个在等待调度期间可能已失效
        val gestureRoot = runCatching { rootProvider() }.getOrNull()
        val result = try {
            clickAction(gestureRoot, match)
        } finally {
            NodeRecycler.recycle(gestureRoot)
        }

        when (result) {
            is ClickOutcome.Success -> emitLog(match, packageName, activityName, result.method)
            is ClickOutcome.Failed -> log("点击失败: ${result.reason}（$packageName）")
        }
    }

    /**
     * 读取事件自带的文本候选（不产生任何 IPC）。
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
     * 界面发生窗口切换时标记「需要重置防误点记账」。
     *
     * 不重置会导致"上一个界面的冷却时间"影响新界面 ——
     * 用户从应用 A 切到应用 B，B 的开屏广告因 A 的残留冷却而点不掉。
     * 这类问题在实机调试中表现为"偶发失效"，极难定位，因此必须主动清理。
     *
     * 本方法**由主线程调用**，只做一次原子置位；真正的 `reset()`
     * 在消费者线程执行（[AntiMisclickGate] 非线程安全）。
     */
    fun onWindowChanged() {
        pendingWindowReset.set(true)
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
        scope.launch { onIntercepted(record) }
    }

    private companion object {
        const val TAG = "NoAdEventProcessor"

        /** Deferred 分支在「未携带执行环境」时使用的规则名占位 */
        const val NO_HANDOFF_PLACEHOLDER = "(未投递)"
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
