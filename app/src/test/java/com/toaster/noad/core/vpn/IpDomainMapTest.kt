package com.toaster.noad.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [IpDomainMap] 的 JVM 单测（阶段 E 的映射表）。
 *
 * 时钟注入（项目惯例），过期语义可确定性断言。
 */
class IpDomainMapTest {

    private var now = 1_000_000L
    private val map = IpDomainMap(clock = { now }, ttlMs = 300_000L)

    private val ip: Int = 0x5DB8D822 // 93.184.216.34

    @Test
    fun givenRecorded_whenLookup_thenDomainReturned() {
        map.record(ip, "ads.example.com")
        assertEquals("ads.example.com", map.lookup(ip))
    }

    @Test
    fun givenUnknownIp_whenLookup_thenNull() {
        assertNull(map.lookup(0x01020304))
    }

    @Test
    fun givenExpiredTtl_whenLookup_thenNull() {
        map.record(ip, "ads.example.com")
        now += 300_000L // 恰好到达 TTL（>= 语义过期）
        assertNull(map.lookup(ip))
    }

    @Test
    fun givenWithinTtl_whenLookup_thenStillValid() {
        map.record(ip, "ads.example.com")
        now += 299_999L
        assertEquals("ads.example.com", map.lookup(ip))
    }

    @Test
    fun givenSameIpRecordedAgain_whenLookup_thenLatestDomainWins() {
        // CDN 复用 IP 的场景：同一 IP 先后属于不同域名，取最新
        map.record(ip, "first.example.com")
        map.record(ip, "second.example.com")
        assertEquals("second.example.com", map.lookup(ip))
    }

    @Test
    fun givenBlankDomain_whenRecord_thenIgnored() {
        map.record(ip, "   ")
        assertNull(map.lookup(ip))
        assertEquals(0, map.size)
    }

    @Test
    fun givenMultipleEntries_whenPrune_thenExpiredRemoved() {
        map.record(0x01010101, "a.example.com")
        now += 100_000L
        map.record(0x02020202, "b.example.com")

        now += 200_001L // 第一条过期（300_001ms），第二条仍有效（200_001ms）
        map.prune()

        assertNull(map.lookup(0x01010101))
        assertEquals("b.example.com", map.lookup(0x02020202))
    }

    @Test
    fun givenClear_whenLookup_thenNull() {
        map.record(ip, "ads.example.com")
        map.clear()
        assertNull(map.lookup(ip))
    }

    @Test
    fun givenIpToString_whenConvert_thenDotted() {
        // 与 Ip4UdpPacket.ipToString 口径一致（映射表的日志展示用）
        assertEquals("93.184.216.34", Ip4UdpPacket.ipToString(ip))
    }
}
