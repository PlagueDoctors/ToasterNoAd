package com.toaster.noad.core.engine.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 点击执行器（含三级降级）。
 *
 * ## 为什么必须降级
 *
 * 实践中大量广告的「跳过」是 `TextView` / `ImageView`，
 * 它们**自身 `clickable=false`** —— 真正响应点击的是父容器
 * （常见的 `FrameLayout` 包裹层）。
 *
 * 只对匹配到的节点调用 `ACTION_CLICK` 会大面积失败，
 * 表现就是"规则配置正确但就是点不掉"，这是 S1 最常见的失效原因。
 *
 * 因此执行顺序为：
 *
 * ```
 * 1. 节点自身可点击          -> ACTION_CLICK
 * 2. 向上找最近可点击祖先     -> ACTION_CLICK
 * 3. 坐标手势点击节点中心     -> dispatchGesture
 * ```
 *
 * 第 3 级需要服务声明 `canPerformGestures="true"`；
 * 未声明时直接返回失败，而不是静默无效。
 */
class ClickExecutor(
    private val service: AccessibilityService,
) {

    /**
     * 执行点击。
     *
     * @param root 当前界面根节点，用于坐标降级时回查真实节点
     * @param match 匹配结果
     * @param gestureTimeoutMs 手势派发超时
     * @return 是否成功派发（不代表界面一定响应）
     */
    fun execute(
        root: AccessibilityNodeInfo?,
        match: MatchResult,
        gestureTimeoutMs: Long = DEFAULT_GESTURE_TIMEOUT_MS,
    ): ClickOutcome {
        val node = match.node

        // 合成坐标节点（COORDINATE 规则且坐标未落在任何节点内）直接走手势
        if (node.index == UiMatcher.SYNTHETIC_NODE_INDEX) {
            return tapByGesture(node.centerX, node.centerY, gestureTimeoutMs)
        }

        val real = findByIndex(root, node.index) ?: run {
            // 树已变化，回查不到真实节点 —— 退回坐标手势
            return tapByGesture(node.centerX, node.centerY, gestureTimeoutMs)
        }

        return try {
            // 1. 自身可点击
            if (real.isClickable && real.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return ClickOutcome.Success(ClickMethod.NODE_SELF)
            }

            // 2. 向上找可点击祖先
            clickFirstClickableAncestor(real)?.let { return it }

            // 3. 坐标降级
            val rect = Rect().also { real.getBoundsInScreen(it) }
            if (rect.width() <= 0 || rect.height() <= 0) {
                ClickOutcome.Failed("节点无有效面积，且无祖先可点击")
            } else {
                tapByGesture(rect.centerX(), rect.centerY(), gestureTimeoutMs)
            }
        } finally {
            // findByIndex 返回的是新建对象，必须回收
            NodeRecycler.recycle(real)
        }
    }

    /**
     * 沿 parent 链向上寻找第一个可点击节点并点击。
     *
     * 每一级 `getParent()` 返回新对象，必须逐一回收，
     * 否则一次点击就会泄漏整条祖先链上的节点。
     *
     * 限制向上层数：极端情况下 parent 链可能很长，
     * 且越往上越可能是整个页面的容器 —— 点它等于点了整页，
     * 误点风险高。因此设上限。
     */
    private fun clickFirstClickableAncestor(node: AccessibilityNodeInfo): ClickOutcome? {
        var current: AccessibilityNodeInfo? = runCatching { node.parent }.getOrNull()
        var level = 0

        while (current != null && level < MAX_ANCESTOR_LOOKUP) {
            // 赋给非空局部变量：`current` 是 var，在 lambda 中无法被智能转换，
            // 而 runCatching 的 lambda 正需要非空接收者
            val parent: AccessibilityNodeInfo = current
            val clicked = runCatching {
                parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrElse { false }

            // 取下一级必须在回收 parent 之前完成
            val next = runCatching { parent.parent }.getOrNull()

            if (clicked) {
                NodeRecycler.recycle(parent)
                // 命中后不再继续向上：剩余的 next 也需要回收
                NodeRecycler.recycle(next)
                return ClickOutcome.Success(ClickMethod.CLICKABLE_ANCESTOR)
            }

            NodeRecycler.recycle(parent)
            current = next
            level++
        }

        return null
    }

    /**
     * 按序号在树中回查节点。
     *
     * ## 为什么要回查而不是缓存节点引用
     *
     * 快照是在事件回调中生成的，而点击可能发生在若干毫秒之后
     * （异步写库、协程调度）。期间界面可能已变化，
     * 缓存的 `AccessibilityNodeInfo` 会失效甚至已被回收。
     *
     * 当前实现按 BFS 序号重新遍历回查，保证拿到的是**当下有效**的节点。
     * 代价是二次遍历，但在数百节点规模下耗时可忽略，
     * 换来的是避免"点了已失效节点"这类难排查的失败。
     */
    private fun findByIndex(root: AccessibilityNodeInfo?, targetIndex: Int): AccessibilityNodeInfo? {
        root ?: return null
        if (targetIndex <= 0) return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val indexQueue = ArrayDeque<Int>()
        queue.addLast(root)
        indexQueue.addLast(0)

        var nextIndex = 1
        var visitedGuard = 0

        while (queue.isNotEmpty() && visitedGuard < UiTreeScanner.MAX_NODES) {
            val node = queue.removeFirst()
            val index = indexQueue.removeFirst()
            visitedGuard++

            if (index == targetIndex) {
                // 命中：回收队列中剩余节点后返回（返回的 node 交由调用方回收）
                drainWithRecycle(queue)
                return node
            }

            val count = runCatching { node.childCount }.getOrElse { 0 }
            for (i in 0 until count) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.addLast(child)
                indexQueue.addLast(nextIndex++)
            }

            // 非命中节点：仅当它不是 root 时回收
            if (node !== root) NodeRecycler.recycle(node)
        }

        drainWithRecycle(queue)
        return null
    }

    private fun drainWithRecycle(queue: ArrayDeque<AccessibilityNodeInfo>) {
        while (queue.isNotEmpty()) {
            NodeRecycler.recycle(queue.removeFirst())
        }
    }

    /**
     * 坐标手势点击。
     *
     * 需要 `canPerformGestures="true"`，否则抛出 `SecurityException` ——
     * 这里捕获并转为可读的失败原因，避免直接崩溃无障碍服务。
     */
    private fun tapByGesture(x: Int, y: Int, timeoutMs: Long): ClickOutcome {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_MS))
            .build()

        return try {
            val dispatched = service.dispatchGesture(gesture, null, null)
            if (dispatched) {
                ClickOutcome.Success(ClickMethod.GESTURE_TAP)
            } else {
                ClickOutcome.Failed("手势派发被拒绝")
            }
        } catch (e: SecurityException) {
            ClickOutcome.Failed("未声明 canPerformGestures，无法执行坐标点击")
        } catch (e: Exception) {
            ClickOutcome.Failed("手势派发异常: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /**
         * 向上查找可点击祖先的最大层数。
         *
         * 5 层的依据：典型广告跳过按钮的结构为
         * `TextView` → `FrameLayout` → `RelativeLayout` → 弹窗根 → Activity 根。
         * 超过 5 层说明更可能是页面级容器，点击它风险过大。
         */
        const val MAX_ANCESTOR_LOOKUP = 5

        /** 单击手势时长：略长于系统最小点击判定，提高被识别为 click 的概率 */
        private const val GESTURE_DURATION_MS = 40L

        private const val DEFAULT_GESTURE_TIMEOUT_MS = 500L
    }
}

/** 实际生效的点击方式，用于日志与调试 */
enum class ClickMethod {
    /** 节点自身响应了 ACTION_CLICK */
    NODE_SELF,

    /** 可点击祖先响应了 ACTION_CLICK */
    CLICKABLE_ANCESTOR,

    /** 坐标手势降级 */
    GESTURE_TAP,
}

/**
 * 点击结果。
 *
 * 刻意区分"成功"与"失败原因"：失败原因会写入日志，
 * 是实机调试时定位"为什么没点掉"的主要依据。
 */
sealed interface ClickOutcome {
    data class Success(val method: ClickMethod) : ClickOutcome
    data class Failed(val reason: String) : ClickOutcome
}
