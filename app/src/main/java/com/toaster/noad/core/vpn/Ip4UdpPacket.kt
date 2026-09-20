package com.toaster.noad.core.vpn

/**
 * IPv4 + UDP 报文的字节级解析与响应构造（S2 包处理循环的数据层，阶段 D）。
 *
 * ## 职责边界
 *
 * 只做**字节翻译**，不做任何决策：是否拦截由 [DnsInterceptor] 判断，
 * 如何转发由 [DnsForwarder] 执行。独立成纯函数的原因与
 * [VpnTunConfig] 相同 —— Android 的 `VpnService`/TUN 无法在 JVM
 * 上运行，而这里的校验和、方向交换、长度改写是**最容易写错且
 * 错了完全无响应**的部分，必须被单元测试直接锁定。
 *
 * ## 响应包构造策略（方案 §4.3 ③）
 *
 * **复用客户端的原查询包**：交换 IP 地址与 UDP 端口、替换 payload、
 * 改写 IP 总长与 UDP 长度、重算 IP 头校验和。这比从零构造响应包
 * 少处理一大类边界（版本/选项字段/TOS 等原样保留即正确）。
 *
 * UDP 校验和**置 0**：IPv4 上这是合法的「不校验」约定（RFC 768），
 * 客户端内核必须接受；重算 UDP 校验和需要伪头部且收益为零。
 * IP 头校验和**必须重算** —— 它是头部必检字段，错了整包被丢。
 */
object Ip4UdpPacket {

    /** 解析结果（各字段为 buffer 内的定位信息，不拷贝数据） */
    class Ip4UdpFrame(
        /** 源 IPv4 地址（大端打包为 32 位整数，便于做映射表 key） */
        val sourceIp: Int,
        /** 目标 IPv4 地址（大端打包为 32 位整数） */
        val destinationIp: Int,
        val sourcePort: Int,
        val destinationPort: Int,
        /** UDP payload（即 DNS 报文）在 buffer 中的起始偏移 */
        val payloadOffset: Int,
        /** UDP payload 长度 */
        val payloadLength: Int,
    )

    /**
     * 解析一个 IPv4/UDP 数据报。
     *
     * 返回 null 的情形（防御性拒绝）：非 IPv4、IHL<5、
     * 声明长度超出 buffer、协议非 UDP、UDP 头不完整、
     * UDP 长度字段与实际不符。
     */
    fun parseUdpDatagram(buffer: ByteArray, length: Int): Ip4UdpFrame? {
        if (length < MIN_IP_UDP_SIZE) return null
        val version = (buffer[0].toInt() and 0xFF) ushr 4
        if (version != 4) return null
        val ihl = buffer[0].toInt() and 0x0F
        if (ihl < 5) return null
        val ipHeaderSize = ihl * 4

        val totalLength = readUnsignedShort(buffer, 2)
        if (totalLength > length || totalLength < ipHeaderSize + UDP_HEADER_SIZE) return null

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != PROTOCOL_UDP) return null

        val udpOffset = ipHeaderSize
        val udpLength = readUnsignedShort(buffer, udpOffset + 4)
        if (udpLength < UDP_HEADER_SIZE || udpOffset + udpLength > totalLength) return null

        return Ip4UdpFrame(
            sourceIp = readInt(buffer, 12),
            destinationIp = readInt(buffer, 16),
            sourcePort = readUnsignedShort(buffer, udpOffset),
            destinationPort = readUnsignedShort(buffer, udpOffset + 2),
            payloadOffset = udpOffset + UDP_HEADER_SIZE,
            payloadLength = udpLength - UDP_HEADER_SIZE,
        )
    }

    /** 把大端打包的 IPv4 整数还原为点分十进制（日志与映射表展示用） */
    fun ipToString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    /**
     * 由原查询包构造方向相反的响应包。
     *
     * @param queryBuffer 原始查询包（从 TUN 读到的完整 IP 包）
     * @param queryLength 原始包长度
     * @param responseDnsPayload 要写回的 DNS 报文（拦截响应或上游应答）
     * @return 可直接写回 TUN 的响应包；查询包非法时返回 null
     */
    fun buildResponsePacket(
        queryBuffer: ByteArray,
        queryLength: Int,
        responseDnsPayload: ByteArray,
    ): ByteArray? {
        val frame = parseUdpDatagram(queryBuffer, queryLength) ?: return null
        val ihl = queryBuffer[0].toInt() and 0x0F
        val ipHeaderSize = ihl * 4
        val udpOffset = ipHeaderSize
        val totalLength = ipHeaderSize + UDP_HEADER_SIZE + responseDnsPayload.size

        val out = ByteArray(totalLength)
        // IP 头：除总长与校验和外原样保留
        System.arraycopy(queryBuffer, 0, out, 0, ipHeaderSize)
        writeUnsignedShort(out, 2, totalLength)

        // 交换 IP 地址（src <-> dst）
        System.arraycopy(queryBuffer, 12, out, 16, 4)
        System.arraycopy(queryBuffer, 16, out, 12, 4)

        // UDP 头：端口交换、长度改写、校验和置 0（IPv4 合法约定）
        val sourcePort = readUnsignedShort(queryBuffer, udpOffset)
        val destinationPort = readUnsignedShort(queryBuffer, udpOffset + 2)
        writeUnsignedShort(out, udpOffset, destinationPort)
        writeUnsignedShort(out, udpOffset + 2, sourcePort)
        writeUnsignedShort(out, udpOffset + 4, UDP_HEADER_SIZE + responseDnsPayload.size)
        writeUnsignedShort(out, udpOffset + 6, 0)

        // DNS payload
        System.arraycopy(responseDnsPayload, 0, out, udpOffset + UDP_HEADER_SIZE, responseDnsPayload.size)

        // IP 头校验和重算（必须：必检字段）
        writeUnsignedShort(out, 10, 0)
        writeUnsignedShort(out, 10, headerChecksum(out, ipHeaderSize))
        return out
    }

    /**
     * IPv4 头校验和：头按 16 位字求和（校验和字段按 0 参与），
     * 进位折叠后取反码。
     */
    fun headerChecksum(header: ByteArray, headerSize: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < headerSize) {
            sum += readUnsignedShort(header, i)
            i += 2
        }
        // 奇数长度尾部（IPv4 头恒为 4 字节倍数，此处纯防御）
        if (headerSize % 2 == 1) {
            sum += (header[headerSize - 1].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.toInt().inv()) and 0xFFFF
    }

    private fun readUnsignedShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    /** 读 4 字节大端整数（IPv4 地址） */
    private fun readInt(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)

    private fun writeUnsignedShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private const val PROTOCOL_UDP = 17
    private const val UDP_HEADER_SIZE = 8
    private const val MIN_IP_UDP_SIZE = 20 + UDP_HEADER_SIZE
}
