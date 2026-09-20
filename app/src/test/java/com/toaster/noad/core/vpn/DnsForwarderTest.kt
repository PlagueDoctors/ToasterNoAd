package com.toaster.noad.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * [DnsForwarder] 的 JVM 单测。
 *
 * DatagramSocket 是 JDK 类，可用 **localhost 真实 UDP** 验证收发闭环：
 * 回显服务监听临时端口，构造时注入 [DnsForwarder.dnsPort]（生产恒 53）。
 * protect 回调用 fake 记录，锁定「protect 必须先于任何发包」这一铁律。
 */
class DnsForwarderTest {

    private val query = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
        'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 0,
        0x00, 0x01, 0x00, 0x01,
    )

    /**
     * 启动本地 UDP 回显服务：收到查询后把首字节改为 0xAA 发回。
     * 返回 (socket, 端口)；socket 由调用方 close。
     */
    private fun startEchoServer(): Pair<DatagramSocket, Int> {
        val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        socket.soTimeout = 3_000
        Thread {
            runCatching {
                val buffer = ByteArray(4_096)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                buffer[0] = 0xAA.toByte()
                socket.send(DatagramPacket(buffer, packet.length, packet.address, packet.port))
            }
        }.apply { isDaemon = true; start() }
        return socket to socket.localPort
    }

    @Test
    fun givenEchoServer_whenForward_thenReturnsResponseBytes() {
        val (server, port) = startEchoServer()
        try {
            val forwarder = DnsForwarder(
                protectSocket = { true },
                socketTimeoutMs = 2_000,
                dnsPort = port,
            )

            val response = forwarder.forward(query, InetAddress.getByName("127.0.0.1"))

            assertTrue(response != null)
            assertEquals(0xAA.toByte(), response!![0])
            // 响应是回显的查询：长度一致、ID 原样
            assertEquals(query.size, response.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun givenProtectRejected_whenForward_thenNullWithoutSending() {
        var socketObserved: DatagramSocket? = null
        val forwarder = DnsForwarder(
            protectSocket = { socket -> socketObserved = socket; false },
            socketTimeoutMs = 300,
            dnsPort = 53,
        )

        val response = forwarder.forward(query, InetAddress.getByName("127.0.0.1"))

        // protect 拒绝 = 第一个包发出前终止（铁律：绝不发未受保护的包）
        assertNull(response)
        assertTrue(socketObserved != null)
    }

    @Test
    fun givenProtectThrows_whenForward_thenNullNotException() {
        val forwarder = DnsForwarder(
            protectSocket = { throw IllegalStateException("not in vpn process") },
            socketTimeoutMs = 300,
        )
        assertNull(forwarder.forward(query, InetAddress.getByName("127.0.0.1")))
    }

    @Test
    fun givenNoListener_whenForward_thenTimesOutToNull() {
        val forwarder = DnsForwarder(protectSocket = { true }, socketTimeoutMs = 300)
        val response = forwarder.forward(query, InetAddress.getByName("127.0.0.1"))
        assertNull(response)
    }

    @Test
    fun givenForwardToAny_whenFirstFailsSecondEchoes_thenReturnsSuccess() {
        val (server, port) = startEchoServer()
        try {
            val forwarder = DnsForwarder(
                protectSocket = { true },
                socketTimeoutMs = 300,
                dnsPort = port,
            )
            // 127.0.0.2:echoPort 无监听 → 第一个上游超时失败；
            // 轮询到 127.0.0.1:echoPort（回显服务）→ 成功
            val response = forwarder.forwardToAny(
                query,
                listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")),
            )
            assertTrue(response != null)
            assertEquals(0xAA.toByte(), response!![0])
        } finally {
            server.close()
        }
    }
}
