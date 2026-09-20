package com.toaster.noad.core.vpn

import com.toaster.noad.core.model.NetworkFilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VpnArbitrator] 决策矩阵的 JVM 单测（方案 §2.2 / §6.9）。
 *
 * 两个探测均为注入布尔，矩阵的每一格都可精确构造。
 * 锁定的核心行为：
 * 1. S4（不占 VPN）与他人 VPN 天然共存，绝不因让位而放弃；
 * 2. 占 VPN 的模式遇其他 VPN 且可降级时，**改写为 APP_FIREWALL**；
 * 3. 无降级能力时让位（不启动），避免无限抢占循环。
 */
class VpnArbitratorTest {

    private fun arbitrator(
        otherVpn: Boolean = false,
        s4Available: Boolean = false,
    ): VpnArbitrator = VpnArbitrator(
        isOtherVpnActive = { otherVpn },
        canControlPerAppNetwork = { s4Available },
    )

    @Test
    fun givenUserDisabled_whenResolved_thenDoesNotStart() {
        val d = arbitrator(otherVpn = false).resolveStartDecision(
            userEnabled = false,
            requestedMode = NetworkFilterMode.DNS_ONLY,
        )
        assertFalse(d.start)
        assertEquals(VpnStartReason.USER_DISABLED, d.reason)
    }

    @Test
    fun givenOffMode_whenResolved_thenDoesNotStart() {
        val d = arbitrator().resolveStartDecision(
            userEnabled = true,
            requestedMode = NetworkFilterMode.OFF,
        )
        assertFalse(d.start)
        assertEquals(VpnStartReason.MODE_OFF, d.reason)
    }

    @Test
    fun givenAppFirewall_whenOtherVpnActive_thenStillStarts() {
        // S4 不建立 TUN，与他人 VPN 共存 —— 让位仲裁管不到它
        val d = arbitrator(otherVpn = true, s4Available = false).resolveStartDecision(
            userEnabled = true,
            requestedMode = NetworkFilterMode.APP_FIREWALL,
        )
        assertTrue(d.start)
        assertEquals(NetworkFilterMode.APP_FIREWALL, d.mode)
        assertEquals(VpnStartReason.PROCEED, d.reason)
    }

    @Test
    fun givenVpnMode_whenNoOtherVpn_thenStartsRequestedMode() {
        val d = arbitrator(otherVpn = false).resolveStartDecision(
            userEnabled = true,
            requestedMode = NetworkFilterMode.FULL_TRAFFIC,
        )
        assertTrue(d.start)
        assertEquals(NetworkFilterMode.FULL_TRAFFIC, d.mode)
        assertEquals(VpnStartReason.PROCEED, d.reason)
    }

    @Test
    fun givenVpnMode_whenOtherVpnAndS4Available_thenDegradesToAppFirewall() {
        val d = arbitrator(otherVpn = true, s4Available = true).resolveStartDecision(
            userEnabled = true,
            requestedMode = NetworkFilterMode.DNS_ONLY,
        )
        assertTrue(d.start)
        // 降级会改写实际模式：调用方必须使用 decision.mode 而非原始请求
        assertEquals(NetworkFilterMode.APP_FIREWALL, d.mode)
        assertEquals(VpnStartReason.DEGRADED_TO_APP_FIREWALL, d.reason)
    }

    @Test
    fun givenVpnMode_whenOtherVpnAndNoS4_thenYields() {
        val d = arbitrator(otherVpn = true, s4Available = false).resolveStartDecision(
            userEnabled = true,
            requestedMode = NetworkFilterMode.DNS_ONLY,
        )
        assertFalse(d.start)
        assertEquals(NetworkFilterMode.DNS_ONLY, d.mode)
        assertEquals(VpnStartReason.YIELD_TO_OTHER_VPN, d.reason)
    }

    @Test
    fun givenHybrid_whenOtherVpnAndNoS4_thenYields() {
        // HYBRID 占 VPN（S4+S2），同样受让位仲裁约束
        val d = arbitrator(otherVpn = true, s4Available = false).resolveStartDecision(
            userEnabled = true,
            requestedMode = NetworkFilterMode.HYBRID,
        )
        assertFalse(d.start)
        assertEquals(VpnStartReason.YIELD_TO_OTHER_VPN, d.reason)
    }
}
