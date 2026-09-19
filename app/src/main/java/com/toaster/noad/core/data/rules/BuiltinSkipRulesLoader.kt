package com.toaster.noad.core.data.rules

import android.content.Context
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.SkipRuleSource
import com.toaster.noad.core.model.TargetType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置**跳过**规则加载器（S1 无障碍使用）。
 *
 * ## 为什么需要它
 *
 * S1 引擎链路（`EventProcessor` → `S1RuleCache` → `UiMatcher`）本身是完整的，
 * 但规则表 `skip_rule` 没有任何写入来源，导致引擎第一道闸门
 * （按包名取规则）永远返回空列表，**所有事件在第一步就被丢弃**。
 * 表现为"授权成功、应用已纳管，但既不跳过也不产生日志"。
 *
 * 本加载器就是缺失的那一环：把内置的开屏跳过规则从 assets 导入规则表。
 *
 * ## 与域名规则加载器的差异
 *
 * | 维度 | 域名规则 | 跳过规则 |
 * |---|---|---|
 * | 唯一性 | 按 `pattern` 全局唯一 | **同一目标应用可有多条规则**，靠优先级排序 |
 * | 定位方式 | 单一只需匹配模式 | 四种 `targetType`，语义完全不同 |
 * | 结构 | 扁平列表 | 按 `app` 分组（一本规则 = 一个应用） |
 *
 * "按 app 分组"而不是扁平列表，是因为跳过规则天然属于某个应用：
 * 分组后新增应用只需追加一个对象，且**同应用内的规则紧邻**，
 * 便于人工审阅——审阅时人总是按应用来看规则的。
 *
 * ## 数据安全边界
 *
 * 本加载器只负责**解析**，不接触数据库。导入时如何与既有数据合并
 * （尤其是保留用户的停用/删除意图）属于 `RuleRepository` 的职责。
 */
object BuiltinSkipRulesLoader {

    /**
     * 资产路径。
     *
     * 与 `builtin_domains.json` 分文件：两者的消费方、字段结构、
     * 更新节奏均不同，合并只会让任何一方加字段都牵连另一方。
     */
    const val ASSET_PATH = "rules/builtin_skip_rules.json"

    private const val KEY_VERSION = "version"
    private const val KEY_APPS = "apps"

    // app 级字段
    private const val KEY_PACKAGE = "package"
    private const val KEY_APP_LABEL = "appLabel"
    private const val KEY_RULES = "rules"

    // rule 级字段
    private const val KEY_NAME = "name"
    private const val KEY_ACTIVITY = "activity"
    private const val KEY_TARGET_TYPE = "targetType"
    private const val KEY_TARGET_VALUE = "targetValue"
    private const val KEY_MATCH_MODE = "matchMode"
    private const val KEY_PRIORITY = "priority"
    private const val KEY_NOTE = "note"

    /**
     * 内置规则默认优先级基准。低于此值的规则更靠后尝试
     */
    private const val DEFAULT_PRIORITY = 0

    /**
     * 解析结果。
     *
     * [appCount] 与 [skipped] 是数据健康度指标：内置文件应当 `skipped == 0`。
     */
    data class LoadResult(
        val rules: List<SkipRule>,
        val version: Int,
        val appCount: Int,
        val skipped: Int,
    ) {
        val totalCount: Int get() = rules.size

        /** 涉及的应用包名集合，供 UI 展示"覆盖了哪些应用" */
        val packages: Set<String> get() = rules.mapTo(LinkedHashSet()) { it.packageName }

        companion object {
            val EMPTY = LoadResult(emptyList(), version = 0, appCount = 0, skipped = 0)
        }
    }

    /** 从 assets 读取并解析。文件缺失或读取失败时返回 [LoadResult.EMPTY]，不抛异常。 */
    fun load(context: Context): LoadResult {
        val raw = BuiltinRuleAssets.readAssetText(context, ASSET_PATH) ?: return LoadResult.EMPTY
        return parse(raw)
    }

    /** 纯 JVM 解析入口，供单测直接调用。 */
    fun parse(rawJson: String): LoadResult {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return LoadResult.EMPTY

        val version = root.optInt(KEY_VERSION, 0)
        val apps = root.optJSONArray(KEY_APPS) ?: return LoadResult.EMPTY.copy(version = version)

        val rules = ArrayList<SkipRule>(apps.length() * INITIAL_RULES_PER_APP)
        var appCount = 0
        var skipped = 0

        for (i in 0 until apps.length()) {
            val appObj = apps.optJSONObject(i)
            if (appObj == null) {
                skipped++
                continue
            }

            val packageName = appObj.optString(KEY_PACKAGE).trim()
            if (packageName.isEmpty()) {
                // 无包名的规则无法路由到任何应用，整组丢弃
                skipped++
                continue
            }

            val parsed = parseAppRulesDetailed(appObj, packageName)
            skipped += parsed.skipped
            if (parsed.rules.isEmpty()) {
                // 应用下没有一条可用规则：不计入 appCount，
                // 避免 UI 上报"覆盖了 N 个应用"而实际一条规则都没有
                continue
            }

            rules += parsed.rules
            appCount++
        }

        return LoadResult(rules, version, appCount, skipped)
    }

    private data class AppRules(val rules: List<SkipRule>, val skipped: Int)

    /**
     * 解析单个应用下的规则数组。
     *
     * 逐条容错：坏条目只跳过自己，同一应用下的其他规则仍会加载。
     * 这与域名规则解析器的契约一致 —— 一个手误不应让整份文件失效。
     */
    private fun parseAppRulesDetailed(appObj: JSONObject, packageName: String): AppRules {
        val array = appObj.optJSONArray(KEY_RULES) ?: return AppRules(emptyList(), 0)
        val appLabel = appObj.optString(KEY_APP_LABEL).trim()

        val rules = ArrayList<SkipRule>(array.length())
        var skipped = 0

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                skipped++
                continue
            }

            val name = obj.optString(KEY_NAME).trim()
            val targetValue = obj.optString(KEY_TARGET_VALUE).trim()
            if (targetValue.isEmpty()) {
                skipped++
                continue
            }

            val targetType = parseTargetType(obj.optString(KEY_TARGET_TYPE))
            if (targetType == null) {
                // 未知定位方式无法执行，只能跳过。与域名规则"未知分类退回默认值"
                // 不同：定位方式错了会去点**错误的节点**，误操作代价高于漏拦。
                skipped++
                continue
            }

            rules += SkipRule(
                id = 0L,
                name = name.ifEmpty { defaultRuleName(appLabel, targetValue) },
                packageName = packageName,
                activityName = obj.optString(KEY_ACTIVITY).trim().ifBlank { null },
                targetType = targetType,
                targetValue = targetValue,
                matchMode = parseMatchMode(obj.optString(KEY_MATCH_MODE), targetType),
                clickDelayMs = 0L,
                enabled = true,
                priority = obj.optInt(KEY_PRIORITY, DEFAULT_PRIORITY),
                source = SkipRuleSource.BUILTIN,
            )
        }

        return AppRules(rules, skipped)
    }

    /**
     * 定位方式解析。
     *
     * 返回 `null` 表示**无法使用**该规则（而非退回默认值），
     * 理由见调用处注释：定位方式错误会导致点击错误节点。
     */
    private fun parseTargetType(raw: String): TargetType? =
        when (raw.trim().uppercase()) {
            "VIEW_ID", "VIEWID" -> TargetType.VIEW_ID
            "TEXT" -> TargetType.TEXT
            "DESCRIPTION", "DESC" -> TargetType.DESCRIPTION
            // 只读用途：COORDINATE 跨分辨率必然失效，内置规则不提供
            "COORDINATE" -> TargetType.COORDINATE
            else -> null
        }

    /**
     * 匹配模式解析。
     *
     * 未显式声明时按定位方式给出合理默认：
     * - `VIEW_ID` 天然是全串比较（见 `UiMatcher.matchByViewId`），模式不生效
     * - 文本类定位默认**精确**：内置规则宁可漏拦，不可误点
     */
    private fun parseMatchMode(raw: String, targetType: TargetType): MatchMode {
        val explicit = when (raw.trim().uppercase()) {
            "EXACT" -> MatchMode.EXACT
            "CONTAINS" -> MatchMode.CONTAINS
            "REGEX" -> MatchMode.REGEX
            else -> null
        }
        return explicit ?: when (targetType) {
            TargetType.TEXT, TargetType.DESCRIPTION -> MatchMode.EXACT
            else -> MatchMode.EXACT
        }
    }

    private fun defaultRuleName(appLabel: String, targetValue: String): String =
        if (appLabel.isEmpty()) targetValue else "$appLabel-$targetValue"

    private const val INITIAL_RULES_PER_APP = 4
}
