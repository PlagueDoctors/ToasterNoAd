package com.toaster.noad.core.engine.ui

/**
 * 误点防护闸门。
 *
 * ## 为什么必须有这一层
 *
 * 无障碍事件是**高频且重复**的：一次界面变化会连续回调多次
 * （`TYPE_WINDOW_CONTENT_CHANGED` 尤其明显），
 * 同一个「跳过」按钮在一秒内可能被匹配到十几次。
 *
 * 若不加限制地每次命中都点击，会产生三类真实事故：
 *
 * 1. **连点**：广告关掉后，同一坐标或同一文本命中了**下方正片内容**中的
 *    另一个节点，用户正在看的页面被误触。
 * 2. **抖动**：点击触发新的界面变化 → 又匹配到 → 再点击，
 *    形成互相触发的循环，界面反复跳转。
 * 3. **误触付费**：连点恰好落在「立即开通」类按钮上。
 *
 * 因此本闸门做三件事：**同一规则冷却**、**同一节点去重**、**全局节流**。
 *
 * ## 设计取舍
 *
 * 宁可漏点也不重点：冷却窗口偏保守。
 * 漏点的后果是"这条广告没关掉"，误点的后果可能是"用户被扣费"。
 */
class AntiMisclickGate(
    private val ruleCooldownMs: Long = DEFAULT_RULE_COOLDOWN_MS,
    private val nodeCooldownMs: Long = DEFAULT_NODE_COOLDOWN_MS,
    private val globalThrottleMs: Long = DEFAULT_GLOBAL_THROTTLE_MS,
) {

    private val lastFireByRule = HashMap<Long, Long>()
    private val lastFireByNode = HashMap<String, Long>()
    private var lastGlobalFireAt = 0L

    /**
     * 判断本次命中是否允许执行点击。
     *
     * 该方法**有副作用**（允许时记录时间戳），这是刻意的：
     * 把"判断"与"记账"分开会让调用方有机会忘记记账，
     * 而忘记记账就等于闸门失效。
     *
     * @param ruleId 规则 ID（新建未落库的规则用其 hashCode 兜底）
     * @param nodeKey 节点身份键，见 [nodeKey]
     * @param now 当前时间戳（便于测试注入）
     * @return true 允许点击
     */
    fun tryAcquire(ruleId: Long, nodeKey: String, now: Long): Boolean {
        // 1. 全局节流：任何两次点击之间的最小间隔
        if (now - lastGlobalFireAt < globalThrottleMs) return false

        // 2. 同一规则冷却：防止同一规则在短时间内反复触发
        val ruleLast = lastFireByRule[ruleId]
        if (ruleLast != null && now - ruleLast < ruleCooldownMs) return false

        // 3. 同一节点去重：节点被点掉前不应重复点
        val nodeLast = lastFireByNode[nodeKey]
        if (nodeLast != null && now - nodeLast < nodeCooldownMs) return false

        lastGlobalFireAt = now
        lastFireByRule[ruleId] = now
        lastFireByNode[nodeKey] = now
        prune(now)
        return true
    }

    /** 清空全部记账。界面发生重大切换（Activity 变化）时应调用。 */
    fun reset() {
        lastFireByRule.clear()
        lastFireByNode.clear()
        lastGlobalFireAt = 0L
    }

    /**
     * 清理过期记账。
     *
     * 记账表若只增不减，长时间运行会缓慢泄漏。
     * 每次允许点击时顺带清理（点击是低频事件，开销可忽略）。
     */
    private fun prune(now: Long) {
        val ruleCutoff = now - ruleCooldownMs * PRUNE_KEEP_FACTOR
        lastFireByRule.entries.removeAll { it.value < ruleCutoff }

        val nodeCutoff = now - nodeCooldownMs * PRUNE_KEEP_FACTOR
        lastFireByNode.entries.removeAll { it.value < nodeCutoff }
    }

    /** 当前记账规模，供测试断言防泄漏行为 */
    val trackedRuleCount: Int get() = lastFireByRule.size
    val trackedNodeCount: Int get() = lastFireByNode.size

    companion object {
        /**
         * 同一规则的冷却窗口。
         *
         * 3 秒的依据：开屏广告通常展示 3–5 秒，
         * 同一应用在 3 秒内不会连续出现两个需要跳过的广告。
         */
        const val DEFAULT_RULE_COOLDOWN_MS = 3_000L

        /**
         * 同一节点的冷却窗口。
         *
         * 比规则冷却更长：节点被点掉后通常不再存在，
         * 若它仍然存在（说明点击无效），重复点击也无意义。
         */
        const val DEFAULT_NODE_COOLDOWN_MS = 5_000L

        /**
         * 全局节流间隔。
         *
         * 400ms 略大于系统的最小可感知点击间隔，
         * 同时远小于人手动操作的速度上限，不影响正常跳过体验。
         */
        const val DEFAULT_GLOBAL_THROTTLE_MS = 400L

        /** 清理时保留冷却窗口的倍数，避免刚写入就被清掉 */
        private const val PRUNE_KEEP_FACTOR = 4

        /**
         * 构造节点身份键。
         *
         * 用「viewId + 文本 + 坐标」而非节点序号：
         * 节点序号每次遍历都会重排，无法跨事件标识"同一个按钮"。
         */
        fun nodeKey(node: NodeSnapshot): String = buildString {
            append(node.viewId ?: "")
            append('|')
            append(node.text ?: "")
            append('|')
            append(node.contentDescription ?: "")
            append('|')
            append(node.centerX)
            append(',')
            append(node.centerY)
        }
    }
}
