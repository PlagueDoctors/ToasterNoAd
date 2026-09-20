package com.toaster.noad.core.vpn

/**
 * IP → 域名映射表（阶段 E：S3「域名级丢包」的基础设施）。
 *
 * ## 它解决什么问题
 *
 * S3 接管全流量后，我们看到的只有 IP 包头 —— 而规则是**按域名**编写的。
 * 因此需要记住「这个 IP 当初是为哪个域名解析出来的」：
 * 从 TUN 读到的 DNS 响应里提取 A 记录（[DnsPacketParser.parseFirstARecord]），
 * 建立 IP → 域名映射；之后遇到该 IP 的连接即可用域名规则判定。
 *
 * ## 边界（诚实标注）
 *
 * | 场景 | 是否覆盖 | 原因 |
 * |---|---|---|
 * | 经我们转发的 A 记录 | ✅ | 映射由经过的 DNS 响应建立 |
 * | 直连 IP（无 DNS 查询） | ❌ | 从未见过该 IP 的域名 |
 * | IPv6（AAAA） | ❌ | 明确不支持（S3 仅 IPv4） |
 * | 应用自带 DoH/DoT | ❌ | 查询不经我们，映射建立不起来 |
 * | 缓存命中（无新查询） | 部分 | 仅覆盖映射存活期内 |
 *
 * ## 过期策略
 *
 * 域名与 IP 的对应关系随 CDN 调度变化，映射不能永久保留：
 * 默认 TTL 5 分钟（DNS 记录 TTL 的常见量级）。
 * 过期清扫是**惰性**的（在 [record] 时顺带清理），
 * 避免为一张辅助表引入定时任务。
 *
 * ## 线程安全
 *
 * S3 包循环是单消费者（与 S2 同样的模型），但 [lookup] 可能被
 * 其他线程读取（如 UI 展示映射规模），因此用 `ConcurrentHashMap`。
 */
class IpDomainMap(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private class Entry(val domain: String, val recordedAt: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<Int, Entry>()

    /** 记录一条 IP → 域名映射（同一 IP 被不同域名复用时取最新） */
    fun record(ip: Int, domain: String) {
        if (domain.isBlank()) return
        entries[ip] = Entry(domain, clock())
        pruneIfNeeded()
    }

    /** 查询某 IP 对应的域名；未记录或已过期返回 null */
    fun lookup(ip: Int): String? {
        val entry = entries[ip] ?: return null
        if (isExpired(entry, clock())) {
            entries.remove(ip, entry)
            return null
        }
        return entry.domain
    }

    /** 当前有效映射数量（UI 展示 / 测试断言） */
    val size: Int
        get() {
            prune()
            return entries.size
        }

    /** 立即清理所有过期条目 */
    fun prune() {
        val now = clock()
        entries.entries.removeAll { isExpired(it.value, now) }
    }

    /** 清空全部映射（停止 S3 时调用） */
    fun clear() {
        entries.clear()
    }

    private fun isExpired(entry: Entry, now: Long): Boolean = now - entry.recordedAt >= ttlMs

    /**
     * 惰性清理：每积累一定量新记录时做一次全表清扫。
     *
     * 批量阈值取 64：单次清扫的成本与表规模成正比，
     * 而每次 [record] 都全表扫描在映射表变大后会产生可观测开销。
     */
    private fun pruneIfNeeded() {
        if (entries.size >= PRUNE_THRESHOLD) prune()
    }

    private companion object {
        /** 映射有效期：5 分钟（与常见 DNS TTL 同量级） */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        /** 触发惰性清扫的表规模阈值 */
        const val PRUNE_THRESHOLD = 64
    }
}
