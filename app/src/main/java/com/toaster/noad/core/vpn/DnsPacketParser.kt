package com.toaster.noad.core.vpn

/**
 * DNS 报文解析与响应构造（S2 / S3 共享，阶段 C）。
 *
 * ## 职责边界
 *
 * 本类只工作在 **DNS UDP payload 层**，不接触 IP/UDP 头 ——
 * 校验和重算、端口判断属于包处理循环（阶段 D）的职责。
 * 与 [com.toaster.noad.core.engine.DomainRuleEngine] 的分工同样清晰：
 * 本类把字节变成域名，引擎判断域名该不该拦。
 *
 * ## 响应构造策略（方案 §4.3 ③④）
 *
 * 拦截时**不追加任何 answer 记录**，只在原查询字节上：
 * 1. 置 QR=1（改为响应）；
 * 2. 低 4 位写 RCODE；
 * 3. ANCOUNT/NSCOUNT/ARCOUNT 清零。
 *
 * 长度保持不变 —— 这是刻意的最小实现：客户端收到 NXDOMAIN 后
 * **快速放弃**而非等待超时，零额外字节拷贝。
 *
 * ## RCODE 语义区分（方案 §4.3 ④，不可混用）
 *
 * | 场景 | RCODE | 客户端行为 |
 * |---|---|---|
 * | 命中黑名单 | `NXDOMAIN` (3) | 域名不存在，快速放弃 |
 * | 上游不可达 | `SERVFAIL` (2) | 解析服务临时故障，可重试 |
 *
 * 混用的后果是真实的：把故障伪装成「域名不存在」，
 * 用户会误以为网站挂了；把拦截伪装成故障，应用会无限重试。
 *
 * ## 防御性拒绝（返回 [ParseOutcome.NotDns]）
 *
 * - payload 不足 12 字节（header 都不完整）；
 * - QR=1（是响应不是查询）；
 * - opcode 非 0（非标准查询，INVERSE/STATUS 等）；
 * - QDCOUNT != 1（多 question 极罕见，拒绝比猜语义安全）；
 * - QNAME 中出现压缩指针或非法 label 长度（question 区的
 *   域名按 RFC 1035 本就应是完整编码，出现指针意味着格式
 *   超出本解析器承诺的范围，宁拒不猜）。
 */
class DnsPacketParser {

    /** 解析结果 */
    sealed interface ParseOutcome {
        /** 标准 A/AAAA 等 IN 查询，[domain] 为已按 label 还原的域名（保留原始大小写） */
        data class Query(val id: Int, val domain: String) : ParseOutcome

        /** 是合法 DNS 报文但不是可处理的标准查询（响应/非标准 opcode/多 question） */
        data object NotQuery : ParseOutcome

        /** 不是 DNS 报文或已损坏（过短/畸形 QNAME），不应作为 DNS 处理 */
        data object NotDns : ParseOutcome
    }

    /** header 长度：ID(2) + flags(2) + QDCOUNT(2) + ANCOUNT(2) + NSCOUNT(2) + ARCOUNT(2) */
    private val HEADER_SIZE = 12

    /** 单个 label 最大长度（RFC 1035） */
    private val MAX_LABEL_SIZE = 63

    /** QNAME 最大总长（255 字节含长度字节，防御性收紧到解析上限） */
    private val MAX_NAME_SIZE = 255

    fun parseQuery(payload: ByteArray): ParseOutcome {
        if (payload.size < HEADER_SIZE) return ParseOutcome.NotDns

        val flags = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        val isQuery = (flags and FLAG_QR) == 0
        val standardOpcode = ((flags shr OPCODE_SHIFT) and 0xF) == 0
        if (!isQuery || !standardOpcode) return ParseOutcome.NotQuery

        val qdCount = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (qdCount != 1) return ParseOutcome.NotQuery

        val id = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val domain = readQuestionName(payload) ?: return ParseOutcome.NotDns
        return ParseOutcome.Query(id = id, domain = domain)
    }

    /**
     * 在原查询字节上构造错误响应（NXDOMAIN / SERVFAIL，见类注释）。
     *
     * @param rcode 2 = SERVFAIL，3 = NXDOMAIN（其余值按 RFC 1035 语义透传）
     * @return 与输入等长的响应字节；输入不是标准查询时返回 null
     */
    fun buildErrorResponse(payload: ByteArray, rcode: Int): ByteArray? {
        if (parseQuery(payload) !is ParseOutcome.Query) return null

        val out = payload.copyOf()
        // 字节 2：置 QR=1（保留 opcode 等其余位）
        out[2] = (out[2].toInt() or 0x80).toByte()
        // 字节 3：低 4 位写 RCODE（保留 RD 等高 4 位）
        out[3] = ((out[3].toInt() and 0xF0) or (rcode and 0x0F)).toByte()
        // ANCOUNT(6..7) / NSCOUNT(8..9) / ARCOUNT(10..11) 清零
        for (i in 6..11) out[i] = 0
        return out
    }

    /**
     * 读取 question 区的 QNAME。
     *
     * 格式：`len1 字符串 len2 字符串 ... 0`（root label 为单个 0 字节结尾）。
     * 返回点分域名；遇到压缩指针（高两位为 11）、label 超长、总长超限、
     * 或长度字节越过 payload 末尾时返回 null。
     */
    private fun readQuestionName(payload: ByteArray): String? {
        var offset = HEADER_SIZE
        val sb = StringBuilder(MAX_NAME_SIZE)
        var nameSize = 0

        while (true) {
            if (offset >= payload.size) return null
            val len = payload[offset].toInt() and 0xFF
            if (len == 0) break
            // 高两位非 0：压缩指针（0xC0..0xFF）或保留值 —— 拒绝
            if (len and 0xC0 != 0) return null
            if (len > MAX_LABEL_SIZE) return null
            // label 数据须完整落在 payload 内
            if (offset + 1 + len > payload.size) return null
            if (nameSize + len + 1 > MAX_NAME_SIZE) return null

            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..len) {
                sb.append((payload[offset + i].toInt() and 0xFF).toChar())
            }
            offset += 1 + len
            nameSize += len + 1
        }
        // root label 之后必须还有 QTYPE(2) + QCLASS(2)，否则查询不完整
        if (offset + 1 + QUESTION_TRAILER_SIZE > payload.size) return null
        return sb.toString()
    }

    /**
     * 从任意 DNS 报文（含响应）中提取 Question 段的域名。
     *
     * ## 为什么不能复用 [parseQuery]
     *
     * [parseQuery] 会拒绝 QR=1 的响应报文（那是查询解析器的职责边界）。
     * 但响应报文**同样携带 Question 回显**（RFC 1035），
     * 而 S3 的 IP↔域名映射正是在响应上建立 —— 必须能只取域名而不问类型。
     */
    fun parseQuestionDomain(payload: ByteArray): String? {
        if (payload.size < HEADER_SIZE) return null
        val questionCount = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (questionCount < 1) return null
        return readQuestionName(payload)
    }

    /**
     * 从 DNS **响应**中解析第一条 A 记录（阶段 E 的 IP↔域名映射数据源）。
     *
     * ## 用途
     *
     * S3 的「域名级丢包」需要一个「IP → 域名」映射：客户端拿着
     * 我们转发回来的 IP 去连接，我们要能反查这个 IP 当初是为什么域名
     * 解析出来的，才能用域名规则判定是否丢弃该连接。
     *
     * ## 解析要点
     *
     * - Answer 段的名字通常是**压缩指针**（0xC0xx）而非完整域名；
     * - 只接受 `TYPE=A` + `CLASS=IN` + `RDLENGTH=4`，其余记录（AAAA/CNAME 等）
     *   一律跳过 —— AAAA（IPv6）不支持是明确的边界，不是遗漏；
     * - 任何越界/畸形一律返回 null，绝不在解析器里「猜」。
     *
     * @return 点分十进制 IPv4；无 A 记录或解析失败返回 null
     */
    fun parseFirstARecord(payload: ByteArray): String? {
        if (payload.size < HEADER_SIZE) return null

        val answerCount = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        if (answerCount <= 0) return null

        // 跳过 Question 段（QNAME + QTYPE + QCLASS）
        var offset = skipName(payload, HEADER_SIZE) ?: return null
        offset += QUESTION_TRAILER_SIZE

        repeat(answerCount) {
            offset = skipName(payload, offset) ?: return null
            // RR 固定部分：TYPE(2) CLASS(2) TTL(4) RDLENGTH(2)
            if (offset + RECORD_HEADER_SIZE > payload.size) return null

            val type = readUnsignedShort(payload, offset)
            val recordClass = readUnsignedShort(payload, offset + 2)
            val rdLength = readUnsignedShort(payload, offset + 8)
            val rdataOffset = offset + RECORD_HEADER_SIZE

            if (type == TYPE_A && recordClass == CLASS_IN && rdLength == IPV4_SIZE) {
                if (rdataOffset + IPV4_SIZE > payload.size) return null
                return buildString {
                    for (i in 0 until IPV4_SIZE) {
                        if (i > 0) append('.')
                        append(payload[rdataOffset + i].toInt() and 0xFF)
                    }
                }
            }
            offset = rdataOffset + rdLength
        }
        return null
    }

    /**
     * 跳过一段域名编码，返回其后的偏移。
     *
     * 与 [readQuestionName] 的区别：本函数**只定位不还原**，
     * 因此天然支持 Answer 段常见的压缩指针（读到 0xC0xxxx 即跳 2 字节）。
     */
    private fun skipName(payload: ByteArray, start: Int): Int? {
        var offset = start
        while (true) {
            if (offset >= payload.size) return null
            val len = payload[offset].toInt() and 0xFF
            when {
                len == 0 -> return offset + 1
                len and 0xC0 == 0xC0 -> return offset + 2
                len and 0xC0 != 0 -> return null
                len > MAX_LABEL_SIZE -> return null
                else -> offset += 1 + len
            }
        }
    }

    private fun readUnsignedShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private companion object {
        const val FLAG_QR = 0x8000
        const val OPCODE_SHIFT = 11

        /** QNAME 结束后的 QTYPE + QCLASS 字节数 */
        const val QUESTION_TRAILER_SIZE = 4

        /** DNS 资源记录固定部分：TYPE + CLASS + TTL + RDLENGTH */
        const val RECORD_HEADER_SIZE = 10

        /** 记录类型 A（IPv4 地址）与 IN 类 */
        const val TYPE_A = 1
        const val CLASS_IN = 1
        const val IPV4_SIZE = 4
    }
}
