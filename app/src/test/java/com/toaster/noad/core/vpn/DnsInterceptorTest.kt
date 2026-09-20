package com.toaster.noad.core.vpn

import com.toaster.noad.core.engine.DomainRuleEngine
import com.toaster.noad.core.model.DomainMatchType
import com.toaster.noad.core.model.DomainPolicy
import com.toaster.noad.core.model.DomainRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [DnsInterceptor] 的 JVM 单测（方案 §4.2 判定语义）。
 *
 * 锁定的核心契约：
 * 1. 黑名单命中 → Block，响应为本地构造的 NXDOMAIN（RCODE=3）；
 * 2. **白名单优先**：同时命中黑白名单时 Forward；
 * 3. 未命中 → Forward，**原查询字节原样透传**；
 * 4. 非标准查询 / 畸形 → Ignore。
 */
class DnsInterceptorTest {

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

    private val interceptor = DnsInterceptor(engineProvider = { engine })

    /** 构造标准 A 查询（RD=1, QDCOUNT=1, QTYPE=A, QCLASS=IN） */
    private fun aQuery(domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x12); out.write(0x34) // ID
        out.write(0x01); out.write(0x00) // flags: RD=1
        out.write(0x00); out.write(0x01) // QDCOUNT=1
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        domain.split('.').forEach { label ->
            out.write(label.length)
            label.forEach { out.write(it.code and 0xFF) }
        }
        out.write(0)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    @Test
    fun givenBlacklistedDomain_whenDecide_thenBlockWithNxDomain() {
        val query = aQuery("tracker.ads.example.com")
        val decision = interceptor.decide(query)

        assertTrue(decision is DnsInterceptor.Decision.Block)
        val response = (decision as DnsInterceptor.Decision.Block).responsePayload
        // 本地响应：QR=1、RCODE=3、与查询等长
        assertEquals(query.size, response.size)
        assertEquals(0x80, response[2].toInt() and 0x80)
        assertEquals(3, response[3].toInt() and 0x0F)
    }

    @Test
    fun givenWhitelistedSubdomain_whenDecide_thenForwardDespiteBlacklist() {
        // safe.ads.example.com 命中白名单后缀，即使 ads.example.com 在黑名单
        val decision = interceptor.decide(aQuery("safe.ads.example.com"))
        assertTrue(decision is DnsInterceptor.Decision.Forward)
    }

    @Test
    fun givenUnknownDomain_whenDecide_thenForwardOriginalBytes() {
        val query = aQuery("example.org")
        val decision = interceptor.decide(query)

        assertTrue(decision is DnsInterceptor.Decision.Forward)
        // 转发的是原查询字节本身（上游要靠原 ID/QNAME 应答）
        assertArrayEquals(query, (decision as DnsInterceptor.Decision.Forward).queryPayload)
    }

    @Test
    fun givenExactBlacklistedDomain_whenDecide_thenBlock() {
        val decision = interceptor.decide(aQuery("ads.example.com"))
        assertTrue(decision is DnsInterceptor.Decision.Block)
    }

    @Test
    fun givenResponsePacket_whenDecide_thenIgnore() {
        val bytes = aQuery("example.org")
        bytes[2] = (bytes[2].toInt() or 0x80).toByte() // QR=1
        assertEquals(DnsInterceptor.Decision.Ignore, interceptor.decide(bytes))
    }

    @Test
    fun givenGarbageBytes_whenDecide_thenIgnore() {
        assertEquals(DnsInterceptor.Decision.Ignore, interceptor.decide(ByteArray(8)))
    }
}
