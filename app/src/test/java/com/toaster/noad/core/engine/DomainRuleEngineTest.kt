package com.toaster.noad.core.engine

import com.toaster.noad.core.model.DomainCategory
import com.toaster.noad.core.model.DomainMatchType
import com.toaster.noad.core.model.DomainPolicy
import com.toaster.noad.core.model.DomainRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomainRuleEngine] 单元测试。
 *
 * 重点覆盖「后缀匹配误杀」这一最容易出错、后果最严重的场景。
 */
class DomainRuleEngineTest {

    private fun suffix(pattern: String) =
        DomainRule(matchType = DomainMatchType.SUFFIX, pattern = pattern)

    private fun exact(pattern: String) =
        DomainRule(matchType = DomainMatchType.EXACT, pattern = pattern)

    // ============ 归一化 ============

    @Test
    fun `normalize 应处理大小写与尾点`() {
        assertEquals("example.com", DomainRuleEngine.normalize("Example.COM"))
        assertEquals("example.com", DomainRuleEngine.normalize("example.com."))
        assertEquals("example.com", DomainRuleEngine.normalize("  example.com.  "))
    }

    @Test
    fun `normalize 应剥离协议端口与路径`() {
        assertEquals("example.com", DomainRuleEngine.normalize("https://example.com/path?a=1"))
        assertEquals("example.com", DomainRuleEngine.normalize("http://example.com:8080/"))
        assertEquals("example.com", DomainRuleEngine.normalize("example.com:443"))
    }

    @Test
    fun `normalize 对空输入返回空串`() {
        assertEquals("", DomainRuleEngine.normalize(""))
        assertEquals("", DomainRuleEngine.normalize("   "))
    }

    // ============ 后缀匹配的正确性（核心） ============

    @Test
    fun `后缀匹配不应把 badexample_com 误认为 example_com`() {
        val engine = DomainRuleEngine.from(DomainPolicy(blacklist = listOf(suffix("example.com"))))

        // 这是最典型的误杀形态：字符串以 example.com 结尾，但不是它的子域
        assertFalse(
            "badexample.com 不应被 example.com 的后缀规则命中",
            engine.shouldBlock("badexample.com"),
        )
        assertFalse(engine.shouldBlock("notexample.com"))
        assertFalse(engine.shouldBlock("myexample.com"))
    }

    @Test
    fun `后缀匹配应命中自身与各级子域`() {
        val engine = DomainRuleEngine.from(DomainPolicy(blacklist = listOf(suffix("ads.example.com"))))

        assertTrue("自身应命中", engine.shouldBlock("ads.example.com"))
        assertTrue("一级子域应命中", engine.shouldBlock("cdn.ads.example.com"))
        assertTrue("二级子域应命中", engine.shouldBlock("a.b.ads.example.com"))
        assertTrue("大写应命中", engine.shouldBlock("CDN.ADS.EXAMPLE.COM"))
    }

    @Test
    fun `后缀匹配不应命中父域`() {
        val engine = DomainRuleEngine.from(DomainPolicy(blacklist = listOf(suffix("ads.example.com"))))

        assertFalse("父域不应被误伤", engine.shouldBlock("example.com"))
        assertFalse(engine.shouldBlock("com"))
    }

    // ============ 精确匹配 ============

    @Test
    fun `精确匹配只命中完全相同域名`() {
        val engine = DomainRuleEngine.from(DomainPolicy(blacklist = listOf(exact("ads.example.com"))))

        assertTrue(engine.shouldBlock("ads.example.com"))
        assertFalse("子域不应被精确规则命中", engine.shouldBlock("cdn.ads.example.com"))
        assertFalse(engine.shouldBlock("example.com"))
    }

    // ============ 白名单优先（核心） ============

    @Test
    fun `白名单应优先于黑名单`() {
        val engine = DomainRuleEngine.from(
            DomainPolicy(
                blacklist = listOf(suffix("example.com")),
                whitelist = listOf(exact("login.example.com")),
            ),
        )

        assertFalse(
            "白名单中的登录域名不应被黑名单误伤",
            engine.shouldBlock("login.example.com"),
        )
        assertTrue("黑名单内的其他子域仍应拦截", engine.shouldBlock("ads.example.com"))
    }

    @Test
    fun `白名单后缀应覆盖黑名单精确`() {
        val engine = DomainRuleEngine.from(
            DomainPolicy(
                blacklist = listOf(exact("api.example.com")),
                whitelist = listOf(suffix("example.com")),
            ),
        )

        assertFalse(engine.shouldBlock("api.example.com"))
    }

    // ============ 边界 ============

    @Test
    fun `空策略不应拦截任何域名`() {
        val engine = DomainRuleEngine.from(DomainPolicy.EMPTY)

        assertFalse(engine.shouldBlock("ads.example.com"))
        assertTrue(engine.isEmpty)
        assertEquals(0, engine.totalSize)
    }

    @Test
    fun `空域名与无效输入不应拦截`() {
        val engine = DomainRuleEngine.from(DomainPolicy(blacklist = listOf(suffix("example.com"))))

        assertFalse(engine.shouldBlock(""))
        assertFalse(engine.shouldBlock("   "))
    }

    @Test
    fun `禁用的规则不应生效`() {
        val engine = DomainRuleEngine.from(
            DomainPolicy(blacklist = listOf(suffix("example.com").copy(enabled = false))),
        )

        assertFalse(engine.shouldBlock("ads.example.com"))
    }

    @Test
    fun `应正确处理 IPv4 字面量不误匹配域名规则`() {
        val engine = DomainRuleEngine.from(DomainPolicy(blacklist = listOf(suffix("example.com"))))

        assertFalse(engine.shouldBlock("1.2.3.4"))
    }

    @Test
    fun `规模统计应正确`() {
        val engine = DomainRuleEngine.from(
            DomainPolicy(
                blacklist = listOf(suffix("a.com"), suffix("b.com"), exact("c.com")),
                whitelist = listOf(exact("d.com")),
            ),
        )

        assertEquals(1, engine.whitelistSize)
        assertEquals(3, engine.blacklistSize)
        assertEquals(4, engine.totalSize)
    }

    @Test
    fun `命中黑名单应返回具体规则以便记录日志`() {
        val rule = suffix("ads.example.com").copy(category = DomainCategory.TRACKER)
        val engine = DomainRuleEngine.from(DomainPolicy(blacklist = listOf(rule)))

        val verdict = engine.evaluate("cdn.ads.example.com")
        assertTrue(verdict is com.toaster.noad.core.model.DomainVerdict.Blocked)
        assertEquals(
            DomainCategory.TRACKER,
            (verdict as com.toaster.noad.core.model.DomainVerdict.Blocked).rule.category,
        )
    }
}
