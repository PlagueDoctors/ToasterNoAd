package com.toaster.noad.core.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 上游 DNS 转发器（S2/S3 共享的 IO 层，阶段 D）。
 *
 * ## 为什么必须 protect
 *
 * 本应用的全部流量都被自己的 TUN 接管（S3 全量；S2 的 DNS 流量
 * 目的地就是 TUN 网关）。不加保护的 socket 发出去的包会**再次进
 * 自己的 TUN** —— 无限递归。`VpnService.protect(socket)` 把 socket
 * 标记为绕过本应用自己的 VPN，是方案 §4.3 的硬性铁律
 * （「socket 必须 protect()」）。
 *
 * protect 动作本身由 [protectSocket] 注入（生产环境是
 * `VpnService::protect`，测试用 fake 记录调用）—— 本类不继承
 * VpnService，避免为了一个回调而持有 Android 上下文。
 *
 * ## 失败语义
 *
 * 任何失败（socket 创建/protect 拒绝/发送/超时）返回 null，
 * **绝不抛出** —— 包循环据此统一回退为 SERVFAIL
 * （方案 §4.3 ④：上游不可达 ≠ 域名不存在，两种错误码不可混用）。
 */
class DnsForwarder(
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val socketTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    /** 上游 DNS 端口；注入是为了 JVM 回环测试（生产恒 53） */
    private val dnsPort: Int = DNS_PORT,
) {

    /**
     * 把 DNS 查询转发给 [upstream] 并返回响应报文。
     *
     * @return 响应字节；失败（含超时）返回 null
     */
    fun forward(dnsPayload: ByteArray, upstream: InetAddress): ByteArray? {
        val socket = DatagramSocket()
        try {
            // 顺序不可颠倒：必须先 connect 再由调用方 protect？
            // —— 都不是。protect 与 connect 顺序无关，但 protect 必须
            // 在**第一个包发出之前**完成，这里 immediately 满足。
            val protected = try {
                protectSocket(socket)
            } catch (_: Exception) {
                false
            }
            if (!protected) return null

            socket.soTimeout = socketTimeoutMs
            socket.connect(InetSocketAddress(upstream, dnsPort))

            socket.send(DatagramPacket(dnsPayload, dnsPayload.size))
            val buffer = ByteArray(MAX_DNS_RESPONSE_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return buffer.copyOf(packet.length)
        } catch (_: SocketTimeoutException) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 依次尝试上游列表，返回第一个成功响应；全部失败返回 null */
    fun forwardToAny(dnsPayload: ByteArray, upstreams: List<InetAddress>): ByteArray? {
        for (upstream in upstreams) {
            forward(dnsPayload, upstream)?.let { return it }
        }
        return null
    }

    private companion object {
        const val DNS_PORT = 53
        const val DEFAULT_TIMEOUT_MS = 5_000

        /** 单个上游应答的上限：标准 UDP DNS ≤ 512B，EDNS 常见 4KB 封顶 */
        const val MAX_DNS_RESPONSE_SIZE = 4_096
    }
}
