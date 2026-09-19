package com.toaster.noad.core.engine.ui

/**
 * 无障碍节点的**只读快照**。
 *
 * ## 为什么需要这层抽象
 *
 * `AccessibilityNodeInfo` 是 Android 框架对象：
 * 它无法在 JVM 单元测试中构造，且必须成对调用 `recycle()`，
 * 更关键的是 —— 它只在 `onAccessibilityEvent` 的回调期间有效，
 * 一旦回调返回，持有的引用就可能失效或指向已回收对象。
 *
 * 如果匹配逻辑直接操作 `AccessibilityNodeInfo`：
 *
 * 1. **不可测**：核心的「该不该点这个节点」判断无法写单元测试，
 *    而 S1 恰恰是误点风险最高的策略 —— 宁可不点，不可乱点。
 * 2. **易泄漏**：匹配过程中跨线程/跨回调持有节点是常见事故。
 *
 * 因此引入本快照：在遍历节点树的**瞬间**把需要的字段全部拷贝出来，
 * 之后所有匹配与决策都只操作快照。节点树的回收在遍历阶段就已完成。
 *
 * ## 字段选择
 *
 * 只保留匹配与点击**实际需要**的字段，不做通用包装：
 * 无障碍节点包含大量字段，全量拷贝会显著增加每次事件的分配开销，
 * 而 S1 的 `onAccessibilityEvent` 是高频路径。
 */
data class NodeSnapshot(
    /** 节点在树中的稳定序号，用于点击时回查真实节点 */
    val index: Int,

    /** 父节点序号，-1 表示根节点 */
    val parentIndex: Int,

    /** 节点所在屏幕深度，用于「浅层优先」排序 */
    val depth: Int,

    /** `viewIdResourceName`，需服务开启 `FLAG_REPORT_VIEW_IDS`，否则为空 */
    val viewId: String?,

    /** 可见文本 */
    val text: String?,

    /** `contentDescription`，图片按钮通常只有这个 */
    val contentDescription: String?,

    /** 类名，如 `android.widget.TextView` */
    val className: String?,

    /** 是否可点击（自身） */
    val clickable: Boolean,

    /** 是否可见 */
    val visible: Boolean,

    /** 屏幕坐标边界，用于坐标降级与范围校验 */
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /** 节点中心点（坐标降级点击使用） */
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    /** 节点是否有实际面积（宽高均为正） */
    val hasArea: Boolean get() = right > left && bottom > top

    /** 可供匹配的文本候选集：text 与 contentDescription 都参与匹配 */
    val textCandidates: List<String>
        get() = listOfNotNull(
            text?.takeIf { it.isNotBlank() },
            contentDescription?.takeIf { it.isNotBlank() },
        )

    /** 供日志展示的简短描述 */
    fun describe(): String = buildString {
        viewId?.takeIf { it.isNotBlank() }?.let { append("id=").append(it) }
        text?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append(' ')
            append("text=").append(it.take(MAX_DESC_LEN))
        }
        contentDescription?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append(' ')
            append("desc=").append(it.take(MAX_DESC_LEN))
        }
        if (isEmpty()) append("class=").append(className ?: "?")
    }

    private companion object {
        const val MAX_DESC_LEN = 24
    }
}
