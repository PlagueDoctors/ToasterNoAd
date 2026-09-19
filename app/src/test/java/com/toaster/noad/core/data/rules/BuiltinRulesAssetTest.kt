package com.toaster.noad.core.data.rules

import com.toaster.noad.core.engine.DomainRuleEngine
import com.toaster.noad.core.model.DomainPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 对**实际发布的内置规则文件**做契约测试。
 *
 * ## 为什么这个测试重要
 *
 * [BuiltinRulesLoaderTest] 验证的是解析器逻辑，用的是测试内联的 JSON。
 * 如果只测解析器，`assets/rules/builtin_domains.json` 本身写错了
 * （漏逗号、加了 `*.` 前缀、误把业务域名加进黑名单）**不会有任何测试失败**，
 * 而错误会直接进入用户设备。
 *
 * 因此本测试直接读取真实文件并断言其结构与语义约束。
 *
 * ## 读取路径说明
 *
 * 单元测试的工作目录是模块目录（`app/`），因此用相对路径读取。
 * 若 Gradle 改变工作目录约定，此处会失败并暴露问题 —— 这是可接受的，
 * 因为静默跳过测试比测试失败更危险。
 */
class BuiltinRulesAssetTest {

    private val assetFile = File("src/main/assets/rules/builtin_domains.json")

    private fun readAsset(): String {
        assertTrue(
            "内置规则文件未找到：${assetFile.absolutePath}。工作目录可能已变更。",
            assetFile.exists(),
        )
        return assetFile.readText()
    }

    private fun parsedRoot(): JSONObject = JSONObject(readAsset())

    // ============ 文件存在性与可解析性 ============

    @Test
    fun givenBuiltinRulesFile_whenParse_thenSucceedsAndIsNotEmpty() {
        val result = BuiltinRulesLoader.parse(readAsset())

        assertTrue("内置黑名单不应为空", result.blacklist.isNotEmpty())
        assertEquals("内置规则文件不应存在被跳过的条目", 0, result.skipped)
    }

    @Test
    fun givenBuiltinRulesFile_whenParse_thenVersionIsPositive() {
        val result = BuiltinRulesLoader.parse(readAsset())

        assertTrue("规则集必须带版本号以便后续增量升级", result.version > 0)
    }

    // ============ 图案格式约束 ============

    @Test
    fun givenAllPatterns_whenCheck_thenNoSchemeOrWildcardOrTrailingDot() {
        val result = BuiltinRulesLoader.parse(readAsset())

        (result.blacklist + result.whitelist).forEach { rule ->
            val p = rule.pattern
            assertFalse("图案不应含协议前缀：$p", p.contains("://"))
            assertFalse("图案不应含通配符（匹配类型已由 SUFFIX 表达）：$p", p.contains("*"))
            assertFalse("图案不应含路径或端口：$p", p.contains("/") || p.contains(":"))
            assertFalse("图案不应有尾点：$p", p.endsWith("."))
            assertEquals("图案必须已归一化为小写：$p", p, p.lowercase())
        }
    }

    @Test
    fun givenAllPatterns_whenCheck_thenAtLeastTwoLabels() {
        val result = BuiltinRulesLoader.parse(readAsset())

        (result.blacklist + result.whitelist).forEach { rule ->
            assertTrue(
                "图案应至少含一个点（二级域名以上），否则误杀面过大：${rule.pattern}",
                rule.pattern.contains('.'),
            )
        }
    }

    @Test
    fun givenAllRules_whenCheck_thenEveryRuleHasNote() {
        val result = BuiltinRulesLoader.parse(readAsset())

        (result.blacklist + result.whitelist).forEach { rule ->
            assertNotNull(
                "每条内置规则必须有 note 说明用途，否则无法审计与移除：${rule.pattern}",
                rule.note,
            )
        }
    }

    // ============ 无重复 ============

    @Test
    fun givenBlacklist_whenCheck_thenNoDuplicatePatterns() {
        val result = BuiltinRulesLoader.parse(readAsset())

        val patterns = result.blacklist.map { it.pattern }
        assertEquals(
            "黑名单存在重复图案：${patterns.groupBy { it }.filterValues { it.size > 1 }.keys}",
            patterns.size,
            patterns.toSet().size,
        )
    }

    @Test
    fun givenWhitelist_whenCheck_thenNoDuplicatePatterns() {
        val result = BuiltinRulesLoader.parse(readAsset())

        val patterns = result.whitelist.map { it.pattern }
        assertEquals(patterns.size, patterns.toSet().size)
    }

    // ============ 核心安全约束：白名单生效 ============

    @Test
    fun givenWhitelistedDomain_whenEvaluate_thenNotBlockedEvenIfSubdomainOfBlacklisted() {
        val result = BuiltinRulesLoader.parse(readAsset())
        val engine = DomainRuleEngine.from(
            DomainPolicy(blacklist = result.blacklist, whitelist = result.whitelist),
        )

        // 白名单里登记的每个域名，无论是否同时落在某条黑名单后缀下，都必须放行
        result.whitelist.forEach { white ->
            assertFalse(
                "白名单域名被拦截了，误杀防护失效：${white.pattern}",
                engine.shouldBlock(white.pattern),
            )
        }
    }

    @Test
    fun givenKnownAdDomain_whenEvaluate_thenBlocked() {
        val result = BuiltinRulesLoader.parse(readAsset())
        val engine = DomainRuleEngine.from(
            DomainPolicy(blacklist = result.blacklist, whitelist = result.whitelist),
        )

        assertTrue("doubleclick.net 应被拦截", engine.shouldBlock("doubleclick.net"))
        assertTrue(
            "doubleclick.net 的子域应被拦截（SUFFIX 语义）",
            engine.shouldBlock("securepubads.g.doubleclick.net"),
        )
    }

    @Test
    fun givenBusinessDomain_whenEvaluate_thenNotBlocked() {
        val result = BuiltinRulesLoader.parse(readAsset())
        val engine = DomainRuleEngine.from(
            DomainPolicy(blacklist = result.blacklist, whitelist = result.whitelist),
        )

        // 这些是必须放行的业务/基础服务域名，一旦被拦会直接破坏用户可用性
        listOf(
            "google.com",
            "www.google.com",
            "accounts.google.com",
            "weixin.qq.com",
            "login.taobao.com",
            "github.com",
            "example.com",
        ).forEach { domain ->
            assertFalse("业务域名被误拦：$domain", engine.shouldBlock(domain))
        }
    }

    // ============ 规模约束 ============

    @Test
    fun givenBuiltinBlacklist_whenCount_thenWithinCuratedRange() {
        val result = BuiltinRulesLoader.parse(readAsset())

        assertTrue(
            "内置黑名单应保持精简（< 200 条）以免默认配置产生误杀，当前 ${result.blacklist.size} 条",
            result.blacklist.size < 200,
        )
    }

    @Test
    fun givenBuiltinWhitelist_whenCount_thenNotOverwhelmingBlacklist() {
        val result = BuiltinRulesLoader.parse(readAsset())

        assertTrue(
            "内置白名单不应超过黑名单数量，否则说明规则选取标准有问题",
            result.whitelist.size <= result.blacklist.size,
        )
    }

    // ============ 冗余子域声明是有意的 ============

    @Test
    fun givenBlacklist_whenCheck_thenCategoryValuesAreValid() {
        val root = parsedRoot()
        val validCategories = setOf("AD", "TRACKER", "ANALYTICS", "CUSTOM")

        val array: JSONArray = root.optJSONArray("blacklist") ?: JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val category = obj.optString("category")
            assertTrue(
                "分类 '$category' 不是合法枚举值，将静默退回 AD：${obj.optString("pattern")}",
                category in validCategories,
            )
        }
    }
}
