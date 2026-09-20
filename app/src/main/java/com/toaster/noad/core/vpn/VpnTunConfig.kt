package com.toaster.noad.core.vpn

import com.toaster.noad.core.model.NetworkFilterMode

/**
 * 模式 → TUN 建立参数（阶段 C 抽出为纯数据的原因见下）。
 *
 * ## 为什么独立成纯函数
 *
 * `VpnService.Builder` 依赖 Android 框架，无法在 JVM 上测试；
 * 而「每个模式到底 addRoute 了什么」是**整个 S2 方案的安全边界**：
 * S2 若误写 `addRoute("0.0.0.0", 0)`，就会从「只接管 DNS」变成
 * 「接管全部流量」——性能模型全毁，且 S2/S3 的互斥语义失去意义。
 * 把参数翻译成纯数据后，这条铁律可以被单元测试**直接锁定**，
 * [com.toaster.noad.core.service.NoAdVpnService] 里的 `buildBuilder`
 * 只做机械翻译，不再承载任何决策。
 *
 * ## ⚠️ DNS 虚拟地址绝不能等于 TUN 地址（实机取证发现的致命坑）
 *
 * 原方案（§4.1）`addAddress("10.0.0.1") + addDnsServer("10.0.0.1")`
 * 在实机上导致全设备解析瘫痪：`ip rule` 第 0 条 `from all lookup local`
 * 优先级最高，而 10.0.0.1 被配置为**本机地址**后，发往它的 DNS 查询
 * 被 local 路由表劫持交给本机协议栈（内核直接应答 ICMP、UDP:53 无人
 * 监听则丢弃），**永远不会到达 TUN 的用户态**。取证方式：
 * `ping 10.0.0.1` 得到 ttl=64 的本地应答且应用日志无任何包。
 *
 * 因此采用业界标准拓扑：**TUN 地址与 DNS 虚拟地址分离**——
 * DNS 指向一个**非本机**的虚拟地址（10.0.0.2），并 addRoute 它，
 * 使查询路由进 TUN；响应包写回 TUN 后目标（10.0.0.1:port）是本机
 * 地址，由内核 UDP 栈按端口交付给客户端 socket，链路闭合。
 *
 * ## 各模式的取值（§4.1 修订）
 *
 * | 模式 | address | DNS | route | 影响面 |
 * |---|---|---|---|---|
 * | DNS_ONLY / HYBRID | 10.0.0.1/32 | **10.0.0.2** | **10.0.0.2/32** | 只有 DNS 查询进 TUN |
 * | FULL_TRAFFIC | 10.0.0.2/32 | **10.0.0.3** | 0.0.0.0/0 | 全部流量进 TUN |
 *
 * [NetworkFilterMode.OFF] 与 `APP_FIREWALL` 不占 VPN，
 * 不会走到本函数（由 VpnArbitrator 把门），传入即抛错。
 */
data class VpnTunConfig(
    val session: String,
    val address: String,
    val addressPrefix: Int,
    val dnsServer: String,
    val route: String,
    val routePrefix: Int,
    val mtu: Int,
) {
    /** S2 语义（DNS 接管）：只路由 DNS 虚拟地址，绝不全量接管 */
    val isDnsOnlyRoute: Boolean
        get() = route == DNS_ANYCAST && routePrefix == 32

    companion object {

        /**
         * S2/HYBRID 的虚拟 DNS anycast 地址。
         *
         * ⚠️ 必须与 [DNS_TUN] 的 address（10.0.0.1）**不同**，
         * 否则 DNS 查询被内核 local 路由表劫持（见类注释）。
         */
        const val DNS_ANYCAST = "10.0.0.2"

        /** S3 的虚拟网关地址 */
        const val FULL_ANYCAST = "10.0.0.2"

        private val DNS_TUN = VpnTunConfig(
            session = "NoAd DNS",
            address = "10.0.0.1",
            addressPrefix = 32,
            dnsServer = DNS_ANYCAST,
            route = DNS_ANYCAST,
            routePrefix = 32,
            mtu = 1500,
        )

        private val FULL_TUN = VpnTunConfig(
            session = "NoAd",
            address = FULL_ANYCAST,
            addressPrefix = 32,
            dnsServer = "10.0.0.3",
            route = "0.0.0.0",
            routePrefix = 0,
            mtu = 1500,
        )

        /**
         * 按模式取 TUN 配置。
         *
         * 只接受会 `establish` 的模式（`occupiesVpn == true`）；
         * OFF / APP_FIREWALL 走到这里属于调用方门禁失守，直接抛错
         * 而不是返回一个「看起来能用」的错误配置。
         */
        fun forMode(mode: NetworkFilterMode): VpnTunConfig = when (mode) {
            NetworkFilterMode.FULL_TRAFFIC -> FULL_TUN
            NetworkFilterMode.DNS_ONLY, NetworkFilterMode.HYBRID -> DNS_TUN
            NetworkFilterMode.OFF, NetworkFilterMode.APP_FIREWALL ->
                throw IllegalArgumentException(
                    "mode $mode does not occupy a VPN; no TUN config exists",
                )
        }
    }
}
