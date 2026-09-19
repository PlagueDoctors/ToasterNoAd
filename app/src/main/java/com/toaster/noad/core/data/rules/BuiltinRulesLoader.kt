package com.toaster.noad.core.data.rules

import android.content.Context
import com.toaster.noad.core.model.DomainCategory
import com.toaster.noad.core.model.DomainMatchType
import com.toaster.noad.core.model.DomainRule
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置**域名**规则加载器（S2/S3/S4 使用）。
 *
 * ## 加载范围
 *
 * 只读取 [ASSET_PATH] 一个文件。域名规则与跳过规则的字段结构不同，
 * 由各自的加载器负责解析，共享的只有「坏数据单条跳过」这一容错原则
 * （见 [BuiltinRuleAssets]）。
 *
 * ## 解析必须可离线测试
 *
 * [parse] 不依赖 `Context`，因此可以在 JVM 单测中直接喂字符串。
 * 这不是可有可无的设计：内置规则是**可被人工编辑的数据**，
 * 一个手误不应导致规则整体失效，更不应导致启动崩溃。
 * 这类容错行为只能靠单测锁住。
 */
object BuiltinRulesLoader {

    const val SOURCE_BUILTIN = "builtin"

    /**
     * 资产路径。
     *
     * 与跳过规则分文件存放而非合并成一个：两者字段结构、消费方、
     * 更新节奏都不同；合并后任何一方加字段都要考虑另一方的解析器。
     */
    const val ASSET_PATH = "rules/builtin_domains.json"

    private const val KEY_BLACKLIST = "blacklist"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_PATTERN = "pattern"
    private const val KEY_CATEGORY = "category"
    private const val KEY_NOTE = "note"
    private const val KEY_VERSION = "version"

    /**
     * 解析结果。
     *
     * [skipped] 不是错误计数，而是**数据健康度指标**：
     * 内置文件应当 `skipped == 0`，非零说明文件被改坏了，
     * 由 `BuiltinRulesAssetTest` 对该契约做断言。
     */
    data class LoadResult(
        val blacklist: List<DomainRule>,
        val whitelist: List<DomainRule>,
        val version: Int,
        val skipped: Int,
    ) {
        val totalCount: Int get() = blacklist.size + whitelist.size

        companion object {
            val EMPTY = LoadResult(emptyList(), emptyList(), version = 0, skipped = 0)
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
        val blacklist = parseEntries(root.optJSONArray(KEY_BLACKLIST))
        val whitelist = parseEntries(root.optJSONArray(KEY_WHITELIST))

        return LoadResult(
            blacklist = blacklist.rules,
            whitelist = whitelist.rules,
            version = version,
            skipped = blacklist.skipped + whitelist.skipped,
        )
    }

    private data class ParsedEntries(val rules: List<DomainRule>, val skipped: Int)

    /**
     * 解析条目数组。
     *
     * 四种坏数据均只跳过单条：非对象元素、`pattern` 缺失、`pattern` 空白、
     * 无法识别的 `category`（该项退回 [DomainCategory.AD] 而非丢弃，
     * 因为新增分类不应该让规则消失）。
     */
    private fun parseEntries(array: JSONArray?): ParsedEntries {
        if (array == null) return ParsedEntries(emptyList(), 0)

        val rules = ArrayList<DomainRule>(array.length())
        var skipped = 0

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                skipped++
                continue
            }

            val pattern = obj.optString(KEY_PATTERN).trim()
            if (pattern.isEmpty()) {
                skipped++
                continue
            }

            rules += DomainRule(
                matchType = DomainMatchType.SUFFIX,
                pattern = pattern,
                category = parseCategory(obj.optString(KEY_CATEGORY)),
                note = obj.optString(KEY_NOTE).ifBlank { null },
                enabled = true,
            )
        }

        return ParsedEntries(rules, skipped)
    }

    private fun parseCategory(raw: String): DomainCategory =
        runCatching { DomainCategory.valueOf(raw.trim().uppercase()) }
            .getOrDefault(DomainCategory.AD)
}
