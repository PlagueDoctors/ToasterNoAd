package com.toaster.noad.core.data.rules

import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRuleSource
import com.toaster.noad.core.model.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BuiltinSkipRulesLoader] 解析逻辑的单元测试。
 *
 * ## 测试重点：坏数据不能扩散
 *
 * 内置规则是**可被人工编辑的数据**。一个手误（漏写 targetType、
 * 写错枚举名、留空 targetValue）不应导致整份文件失效，
 * 更不应导致应用启动崩溃 —— 加载发生在 `Application.onCreate` 路径上。
 *
 * 因此本测试大量覆盖"单条坏数据被跳过、同应用的其他规则仍加载"、
 * "整个应用组失效不影响其他应用组"这两类契约。
 */
class BuiltinSkipRulesLoaderTest {

    // ============ 正常路径 ============

    @Test
    fun givenValidFile_whenParse_thenRulesAndCountsAreCorrect() {
        val json = """
            {
              "version": 2,
              "apps": [
                {
                  "package": "com.example.a",
                  "appLabel": "示例A",
                  "rules": [
                    { "name": "r1", "targetType": "VIEW_ID", "targetValue": "com.example.a:id/skip" },
                    { "name": "r2", "targetType": "TEXT", "targetValue": "跳过" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(2, result.version)
        assertEquals(2, result.totalCount)
        assertEquals(1, result.appCount)
        assertEquals(0, result.skipped)
    }

    @Test
    fun givenFile_whenParse_thenSourceIsBuiltin() {
        val json = """
            { "apps": [ { "package": "com.example", "rules": [
              { "targetType": "VIEW_ID", "targetValue": "id/skip" } ] } ] }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(SkipRuleSource.BUILTIN, result.rules.single().source)
    }

    @Test
    fun givenFile_whenParse_thenPackageAppliedToAllRules() {
        val json = """
            {
              "apps": [
                { "package": "com.example.a", "rules": [
                  { "targetType": "VIEW_ID", "targetValue": "id/skip" } ] },
                { "package": "com.example.b", "rules": [
                  { "targetType": "TEXT", "targetValue": "跳过" } ] }
              ]
            }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        // app 级 package 必须下发到每条规则，否则规则无法路由到应用
        assertEquals(listOf("com.example.a", "com.example.b"), result.rules.map { it.packageName })
        assertEquals(setOf("com.example.a", "com.example.b"), result.packages)
    }

    @Test
    fun givenEachTargetType_whenParse_thenParsed() {
        val json = rulesJson(
            """{ "targetType": "VIEW_ID", "targetValue": "id/a" }""",
            """{ "targetType": "TEXT", "targetValue": "跳过" }""",
            """{ "targetType": "DESCRIPTION", "targetValue": "关闭" }""",
            """{ "targetType": "COORDINATE", "targetValue": "100,200" }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(
            listOf(
                TargetType.VIEW_ID,
                TargetType.TEXT,
                TargetType.DESCRIPTION,
                TargetType.COORDINATE,
            ),
            result.rules.map { it.targetType },
        )
    }

    @Test
    fun givenLowercaseTargetType_whenParse_thenParsed() {
        val json = rulesJson("""{ "targetType": "view_id", "targetValue": "id/a" }""")

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(TargetType.VIEW_ID, result.rules.single().targetType)
    }

    @Test
    fun givenViewIdAlias_whenParse_thenParsedAsViewId() {
        // "viewId" 是更自然的写法，允许它可减少人工编写时的困惑
        val json = rulesJson("""{ "targetType": "viewId", "targetValue": "id/a" }""")

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(TargetType.VIEW_ID, result.rules.single().targetType)
    }

    @Test
    fun givenExplicitMatchMode_whenParse_thenHonored() {
        val json = rulesJson(
            """{ "targetType": "TEXT", "targetValue": "跳过广告 5s", "matchMode": "CONTAINS" }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(MatchMode.CONTAINS, result.rules.single().matchMode)
    }

    @Test
    fun givenMissingMatchMode_whenParse_thenDefaultsToExact() {
        val json = rulesJson("""{ "targetType": "TEXT", "targetValue": "跳过" }""")

        val result = BuiltinSkipRulesLoader.parse(json)

        // 默认必须是 EXACT：内置规则宁可漏拦，绝不错点
        assertEquals(MatchMode.EXACT, result.rules.single().matchMode)
    }

    @Test
    fun givenActivityField_whenParse_thenApplied() {
        val json = rulesJson(
            """{ "targetType": "TEXT", "targetValue": "跳过", "activity": "com.example.SplashActivity" }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals("com.example.SplashActivity", result.rules.single().activityName)
    }

    @Test
    fun givenNoActivityField_whenParse_thenActivityIsNull() {
        val json = rulesJson("""{ "targetType": "TEXT", "targetValue": "跳过" }""")

        val result = BuiltinSkipRulesLoader.parse(json)

        // null 表示"应用内所有界面均可触发"
        assertNull(result.rules.single().activityName)
    }

    @Test
    fun givenBlankActivityField_whenParse_thenActivityIsNull() {
        val json = rulesJson(
            """{ "targetType": "TEXT", "targetValue": "跳过", "activity": "   " }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        assertNull(result.rules.single().activityName)
    }

    @Test
    fun givenPriority_whenParse_thenApplied() {
        val json = rulesJson(
            """{ "targetType": "VIEW_ID", "targetValue": "id/a", "priority": 100 }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(100, result.rules.single().priority)
    }

    @Test
    fun givenNoPriority_whenParse_thenZero() {
        val json = rulesJson("""{ "targetType": "VIEW_ID", "targetValue": "id/a" }""")

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(0, result.rules.single().priority)
    }

    @Test
    fun givenEnabledField_whenParse_thenEnabled() {
        val json = rulesJson("""{ "targetType": "VIEW_ID", "targetValue": "id/a" }""")

        val result = BuiltinSkipRulesLoader.parse(json)

        assertTrue(result.rules.single().enabled)
    }

    @Test
    fun givenId_whenParse_thenIdIsZeroForRoomAutoGenerate() {
        val json = rulesJson("""{ "targetType": "VIEW_ID", "targetValue": "id/a" }""")

        val result = BuiltinSkipRulesLoader.parse(json)

        // 非 0 的 id 会与 Room 自增主键冲突
        assertEquals(0L, result.rules.single().id)
    }

    @Test
    fun givenBlankName_whenParse_thenNameDerivedFromLabelAndValue() {
        val json = """
            {
              "apps": [
                { "package": "com.example", "appLabel": "示例", "rules": [
                  { "targetType": "TEXT", "targetValue": "跳过" } ] }
              ]
            }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        // 规则名会展示在日志与规则页，不能为空
        assertEquals("示例-跳过", result.rules.single().name)
    }

    @Test
    fun givenTrimmedValues_whenParse_thenTrimmed() {
        val json = rulesJson(
            """{ "targetType": "TEXT", "targetValue": "  跳过  ", "name": "  规则名  " }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals("跳过", result.rules.single().targetValue)
        assertEquals("规则名", result.rules.single().name)
    }

    // ============ 坏数据路径 ============

    @Test
    fun givenUnknownTargetType_whenParse_thenEntrySkipped() {
        val json = rulesJson(
            """{ "targetType": "TELEPATHY", "targetValue": "id/a" }""",
            """{ "targetType": "TEXT", "targetValue": "跳过" }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        // 与域名规则"未知分类退回默认值"不同：定位方式错了会去点错误的节点，
        // 误操作代价高于漏拦，因此必须跳过而非退回默认值
        assertEquals(1, result.totalCount)
        assertEquals(1, result.skipped)
        assertEquals(TargetType.TEXT, result.rules.single().targetType)
    }

    @Test
    fun givenMissingTargetType_whenParse_thenEntrySkipped() {
        val json = rulesJson(
            """{ "targetValue": "id/a" }""",
            """{ "targetType": "TEXT", "targetValue": "跳过" }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(1, result.totalCount)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenBlankTargetValue_whenParse_thenEntrySkipped() {
        val json = rulesJson(
            """{ "targetType": "TEXT", "targetValue": "   " }""",
            """{ "targetType": "TEXT", "targetValue": "跳过" }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(1, result.totalCount)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenMissingTargetValue_whenParse_thenEntrySkipped() {
        val json = rulesJson(
            """{ "targetType": "TEXT" }""",
            """{ "targetType": "TEXT", "targetValue": "跳过" }""",
        )

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(1, result.totalCount)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenNonObjectRule_whenParse_thenEntrySkipped() {
        val json = """
            {
              "apps": [
                { "package": "com.example", "rules": [
                  "not-an-object",
                  { "targetType": "TEXT", "targetValue": "跳过" } ] }
              ]
            }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(1, result.totalCount)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenNullRuleInsideArray_whenParse_thenEntrySkipped() {
        val json = """
            {
              "apps": [
                { "package": "com.example", "rules": [
                  { "targetType": "TEXT", "targetValue": "跳过" }, null ] }
              ]
            }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(1, result.totalCount)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenAppWithoutPackage_whenParse_thenWholeGroupSkipped() {
        val json = """
            {
              "apps": [
                { "rules": [ { "targetType": "TEXT", "targetValue": "跳过" } ] },
                { "package": "com.example", "rules": [
                  { "targetType": "TEXT", "targetValue": "跳过" } ] }
              ]
            }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        // 无包名的规则无法路由到任何应用
        assertEquals(1, result.totalCount)
        assertEquals(1, result.skipped)
        assertEquals("com.example", result.rules.single().packageName)
    }

    @Test
    fun givenAppWithAllRulesBroken_whenParse_thenAppNotCounted() {
        val json = """
            {
              "apps": [
                { "package": "com.broken", "rules": [ { "targetValue": "id/a" } ] },
                { "package": "com.good", "rules": [
                  { "targetType": "TEXT", "targetValue": "跳过" } ] }
              ]
            }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        // 一条规则都没加载成功的应用不应计入 appCount，
        // 否则 UI 会显示"覆盖 N 个应用"而实际毫无作用
        assertEquals(1, result.appCount)
        assertEquals(1, result.skipped)
    }

    @Test
    fun givenBrokenApp_whenParse_thenOtherAppsStillLoaded() {
        val json = """
            {
              "apps": [
                { "package": "com.a", "rules": [ { "targetValue": "x" } ] },
                { "package": "com.b", "rules": [
                  { "targetType": "TEXT", "targetValue": "跳过" } ] },
                { "package": "com.c", "rules": [
                  { "targetType": "VIEW_ID", "targetValue": "id/skip" } ] }
              ]
            }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        // 一个应用的错误不得扩散到其他应用 —— 这是"单条跳过"契约的组级版本
        assertEquals(setOf("com.b", "com.c"), result.packages)
        assertEquals(2, result.appCount)
    }

    @Test
    fun givenAppWithoutRulesArray_whenParse_thenContributesNothing() {
        val json = """
            { "apps": [ { "package": "com.empty" } ] }
        """.trimIndent()

        val result = BuiltinSkipRulesLoader.parse(json)

        assertEquals(0, result.totalCount)
        assertEquals(0, result.appCount)
        assertEquals(0, result.skipped)
    }

    // ============ 整体失败路径 ============

    @Test
    fun givenMalformedJson_whenParse_thenReturnEmptyWithoutThrowing() {
        val result = BuiltinSkipRulesLoader.parse("{ this is not json")

        assertEquals(0, result.totalCount)
        assertEquals(BuiltinSkipRulesLoader.LoadResult.EMPTY.totalCount, result.totalCount)
    }

    @Test
    fun givenEmptyString_whenParse_thenReturnEmpty() {
        assertEquals(0, BuiltinSkipRulesLoader.parse("").totalCount)
    }

    @Test
    fun givenMissingAppsArray_whenParse_thenEmptyButVersionKept() {
        val result = BuiltinSkipRulesLoader.parse("""{ "version": 7 }""")

        assertEquals(0, result.totalCount)
        // 版本号仍应读出：它用于诊断"加载到了哪一版数据"
        assertEquals(7, result.version)
    }

    @Test
    fun givenEmptyAppsArray_whenParse_thenEmpty() {
        val result = BuiltinSkipRulesLoader.parse("""{ "version": 1, "apps": [] }""")

        assertEquals(0, result.totalCount)
        assertEquals(0, result.appCount)
    }

    // ============ 辅助 ============

    /** 把若干条规则包进一个固定应用下，减少测试样板 */
    private fun rulesJson(vararg rules: String): String = """
        {
          "apps": [
            { "package": "com.example", "appLabel": "示例", "rules": [ ${rules.joinToString(",")} ] }
          ]
        }
    """.trimIndent()
}
