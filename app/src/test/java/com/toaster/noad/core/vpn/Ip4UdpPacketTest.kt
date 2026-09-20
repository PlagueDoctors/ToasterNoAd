package com.toaster.noad.core.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [Ip4UdpPacket] 的字节级 JVM 单测。
 *
 * 测试包按 IPv4/UDP 规范手工构造，锁定四类契约：
 * 解析定位（端口/payload 偏移）、防御性拒绝（版本/协议/截断/IHL）、
 * 响应构造（地址端口交换 + payload 替换 + 长度改写）、
 * IP 头校验和重算的正确性。
 */
class Ip4UdpPacketTest {

    private val dnsPayload = byteArrayOf(
        0x12, 0x34, // ID
        0x01, 0x00, // flags: RD=1
        0x00, 0x01, // QDCOUNT=1
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x03, 'a'.code.toByte(), 0x04, 'b'.code.toByte(), // a.b.test 的前两级
    )

    /** 构造 IPv4/UDP 数据报（校验和先按 0 填，需要时用真实算法回填） */
    private fun udpPacket(
        sourceIp: String = "10.0.0.1",
        destinationIp: String = "10.0.0.1",
        sourcePort: Int = 41234,
        destinationPort: Int = 53,
        payload: ByteArray = dnsPayload,
        protocol: Int = 17,
        version: Int = 4,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val totalLength = 20 + 8 + payload.size

        val firstByte = (version shl 4) or 5
        out.write(firstByte)
        out.write(0) // TOS
        out.write((totalLength shr 8) and 0xFF)
        out.write(totalLength and 0xFF)
        out.write(0); out.write(0) // ID
        out.write(0x40); out.write(0) // flags=DF
        out.write(64) // TTL
        out.write(protocol)
        out.write(0); out.write(0) // checksum 占位
        sourceIp.split('.').forEach { out.write(it.toInt()) }
        destinationIp.split('.').forEach { out.write(it.toInt()) }

        out.write((sourcePort shr 8) and 0xFF)
        out.write(sourcePort and 0xFF)
        out.write((destinationPort shr 8) and 0xFF)
        out.write(destinationPort and 0xFF)
        out.write(((8 + payload.size) shr 8) and 0xFF)
        out.write((8 + payload.size) and 0xFF)
        out.write(0); out.write(0) // UDP checksum=0（合法）

        out.write(payload)
        val bytes = out.toByteArray()
        // 回填真实 IP 校验和（查询包自身也要过校验和这条逻辑）
        val checksum = Ip4UdpPacket.headerChecksum(bytes, 20)
        bytes[10] = ((checksum shr 8) and 0xFF).toByte()
        bytes[11] = (checksum and 0xFF).toByte()
        return bytes
    }

    private fun readUnsignedShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    @Test
    fun givenValidUdpDatagram_whenParsed_thenPortsAndPayloadLocated() {
        val frame = Ip4UdpPacket.parseUdpDatagram(udpPacket(), udpPacket().size)!!

        assertEquals(41234, frame.sourcePort)
        assertEquals(53, frame.destinationPort)
        assertEquals(28, frame.payloadOffset)
        assertEquals(dnsPayload.size, frame.payloadLength)
    }

    @Test
    fun givenNonIpv4_whenParsed_thenNull() {
        val bytes = udpPacket(version = 6)
        assertNull(Ip4UdpPacket.parseUdpDatagram(bytes, bytes.size))
    }

    @Test
    fun givenNonUdpProtocol_whenParsed_thenNull() {
        val bytes = udpPacket(protocol = 6) // TCP
        assertNull(Ip4UdpPacket.parseUdpDatagram(bytes, bytes.size))
    }

    @Test
    fun givenTruncatedPacket_whenParsed_thenNull() {
        val bytes = udpPacket()
        assertNull(Ip4UdpPacket.parseUdpDatagram(bytes, 20)) // 只有 IP 头
        assertNull(Ip4UdpPacket.parseUdpDatagram(bytes, 10)) // 更短
    }

    @Test
    fun givenDeclaredLengthExceedsBuffer_whenParsed_thenNull() {
        val bytes = udpPacket()
        bytes[2] = 0xFF.toByte() // total length 高字节改大：声明超出实际
        bytes[3] = 0xFF.toByte()
        assertNull(Ip4UdpPacket.parseUdpDatagram(bytes, bytes.size))
    }

    @Test
    fun givenQuery_whenBuildResponse_thenAddressesAndPortsSwapped() {
        val query = udpPacket(sourcePort = 41234, destinationPort = 53)
        val response = Ip4UdpPacket.buildResponsePacket(query, query.size, dnsPayload)!!

        assertEquals(53, readUnsignedShort(response, 20)) // UDP src port（原 dst）
        assertEquals(41234, readUnsignedShort(response, 22)) // UDP dst port（原 src）
    }

    @Test
    fun givenQuery_whenBuildResponse_thenPayloadReplacedAndLengthsUpdated() {
        val query = udpPacket()
        val longerResponse = ByteArray(dnsPayload.size + 6) { 0x55 }
        val response = Ip4UdpPacket.buildResponsePacket(query, query.size, longerResponse)!!

        val totalLength = readUnsignedShort(response, 2)
        assertEquals(20 + 8 + longerResponse.size, totalLength)
        assertEquals(response.size, totalLength)
        assertEquals(8 + longerResponse.size, readUnsignedShort(response, 24)) // UDP length
        assertArrayEquals(longerResponse, response.copyOfRange(28, 28 + longerResponse.size))
    }

    @Test
    fun givenResponsePacket_thenIpChecksumValid() {
        val query = udpPacket()
        val response = Ip4UdpPacket.buildResponsePacket(query, query.size, dnsPayload)!!

        // 重算整个 IP 头（含已写入的校验和字段）应为 0（补数和的校验性质）
        val stored = readUnsignedShort(response, 10)
        response[10] = 0.toByte(); response[11] = 0.toByte()
        val recomputed = Ip4UdpPacket.headerChecksum(response, 20)
        assertEquals(stored, recomputed)
    }

    @Test
    fun givenResponsePacket_thenUdpChecksumZeroed() {
        val response = Ip4UdpPacket.buildResponsePacket(udpPacket(), udpPacket().size, dnsPayload)!!
        assertEquals(0, readUnsignedShort(response, 26)) // UDP checksum 偏移 20+6
    }

    @Test
    fun givenMalformedQuery_whenBuildResponse_thenNull() {
        val garbage = ByteArray(30) { 0x11 }
        assertNull(Ip4UdpPacket.buildResponsePacket(garbage, garbage.size, dnsPayload))
    }

    @Test
    fun givenRealChecksum_whenHeaderChecksumVerifies_thenFoldedSumIsAllOnes() {
        // 反码校验和的验证性质：对含校验和字段的完整头求和并折叠，
        // 结果应为 0xFFFF（即 ~0xFFFF = 0，校验通过）
        val bytes = udpPacket()
        val sum = run {
            var s = 0L
            var i = 0
            while (i < 20) {
                s += readUnsignedShort(bytes, i)
                s = (s and 0xFFFF) + (s ushr 16)
                i += 2
            }
            s
        }
        assertEquals(0xFFFF, sum.toInt())
        assertTrue(bytes.size > 28)
    }
}
