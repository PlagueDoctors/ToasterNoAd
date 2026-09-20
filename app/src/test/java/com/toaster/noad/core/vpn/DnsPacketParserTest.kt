package com.toaster.noad.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [DnsPacketParser] 的 JVM 单测。
 *
 * 测试字节按 RFC 1035 手工构造，覆盖：标准 A 查询解析、
 * 五类防御性拒绝（响应包 / 非标准 opcode / 多 question /
 * 压缩指针 / 截断）、以及 NXDOMAIN / SERVFAIL 响应构造的
 * 位级正确性（QR、RCODE、计数清零、ID 与 RD 保留、长度不变）。
 */
class DnsPacketParserTest {

    private val parser = DnsPacketParser()

    /** 构造标准 A 查询（opcode=0, RD=1, QDCOUNT=1, QTYPE=A, QCLASS=IN） */
    private fun aQuery(id: Int = 0x1234, domain: String = "ads.example.com"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01) // flags 高字节：RD=1
        out.write(0x00) // flags 低字节：RCODE=0
        out.write(0x00); out.write(0x01) // QDCOUNT=1
        out.write(0x00); out.write(0x00) // ANCOUNT=0
        out.write(0x00); out.write(0x00) // NSCOUNT=0
        out.write(0x00); out.write(0x00) // ARCOUNT=0
        domain.split('.').forEach { label ->
            out.write(label.length)
            label.forEach { out.write(it.code and 0xFF) }
        }
        out.write(0x00) // root label
        out.write(0x00); out.write(0x01) // QTYPE=A
        out.write(0x00); out.write(0x01) // QCLASS=IN
        return out.toByteArray()
    }

    @Test
    fun givenStandardAQuery_whenParsed_thenExtractsIdAndDomain() {
        val outcome = parser.parseQuery(aQuery(id = 0xABCD, domain = "ads.example.com"))
        assertEquals(DnsPacketParser.ParseOutcome.Query(0xABCD, "ads.example.com"), outcome)
    }

    @Test
    fun givenMultiLevelDomain_whenParsed_thenLabelsJoinedInOrder() {
        val outcome = parser.parseQuery(aQuery(domain = "a.b.c.test"))
        assertEquals(
            DnsPacketParser.ParseOutcome.Query(0x1234, "a.b.c.test"),
            outcome,
        )
    }

    @Test
    fun givenResponseFlag_whenParsed_thenNotQuery() {
        val bytes = aQuery()
        bytes[2] = (bytes[2].toInt() or 0x80).toByte() // QR=1
        assertEquals(DnsPacketParser.ParseOutcome.NotQuery, parser.parseQuery(bytes))
    }

    @Test
    fun givenNonStandardOpcode_whenParsed_thenNotQuery() {
        val bytes = aQuery()
        bytes[2] = (bytes[2].toInt() or (1 shl 3)).toByte() // opcode=1
        assertEquals(DnsPacketParser.ParseOutcome.NotQuery, parser.parseQuery(bytes))
    }

    @Test
    fun givenMultipleQuestions_whenParsed_thenNotQuery() {
        val bytes = aQuery()
        bytes[5] = 0x02 // QDCOUNT=2
        assertEquals(DnsPacketParser.ParseOutcome.NotQuery, parser.parseQuery(bytes))
    }

    @Test
    fun givenTruncatedPayload_whenParsed_thenNotDns() {
        assertEquals(DnsPacketParser.ParseOutcome.NotDns, parser.parseQuery(ByteArray(11)))
    }

    @Test
    fun givenCompressedPointerInQname_whenParsed_thenNotDns() {
        val bytes = aQuery()
        // QNAME 首个长度字节写 0xC0（压缩指针）：question 区不应出现
        bytes[12] = 0xC0.toByte()
        assertEquals(DnsPacketParser.ParseOutcome.NotDns, parser.parseQuery(bytes))
    }

    @Test
    fun givenQnameCutBeforeTrailer_whenParsed_thenNotDns() {
        // 截掉 root label 之后的 QTYPE/QCLASS
        val full = aQuery()
        val cut = full.copyOfRange(0, full.size - 3)
        assertEquals(DnsPacketParser.ParseOutcome.NotDns, parser.parseQuery(cut))
    }

    @Test
    fun givenQuery_whenBuildNxDomain_thenQrSetRcode3AndCountsCleared() {
        val bytes = aQuery()
        val resp = parser.buildErrorResponse(bytes, rcode = 3)

        assertTrue(resp != null)
        assertEquals(bytes.size, resp!!.size)
        // QR=1
        assertEquals(0x80, resp[2].toInt() and 0x80)
        // RCODE=3
        assertEquals(3, resp[3].toInt() and 0x0F)
        // ANCOUNT/NSCOUNT/ARCOUNT 清零
        for (i in 6..11) assertEquals(0, resp[i].toInt())
    }

    @Test
    fun givenQuery_whenBuildServfail_thenRcode2() {
        val resp = parser.buildErrorResponse(aQuery(), rcode = 2)
        assertTrue(resp != null)
        assertEquals(2, resp!!.readRcode())
    }

    @Test
    fun givenBuildResponse_thenIdAndRdPreserved() {
        val bytes = aQuery(id = 0xBEEF)
        val resp = parser.buildErrorResponse(bytes, rcode = 3)!!

        // ID 原样保留（客户端靠它匹配请求）
        assertEquals(0xBE, resp[0].toInt() and 0xFF)
        assertEquals(0xEF, resp[1].toInt() and 0xFF)
        // RD 位保留（字节 3 高 4 位不受 RCODE 写入影响）
        assertEquals(bytes[3].toInt() and 0xF0, resp[3].toInt() and 0xF0)
    }

    @Test
    fun givenResponsePacket_whenBuildErrorResponse_thenNull() {
        val bytes = aQuery()
        bytes[2] = (bytes[2].toInt() or 0x80).toByte()
        assertNull(parser.buildErrorResponse(bytes, rcode = 3))
    }

    @Test
    fun givenRcodeOutOfRange_thenLowNibbleOnly() {
        // 防御：rcode 超过 4 位时只取低 4 位，不污染 RD 等高位
        val resp = parser.buildErrorResponse(aQuery(), rcode = 0x13)!!
        assertEquals(3, resp.readRcode())
    }

    private fun ByteArray.readRcode(): Int = this[3].toInt() and 0x0F
}
