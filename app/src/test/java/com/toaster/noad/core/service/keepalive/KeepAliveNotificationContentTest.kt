package com.toaster.noad.core.service.keepalive

import com.toaster.noad.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KeepAliveNotificationContent] 单元测试。
 *
 * ## 为什么这个类必须被重点测试
 *
 * 常驻通知是用户对「拦截是否在工作」最直接的感知窗口 ——
 * 它的内容错误（次数不对、应用名残缺、开关关了还在动）会被
 * 解读为「应用不可信」。而通知文案由「开关 × 计数 × 最新记录」
 * 三个输入共同决定，其中「计数 > 0 但最新记录缺失」「应用名为空串」
 * 两个防御分支**在正常数据下永远不会走到**，没有测试就等于
 * 从未被验证 —— 恰好是容量裁剪与并发写入交错时才会暴露的坑。
 *
 * ## 测试组的划分依据
 *
 * 按 [KeepAliveNotificationContent.resolve] 的四个分支分组，
 * 另加两条防御分支与参数传递正确性断言。
 */
class KeepAliveNotificationContentTest {

    // ==================================================================
    // 第 1 组：开关关闭 —— 内容必须是纯静态保活文案
    // ==================================================================

    @Test
    fun givenStatsDisabled_whenResolved_thenStaticContentRegardlessOfData() {
        // 即使有拦截数据，开关关闭也不得显示任何动态内容
        val content = KeepAliveNotificationContent.resolve(
            showStats = false,
            totalCount = 42,
            latestAppLabel = "哔哩哔哩",
            latestAdTypeLabel = "开屏广告",
        )

        assertEquals(R.string.keep_alive_notification_title, content.titleRes)
        assertEquals(R.string.keep_alive_notification_text, content.textRes)
        assertEquals(emptyList<Any>(), content.titleArgs)
        assertEquals(emptyList<Any>(), content.textArgs)
    }

    // ==================================================================
    // 第 2 组：拦截数为 0 —— 静态标题 + 「暂无拦截记录」
    // ==================================================================

    @Test
    fun givenZeroCount_whenStatsEnabled_thenIdleTitleAndNoneText() {
        val content = KeepAliveNotificationContent.resolve(
            showStats = true,
            totalCount = 0,
            latestAppLabel = null,
            latestAdTypeLabel = "",
        )

        assertEquals(R.string.keep_alive_notification_title, content.titleRes)
        assertEquals(R.string.keep_alive_status_text_none, content.textRes)
    }

    // ==================================================================
    // 第 3 组：正常展示 —— 计数进标题，应用与类型进正文
    // ==================================================================

    @Test
    fun givenInterceptions_whenStatsEnabled_thenActiveTitleWithCountAndLatestText() {
        val content = KeepAliveNotificationContent.resolve(
            showStats = true,
            totalCount = 42,
            latestAppLabel = "哔哩哔哩",
            latestAdTypeLabel = "开屏广告",
        )

        assertEquals(R.string.keep_alive_status_title_active, content.titleRes)
        assertEquals("拦截次数必须作为标题格式化参数传入", listOf<Any>(42), content.titleArgs)
        assertEquals(R.string.keep_alive_status_text_latest, content.textRes)
        assertEquals(
            "应用名与广告类型必须按顺序传入正文",
            listOf<Any>("哔哩哔哩", "开屏广告"),
            content.textArgs,
        )
    }

    // ==================================================================
    // 第 4 组：防御分支 —— 正常数据走不到、交错窗口会走到
    // ==================================================================

    @Test
    fun givenCountPositiveButLatestMissing_whenStatsEnabled_thenShowNoneTextDefensively() {
        // COUNT 与 LIMIT 1 是两次独立快照：裁剪/并发交错下可能出现
        // 「计数已刷新、最新行刚被裁掉」的瞬间。要求：不崩溃、正文回退。
        val content = KeepAliveNotificationContent.resolve(
            showStats = true,
            totalCount = 42,
            latestAppLabel = null,
            latestAdTypeLabel = "",
        )

        assertEquals(R.string.keep_alive_status_title_active, content.titleRes)
        assertEquals(R.string.keep_alive_status_text_none, content.textRes)
    }

    @Test
    fun givenBlankAppLabel_whenStatsEnabled_thenFallbackToUnknownAppResource() {
        // 应用名空串时不得输出「最近拦截：· 开屏广告」这种残缺文案，
        // 兜底为资源引用（由服务侧解析为「未知应用」）
        val content = KeepAliveNotificationContent.resolve(
            showStats = true,
            totalCount = 42,
            latestAppLabel = "  ",
            latestAdTypeLabel = "开屏广告",
        )

        val labelArg = content.textArgs.first()
        assertEquals(
            "空应用名必须兜底为 keep_alive_status_unknown_app 资源引用",
            ResId(R.string.keep_alive_status_unknown_app),
            labelArg,
        )
        assertEquals("开屏广告", content.textArgs[1])
    }

    @Test
    fun givenBlankAdTypeLabel_whenStatsEnabled_thenPassedThroughVerbatim() {
        // AdType.label 由枚举保证非空，本层不做兜底（避免重复决策）：
        // 传什么就展示什么，验证透传语义
        val content = KeepAliveNotificationContent.resolve(
            showStats = true,
            totalCount = 1,
            latestAppLabel = "淘宝",
            latestAdTypeLabel = "弹窗广告",
        )

        assertEquals("弹窗广告", content.textArgs[1])
    }
}
