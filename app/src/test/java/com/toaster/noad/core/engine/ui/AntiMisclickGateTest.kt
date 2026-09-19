package com.toaster.noad.core.engine.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AntiMisclickGate] 单元测试。
 *
 * 覆盖重点：闸门的作用是**阻止**行为，因此测试主要断言"应当被拒"的场景。
 * 一个失效的闸门（总是放行）比没有闸门更危险 —— 它给人虚假的安全感。
 */
class AntiMisclickGateTest {

    private val ruleCooldown = 3_000L
    private val nodeCooldown = 5_000L
    private val throttle = 400L

    private fun gate() = AntiMisclickGate(
        ruleCooldownMs = ruleCooldown,
        nodeCooldownMs = nodeCooldown,
        globalThrottleMs = throttle,
    )

    // ------------------------------------------------------------------
    // 全局节流
    // ------------------------------------------------------------------

    @Test
    fun `given first click when acquiring then allowed`() {
        assertTrue(gate().tryAcquire(ruleId = 1L, nodeKey = "a", now = 100_000L))
    }

    @Test
    fun `given second click within throttle window when acquiring then rejected`() {
        val gate = gate()

        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "a", now = 100_000L))
        // 同一时刻的不同节点也必须被全局节流拦下
        assertFalse(gate.tryAcquire(ruleId = 2L, nodeKey = "b", now = 100_000L))
        assertFalse(gate.tryAcquire(ruleId = 2L, nodeKey = "b", now = 100_000L + throttle - 1))
    }

    @Test
    fun `given click after throttle window when acquiring then allowed`() {
        val gate = gate()

        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "a", now = 100_000L))
        assertTrue(gate.tryAcquire(ruleId = 2L, nodeKey = "b", now = 100_000L + throttle))
    }

    // ------------------------------------------------------------------
    // 同一规则冷却
    // ------------------------------------------------------------------

    @Test
    fun `given same rule within cooldown when acquiring then rejected`() {
        val gate = gate()

        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "a", now = 100_000L))
        // 越过全局节流但仍落在规则冷却窗口内
        assertFalse(gate.tryAcquire(ruleId = 1L, nodeKey = "b", now = 100_000L + throttle))
    }

    @Test
    fun `given same rule after cooldown when acquiring then allowed`() {
        val gate = gate()

        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "a", now = 100_000L))
        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "b", now = 100_000L + ruleCooldown))
    }

    @Test
    fun `given different rules within throttle gap when acquiring then second allowed after gap`() {
        // 不同规则之间不受规则冷却影响，只受全局节流约束
        val gate = gate()

        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "a", now = 100_000L))
        assertTrue(gate.tryAcquire(ruleId = 2L, nodeKey = "b", now = 100_000L + throttle))
    }

    // ------------------------------------------------------------------
    // 同一节点去重
    // ------------------------------------------------------------------

    @Test
    fun `given same node within node cooldown when acquiring then rejected`() {
        val gate = gate()

        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "same", now = 100_000L))
        // 用不同规则绕开规则冷却，但仍应被节点冷却拦下
        assertFalse(gate.tryAcquire(ruleId = 2L, nodeKey = "same", now = 100_000L + throttle))
        assertFalse(gate.tryAcquire(ruleId = 3L, nodeKey = "same", now = 100_000L + nodeCooldown - 1))
    }

    @Test
    fun `given same node after node cooldown when acquiring then allowed`() {
        val gate = gate()

        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "same", now = 100_000L))
        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "same", now = 100_000L + nodeCooldown))
    }

    // ------------------------------------------------------------------
    // 重置
    // ------------------------------------------------------------------

    @Test
    fun `given reset when acquiring same rule immediately then allowed`() {
        val gate = gate()

        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "a", now = 100_000L))
        gate.reset()

        // 新界面上同一规则应可立即生效，否则会出现"某个应用偶发点不掉"
        assertTrue(gate.tryAcquire(ruleId = 1L, nodeKey = "a", now = 100_000L))
    }

    @Test
    fun `given reset when reading tracked counts then empty`() {
        val gate = gate()
        gate.tryAcquire(ruleId = 1L, nodeKey = "a", now = 100_000L)

        gate.reset()

        assertEquals(0, gate.trackedRuleCount)
        assertEquals(0, gate.trackedNodeCount)
    }

    // ------------------------------------------------------------------
    // 防泄漏
    // ------------------------------------------------------------------

    @Test
    fun `given many distinct rules when acquiring then old entries pruned`() {
        val gate = gate()

        // 每次点击后推进时间，确保全局节流不阻塞
        var now = 100_000L
        repeat(200) { i ->
            gate.tryAcquire(ruleId = i.toLong(), nodeKey = "node$i", now = now)
            now += throttle
        }

        // 记账不应无限增长：过期条目在每次允许点击时被清理
        assertTrue(
            "rule 记账数应远小于总调用次数，实际 ${gate.trackedRuleCount}",
            gate.trackedRuleCount < 200,
        )
        assertTrue(
            "node 记账数应远小于总调用次数，实际 ${gate.trackedNodeCount}",
            gate.trackedNodeCount < 200,
        )
    }

    // ------------------------------------------------------------------
    // nodeKey 构造
    // ------------------------------------------------------------------

    @Test
    fun `given same node content but different position when building key then keys differ`() {
        val a = nodeSnapshot(index = 1, text = "跳过", centerX = 100, centerY = 200)
        val b = nodeSnapshot(index = 2, text = "跳过", centerX = 300, centerY = 400)

        assertNotEquals(AntiMisclickGate.nodeKey(a), AntiMisclickGate.nodeKey(b))
    }

    @Test
    fun `given identical node content when building key then keys equal`() {
        val a = nodeSnapshot(index = 1, text = "跳过", centerX = 100, centerY = 200)
        // 序号不同但内容一致 —— 序号每次遍历都会重排，不应参与身份判定
        val b = nodeSnapshot(index = 99, text = "跳过", centerX = 100, centerY = 200)

        assertEquals(AntiMisclickGate.nodeKey(a), AntiMisclickGate.nodeKey(b))
    }

    private fun nodeSnapshot(
        index: Int,
        text: String?,
        centerX: Int,
        centerY: Int,
    ) = NodeSnapshot(
        index = index,
        parentIndex = -1,
        depth = 0,
        viewId = null,
        text = text,
        contentDescription = null,
        className = null,
        clickable = false,
        visible = true,
        left = centerX,
        top = centerY,
        right = centerX,
        bottom = centerY,
    )
}
