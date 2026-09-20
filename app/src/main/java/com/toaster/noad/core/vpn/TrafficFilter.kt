package com.toaster.noad.core.vpn

import com.toaster.noad.core.engine.DomainRuleEngine

/**
 * S3 全流量模式的目标判定（阶段 E，方案 §5.2「方案 C：域名级丢包」）。
 *
 * ## 判定流程
 *
 * ```
 * 目标 IP → IpDomainMap 反查域名
 *     ├─ 无映射 → Unknown（放行）
 *     └─ 有域名 → DomainRuleEngine 判定
 *           ├─ Blocked → Drop
 *           └─ Allowed / NoMatch → Pass
 * ```
 *
 * ## 为什么「无映射」必须放行（而不是丢弃）
 *
 * 这是本类最重要的安全边界，与 S1 预筛的「只放宽不收紧」同源：
 * 映射表只覆盖「经我们转发的 A 记录」，直连 IP、DoH、IPv6、
 * 映射过期等都是**常态**。把它们当作可丢弃流量会让用户**直接断网** ——
 * 拦不住的代价远小于误杀。
 *
 * ## 为什么 IPv6 明确不支持
 *
 * 映射表以 32 位 IPv4 为 key；IPv6（128 位）需要独立的解析与映射体系。
 * IPv6 一律 [Verdict.PASS] 并如实标注，不假装支持。
 */
class TrafficFilter(
    private val engineProvider: () -> DomainRuleEngine,
    private val ipDomainMap: IpDomainMap,
    private val parser: DnsPacketParser = DnsPacketParser(),
) {

    enum class Verdict {
        /** 命中黑名单：丢弃该连接（客户端表现为超时/重置） */
        DROP,

        /** 未命中或白名单：放行 */
        PASS,

        /** 该 IP 没有域名映射（直连/DoH/过期）—— 放行，见类注释 */
        UNKNOWN_IP,
    }

    /** 依据目标 IPv4 判定 */
    fun decide(destinationIp: Int): Verdict {
        val domain = ipDomainMap.lookup(destinationIp) ?: return Verdict.UNKNOWN_IP
        return if (engineProvider().shouldBlock(domain)) Verdict.DROP else Verdict.PASS
    }

    /**
     * 学习一次 DNS 响应：把其中的 A 记录与 Question 域名写入映射表。
     *
     * 由包循环在**转发上游响应成功后**调用 —— 只有真正回给客户端的
     * 解析结果才代表「这个 IP 会被连接」，学习它才有意义。
     */
    fun learnFromDnsResponse(responsePayload: ByteArray) {
        val domain = parser.parseQuestionDomain(responsePayload) ?: return
        val dottedIp = parser.parseFirstARecord(responsePayload) ?: return
        val ip = ipToInt(dottedIp)
        if (ip == 0) return
        ipDomainMap.record(ip, domain)
    }

    /** 把点分十进制转为映射表用的 32 位整数；非法输入返回 0 */
    fun ipToInt(dotted: String): Int {
        val parts = dotted.split('.')
        if (parts.size != IPV4_PARTS) return 0
        var result = 0
        for (part in parts) {
            val value = part.toIntOrNull() ?: return 0
            if (value !in 0..255) return 0
            result = (result shl 8) or value
        }
        return result
    }

    private companion object {
        const val IPV4_PARTS = 4
    }
}
