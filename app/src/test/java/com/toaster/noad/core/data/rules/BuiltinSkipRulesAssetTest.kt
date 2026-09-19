package com.toaster.noad.core.data.rules

import com.toaster.noad.core.engine.ui.UiMatcher
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRuleSource
import com.toaster.noad.core.model.TargetType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 对**真实的**内置跳过规则文件做契约测试。
 *
 * ## 为什么这类测试比解析器测试更重要
 *
 * [BuiltinSkipRulesLoaderTest] 验证的是"解析逻辑遇到坏数据不会崩"，
 * 但一个**语义写错**的文件（例如把「跳过」写成 CONTAINS、
 * 或把某条规则的定位值写成业务按钮文案）能让所有解析测试通过，
 * 却直接在用户设备上造成误点 —— 而 S1 的误点代价可能是付费订阅。
 *
 * 因此本测试直接读取 `src/main/assets/rules/builtin_skip_rules.json`，
 * 对文件本身的格式与语义约束做断言。文件被改坏时，这里会失败。
 */
class BuiltinSkipRulesAssetTest {

    /**
     * 单测工作目录是模块目录（`app/`），与 [BuiltinRulesAssetTest] 一致。
     */
    private val assetFile = File("src/main/assets/rules/builtin_skip_rules.json")

    private val loaderResult by lazy {
        assertTrue(
            "内置跳过规则文件未找到：${assetFile.absolutePath}。工作目录可能已变更。",
            assetFile.exists(),
        )
        BuiltinSkipRulesLoader.parse(assetFile.readText())
    }

    private fun parsedRoot(): JSONObject = JSONObject(assetFile.readText())

    // ============ 文件可用性 ============

    @Test
    fun givenAssetFile_whenParse_thenRulesAreNotEmpty() {
        assertTrue("内置跳过规则为空，S1 将无法跳过任何广告", loaderResult.rules.isNotEmpty())
    }

    @Test
    fun givenAssetFile_whenParse_thenNoEntrySkipped() {
        // skipped 非零意味着文件被改坏了 —— 坏条目会在运行期静默消失
        assertEquals(
            "内置跳过规则文件存在无法解析的条目，请检查 targetType / targetValue 是否缺失",
            0,
            loaderResult.skipped,
        )
    }

    @Test
    fun givenAssetFile_whenParse_thenVersionIsPositive() {
        assertTrue(loaderResult.version > 0)
    }

    @Test
    fun givenAssetFile_whenParse_thenEveryRuleIsBuiltinSource() {
        // 来源标记错误会导致内置规则在导入时被当作"用户可删除资产"处理
        assertTrue(
            loaderResult.rules.all { it.source == SkipRuleSource.BUILTIN },
        )
    }

    // ============ 规则内容的硬约束 ============

    @Test
    fun givenAssetFile_whenParse_thenEveryRuleHasName() {
        loaderResult.rules.forEach { rule ->
            assertTrue("规则缺少可读名称：$rule", rule.name.isNotBlank())
        }
    }

    @Test
    fun givenAssetFile_whenParse_thenEveryRuleHasNote() {
        // note 是后续审计与移除规则的唯一依据，内置文件必须逐条填写
        val root = parsedRoot()
        val apps = root.getJSONArray("apps")
        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            val rules = app.getJSONArray("rules")
            for (j in 0 until rules.length()) {
                val rule = rules.getJSONObject(j)
                assertNotNull(
                    "应用 ${app.getString("package")} 的规则 ${rule.optString("name")} 缺少 note",
                    rule.optString("note").takeIf { it.isNotBlank() },
                )
            }
        }
    }

    @Test
    fun givenAssetFile_whenParse_thenPackageNamesAreUnique() {
        val root = parsedRoot()
        val apps = root.getJSONArray("apps")
        val packages = (0 until apps.length()).map { apps.getJSONObject(it).getString("package") }

        assertEquals(
            "同一应用出现多次，请合并到同一个 app 对象：$packages",
            packages.size,
            packages.toSet().size,
        )
    }

    @Test
    fun givenAssetFile_whenParse_thenRulesUniqueWithinEachApp() {
        // 同一应用内重复的 (targetType, targetValue) 会让同一条规则被导入两次，
        // 在规则页表现为"规则重复出现"
        loaderResult.rules
            .groupBy { it.packageName }
            .forEach { (packageName, rules) ->
                val keys = rules.map { it.targetType to it.targetValue.lowercase() }
                assertEquals(
                    "$packageName 内存在重复规则：$keys",
                    keys.size,
                    keys.toSet().size,
                )
            }
    }

    @Test
    fun givenAssetFile_whenParse_thenPackageNamesLookValid() {
        loaderResult.rules.forEach { rule ->
            // 至少要有一个点，且不含空白 —— 明显的笔误应立即暴露
            assertTrue("包名格式可疑：${rule.packageName}", rule.packageName.contains('.'))
            assertTrue("包名含空白：${rule.packageName}", !rule.packageName.any { it.isWhitespace() })
        }
    }

    // ============ 匹配模式的安全性（本测试最关键的部分）============

    @Test
    fun givenAssetFile_whenParse_thenContainsRulesTargetLongEnoughPhrase() {
        // CONTAINS 是唯一会"命中更长的文本"的模式（见 UiMatcher.matchesContains），
        // 因此内置文件中的 CONTAINS 必须只用于足够长的专有词组。
        // 例如「跳过」用 CONTAINS 会命中「跳过此步」这类引导文案。
        loaderResult.rules
            .filter { it.matchMode == MatchMode.CONTAINS }
            .forEach { rule ->
                assertTrue(
                    "CONTAINS 规则的目标串过短（${rule.targetValue}），" +
                        "应改用 EXACT 或使用更长词组：${rule.name}",
                    rule.targetValue.length >= MIN_CONTAINS_TARGET_LENGTH,
                )
            }
    }

    @Test
    fun givenAssetFile_whenParse_thenContainsRulesAreTextBased() {
        // VIEW_ID / COORDINATE 是精确匹配，声明 CONTAINS 无意义，
        // 出现即说明规则作者误解了字段语义
        loaderResult.rules
            .filter { it.matchMode == MatchMode.CONTAINS }
            .forEach { rule ->
                assertTrue(
                    "非文本定位的规则不应使用 CONTAINS：${rule.name}（${rule.targetType}）",
                    rule.targetType == TargetType.TEXT ||
                        rule.targetType == TargetType.DESCRIPTION,
                )
            }
    }

    @Test
    fun givenAssetFile_whenParse_thenNoRuleTargetsClickBaitWords() {
        // 内置规则绝不收录"点进去"的文案。这些词对应的节点是可点击的业务入口，
        // 命中即造成误点 —— 是 S1 最严重的事故类型。
        val forbidden = listOf(
            "立即", "领取", "查看", "下载", "打开", "安装", "购买", "下单",
            "抽奖", "红包", "优惠", "购买", "开通", "授权", "同意", "允许",
        )

        loaderResult.rules.forEach { rule ->
            val value = rule.targetValue
            val hit = forbidden.firstOrNull { value.contains(it, ignoreCase = true) }
            assertTrue(
                "内置规则目标值含业务/诱导性词汇「$hit」，会造成误点：${rule.name}（$value）",
                hit == null,
            )
        }
    }

    @Test
    fun givenAssetFile_whenParse_thenEveryRuleIsActionable() {
        // 定位值为空或纯空格的规则永远匹配不到，属于无效规则
        loaderResult.rules.forEach { rule ->
            assertTrue("规则定位值为空：${rule.name}", rule.targetValue.isNotBlank())
        }
    }

    @Test
    fun givenAssetFile_whenParse_thenNoCoordinateRules() {
        // 坐标规则跨分辨率必然失效，且无法适配不同屏幕，
        // 内置规则不提供坐标兜底（用户可在规则页自行添加）
        loaderResult.rules.forEach { rule ->
            assertTrue(
                "内置规则不应使用坐标定位（跨分辨率失效）：${rule.name}",
                rule.targetType != TargetType.COORDINATE,
            )
        }
    }

    @Test
    fun givenAssetFile_whenParse_thenPrioritiesAreNonNegative() {
        loaderResult.rules.forEach { rule ->
            assertTrue("规则优先级不应为负：${rule.name} = ${rule.priority}", rule.priority >= 0)
        }
    }

    // ============ 规模约束 ============

    @Test
    fun givenAssetFile_whenParse_thenRuleCountIsReasonable() {
        // 上限的意义：内置规则随版本维护，规模失控意味着单条规则的
        // 维护成本（验证是否失效）无法承担，最终整份文件变成"没人敢改"
        assertTrue(
            "内置跳过规则数量异常（${loaderResult.rules.size}），应保持在可人工维护的规模",
            loaderResult.rules.size in 1..MAX_EXPECTED_RULES,
        )
    }

    // ============ 重点应用覆盖 ============

    @Test
    fun givenAssetFile_whenParse_thenCoversHighFrequencyApps() {
        // 这几款是用户最高频遇到开屏广告的应用，缺失会直接导致
        // "装了但感觉没用"的体验。断言它们确实被覆盖。
        val required = setOf(
            "com.taobao.taobao",
            "com.jingdong.app.mall",
            "tv.danmaku.bili",
        )

        val missing = required - loaderResult.packages
        assertTrue("内置规则缺失高频应用：$missing", missing.isEmpty())
    }

    // ============ 与匹配器的联动 ============

    @Test
    fun givenAssetRules_whenMatchAgainstCleanTree_thenNoFalsePositive() {
        // 用一个「正常的应用界面」节点树验证内置规则不会误命中：
        // 节点全是正文/业务入口，没有任何跳过按钮。
        val matcher = UiMatcher()
        val normalNodes = listOf(
            snapshot(index = 0, depth = 0, text = "首页"),
            snapshot(index = 1, depth = 1, text = "立即购买", clickable = true),
            snapshot(index = 2, depth = 1, text = "登录/注册"),
            snapshot(index = 3, depth = 2, text = "跳过此步可在设置中重新开启", clickable = true),
        )

        loaderResult.rules.forEach { rule ->
            // 逐条单独匹配：一条误命中就说明该规则过于激进
            val match = matcher.match(rule, normalNodes)
            assertEquals(
                "规则「${rule.name}」在普通界面上产生了误命中：${match?.node?.describe()}",
                null,
                match,
            )
        }
    }

    // ============ 辅助 ============

    private fun snapshot(
        index: Int,
        depth: Int,
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        clickable: Boolean = false,
        visible: Boolean = true,
    ) = com.toaster.noad.core.engine.ui.NodeSnapshot(
        index = index,
        parentIndex = -1,
        depth = depth,
        viewId = viewId,
        text = text,
        contentDescription = desc,
        className = null,
        clickable = clickable,
        visible = visible,
        left = 0,
        top = 0,
        right = 100,
        bottom = 50,
    )

    private companion object {
        /** CONTAINS 目标串的最小长度（与文件 meta.criteria 的约定一致） */
        const val MIN_CONTAINS_TARGET_LENGTH = 4

        /** 内置跳过规则的规模上限 */
        const val MAX_EXPECTED_RULES = 300
    }
}
