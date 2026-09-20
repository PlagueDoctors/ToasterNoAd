package com.toaster.noad.core.vpn

import com.toaster.noad.core.model.NetworkFilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VpnTunConfig] 的 JVM 单测 —— 锁定 S2 方案的安全边界。
 *
 * 最关键的一条铁律：**DNS 接管类模式绝不允许全量路由**
 * （方案 §4.1：`addRoute("0.0.0.0", 0)` 会把 S2 从
 * 「只接管 DNS」变成「接管全部流量」，性能模型全毁）。
 * 该约束以前只存在于注释里，现在由本测试在字节层面锁死。
 */
class VpnTunConfigTest {

    @Test
    fun givenDnsOnly_whenForMode_thenRoutesOnlyAnycastHost() {
        val c = VpnTunConfig.forMode(NetworkFilterMode.DNS_ONLY)

        assertEquals("10.0.0.2", c.route)
        assertEquals(32, c.routePrefix)
        assertTrue(c.isDnsOnlyRoute)
        // DNS 服务器 = 虚拟 anycast 地址
        assertEquals("10.0.0.2", c.dnsServer)
    }

    @Test
    fun givenAnyMode_thenDnsAddressNeverEqualsTunAddress() {
        // 🔴 防回归（实机取证发现的致命坑）：DNS 虚拟地址若等于 TUN 本机
        // 地址，DNS 查询被内核 local 路由表劫持，永远进不了 TUN 用户态
        // （症状 = 全设备解析瘫痪）。业界标准拓扑 = 两个地址必须分离。
        listOf(
            NetworkFilterMode.DNS_ONLY,
            NetworkFilterMode.HYBRID,
            NetworkFilterMode.FULL_TRAFFIC,
        ).forEach { mode ->
            val c = VpnTunConfig.forMode(mode)
            assertTrue(
                "mode $mode: dnsServer must differ from TUN address",
                c.dnsServer != c.address,
            )
        }
    }

    @Test
    fun givenHybrid_whenForMode_thenVpnPartIsDnsOnly() {
        // HYBRID = S4 + S2，VPN 部分就是 S2 的最小路由
        val c = VpnTunConfig.forMode(NetworkFilterMode.HYBRID)
        assertTrue(c.isDnsOnlyRoute)
        assertEquals(VpnTunConfig.forMode(NetworkFilterMode.DNS_ONLY), c)
    }

    @Test
    fun givenFullTraffic_whenForMode_thenFullRoute() {
        val c = VpnTunConfig.forMode(NetworkFilterMode.FULL_TRAFFIC)

        assertEquals("0.0.0.0", c.route)
        assertEquals(0, c.routePrefix)
        assertFalse(c.isDnsOnlyRoute)
        // S3 的虚拟网关与 S2 区分开，便于诊断时识别当前模式
        assertEquals("10.0.0.2", c.address)
        // S3 的 DNS 地址也不能是本机地址（全接管路由覆盖 10.0.0.3）
        assertEquals("10.0.0.3", c.dnsServer)
    }

    @Test
    fun givenDnsOnlyConfig_thenNeverFullRoute() {
        // 铁律断言的显式形式：任何 DNS 接管配置的 routePrefix 都不是 0
        listOf(NetworkFilterMode.DNS_ONLY, NetworkFilterMode.HYBRID).forEach { mode ->
            val c = VpnTunConfig.forMode(mode)
            assertTrue("mode $mode must not take over full traffic", c.routePrefix != 0)
        }
    }

    @Test
    fun givenNonVpnMode_whenForMode_thenThrows() {
        // OFF / APP_FIREWALL 不占 VPN，没有 TUN 配置；
        // 抛错优于返回一个「看起来能用」的错误配置
        assertThrows(IllegalArgumentException::class.java) {
            VpnTunConfig.forMode(NetworkFilterMode.OFF)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VpnTunConfig.forMode(NetworkFilterMode.APP_FIREWALL)
        }
    }
}
