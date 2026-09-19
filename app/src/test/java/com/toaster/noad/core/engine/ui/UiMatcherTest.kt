package com.toaster.noad.core.engine.ui

import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UiMatcher] 单元测试。
 *
 * 覆盖重点：**误点是 S1 最严重的缺陷形态**，
 * 因此除了"能否匹配到"，更要验证"不该匹配时确实不匹配"。
 */
class UiMatcherTest {

    private val matcher = UiMatcher()

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    private fun node(
        index: Int = 0,
        depth: Int = 0,
        viewId: String? = null,
        text: String? = null,
        desc: String? = null,
        className: String? = null,
        clickable: Boolean = false,
        visible: Boolean = true,
        left: Int = 0,
        top: Int = 0,
        right: Int = 100,
        bottom: Int = 50,
    ) = NodeSnapshot(
        index = index,
        parentIndex = -1,
        depth = depth,
        viewId = viewId,
        text = text,
        contentDescription = desc,
        className = className,
        clickable = clickable,
        visible = visible,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    private fun rule(
        id: Long = 1L,
        name: String = "test",
        targetType: TargetType = TargetType.TEXT,
        targetValue: String = "跳过",
        matchMode: MatchMode = MatchMode.EXACT,
        priority: Int = 0,
    ) = SkipRule(
        id = id,
        name = name,
        packageName = "com.example",
        targetType = targetType,
        targetValue = targetValue,
        matchMode = matchMode,
        priority = priority,
    )

    // ------------------------------------------------------------------
    // TEXT 匹配
    // ------------------------------------------------------------------

    @Test
    fun `given exact match rule when node text equals target then matched`() {
        val nodes = listOf(node(text = "跳过"))

        val result = matcher.match(rule(targetValue = "跳过", matchMode = MatchMode.EXACT), nodes)

        assertNotNull(result)
        assertEquals("跳过", result!!.node.text)
        assertEquals(MatchResult.CONFIDENCE_TEXT, result.confidence)
    }

    @Test
    fun `given exact match rule when node text merely contains target then not matched`() {
        // 「跳过广告，立即观看正片」包含「跳过」，但 EXACT 模式下不应命中。
        // 若这里命中，用户点「跳过广告」的正文按钮会被误触。
        val nodes = listOf(node(text = "跳过广告，立即观看正片"))

        val result = matcher.match(rule(targetValue = "跳过", matchMode = MatchMode.EXACT), nodes)

        assertNull(result)
    }

    @Test
    fun `given contains rule when node text contains target then matched`() {
        val nodes = listOf(node(text = "跳过广告，立即观看正片"))

        val result = matcher.match(rule(targetValue = "跳过", matchMode = MatchMode.CONTAINS), nodes)

        assertNotNull(result)
    }

    @Test
    fun `given regex rule when node text matches pattern then matched`() {
        val nodes = listOf(node(text = "跳过 3s"))

        val result = matcher.match(
            rule(targetValue = """跳过\s*\d+s""", matchMode = MatchMode.REGEX),
            nodes,
        )

        assertNotNull(result)
    }

    @Test
    fun `given invalid regex rule when matching then returns null without throwing`() {
        // 一条坏规则不应影响其余规则，更不应让无障碍服务崩溃
        val nodes = listOf(node(text = "跳过"))

        val result = matcher.match(
            rule(targetValue = "[unclosed", matchMode = MatchMode.REGEX),
            nodes,
        )

        assertNull(result)
    }

    @Test
    fun `given text rule when matching then case insensitive`() {
        val nodes = listOf(node(text = "SKIP"))

        val result = matcher.match(rule(targetValue = "skip", matchMode = MatchMode.EXACT), nodes)

        assertNotNull(result)
    }

    @Test
    fun `given text rule when node invisible then not matched`() {
        // 不可见节点即便文本命中也不能点 —— 它的坐标可能已经失效
        val nodes = listOf(node(text = "跳过", visible = false))

        val result = matcher.match(rule(targetValue = "跳过"), nodes)

        assertNull(result)
    }

    @Test
    fun `given text rule when target value blank then not matched`() {
        val nodes = listOf(node(text = "跳过"))

        val result = matcher.match(rule(targetValue = "   "), nodes)

        assertNull(result)
    }

    @Test
    fun `given description rule when content description matches then matched`() {
        // 图片按钮通常只有 contentDescription
        val nodes = listOf(node(desc = "关闭广告"))

        val result = matcher.match(
            rule(targetType = TargetType.DESCRIPTION, targetValue = "关闭广告"),
            nodes,
        )

        assertNotNull(result)
        assertEquals(MatchResult.CONFIDENCE_DESCRIPTION, result!!.confidence)
    }

    // ------------------------------------------------------------------
    // VIEW_ID 匹配
    // ------------------------------------------------------------------

    @Test
    fun `given view id rule when full id matches then matched with highest confidence`() {
        val nodes = listOf(node(viewId = "com.example:id/btn_skip"))

        val result = matcher.match(
            rule(targetType = TargetType.VIEW_ID, targetValue = "com.example:id/btn_skip"),
            nodes,
        )

        assertNotNull(result)
        assertEquals(MatchResult.CONFIDENCE_VIEW_ID, result!!.confidence)
    }

    @Test
    fun `given view id rule when only short name given then matched`() {
        // 规则只写短名，换包名后依然可用 —— 这是刻意支持的能力
        val nodes = listOf(node(viewId = "com.example:id/btn_skip"))

        val result = matcher.match(
            rule(targetType = TargetType.VIEW_ID, targetValue = "btn_skip"),
            nodes,
        )

        assertNotNull(result)
    }

    @Test
    fun `given view id rule when id differs then not matched`() {
        val nodes = listOf(node(viewId = "com.example:id/btn_share"))

        val result = matcher.match(
            rule(targetType = TargetType.VIEW_ID, targetValue = "btn_skip"),
            nodes,
        )

        assertNull(result)
    }

    // ------------------------------------------------------------------
    // COORDINATE 匹配
    // ------------------------------------------------------------------

    @Test
    fun `given coordinate rule when point inside node then reuses that node`() {
        val nodes = listOf(node(left = 0, top = 0, right = 100, bottom = 50, text = "跳过"))

        val result = matcher.match(
            rule(targetType = TargetType.COORDINATE, targetValue = "50,25"),
            nodes,
        )

        assertNotNull(result)
        // 坐标落在节点内时应复用该节点，使日志可读
        assertEquals("跳过", result!!.node.text)
        assertEquals(MatchResult.CONFIDENCE_COORDINATE, result.confidence)
    }

    @Test
    fun `given coordinate rule when point outside all nodes then synthesizes node`() {
        val nodes = listOf(node(left = 0, top = 0, right = 10, bottom = 10))

        val result = matcher.match(
            rule(targetType = TargetType.COORDINATE, targetValue = "500,800"),
            nodes,
        )

        assertNotNull(result)
        assertEquals(UiMatcher.SYNTHETIC_NODE_INDEX, result!!.node.index)
        assertEquals(500, result.node.centerX)
        assertEquals(800, result.node.centerY)
    }

    @Test
    fun `given coordinate rule when value malformed then not matched`() {
        val nodes = listOf(node())

        assertNull(matcher.match(rule(targetType = TargetType.COORDINATE, targetValue = "abc"), nodes))
        assertNull(matcher.match(rule(targetType = TargetType.COORDINATE, targetValue = "1"), nodes))
        assertNull(matcher.match(rule(targetType = TargetType.COORDINATE, targetValue = "-1,5"), nodes))
    }

    // ------------------------------------------------------------------
    // 深度优先与最佳匹配
    // ------------------------------------------------------------------

    @Test
    fun `given multiple matching nodes then picks shallowest`() {
        // 浅层节点通常是弹窗按钮，深层多为列表项中的同名字段
        val deep = node(index = 2, depth = 8, text = "跳过")
        val shallow = node(index = 1, depth = 2, text = "跳过")

        val result = matcher.match(rule(targetValue = "跳过"), listOf(deep, shallow))

        assertEquals(1, result!!.node.index)
    }

    @Test
    fun `given several rules when matching then view id wins over text`() {
        val byId = node(index = 1, depth = 5, viewId = "com.example:id/btn_skip")
        val byText = node(index = 2, depth = 1, text = "跳过")

        val rules = listOf(
            rule(id = 1, name = "text rule", targetType = TargetType.TEXT, targetValue = "跳过"),
            rule(id = 2, name = "id rule", targetType = TargetType.VIEW_ID, targetValue = "btn_skip"),
        )

        val result = matcher.matchBest(rules, listOf(byId, byText))

        // VIEW_ID 置信度 400 > TEXT 300，即使 TEXT 节点更浅也应选中 VIEW_ID
        assertEquals("id rule", result!!.rule.name)
        assertEquals(1, result.node.index)
    }

    @Test
    fun `given equal confidence when matching then higher priority wins`() {
        val nodes = listOf(node(index = 1, text = "跳过"))

        val rules = listOf(
            rule(id = 1, name = "low", targetValue = "跳过", priority = 1),
            rule(id = 2, name = "high", targetValue = "跳过", priority = 100),
        )

        val result = matcher.matchBest(rules, nodes)

        assertEquals("high", result!!.rule.name)
    }

    @Test
    fun `given no matching rule when matching then returns null`() {
        val nodes = listOf(node(text = "立即购买"))

        val result = matcher.matchBest(listOf(rule(targetValue = "跳过")), nodes)

        assertNull(result)
    }

    @Test
    fun `given empty node list when matching then returns null`() {
        assertNull(matcher.match(rule(), emptyList()))
        assertNull(matcher.matchBest(listOf(rule()), emptyList()))
    }

    // ------------------------------------------------------------------
    // NodeSnapshot 辅助属性
    // ------------------------------------------------------------------

    @Test
    fun `given node with bounds when reading center then returns midpoint`() {
        val n = node(left = 10, top = 20, right = 110, bottom = 70)

        assertEquals(60, n.centerX)
        assertEquals(45, n.centerY)
        assertTrue(n.hasArea)
    }

    @Test
    fun `given zero size node when checking area then false`() {
        val n = node(left = 10, top = 10, right = 10, bottom = 10)

        assertEquals(false, n.hasArea)
    }

    @Test
    fun `given node with both text and description when reading candidates then both included`() {
        val n = node(text = "跳过", desc = "关闭")

        assertEquals(listOf("跳过", "关闭"), n.textCandidates)
    }

    @Test
    fun `given node with blank text when reading candidates then blank excluded`() {
        val n = node(text = "   ", desc = "关闭")

        assertEquals(listOf("关闭"), n.textCandidates)
    }
}
