package com.toaster.noad.core.vpn

import com.toaster.noad.core.engine.DomainRuleEngine
import com.toaster.noad.core.model.DomainMatchType
import com.toaster.noad.core.model.DomainPolicy
import com.toaster.noad.core.model.DomainRule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [TrafficFilter] 与 DNS 响应解析的 JVM 单测（阶段 E）。
 *
 * 核心契约：
 * 1. 映射命中 + 黑名单 → DROP；白名单/未命中 → PASS；
 * 2. **无映射 → UNKNOWN_IP（放行）** —— 拦不住的代价远小于误杀；
 * 3. 能从真实格式的 DNS 响应（含压缩指针的 Answer）学会 IP→域名。
 */
class TrafficFilterTest {

    private val engine = DomainRuleEngine.from(
        DomainPolicy(
            blacklist = listOf(
                DomainRule(matchType = DomainMatchType.SUFFIX, pattern = "ads.example.com"),
            ),
            whitelist = listOf(
                DomainRule(matchType = DomainMatchType.SUFFIX, pattern = "safe.ads.example.com"),
            ),
        ),
    )

    private val map = IpDomainMap()
    private val filter = TrafficFilter(engineProvider = { engine }, ipDomainMap = map)

    /** 构造一条带 A 记录的 DNS 响应（Answer 使用压缩指针，与真实响应一致） */
    private fun aResponse(domain: String, ip: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x12); out.write(0x34) // ID
        out.write(0x81); out.write(0x80) // flags：QR=1, RD=1, RA=1
        out.write(0x00); out.write(0x01) // QDCOUNT=1
        out.write(0x00); out.write(0x01) // ANCOUNT=1
        out.write(0x00); out.write(0x00) // NSCOUNT
        out.write(0x00); out.write(0x00) // ARCOUNT

        // Question
        domain.split('.').forEach { label ->
            out.write(label.length)
            label.forEach { out.write(it.code and 0xFF) }
        }
        out.write(0)
        out.write(0x00); out.write(0x01) // QTYPE=A
        out.write(0x00); out.write(0x01) // QCLASS=IN

        // Answer：名字用压缩指针 0xC00C（指向 question 的域名起点）
        out.write(0xC0); out.write(0x0C)
        out.write(0x00); out.write(0x01) // TYPE=A
        out.write(0x00); out.write(0x01) // CLASS=IN
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0x2C) // TTL=300
        out.write(0x00); out.write(0x04) // RDLENGTH=4
        ip.split('.').forEach { out.write(it.toInt()) }
        return out.toByteArray()
    }

    @Test
    fun givenBlacklistedDomainMappedIp_whenDecide_thenDrop() {
        map.record(filter.ipToInt("93.184.216.34"), "tracker.ads.example.com")
        assertEquals(TrafficFilter.Verdict.DROP, filter.decide(filter.ipToInt("93.184.216.34")))
    }

    @Test
    fun givenWhitelistedDomainMappedIp_whenDecide_thenPass() {
        map.record(filter.ipToInt("1.2.3.4"), "safe.ads.example.com")
        assertEquals(TrafficFilter.Verdict.PASS, filter.decide(filter.ipToInt("1.2.3.4")))
    }

    @Test
    fun givenUnknownDomainMappedIp_whenDecide_thenPass() {
        map.record(filter.ipToInt("5.6.7.8"), "normal.example.org")
        assertEquals(TrafficFilter.Verdict.PASS, filter.decide(filter.ipToInt("5.6.7.8")))
    }

    @Test
    fun givenUnmappedIp_whenDecide_thenUnknownNotDrop() {
        // ★ 安全边界：无映射必须放行（直连/DoH/过期都是常态，丢弃= 断网）
        assertEquals(
            TrafficFilter.Verdict.UNKNOWN_IP,
            filter.decide(filter.ipToInt("203.0.113.9")),
        )
    }

    @Test
    fun givenRealResponseFormat_whenLearn_thenMapPopulated() {
        filter.learnFromDnsResponse(aResponse("ads.example.com", "93.184.216.34"))

        assertEquals("ads.example.com", map.lookup(filter.ipToInt("93.184.216.34")))
    }

    @Test
    fun givenLearnedBlacklistedIp_whenDecide_thenDrop() {
        filter.learnFromDnsResponse(aResponse("tracker.ads.example.com", "10.20.30.40"))

        assertEquals(TrafficFilter.Verdict.DROP, filter.decide(filter.ipToInt("10.20.30.40")))
    }

    @Test
    fun givenResponseWithoutAnswer_whenLearn_thenNothingRecorded() {
        val noAnswer = aResponse("ads.example.com", "93.184.216.34").copyOf()
        noAnswer[7] = 0x00 // ANCOUNT=0
        filter.learnFromDnsResponse(noAnswer)
        assertEquals(0, map.size)
    }

    @Test
    fun givenGarbagePayload_whenLearn_thenNoCrash() {
        filter.learnFromDnsResponse(ByteArray(4))
        assertEquals(0, map.size)
    }

    @Test
    fun givenInvalidDottedIp_whenIpToInt_thenZero() {
        assertEquals(0, filter.ipToInt("not-an-ip"))
        assertEquals(0, filter.ipToInt("1.2.3"))
        assertEquals(0, filter.ipToInt("256.1.1.1"))
    }

    @Test
    fun givenValidDottedIp_whenIpToInt_thenPackedCorrectly() {
        assertEquals(0x01020304, filter.ipToInt("1.2.3.4"))
        assertEquals(0x5DB8D822, filter.ipToInt("93.184.216.34"))
    }
}
