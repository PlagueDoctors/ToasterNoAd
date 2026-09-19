package com.toaster.noad.core.data.rules

import com.toaster.noad.core.model.DomainCategory
import com.toaster.noad.core.model.DomainMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BuiltinRulesLoader] 解析逻辑的单元测试。
 *
 * ## 为什么重点测「失败路径」
 *
 * 内置规则来自 assets 文件，属于**可被人工编辑的数据**。
 * 一个手误（漏逗号、写错分类、留空 pattern）不应导致规则集整体失效，
 * 更不应导致应用启动崩溃。因此本测试覆盖的重点是
 * 「坏数据被单条跳过、好数据仍被加载」这一契约。
 */
class BuiltinRulesLoaderTest {

    // ============ 正常路径 ============

    @Test
    fun givenValidRules_whenParse_thenReturnBothLists() {
        val json = """
            {
              "version": 3,
              "blacklist": [
                { "pattern": "ads.example.com", "category": "AD", "note": "示例" },
                { "pattern": "track.example.net", "category": "TRACKER" }
              ],
              "whitelist": [
                { "pattern": "login.example.com", "category": "CUSTOM" }
              ]
            }
        """.trimIndent()

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(3, result.version)
        assertEquals(2, result.blacklist.size)
        assertEquals(1, result.whitelist.size)
        assertEquals(0, result.skipped)
        assertEquals(3, result.totalCount)
    }

    @Test
    fun givenRule_whenParse_thenMatchTypeIsSuffix() {
        val json = """{ "blacklist": [ { "pattern": "ads.example.com" } ] }"""

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(DomainMatchType.SUFFIX, result.blacklist.single().matchType)
    }

    @Test
    fun givenRule_whenParse_thenEnabledByDefault() {
        val json = """{ "blacklist": [ { "pattern": "ads.example.com" } ] }"""

        val result = BuiltinRulesLoader.parse(json)

        assertTrue(result.blacklist.single().enabled)
    }

    @Test
    fun givenMissingNote_whenParse_thenNoteIsNull() {
        val json = """{ "blacklist": [ { "pattern": "ads.example.com" } ] }"""

        val result = BuiltinRulesLoader.parse(json)

        assertNull(result.blacklist.single().note)
    }

    @Test
    fun givenEachCategory_whenParse_thenCategoryParsed() {
        val json = """
            {
              "blacklist": [
                { "pattern": "a.com", "category": "AD" },
                { "pattern": "b.com", "category": "TRACKER" },
                { "pattern": "c.com", "category": "ANALYTICS" },
                { "pattern": "d.com", "category": "CUSTOM" }
              ]
            }
        """.trimIndent()

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(
            listOf(
                DomainCategory.AD,
                DomainCategory.TRACKER,
                DomainCategory.ANALYTICS,
                DomainCategory.CUSTOM,
            ),
            result.blacklist.map { it.category },
        )
    }

    @Test
    fun givenLowercaseCategory_whenParse_thenStillParsed() {
        val json = """{ "blacklist": [ { "pattern": "a.com", "category": "tracker" } ] }"""

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(DomainCategory.TRACKER, result.blacklist.single().category)
    }

    // ============ 坏数据路径：单条跳过，不整体丢弃 ============

    @Test
    fun givenUnknownCategory_whenParse_thenFallbackToAd() {
        val json = """{ "blacklist": [ { "pattern": "a.com", "category": "SOMETHING_NEW" } ] }"""

        val result = BuiltinRulesLoader.parse(json)

        // 新增分类不应导致规则被丢弃，退回 AD 是安全默认值
        assertEquals(1, result.blacklist.size)
        assertEquals(DomainCategory.AD, result.blacklist.single().category)
    }

    @Test
    fun givenBlankPattern_whenParse_thenEntrySkipped() {
        val json = """
            {
              "blacklist": [
                { "pattern": "   ", "category": "AD" },
                { "pattern": "good.com", "category": "AD" }
              ]
            }
        """.trimIndent()

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(1, result.blacklist.size)
        assertEquals("good.com", result.blacklist.single().pattern)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenMissingPattern_whenParse_thenEntrySkipped() {
        val json = """
            {
              "blacklist": [
                { "category": "AD" },
                { "pattern": "good.com", "category": "AD" }
              ]
            }
        """.trimIndent()

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(1, result.blacklist.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenNonObjectEntry_whenParse_thenEntrySkipped() {
        val json = """{ "blacklist": [ "not-an-object", { "pattern": "good.com" } ] }"""

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(1, result.blacklist.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenMalformedJson_whenParse_thenReturnEmptyWithoutThrowing() {
        val result = BuiltinRulesLoader.parse("{ this is not json")

        assertEquals(0, result.totalCount)
        assertEquals(BuiltinRulesLoader.LoadResult.EMPTY.totalCount, result.totalCount)
    }

    @Test
    fun givenEmptyString_whenParse_thenReturnEmpty() {
        val result = BuiltinRulesLoader.parse("")

        assertEquals(0, result.totalCount)
    }

    @Test
    fun givenMissingLists_whenParse_thenReturnEmptyLists() {
        val json = """{ "version": 1 }"""

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(0, result.blacklist.size)
        assertEquals(0, result.whitelist.size)
        assertEquals(1, result.version)
    }

    @Test
    fun givenNullEntryInsideArray_whenParse_thenEntrySkipped() {
        val json = """{ "blacklist": [ { "pattern": "a.com" }, null ] }"""

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(1, result.blacklist.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenSkippedInBothLists_whenParse_thenSkippedIsSummed() {
        val json = """
            {
              "blacklist": [ { "pattern": "" } ],
              "whitelist": [ { "pattern": "" } ]
            }
        """.trimIndent()

        val result = BuiltinRulesLoader.parse(json)

        assertEquals(2, result.skipped)
    }

    @Test
    fun givenPatternWithWhitespace_whenParse_thenTrimmed() {
        val json = """{ "blacklist": [ { "pattern": "  ads.example.com  " } ] }"""

        val result = BuiltinRulesLoader.parse(json)

        assertEquals("ads.example.com", result.blacklist.single().pattern)
    }
}
