package com.toaster.noad.core.data.rules

import android.content.Context

/**
 * 内置规则资产读取的共用入口。
 *
 * ## 为什么单独抽出
 *
 * 域名规则（[BuiltinRulesLoader]）与跳过规则（[BuiltinSkipRulesLoader]）
 * 使用两份结构不同的资产文件，但"读文件"这一步是完全相同的：
 * 打开、按 UTF-8 读全量、任何失败都返回 `null` 而不抛异常。
 *
 * 把这段重复的 IO 与容错集中在一处，有两个实际收益：
 * 1. 启动路径（`Application.onCreate`）上**不允许**因资产问题崩溃，
 *    容错策略只需在一处保证正确；
 * 2. 两个加载器都保持"解析可纯 JVM 测试"的形态 —— IO 被隔离在这里，
 *    解析函数只吃 `String`。
 */
internal object BuiltinRuleAssets {

    /**
     * 读取 assets 中的文本文件。
     *
     * @return 文件内容；文件不存在、编码异常或读取中断时返回 `null`。
     *
     * 刻意不区分各类失败的返回值：对调用方而言"拿不到内容"的处理方式
     * 完全一致（跳过导入，保留既有数据），细分错误类型只会增加无用的分支。
     * 真正的诊断信息由调用方在导入结果中体现。
     */
    fun readAssetText(context: Context, assetPath: String): String? =
        runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrNull()
}
