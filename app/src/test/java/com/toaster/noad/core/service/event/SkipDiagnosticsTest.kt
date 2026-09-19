package com.toaster.noad.core.service.event

import com.toaster.noad.core.engine.ui.ClickMethod
import com.toaster.noad.core.engine.ui.MatchResult
import com.toaster.noad.core.engine.ui.NodeSnapshot
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SkipDiagnostics] 单元测试。
 *
 * ## 为什么这个组件值得单独测试
 *
 * 诊断是**排障的唯一可见通道** ——
 * 实测设备的 ROM 抑制了应用日志，用户报告"没效果"时，
 * 规则页上的这段文案是唯一能判断"卡在哪一道闸门"的依据。
 *
 * 因此以下两点必须钉死：
 *
 * 1. **状态语义正确**：`lastReason` 与 `lastOutcome` 互斥，
 *    UI 靠这个不变式在两者间二选一
 * 2. **累加计数不被覆盖**：`totalEvents` / `totalClicks` 只增不减，
 *    它们是"服务到底有没有在工作"的硬证据
 *
 * 若 2 被破坏，用户会看到"事件数永远是 1"，从而误判服务没有持续工作。
 *
 * ## 单例状态的测试隔离
 *
 * [SkipDiagnostics] 是 `object`（全局单例），测试之间会互相污染。
 * 用 `@Before` 把状态重置为 [SkipDiagnosticsState.EMPTY]。
 * 无法重置 `totalEvents` 为零 —— `onServiceConnected` 刻意保留计数器
 * （见其文档），因此断言一律用**相对增量**而非绝对值。
 */
class SkipDiagnosticsTest {

    @Before
    fun setUp() {
        SkipDiagnostics.onServiceConnected(now = 0L)
    }

    @After
    fun tearDown() {
        // 不留状态给其他测试类
        SkipDiagnostics.onServiceConnected(now = 0L)
    }

    // ==================================================================
    // 服务状态
    // ==================================================================

    @Test
    fun givenFreshState_whenOnServiceConnected_thenShowsConnectedWithoutReason() {
        val state = SkipDiagnostics.state.value

        assertEquals("S1 服务已连接，等待事件", state.lastOutcome)
        assertNull(state.lastReason)
        // 服务已连接但还没有事件 —— UI 必须能区分这两种状态，
        // 否则用户会以为"服务在跑但拦截坏了"，实际只是还没打开任何应用
        assertFalse(state.hasReceivedEvent)
    }

    @Test
    fun givenConnected_whenOnServiceDisconnected_thenShowsDisconnected() {
        SkipDiagnostics.onServiceDisconnected(now = 1L)

        val state = SkipDiagnostics.state.value
        assertEquals("S1 服务已断开", state.lastOutcome)
        assertNull(state.lastReason)
    }

    @Test
    fun givenTotalEnabled_whenRecordDisabled_thenOutcomeExplainsSwitchAndRuleCountZero() {
        SkipDiagnostics.recordDisabled(packageName = "com.a", now = 1L)

        val state = SkipDiagnostics.state.value
        assertEquals("com.a", state.lastPackageName)
        assertEquals("S1 总开关未开启，事件未被处理", state.lastOutcome)
        // 开关关闭时规则数归零，避免 UI 误报"该应用有 N 条规则却不生效"
        assertEquals(0, state.availableRuleCount)
    }

    // ==================================================================
    // 丢弃类结果：lastReason 有值，lastOutcome 为空
    // ==================================================================

    @Test
    fun givenIgnoredOutcome_whenRecord_thenReasonSetAndOutcomeCleared() {
        SkipDiagnostics.onServiceConnected(now = 0L)
        // 先制造一个 lastOutcome 非空的状态，验证它会被清掉
        SkipDiagnostics.onServiceDisconnected(now = 1L)

        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 32,
            outcome = ProcessOutcome.Ignored(SkipReason.NO_NODE_MATCH, "com.a", ruleCount = 7),
            ruleCount = 7,
            now = 2L,
        )

        val state = SkipDiagnostics.state.value
        assertEquals(SkipReason.NO_NODE_MATCH, state.lastReason)
        // ★ 互斥不变式：UI 只判断"reason 非空就显示丢弃原因"，
        // 若这里不清空 outcome，界面会同时显示两条结论
        assertNull(state.lastOutcome)
        assertEquals(7, state.availableRuleCount)
        assertEquals(2L, state.updatedAt)
    }

    @Test
    fun givenAppNotManaged_whenRecord_thenReasonReflectsFirstGate() {
        SkipDiagnostics.record(
            packageName = "com.unmanaged",
            eventType = 32,
            outcome = ProcessOutcome.Ignored(SkipReason.APP_NOT_MANAGED, "com.unmanaged"),
            ruleCount = 0,
            now = 1L,
        )

        val state = SkipDiagnostics.state.value
        assertEquals(SkipReason.APP_NOT_MANAGED, state.lastReason)
        // 应用未纳管时规则数必然是 0 —— 但它与"纳管了但无规则"是不同的修复动作，
        // 因此 reason 必须能区分（见 SkipReason 的文档）
        assertEquals(0, state.availableRuleCount)
    }

    @Test
    fun givenNoRuleForPackage_whenRecord_thenRuleCountZeroButManagedDistinct() {
        SkipDiagnostics.record(
            packageName = "com.managed",
            eventType = 32,
            outcome = ProcessOutcome.Ignored(SkipReason.NO_RULE_FOR_PACKAGE, "com.managed"),
            ruleCount = 0,
            now = 1L,
        )

        assertEquals(SkipReason.NO_RULE_FOR_PACKAGE, SkipDiagnostics.state.value.lastReason)
    }

    // ==================================================================
    // 非丢弃类结果：lastReason 为空，lastOutcome 有值
    // ==================================================================

    @Test
    fun givenThrottledOutcome_whenRecord_thenOutcomeNamesRuleAndReasonCleared() {
        // 先让 reason 有值，验证被清掉
        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 32,
            outcome = ProcessOutcome.Ignored(SkipReason.NO_NODE_MATCH, "com.a"),
            ruleCount = 3,
            now = 1L,
        )

        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 32,
            outcome = ProcessOutcome.Throttled("B站开屏", "com.a", ruleCount = 3),
            ruleCount = 3,
            now = 2L,
        )

        val state = SkipDiagnostics.state.value
        assertNull(state.lastReason)
        assertTrue(state.lastOutcome!!.contains("B站开屏"))
        // 被冷却拦下不算点击成功
        assertEquals(0, state.totalClicks)
    }

    @Test
    fun givenClickedOutcome_whenRecord_thenClicksIncremented() {
        SkipDiagnostics.onServiceConnected(now = 0L)
        val before = SkipDiagnostics.state.value.totalClicks

        SkipDiagnostics.record(
            packageName = "tv.danmaku.bili",
            eventType = 32,
            outcome = ProcessOutcome.Clicked(
                match = matchOf("跳过"),
                method = ClickMethod.NODE_SELF,
                packageName = "tv.danmaku.bili",
                ruleCount = 3,
            ),
            ruleCount = 3,
            now = 5L,
        )

        val state = SkipDiagnostics.state.value
        assertNull(state.lastReason)
        assertTrue(state.lastOutcome!!.startsWith("已点击："))
        assertEquals(before + 1, state.totalClicks)
        assertEquals(3, state.availableRuleCount)
    }

    @Test
    fun givenClickFailed_whenRecord_thenReasonTextIncludesCauseAndNoClickCounted() {
        val before = SkipDiagnostics.state.value.totalClicks

        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 32,
            outcome = ProcessOutcome.ClickFailed(
                match = matchOf("跳过"),
                reason = "节点已失效",
                packageName = "com.a",
                ruleCount = 2,
            ),
            ruleCount = 2,
            now = 1L,
        )

        val state = SkipDiagnostics.state.value
        assertTrue(state.lastOutcome!!.contains("节点已失效"))
        // ★ 点击失败绝不能计入成功数 —— 否则统计页会虚高，
        // 用户看到"拦截了 10 次"但实际一次都没跳过去
        assertEquals(before, state.totalClicks)
    }

    // ==================================================================
    // 计数累加
    // ==================================================================

    @Test
    fun givenManyEvents_whenRecord_thenTotalEventsAccumulates() {
        val before = SkipDiagnostics.state.value.totalEvents

        repeat(5) { index ->
            SkipDiagnostics.record(
                packageName = "com.a",
                eventType = 32,
                outcome = ProcessOutcome.Ignored(SkipReason.IRRELEVANT_EVENT, "com.a"),
                ruleCount = 1,
                now = index.toLong(),
            )
        }

        // 事件数是"服务在工作"的硬证据，绝不能因状态被覆盖而丢失
        assertEquals(before + 5, SkipDiagnostics.state.value.totalEvents)
    }

    @Test
    fun givenRecordCalls_whenCounting_thenHasReceivedEventBecomesTrue() {
        assertFalse(SkipDiagnostics.state.value.hasReceivedEvent)

        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 32,
            outcome = ProcessOutcome.Ignored(SkipReason.IRRELEVANT_EVENT, "com.a"),
            ruleCount = 0,
            now = 1L,
        )

        assertTrue(SkipDiagnostics.state.value.hasReceivedEvent)
    }

    @Test
    fun givenManyClicks_whenRecord_thenTotalClicksAccumulates() {
        val before = SkipDiagnostics.state.value.totalClicks

        repeat(3) { index ->
            SkipDiagnostics.record(
                packageName = "com.a",
                eventType = 32,
                outcome = ProcessOutcome.Clicked(
                    match = matchOf("跳过"),
                    method = ClickMethod.GESTURE_TAP,
                    packageName = "com.a",
                    ruleCount = 1,
                ),
                ruleCount = 1,
                now = index.toLong(),
            )
        }

        assertEquals(before + 3, SkipDiagnostics.state.value.totalClicks)
    }

    @Test
    fun givenLatestEvent_thenOnlyMostRecentIsKept() {
        SkipDiagnostics.record(
            packageName = "com.first",
            eventType = 32,
            outcome = ProcessOutcome.Ignored(SkipReason.NO_NODE_MATCH, "com.first", ruleCount = 1),
            ruleCount = 1,
            now = 1L,
        )
        SkipDiagnostics.record(
            packageName = "com.second",
            eventType = 2048,
            outcome = ProcessOutcome.Ignored(SkipReason.APP_NOT_MANAGED, "com.second"),
            ruleCount = 0,
            now = 2L,
        )

        val state = SkipDiagnostics.state.value
        // 诊断展示"最近一次"，因此必须被覆盖而非保留历史
        assertEquals("com.second", state.lastPackageName)
        assertEquals(2048, state.lastEventType)
        assertEquals(SkipReason.APP_NOT_MANAGED, state.lastReason)
    }

    // ==================================================================
    // 主线程耗时（"启动应用卡一下"的证据通道）
    // ==================================================================

    @Test
    fun givenCostSupplied_whenRecord_thenLastCostStored() {
        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 2048,
            outcome = ProcessOutcome.Ignored(SkipReason.PRE_FILTERED, "com.a", ruleCount = 3),
            ruleCount = 3,
            costMs = 7L,
            now = 1L,
        )

        assertEquals(7L, SkipDiagnostics.state.value.lastCostMs)
    }

    @Test
    fun givenSuccessiveCosts_whenRecord_thenMaxIsMonotonic() {
        // 峰值必须只增不减：偶发尖峰同样让用户感到"有时卡一下"，
        // 若被较小值覆盖，"卡顿"就会从诊断里消失
        listOf(5L, 120L, 8L, 300L, 12L).forEachIndexed { index, cost ->
            SkipDiagnostics.record(
                packageName = "com.a",
                eventType = 2048,
                outcome = ProcessOutcome.Ignored(SkipReason.PRE_FILTERED, "com.a"),
                ruleCount = 1,
                costMs = cost,
                now = index.toLong(),
            )
        }

        val state = SkipDiagnostics.state.value
        assertEquals(12L, state.lastCostMs)
        assertEquals(300L, state.maxCostMs)
    }

    @Test
    fun givenSlowCost_whenRecord_thenFrameDropFlagged() {
        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 2048,
            outcome = ProcessOutcome.Ignored(SkipReason.PRE_FILTERED, "com.a"),
            ruleCount = 1,
            costMs = SkipDiagnosticsState.FRAME_BUDGET_MS + 1,
            now = 1L,
        )

        assertTrue(SkipDiagnostics.state.value.hasFrameDrop)
    }

    @Test
    fun givenFastCost_whenRecord_thenNoFrameDrop() {
        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 2048,
            outcome = ProcessOutcome.Ignored(SkipReason.PRE_FILTERED, "com.a"),
            ruleCount = 1,
            // 恰好等于预算不算掉帧：边界值必须是"通过"，
            // 否则一个 16ms 的正常事件也会被报成卡顿
            costMs = SkipDiagnosticsState.FRAME_BUDGET_MS,
            now = 1L,
        )

        assertFalse(SkipDiagnostics.state.value.hasFrameDrop)
    }

    @Test
    fun givenFreshState_thenSlowEventCountZero() {
        assertEquals(0, SkipDiagnostics.state.value.slowEventCount)
    }

    @Test
    fun givenSlowEventCount_whenRecord_thenStored() {
        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 2048,
            outcome = ProcessOutcome.Ignored(SkipReason.PRE_FILTERED, "com.a"),
            ruleCount = 1,
            costMs = 200L,
            slowEventCount = 3,
            now = 1L,
        )

        assertEquals(3, SkipDiagnostics.state.value.slowEventCount)
    }

    // ==================================================================
    // 预筛丢弃与投递中态
    // ==================================================================

    @Test
    fun givenPreFiltered_whenRecord_thenReasonIsDistinctFromNoNodeMatch() {
        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 2048,
            outcome = ProcessOutcome.Ignored(SkipReason.PRE_FILTERED, "com.a", ruleCount = 5),
            ruleCount = 5,
            now = 1L,
        )

        // ★ 二者现象都是"没点"，但含义完全相反：
        // PRE_FILTERED = 优化命中（没遍历就丢了，规则没问题）
        // NO_NODE_MATCH = 遍历了仍没找到（规则真的过期了）
        // 合并成一个值会让用户去改本来正确的规则。
        assertEquals(SkipReason.PRE_FILTERED, SkipDiagnostics.state.value.lastReason)
        assertTrue(SkipReason.PRE_FILTERED != SkipReason.NO_NODE_MATCH)
    }

    @Test
    fun givenClickScheduled_whenRecord_thenNotCountedAsClick() {
        val before = SkipDiagnostics.state.value.totalClicks

        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 2048,
            outcome = ProcessOutcome.ClickScheduled(
                match = matchOf("跳过"),
                packageName = "com.a",
                ruleCount = 2,
            ),
            ruleCount = 2,
            now = 1L,
        )

        val state = SkipDiagnostics.state.value
        // ★ 投递 ≠ 成功。计入点击数会让统计虚高，
        // 用户看到"拦截了 N 次"但实际可能一次都没跳过去。
        assertEquals(before, state.totalClicks)
        assertTrue(state.lastOutcome!!.contains("点击已投递"))
    }

    @Test
    fun givenDeferred_whenRecord_thenSurfacedAsExplicitOutcome() {
        SkipDiagnostics.record(
            packageName = "com.a",
            eventType = 2048,
            outcome = ProcessOutcome.Deferred("B站开屏", "com.a", ruleCount = 2),
            ruleCount = 2,
            now = 1L,
        )

        val state = SkipDiagnostics.state.value
        // 缺少执行环境属于接线错误，必须显式暴露 ——
        // 若表现为"什么都没发生"，会与"规则没命中"混在一起无法归因
        assertTrue(state.lastOutcome!!.contains("无执行环境"))
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private fun matchOf(text: String): MatchResult {
        val rule = SkipRule(
            name = "测试规则",
            packageName = "com.a",
            targetType = TargetType.TEXT,
            targetValue = text,
            matchMode = MatchMode.PREFIX,
        )
        return MatchResult(
            rule = rule,
            node = NodeSnapshot(
                index = 0,
                parentIndex = -1,
                depth = 3,
                viewId = null,
                text = text,
                contentDescription = null,
                className = "android.widget.TextView",
                clickable = true,
                visible = true,
                left = 100,
                top = 200,
                right = 300,
                bottom = 260,
            ),
            confidence = MatchResult.CONFIDENCE_TEXT,
        )
    }
}
