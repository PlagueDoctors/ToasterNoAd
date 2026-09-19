package com.toaster.noad.core.data.rules

import android.content.Context
import com.toaster.noad.core.model.DomainCategory
import com.toaster.noad.core.model.DomainMatchType
import com.toaster.noad.core.model.DomainRule
import org.json.JSONObject

/**
 * 内置规则集。
 *
 * ## 为什么放在 assets 而不是 Kotlin 常量
 *
 * 规则属于**数据**而非**逻辑**：
 * - 数量会增长，编译进 `object` 常量会让 APK 体积随规则线性增长于 dex 中
 * - 更新规则不需要改动代码，也便于后续做「从网络更新规则集」的增量替换
 * - 便于人工审阅：`assets/rules/builtin_domains.json` 可读、可 diff、可外部生成
 *
 * ## 失败语义
 *
 * 解析失败**不得**导致应用崩溃或阻塞启动。任何字段缺失、类型错误、
 * JSON 畸形都只丢弃该条规则（而不是整个文件），并在返回值中如实上报
 * [LoadResult.skipped] 计数，供日志与 UI 展示。
 */
object BuiltinRulesLoader {

    /** 内置规则的黑名单来源标识，写入数据库 `source` 列 */
    const val SOURCE_BUILTIN = "builtin"

    /** assets 中的相对路径 */
    private const val ASSET_PATH = "rules/builtin_domains.json"

    private const val KEY_BLACKLIST = "blacklist"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_PATTERN = "pattern"
    private const val KEY_CATEGORY = "category"
    private const val KEY_NOTE = "note"
    private const val KEY_VERSION = "version"

    /**
     * 加载结果。
     *
     * 分开返回黑/白名单，因为写库时 `is_whitelist` 是不同取值，
     * 且白名单必须保证「先于黑名单被引擎读到」这一语义在调用方可见。
     */
    data class LoadResult(
        val blacklist: List<DomainRule>,
        val whitelist: List<DomainRule>,
        val version: Int,
        /** 因格式问题被跳过的条目数，用于暴露数据质量问题而非静默吞掉 */
        val skipped: Int,
    ) {
        val totalCount: Int get() = blacklist.size + whitelist.size

        companion object {
            val EMPTY = LoadResult(emptyList(), emptyList(), version = 0, skipped = 0)
        }
    }

    /**
     * 从 assets 读取内置规则。
     *
     * 该函数执行磁盘 IO，调用方必须在 IO 线程执行。
     */
    fun load(context: Context): LoadResult {
        val raw = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return LoadResult.EMPTY

        return parse(raw)
    }

    /**
     * 解析规则 JSON。
     *
     * 独立于 [load] 暴露出来，使解析逻辑可在纯 JVM 单元测试中验证
     * （`org.json` 在单元测试中需要额外配置，因此解析主体用纯 Kotlin 实现，
     * 见 [parseEntries]）。
     */
    fun parse(rawJson: String): LoadResult {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return LoadResult.EMPTY

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

    private data class ParsedEntries(
        val rules: List<DomainRule>,
        val skipped: Int,
    )

    private fun parseEntries(array: org.json.JSONArray?): ParsedEntries {
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

    /** 未知分类统一归入 [DomainCategory.AD]，避免因新增分类导致规则被丢弃 */
    private fun parseCategory(raw: String): DomainCategory =
        runCatching { DomainCategory.valueOf(raw.trim().uppercase()) }
            .getOrDefault(DomainCategory.AD)
}
