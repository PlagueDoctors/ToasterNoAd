package com.toaster.noad.core.engine.ui

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点树遍历器。
 *
 * ## 职责
 *
 * 把 `AccessibilityNodeInfo` 树转换为扁平的 [NodeSnapshot] 列表，
 * 并在遍历过程中**正确回收**每一个节点。
 *
 * ## 为什么单独成类
 *
 * 这是唯一直接接触框架节点对象的模块，把回收逻辑集中在一处，
 * 避免回收责任散落到匹配、点击、日志各环节 ——
 * 那是 `AccessibilityNodeInfo` 泄漏最常见的成因。
 *
 * ## 遍历顺序：广度优先
 *
 * 采用 BFS 而非 DFS。原因是匹配需要「浅层优先」：
 * 广告跳过按钮几乎总在浅层（弹窗容器、开屏根布局），
 * 而深层同名节点的误点代价高（列表项、正文里的「跳过」字样）。
 * BFS 产出的列表天然按深度有序，下游取 `minByOrNull { depth }` 即可。
 */
object UiTreeScanner {

    /**
     * 单次遍历的最大节点数。
     *
     * 上限存在的意义是**防止病态界面拖垮主线程**：
     * 无障碍事件回调运行在主线程，若某界面的节点树异常巨大
     * （或存在循环引用），无上限遍历会直接造成 ANR。
     *
     * 2000 的依据：正常应用的界面节点数在数十到数百量级，
     * 2000 足以覆盖极复杂的首页，同时把最坏耗时控制在毫秒级。
     */
    const val MAX_NODES = 2_000

    /**
     * 遍历根节点，产出扁平快照列表。
     *
     * @param root 界面根节点。**调用方负责回收该 root**
     *             （通常来自 `event.source` 或 `getRootInActiveWindow()`）
     * @param packageName 目标包名，用于过滤跨应用的节点
     * @return 按深度升序（浅层在前）的快照列表
     */
    fun scan(root: AccessibilityNodeInfo?, packageName: String?): List<NodeSnapshot> {
        root ?: return emptyList()

        val snapshots = ArrayList<NodeSnapshot>(INITIAL_CAPACITY)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        // 与 queue 平行的索引信息，避免在节点上挂载额外数据
        val metaQueue = ArrayDeque<NodeMeta>()

        queue.addLast(root)
        metaQueue.addLast(NodeMeta(index = 0, parentIndex = UiMatcher.NO_PARENT, depth = 0))

        var nextIndex = 1

        while (queue.isNotEmpty() && snapshots.size < MAX_NODES) {
            val node = queue.removeFirst()
            val meta = metaQueue.removeFirst()

            // 回收责任：除 root 外，本节点由本函数 removeFirst 获得，
            // 因此在读完字段后、入队子节点前回收。root 由调用方回收。
            val isRoot = meta.index == 0
            val snapshot = snapshotOf(node, meta)
            if (!isRoot) NodeRecycler.recycle(node)

            if (snapshot != null) {
                snapshots.add(snapshot)
            }

            // 只展开同应用节点，避免把系统 UI（如权限弹窗）纳入匹配
            val children = readChildren(node, isRoot)
            children.forEachIndexed { i, child ->
                if (child == null) return@forEachIndexed
                if (queue.size + snapshots.size >= MAX_NODES) {
                    NodeRecycler.recycle(child)
                    return@forEachIndexed
                }
                if (packageName != null && child.packageName?.toString() != packageName) {
                    // 跨应用节点直接丢弃并回收，不展开其子树
                    NodeRecycler.recycle(child)
                    return@forEachIndexed
                }
                queue.addLast(child)
                metaQueue.addLast(
                    NodeMeta(index = nextIndex++, parentIndex = meta.index, depth = meta.depth + 1),
                )
            }
        }

        // 达到上限时队列中可能仍有未处理节点，必须全部回收到避免泄漏
        drain(queue)

        return snapshots
    }

    /**
     * 读取子节点。
     *
     * `getChild(i)` 返回的是**新建的**节点对象，因此必须由调用方回收。
     * `childCount` 与 `getChild` 之间存在竞态：界面变化时
     * `getChild` 可能返回 null，因此必须逐项判空。
     */
    private fun readChildren(
        node: AccessibilityNodeInfo,
        @Suppress("UNUSED_PARAMETER") isRoot: Boolean,
    ): Array<AccessibilityNodeInfo?> {
        val count = runCatching { node.childCount }.getOrElse { 0 }
        if (count <= 0) return emptyArray()

        return Array(count) { i -> runCatching { node.getChild(i) }.getOrNull() }
    }

    /** 回收队列中剩余的全部节点 */
    private fun drain(queue: ArrayDeque<AccessibilityNodeInfo>) {
        while (queue.isNotEmpty()) {
            NodeRecycler.recycle(queue.removeFirst())
        }
    }

    /**
     * 把节点字段拷贝为快照。
     *
     * 任何字段读取都可能因节点已失效而抛异常，
     * 因此整体包 `runCatching`：**单个坏节点不应中断整次遍历**。
     * 若关键字段全部不可读（节点已回收），返回 null 表示跳过。
     */
    private fun snapshotOf(node: AccessibilityNodeInfo, meta: NodeMeta): NodeSnapshot? =
        runCatching {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            NodeSnapshot(
                index = meta.index,
                parentIndex = meta.parentIndex,
                depth = meta.depth,
                viewId = node.viewIdResourceName,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                clickable = node.isClickable,
                // 自身不可见即认为不可用；`visibleToUser` 比拼 visibility 更贴近实际
                visible = node.isVisibleToUser,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
            )
        }.getOrNull()

    private data class NodeMeta(
        val index: Int,
        val parentIndex: Int,
        val depth: Int,
    )

    private const val INITIAL_CAPACITY = 64
}
