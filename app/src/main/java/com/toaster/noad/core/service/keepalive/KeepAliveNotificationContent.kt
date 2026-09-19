package com.toaster.noad.core.service.keepalive

import androidx.annotation.StringRes
import com.toaster.noad.R

/**
 * 资源引用参数包装。
 *
 * ## 为什么不直接用 Int
 *
 * 通知文案的格式化参数里既有普通值（拦截次数 `Int`）也有资源引用
 * （应用名兜底的「未知应用」）。若裸用 `Int` 表示资源 ID，会与
 * 「次数是 Int」冲突 —— 格式化时无法区分「这个 Int 是次数还是资源 ID」。
 * 用 value class 包装后，格式化侧只需 `is ResId` 判断，类型安全。
 */
@JvmInline
internal value class ResId(val id: Int)

/**
 * 保活常驻通知内容的**纯决策层**（JVM 可测）。
 *
 * ## 职责边界
 *
 * 常驻通知是保活前台服务的系统强制要求（不可移除），本决策层决定的
 * 是它的**内容**：
 *
 * | 条件 | 标题 | 正文 |
 * |---|---|---|
 * | 用户关闭「拦截动态」 | 静态标题 | 静态正文（保活说明） |
 * | 拦截数为 0 | 静态标题 | 暂无拦截记录 |
 * | 有拦截 + 最新一条可用 | 已拦截 N 次广告 | 最近拦截：应用 · 类型 |
 * | 有拦截但最新一条缺失 | 已拦截 N 次广告 | 暂无拦截记录（防御分支） |
 *
 * ## 两个防御分支的理由
 *
 * 1. **`totalCount > 0` 但 `latest == null`**：正常情况下不可能
 *    （有计数必有行），但容量裁剪（`trimToLatest`）与并发写入的
 *    交错窗口里，COUNT 查询与 LIMIT 1 查询是两次独立快照 ——
 *    可能出现计数已刷新、最新行刚被裁掉的瞬间。此时正文回退
 *    「暂无拦截记录」，绝不崩溃或显示空白。
 * 2. **应用名为空串**：`InterceptLog.appLabel` 非空但理论上是空串时
 *    （S1 事件侧解析失败路径），兜底「未知应用」，避免出现
 *    「最近拦截：· 开屏广告」这种残缺文案。
 *
 * Android 组件侧（[KeepAliveService]）只负责把 [Content] 的资源 ID
 * 与参数交给 `getString` 格式化，不含任何判断逻辑 —— 保证这里
 * 断言的行为就是设备上发生的行为。
 */
internal object KeepAliveNotificationContent {

    /**
     * 一份可直接格式化的通知文案。
     *
     * @param titleRes 标题资源
     * @param titleArgs 标题参数（`ResId` 会被格式化侧解析为字符串，其余原样传入）
     * @param textRes 正文资源
     * @param textArgs 正文参数，约定同上
     */
    data class Content(
        @param:StringRes val titleRes: Int,
        val titleArgs: List<Any>,
        @param:StringRes val textRes: Int,
        val textArgs: List<Any>,
    )

    /**
     * 根据拦截动态状态决定通知内容。
     *
     * @param showStats 「拦截动态」开关（设置项 `showNotification`）
     * @param totalCount `intercept_log` 全量条数（随容量裁剪变化，见方案文档）
     * @param latestAppLabel 最近一条记录的应用名；无记录或记录缺失时为 `null`
     * @param latestAdTypeLabel 最近一条记录的广告类型中文名（`AdType.label`，非空）
     */
    fun resolve(
        showStats: Boolean,
        totalCount: Int,
        latestAppLabel: String?,
        latestAdTypeLabel: String,
    ): Content = when {
        !showStats -> Content(
            titleRes = R.string.keep_alive_notification_title,
            titleArgs = emptyList(),
            textRes = R.string.keep_alive_notification_text,
            textArgs = emptyList(),
        )

        totalCount <= 0 -> Content(
            titleRes = R.string.keep_alive_notification_title,
            titleArgs = emptyList(),
            textRes = R.string.keep_alive_status_text_none,
            textArgs = emptyList(),
        )

        latestAppLabel == null -> Content(
            titleRes = R.string.keep_alive_status_title_active,
            titleArgs = listOf(totalCount),
            textRes = R.string.keep_alive_status_text_none,
            textArgs = emptyList(),
        )

        else -> Content(
            titleRes = R.string.keep_alive_status_title_active,
            titleArgs = listOf(totalCount),
            textRes = R.string.keep_alive_status_text_latest,
            textArgs = listOf(
                latestAppLabel.takeIf { it.isNotBlank() }
                    ?: ResId(R.string.keep_alive_status_unknown_app),
                latestAdTypeLabel,
            ),
        )
    }
}
