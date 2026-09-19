package com.toaster.noad.core.service.event

import com.toaster.noad.core.engine.ui.UiMatcher
import com.toaster.noad.core.model.GLOBAL_RULE_PACKAGE
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EventPreFilter] 单元测试。
 *
 * ## 为什么这个类必须被重点测试
 *
 * 预筛是本次"消除启动卡顿"修复的核心手段，但它同时是**最危险**的改动：
 * 它决定了"哪些事件连遍历都不做就直接丢弃"。
 *
 * 一旦预筛比 [UiMatcher] 更严格，就会产生**静默漏拦** ——
 * 现象是"无障碍已授权、应用已纳管、规则也在，但就是不跳过、
 * 没有任何日志、统计恒 0"，与本项目曾经踩过的两次坑**现象完全一致**。
 * 而触发条件会是"某个应用的跳过按钮文本恰好不满足预筛判据"，
 * 极难从现象反推原因。
 *
 * 因此本测试的重心是**放行侧**：凡是"不确定"的输入，
 * 都必须断言返回 true（放行）。
 *
 * ## 测试组的划分依据
 *
 * 按"预筛能否判断"分三组：
 * 1. **不可筛规则**（VIEW_ID / COORDINATE / REGEX）→ 一律放行
 * 2. **信息不足**（事件无文本 / className 未知）→ 一律放行
 * 3. **可筛情形** → 只有确实不匹配才丢弃
 */
class EventPreFilterTest {

    // ==================================================================
    // 第 1 组：不可筛规则一律放行
    // ==================================================================

    @Test
    fun givenViewIdRule_whenEventTextIsIrrelevant_thenPassesThrough() {
        // 这是**内置规则库的常态**：count_down、*tt_splash_skip_btn 都是 VIEW_ID。
        // viewId 不在 AccessibilityEvent 里，预筛无从判断，必须放行。
        val rules = listOf(rule(TargetType.VIEW_ID, "tv.danmaku.bili:id/count_down"))

        assertTrue(
            "VIEW_ID 规则无法靠事件文本预筛，必须放行",
            EventPreFilter.mayMatch(rules, candidates = listOf("推荐视频 精彩内容"), activityName = null),
        )
    }

    @Test
    fun givenSdkSuffixViewIdRule_whenEventHasUnrelatedText_thenPassesThrough() {
        val rules = listOf(rule(TargetType.VIEW_ID, "*tt_splash_skip_btn"))

        // 淘宝开屏时事件文本可能是商品文案，但穿山甲按钮 id 只能靠遍历拿到
        assertTrue(EventPreFilter.mayMatch(rules, listOf("双十一 限时抢购"), activityName = null))
    }

    @Test
    fun givenCoordinateRule_whenEventTextIsIrrelevant_thenPassesThrough() {
        val rules = listOf(
            SkipRule(
                name = "坐标兜底",
                packageName = "com.a",
                targetType = TargetType.COORDINATE,
                targetValue = "960,2100",
            ),
        )

        assertTrue(
            "坐标规则与文本无关，任何事件都可能需要它",
            EventPreFilter.mayMatch(rules, listOf("任意正文"), activityName = null),
        )

    }

    @Test
    fun givenRegexRule_whenEventTextIsIrrelevant_thenPassesThrough() {
        val rules = listOf(
            rule(TargetType.TEXT, "跳过\\s*\\d+").copy(matchMode = MatchMode.REGEX),
        )

        assertTrue(
            "正则语义复杂且编译有开销，不做预筛",
            EventPreFilter.mayMatch(rules, listOf("完全无关的文本"), activityName = null),
        )
    }

    @Test
    fun givenMixedRules_whenOnlyOneIsUnfilterable_thenAllPass() {
        // ★ 关键：只要**存在**一条不可筛规则，整体放行。
        // 若这里按"逐条判断、有一条命中即可"实现，不可筛的那条就会被
        // 其他可筛规则的否定结果连带丢弃 —— 那是静默漏拦的典型成因。
        val rules = listOf(
            rule(TargetType.TEXT, "跳过", MatchMode.PREFIX),
            rule(TargetType.VIEW_ID, "*ksad_splash_circle_skip_view"),
        )

        assertTrue(EventPreFilter.mayMatch(rules, listOf("广告 5 秒后关闭"), activityName = null))
    }

    // ==================================================================
    // 第 2 组：信息不足一律放行
    // ==================================================================

    @Test
    fun givenNoCandidates_whenEventCarriesNothing_thenPassesThrough() {
        val rules = listOf(rule(TargetType.TEXT, "跳过", MatchMode.PREFIX))

        // ★ 最重要的一条：很多 ROM 的 TYPE_WINDOW_STATE_CHANGED 只有包名、
        // 内容全空，而**开屏广告第一次出现恰恰是这个事件**。
        // 这里若返回 false，开屏广告将永远不会被跳过。
        assertTrue(
            "事件无文本时必须放行，否则开屏广告永远无法跳过",
            EventPreFilter.mayMatch(rules, candidates = emptyList(), activityName = null),
        )
    }

    @Test
    fun givenActivityScopedRule_whenActivityUnknown_thenTextMatchStillPassesThrough() {
        val rules = listOf(
            rule(TargetType.TEXT, "跳过", MatchMode.PREFIX)
                .copy(activityName = "com.a.SplashActivity"),
        )

        // 规则有界面限定，事件没给 className。
        // 预筛不去判断 activity，只看文本 —— 文本匹配即放行。
        // 最终该规则是否适用由 EventProcessor.filterByActivity 决定。
        assertTrue(
            "界面限定交由权威实现判断，预筛不应因 className 缺失而丢弃",
            EventPreFilter.mayMatch(rules, listOf("跳过 1"), activityName = null),
        )
    }

    @Test
    fun givenActivityScopedRule_whenActivityDiffersButTextMatches_thenPassesThrough() {
        val rules = listOf(
            rule(TargetType.TEXT, "跳过", MatchMode.PREFIX)
                .copy(activityName = "com.a.SplashActivity"),
        )

        // ★ 保守选择：即使 activity 明显不同也放行。
        // 理由：activity 判定在 [EventProcessor.filterByActivity] 已有一份权威实现，
        // 预筛再实现一遍会引入"两份判据不一致"的风险，而只要预筛那份更严格
        // 就会造成静默漏拦。activity 过滤本身是纯字符串比较、无 IPC，
        // 交给权威实现没有性能损失。
        assertTrue(
            EventPreFilter.mayMatch(rules, listOf("跳过 1"), activityName = "com.a.MainActivity"),
        )
    }

    @Test
    fun givenEmptyRules_thenDoesNotPass() {
        // 无规则时没有任何可命中的对象，丢弃是安全的（引擎侧已先行判空）
        assertFalse(EventPreFilter.mayMatch(emptyList(), listOf("跳过 1"), activityName = null))
    }

    // ==================================================================
    // 第 3 组：可筛情形 —— 匹配则放行
    // ==================================================================

    @Test
    fun givenPrefixRule_whenEventCarriesCountdownText_thenPassesThrough() {
        val rules = listOf(rule(TargetType.TEXT, "跳过", MatchMode.PREFIX))

        // ★ 真实场景：倒计时按钮触发的内容变化事件
        listOf("跳过", "跳过 1", "跳过 3", "跳过广告 5s", "跳过此广告").forEach { text ->
            assertTrue("「$text」应放行", EventPreFilter.mayMatch(rules, listOf(text), null))
        }
    }

    @Test
    fun givenExactRule_whenEventTextEqualsTarget_thenPassesThrough() {
        val rules = listOf(rule(TargetType.TEXT, "跳过", MatchMode.EXACT))

        assertTrue(EventPreFilter.mayMatch(rules, listOf("跳过"), null))
    }

    @Test
    fun givenContainsRule_whenEventTextContainsTarget_thenPassesThrough() {
        val rules = listOf(rule(TargetType.TEXT, "跳过广告", MatchMode.CONTAINS))

        assertTrue(EventPreFilter.mayMatch(rules, listOf("点击跳过广告继续"), null))
    }

    @Test
    fun givenDescriptionRule_whenDescriptionMatches_thenPassesThrough() {
        val rules = listOf(rule(TargetType.DESCRIPTION, "关闭", MatchMode.EXACT))

        assertTrue(EventPreFilter.mayMatch(rules, listOf("关闭"), null))
    }

    @Test
    fun givenMultipleCandidates_whenAnyMatches_thenPassesThrough() {
        val rules = listOf(rule(TargetType.TEXT, "跳过", MatchMode.PREFIX))

        // 事件可能带多个文本（text 是 List）
        assertTrue(
            EventPreFilter.mayMatch(rules, listOf("广告", "会员立减", "跳过 2"), null),
        )
    }

    // ==================================================================
    // 第 3 组：可筛情形 —— 确实不匹配才丢弃
    // ==================================================================

    @Test
    fun givenPrefixRule_whenEventCarriesLongGuidingText_thenDiscards() {
        val rules = listOf(rule(TargetType.TEXT, "跳过", MatchMode.PREFIX))

        // ★ 这是预筛真正要挡掉的大头：权限引导页上的长文案。
        // 它恰好以「跳过」开头，若预筛不带长度上限就会频繁触发全树遍历，
        // 预筛也就失去了意义。
        assertFalse(
            EventPreFilter.mayMatch(rules, listOf("跳过此步可在设置中重新开启"), null),
        )
    }

    @Test
    fun givenPrefixRule_whenEventTextIsUnrelatedContent_thenDiscards() {
        val rules = listOf(rule(TargetType.TEXT, "跳过", MatchMode.PREFIX))

        assertFalse(EventPreFilter.mayMatch(rules, listOf("推荐阅读 详情"), null))
        assertFalse(EventPreFilter.mayMatch(rules, listOf("广告"), null))
    }

    @Test
    fun givenExactRule_whenEventHasCountdownText_thenDiscards() {
        val rules = listOf(rule(TargetType.TEXT, "跳过", MatchMode.EXACT))

        // EXACT 与匹配器口径一致：`跳过 1` != `跳过`。
        // 预筛在这里丢弃是**安全的** —— 因为匹配器同样会判定不命中，
        // 丢弃与否不改变最终结果。
        assertFalse(EventPreFilter.mayMatch(rules, listOf("跳过 1"), null))
    }

    @Test
    fun givenContainsRule_whenEventTextLacksTarget_thenDiscards() {
        val rules = listOf(rule(TargetType.TEXT, "跳过广告", MatchMode.CONTAINS))

        assertFalse(EventPreFilter.mayMatch(rules, listOf("这是一段正文"), null))
    }

    // ==================================================================
    // ⭐ 核心不变式：预筛放行 ⊇ 匹配器命中
    // ==================================================================

    /**
     * 逐条断言：凡 [UiMatcher] 会判为命中的事件文本，预筛**必须**放行。
     *
     * ## 为什么用"枚举真实文案"而不是随机生成
     *
     * 这条不变式（预筛不得比匹配器严格）是这个类存在的安全前提，
     * 但它无法靠穷举输入证明。可行的做法是把**真实出现过的文案**
     * 全部列入 —— 这些正是线上会遇到的形态。
     *
     * 若将来有人给预筛加了更严格的判据（比如再加一个长度下限），
     * 这个测试会立刻失败。这正是它存在的意义。
     */
    @Test
    fun givenRealWorldSkipTexts_whenMatcherHits_thenPreFilterAlwaysPasses() {
        val matcher = UiMatcher()

        // 每条规则配一份"会被它命中的真实文案"
        val cases = listOf(
            rule(TargetType.TEXT, "跳过", MatchMode.PREFIX) to
                listOf("跳过", "跳过 1", "跳过 3", "跳过 5", "跳过广告", "跳过广告 5s", "跳过此广告"),
            rule(TargetType.TEXT, "跳过广告", MatchMode.PREFIX) to
                listOf("跳过广告", "跳过广告 3", "跳过广告 5s"),
            rule(TargetType.TEXT, "跳过", MatchMode.EXACT) to
                listOf("跳过"),
            rule(TargetType.TEXT, "点击跳过", MatchMode.CONTAINS) to
                listOf("点击跳过 3", "点击跳过广告"),
            rule(TargetType.DESCRIPTION, "关闭", MatchMode.EXACT) to
                listOf("关闭"),
        )

        cases.forEach { (rule, texts) ->
            texts.forEach { text ->
                val node = node(text = text)
                val matcherHit = matcher.match(rule, listOf(node)) != null

                // 先确认这组用例确实会被匹配器命中，否则断言没有意义
                assertTrue(
                    "用例本身有误：匹配器未命中「$text」（规则=${rule.targetValue}/${rule.matchMode}）",
                    matcherHit,
                )

                assertTrue(
                    "预筛比匹配器严格，将造成静默漏拦：「$text」" +
                        "（规则=${rule.targetValue}/${rule.matchMode}）",
                    EventPreFilter.mayMatch(listOf(rule), listOf(text), activityName = null),
                )
            }
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private fun rule(
        targetType: TargetType,
        targetValue: String,
        matchMode: MatchMode = MatchMode.EXACT,
    ) = SkipRule(
        name = "测试规则",
        packageName = GLOBAL_RULE_PACKAGE,
        targetType = targetType,
        targetValue = targetValue,
        matchMode = matchMode,
    )

    private fun node(text: String) = com.toaster.noad.core.engine.ui.NodeSnapshot(
        index = 1,
        parentIndex = 0,
        depth = 2,
        viewId = null,
        text = text,
        contentDescription = text,
        className = "android.widget.TextView",
        clickable = true,
        visible = true,
        left = 0,
        top = 0,
        right = 100,
        bottom = 50,
    )
}
