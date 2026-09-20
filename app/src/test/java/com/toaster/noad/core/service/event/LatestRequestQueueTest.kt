package com.toaster.noad.core.service.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LatestRequestQueue] 的 JVM 单测 —— 锁定 S1 性能重构的**合并语义内核**。
 *
 * 契约只有四条，但每一条都直接决定「启动期数十次事件」会被压成几次扫描：
 * 覆盖（只保留最新）、取走即清空、空时返回 null、取走后可再次投递。
 */
class LatestRequestQueueTest {

    private val queue = LatestRequestQueue<String>()

    @Test
    fun givenEmpty_whenPoll_thenNull() {
        assertNull(queue.poll())
        assertFalse(queue.hasPending)
    }

    @Test
    fun givenOffered_whenPoll_thenReturnsItemThenClears() {
        queue.offer("a")
        assertTrue(queue.hasPending)
        assertEquals("a", queue.poll())
        assertNull(queue.poll())
        assertFalse(queue.hasPending)
    }

    @Test
    fun givenMultipleOffers_whenPoll_thenReturnsLatestOnly() {
        // 这是合并语义的**核心断言**：连续 3 次事件只留下最后一个，
        // 等价于「只对最新界面扫描一次」（扫描对象是界面快照而非事件）
        queue.offer("first")
        queue.offer("second")
        queue.offer("third")

        assertEquals("third", queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun givenPolled_whenOfferAgain_thenNewItemAvailable() {
        queue.offer("a")
        queue.poll()
        queue.offer("b")
        assertEquals("b", queue.poll())
    }
}
