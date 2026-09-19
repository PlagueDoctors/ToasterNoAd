package com.toaster.noad.core.service.event

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * S1 事件处理的**实时诊断快照**。
 *
 * ## 为什么需要它（这不是可选功能）
 *
 * 排障时最需要回答的问题是「事件到底卡在哪一道闸门」。
 * 常规做法是看 logcat，但**部分厂商 ROM 会抑制应用日志** ——
 * 实测设备（NX789J）的 `log.tag.NoAdAccessibility` 被系统设为 Silent，
 * `adb logcat -s NoAdAccessibility:D` 完全无输出。
 *
 * 在没有 root 的情况下无法改变该设置（`setprop` 需要权限）。
 * 因此必须让应用**把判定结果写到自己能读的地方**。
 *
 * ## 为什么用内存而非数据库
 *
 * - 这是**排障视图**，不是审计数据。用户打开规则页看当下的状态即可，
 *   没有跨重启保留的价值。
 * - 新建一张表要走 `Migration(2, 3)`，而每次迁移都是有风险的改动；
 *   为诊断信息付这个代价不划算。
 * - 事件频率很高（内容变化事件每秒可达数十次），
 *   每次写库会带来可观的开销。
 *
 * ## 写入方与读取方
 *
 * - 写：[EventProcessor]（在主线程回调中，只做一次 volatile 赋值）
 * - 读：规则页 / 首页（通过 `StateFlow` 收集）
 *
 * 用 [MutableStateFlow] 而非 `AtomicReference`：UI 需要感知变化并重组，
 * 而 `StateFlow` 自带"相同值不重复发射"的语义，
 * 恰好符合"只在原因变化时更新界面"的需求，无需额外的去重逻辑。
 */
object SkipDiagnostics {

    /**
     * 当前诊断状态。
     *
     * 初始值 [SkipDiagnosticsState.EMPTY] 表示"尚无事件经过"，
     * 与"事件被丢弃"是不同的状态 —— UI 文案必须区分，否则用户无法判断
     * 是服务没工作还是规则没命中。
     */
    private val _state = MutableStateFlow(SkipDiagnosticsState.EMPTY)

    val state: StateFlow<SkipDiagnosticsState> = _state.asStateFlow()

    /**
     * 记录一次事件处理结果。
     *
     * 由 [EventProcessor] 在每个事件结束时调用。
     * 这是主线程热路径，**只做一次赋值**，不做任何分配以外的操作。
     *
     * @param packageName 事件包名（可能为空）
     * @param eventType 事件类型（用于区分窗口变化/内容变化）
     * @param outcome 处理结果
     * @param ruleCount 当前该应用可用规则数（用于判断规则是否导入成功）
     * @param costMs 本次处理耗时（毫秒）。这是"事件回调占用了主线程多久"的直接证据
     * @param slowEventCount 累计"处理耗时超过阈值"的事件数
     */
    fun record(
        packageName: String?,
        eventType: Int,
        outcome: ProcessOutcome,
        ruleCount: Int,
        costMs: Long = 0L,
        slowEventCount: Int = 0,
        now: Long = System.currentTimeMillis(),
    ) {
        val next = when (outcome) {
            is ProcessOutcome.Ignored -> _state.value.copy(
                lastPackageName = packageName,
                lastEventType = eventType,
                lastReason = outcome.reason,
                lastOutcome = null,
                availableRuleCount = ruleCount,
                lastCostMs = costMs,
                maxCostMs = maxOf(_state.value.maxCostMs, costMs),
                slowEventCount = slowEventCount,
                updatedAt = now,
                totalEvents = _state.value.totalEvents + 1,
            )

            is ProcessOutcome.Throttled -> _state.value.copy(
                lastPackageName = packageName,
                lastEventType = eventType,
                lastReason = null,
                lastOutcome = "被防误点冷却拦下（${outcome.ruleName}）",
                availableRuleCount = ruleCount,
                lastCostMs = costMs,
                maxCostMs = maxOf(_state.value.maxCostMs, costMs),
                slowEventCount = slowEventCount,
                updatedAt = now,
                totalEvents = _state.value.totalEvents + 1,
            )

            is ProcessOutcome.Clicked -> _state.value.copy(
                lastPackageName = packageName,
                lastEventType = eventType,
                lastReason = null,
                lastOutcome = "已点击：${outcome.match.node.describe()}（${outcome.method}）",
                availableRuleCount = ruleCount,
                lastCostMs = costMs,
                maxCostMs = maxOf(_state.value.maxCostMs, costMs),
                slowEventCount = slowEventCount,
                updatedAt = now,
                totalEvents = _state.value.totalEvents + 1,
                totalClicks = _state.value.totalClicks + 1,
            )

            // 点击已投递到后台线程，尚未落地 → 不能计入成功点击数
            is ProcessOutcome.ClickScheduled -> _state.value.copy(
                lastPackageName = packageName,
                lastEventType = eventType,
                lastReason = null,
                lastOutcome = "已命中规则「${outcome.match.rule.name}」，点击已投递",
                availableRuleCount = ruleCount,
                lastCostMs = costMs,
                maxCostMs = maxOf(_state.value.maxCostMs, costMs),
                slowEventCount = slowEventCount,
                updatedAt = now,
                totalEvents = _state.value.totalEvents + 1,
            )

            is ProcessOutcome.Deferred -> _state.value.copy(
                lastPackageName = packageName,
                lastEventType = eventType,
                lastReason = null,
                lastOutcome = "命中规则「${outcome.ruleName}」但无执行环境，未点击",
                availableRuleCount = ruleCount,
                lastCostMs = costMs,
                maxCostMs = maxOf(_state.value.maxCostMs, costMs),
                slowEventCount = slowEventCount,
                updatedAt = now,
                totalEvents = _state.value.totalEvents + 1,
            )

            is ProcessOutcome.ClickFailed -> _state.value.copy(
                lastPackageName = packageName,
                lastEventType = eventType,
                lastReason = null,
                lastOutcome = "点击失败：${outcome.reason}",
                availableRuleCount = ruleCount,
                lastCostMs = costMs,
                maxCostMs = maxOf(_state.value.maxCostMs, costMs),
                slowEventCount = slowEventCount,
                updatedAt = now,
                totalEvents = _state.value.totalEvents + 1,
            )
        }

        // 诊断不是审计，无需逐条保留；但也不做限流 ——
        // StateFlow 的值合并语义已经天然把高频更新压成"最新状态"。
        _state.value = next
    }

    /**
     * 记录"服务收到了事件但总开关关闭"这一状态。
     *
     * 与"事件被规则丢弃"必须区分：前者是用户没开开关，
     * 后者是规则/纳管配置问题，修复动作完全不同。
     */
    fun recordDisabled(packageName: String?, now: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(
            lastPackageName = packageName,
            lastReason = null,
            lastOutcome = "S1 总开关未开启，事件未被处理",
            availableRuleCount = 0,
            updatedAt = now,
        )
    }

    /** 服务连接时重置，避免展示上一个会话的陈旧状态 */
    fun onServiceConnected(now: Long = System.currentTimeMillis()) {
        _state.value = SkipDiagnosticsState.EMPTY.copy(
            lastOutcome = "S1 服务已连接，等待事件",
            updatedAt = now,
        )
    }

    /** 服务断开时标记，否则界面会一直显示"运行中" */
    fun onServiceDisconnected(now: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(
            lastOutcome = "S1 服务已断开",
            updatedAt = now,
        )
    }
}

/**
 * 诊断快照的不可变状态。
 */
data class SkipDiagnosticsState(
    /** 最近一个经过处理的事件所属包名 */
    val lastPackageName: String? = null,

    /** 最近一个事件的事件类型（`AccessibilityEvent.TYPE_*`） */
    val lastEventType: Int = 0,

    /**
     * 最近一次「事件被丢弃」的原因。
     *
     * 为空不代表正常 —— 也可能是尚未收到任何事件（见 [lastOutcome]）。
     */
    val lastReason: SkipReason? = null,

    /**
     * 非丢弃类结果的描述（已点击 / 被冷却拦下 / 点击失败 / 服务状态）。
     *
     * 与 [lastReason] 互斥：二者只有一个非空，
     * 这样 UI 的展示逻辑可以简单地在两者间二选一。
     */
    val lastOutcome: String? = null,

    /** 最近一个事件时，该应用可用的规则条数。为 0 说明规则没生效 */
    val availableRuleCount: Int = 0,

    /**
     * 最近一个事件在主线程上占用了多少毫秒。
     *
     * ## 为什么必须把它暴露出来
     *
     * `onAccessibilityEvent` 运行在**主线程**，它每多占用 1ms，
     * 系统给当前前台应用的输入/绘制就少 1ms。当这个值达到数百毫秒时，
     * 用户感知到的就是"启动任何应用都卡一下"。
     *
     * 这类卡顿**无法从 logcat 看出**（日志只记录判定结果，不记录耗时），
     * 只能靠实测数字说话。因此把它记进诊断并在规则页展示。
     */
    val lastCostMs: Long = 0L,

    /** 本次会话中单次事件处理的最大耗时（毫秒）—— 抖动与尖峰的证据 */
    val maxCostMs: Long = 0L,

    /** 处理耗时超过 [SLOW_EVENT_THRESHOLD_MS] 的累计事件数 */
    val slowEventCount: Int = 0,

    /** 本次会话累计处理的事件数 */
    val totalEvents: Int = 0,

    /** 本次会话累计成功点击数 */
    val totalClicks: Int = 0,

    /** 状态最后更新时间（毫秒） */
    val updatedAt: Long = 0L,
) {
    /** 是否已经收到过任何事件 */
    val hasReceivedEvent: Boolean get() = totalEvents > 0 || updatedAt > 0L

    /**
     * 是否存在可感知的主线程卡顿。
     *
     * 判据用 [maxCostMs] 而非 [lastCostMs]：偶发尖峰同样会造成
     * "有时卡一下"的观感，只看最近一次会漏掉。16ms 是一帧的预算。
     */
    val hasFrameDrop: Boolean get() = maxCostMs > FRAME_BUDGET_MS

    companion object {
        val EMPTY = SkipDiagnosticsState()

        /**
         * 单帧预算（60Hz 下约 16.7ms）。
         *
         * 超过它就意味着这一帧的主线程工作被挤占，
         * 但单次超出不一定可感知 —— 因此 UI 文案要避免夸大。
         */
        const val FRAME_BUDGET_MS = 16L

        /**
         * 判定为"慢事件"的阈值（毫秒）。
         *
         * 取 100ms 的依据：低于此值的处理耗时会被系统的输入队列吸收，
         * 用户无感；超过 100ms 已接近人眼可察觉的交互延迟下限（约 100ms）。
         * 用它来区分"偶有抖动"与"系统性问题"。
         */
        const val SLOW_EVENT_THRESHOLD_MS = 100L
    }
}
