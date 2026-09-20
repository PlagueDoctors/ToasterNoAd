package com.toaster.noad.core.service.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PrivateDnsOps] 的 JVM 单测。
 *
 * 重点覆盖两个「写错会导致整机断网」的地方：
 * 1. **主机名必须成对写入** mode + specifier（只写一个不生效）；
 * 2. **主机名校验必须拒绝 URL 形态**——用户粘贴
 *    `https://dns.adguard-dns.com/dns-query` 后直接写入，
 *    会让系统进入严格模式却无法解析，表现为整机 DNS 全挂。
 */
class PrivateDnsOpsTest {

    @Test
    fun givenHostname_whenBuildCommands_thenModeAndSpecifierPaired() {
        val commands = PrivateDnsOps.setHostnameCommands("dns.adguard-dns.com")

        assertEquals(2, commands.size)
        assertEquals(
            listOf("settings", "put", "global", "private_dns_mode", "hostname"),
            commands[0],
        )
        assertEquals(
            listOf("settings", "put", "global", "private_dns_specifier", "dns.adguard-dns.com"),
            commands[1],
        )
    }

    @Test
    fun givenOff_whenBuildCommands_thenSingleModeCommand() {
        assertEquals(
            listOf(listOf("settings", "put", "global", "private_dns_mode", "off")),
            PrivateDnsOps.setOffCommands(),
        )
    }

    @Test
    fun givenNullLiteral_whenParseMode_thenOff() {
        // 实机实测：未设置时 `settings get` 输出字面量 "null"
        assertEquals(PrivateDnsOps.DnsMode.OFF, PrivateDnsOps.parseMode("null"))
    }

    @Test
    fun givenActualNull_whenParseMode_thenOff() {
        assertEquals(PrivateDnsOps.DnsMode.OFF, PrivateDnsOps.parseMode(null))
    }

    @Test
    fun givenOffValue_whenParseMode_thenOff() {
        assertEquals(PrivateDnsOps.DnsMode.OFF, PrivateDnsOps.parseMode("off"))
    }

    @Test
    fun givenOpportunistic_whenParseMode_thenAuto() {
        assertEquals(PrivateDnsOps.DnsMode.AUTO, PrivateDnsOps.parseMode("opportunistic"))
    }

    @Test
    fun givenHostnameMode_whenParseMode_thenHostname() {
        assertEquals(PrivateDnsOps.DnsMode.HOSTNAME, PrivateDnsOps.parseMode("hostname"))
    }

    @Test
    fun givenUnknownValue_whenParseMode_thenUnknown() {
        // 厂商自定义值时不得当作已知模式（避免误判「已生效」）
        assertEquals(PrivateDnsOps.DnsMode.UNKNOWN, PrivateDnsOps.parseMode("vendor_custom"))
    }

    @Test
    fun givenNullLiteral_whenParseSpecifier_thenNull() {
        assertNull(PrivateDnsOps.parseSpecifier("null"))
        assertNull(PrivateDnsOps.parseSpecifier(""))
        assertNull(PrivateDnsOps.parseSpecifier(null))
    }

    @Test
    fun givenRealHostname_whenParseSpecifier_thenReturned() {
        assertEquals("dns.adguard-dns.com", PrivateDnsOps.parseSpecifier("dns.adguard-dns.com"))
    }

    @Test
    fun givenValidHostname_whenValidate_thenTrue() {
        assertTrue(PrivateDnsOps.isValidHostname("dns.adguard-dns.com"))
        assertTrue(PrivateDnsOps.isValidHostname("  dot.pub  ")) // 首尾空白可容忍
    }

    @Test
    fun givenUrlFormHostname_whenValidate_thenFalse() {
        // 用户最可能的误输入：直接粘贴服务商文档里的 URL
        assertFalse(PrivateDnsOps.isValidHostname("https://dns.adguard-dns.com/dns-query"))
        assertFalse(PrivateDnsOps.isValidHostname("dns.adguard-dns.com/dns-query"))
    }

    @Test
    fun givenHostnameWithPortOrSpace_whenValidate_thenFalse() {
        assertFalse(PrivateDnsOps.isValidHostname("dns.adguard-dns.com:853"))
        assertFalse(PrivateDnsOps.isValidHostname("dns adguard com"))
    }

    @Test
    fun givenHostnameWithoutDot_whenValidate_thenFalse() {
        assertFalse(PrivateDnsOps.isValidHostname("localhost"))
    }

    @Test
    fun givenEmptyHostname_whenValidate_thenFalse() {
        assertFalse(PrivateDnsOps.isValidHostname(""))
        assertFalse(PrivateDnsOps.isValidHostname("   "))
    }
}
