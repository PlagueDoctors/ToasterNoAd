package com.toaster.noad.core.vpn

import com.toaster.noad.core.engine.DomainRuleEngine

/**
 * DNS 拦截判定（S2/S3 共享的决策层，阶段 D）。
 *
 * ## 与相邻组件的分工
 *
 * [DnsPacketParser] 把字节变成域名 → 本类判断域名该不该拦 →
 * [Ip4UdpPacket] 把结果变回字节。本类**不碰任何字节细节**
 * （那是 parser 的活），也不发起任何网络调用（那是 forwarder 的活）——
 * 决策与 IO 分离，让「白名单优先」「NXDOMAIN 语义」这些核心规则
 * 可以脱离 Android 在 JVM 上全量验证。
 *
 * ## 判定语义（方案 §4.2）
 *
 * | 引擎结论 | 动作 | 客户端感知 |
 * |---|---|---|
 * | Blocked | [Decision.Block]（本地构造 NXDOMAIN） | 域名不存在，快速放弃 |
 * | Allowed / NoMatch | [Decision.Forward]（转发真实上游） | 与无过滤时一致 |
 * | 非标准查询 / 畸形 | [Decision.Ignore] | 丢弃（不出响应，等客户端超时重试） |
 *
 * Ignore 只出现在畸形流量上：正常的非黑名单域名一律 Forward，
 * 绝不因为「看不懂」而静默吞掉用户的正常解析。
 */
class DnsInterceptor(
    private val engineProvider: () -> DomainRuleEngine,
    private val parser: DnsPacketParser = DnsPacketParser(),
) {

    /** 判定结果 */
    sealed interface Decision {

        /** 命中黑名单：[responsePayload] 为本地构造的 NXDOMAIN 响应，直接写回 TUN */
        class Block(val responsePayload: ByteArray) : Decision

        /** 未命中：把 [queryPayload] 原样转发给真实上游 DNS */
        class Forward(val queryPayload: ByteArray) : Decision

        /** 非标准查询或畸形报文：丢弃，不产生任何响应 */
        data object Ignore : Decision
    }

    fun decide(dnsPayload: ByteArray): Decision {
        val query = when (val outcome = parser.parseQuery(dnsPayload)) {
            is DnsPacketParser.ParseOutcome.Query -> outcome
            DnsPacketParser.ParseOutcome.NotQuery, DnsPacketParser.ParseOutcome.NotDns ->
                return Decision.Ignore
        }

        return when {
            engineProvider().shouldBlock(query.domain) -> {
                val response = parser.buildErrorResponse(dnsPayload, RCODE_NXDOMAIN)
                if (response != null) Decision.Block(response) else Decision.Ignore
            }
            else -> Decision.Forward(dnsPayload)
        }
    }

    private companion object {
        const val RCODE_NXDOMAIN = 3
    }
}
