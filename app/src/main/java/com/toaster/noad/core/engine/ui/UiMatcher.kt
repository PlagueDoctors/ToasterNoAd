package com.toaster.noad.core.engine.ui

import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetType

/**
 * 单条规则与一个节点的匹配结果。
 */
data class MatchResult(
    val rule: SkipRule,

    /** 命中的节点 */
    val node: NodeSnapshot,

    /** 定位方式的匹配质量，数值越大越可信 */
    val confidence: Int,
) {
    companion object {
        /** 各定位方式的置信度，顺序即优先级：VIEW_ID > TEXT > DESCRIPTION > COORDINATE */
        const val CONFIDENCE_VIEW_ID = 400
        const val CONFIDENCE_TEXT = 300
        const val CONFIDENCE_DESCRIPTION = 200
        const val CONFIDENCE_COORDINATE = 100
    }
}

/**
 * S1 UI 匹配器（纯逻辑，无 Android 依赖）。
 *
 * ## 设计约束
 *
 * 本类**必须**保持无副作用、可在 JVM 单元测试中直接验证。
 * S1 是四种策略里唯一会**主动代替用户操作界面**的策略，
 * 误点一处就可能触发付费、订阅或授权弹窗 —— 这是最严重的缺陷形态，
 * 远超"没拦住广告"。
 *
 * ## 匹配优先级
 *
 * 单条规则内：
 * ```
 * VIEW_ID  >  TEXT  >  DESCRIPTION  >  COORDINATE
 * ```
 * 精度高的先试，全失败才用坐标兜底。
 *
 * 多条规则命中时按 [MatchResult.confidence] 累加规则 `priority` 排序，
 * 取最高者执行 —— **一次事件只点一个节点**，
 * 连续点击多个节点会极大增加误点概率。
 */
class UiMatcher {

    /**
     * 在一组节点快照中为该规则寻找最佳匹配。
     *
     * @param rule  待匹配规则（调用方保证 `enabled` 与包名已过滤）
     * @param nodes 当前界面的节点快照（已过滤不可见节点）
     * @return 最佳匹配；未命中返回 null
     */
    fun match(rule: SkipRule, nodes: List<NodeSnapshot>): MatchResult? {
        if (nodes.isEmpty()) return null

        return when (rule.targetType) {
            TargetType.VIEW_ID -> matchByViewId(rule, nodes)
            TargetType.TEXT -> matchByText(rule, nodes, MatchResult.CONFIDENCE_TEXT)
            TargetType.DESCRIPTION ->
                matchByText(rule, nodes, MatchResult.CONFIDENCE_DESCRIPTION)
            TargetType.COORDINATE -> matchByCoordinate(rule, nodes)
        }
    }

    /**
     * 为多条规则寻找全局最佳匹配。
     *
     * 结果排序键：`confidence + priority` 降序；
     * 同分时优先**深度更浅**的节点（弹窗与开屏按钮通常在浅层，
     * 深层同文本节点多为列表项，误点代价更高）。
     */
    fun matchBest(rules: List<SkipRule>, nodes: List<NodeSnapshot>): MatchResult? =
        rules.asSequence()
            .mapNotNull { rule -> match(rule, nodes) }
            .maxWithOrNull(
                compareBy<MatchResult> { it.confidence + it.rule.priority }
                    .thenByDescending { -it.node.depth },
            )

    // ------------------------------------------------------------------
    // 各定位方式
    // ------------------------------------------------------------------

    /**
     * 按 viewId 匹配。
     *
     * viewId 形如 `com.example:id/btn_skip`。规则中可能只写短名 `btn_skip`，
     * 因此采用「完整相等 或 冒号后短名相等」两种判定，
     * 让规则编写者不必绑定具体包名（换包名后规则依然可用）。
     */
    private fun matchByViewId(rule: SkipRule, nodes: List<NodeSnapshot>): MatchResult? {
        val target = rule.targetValue.trim()
        if (target.isEmpty()) return null

        return nodes.asSequence()
            .filter { it.viewId != null }
            .filter { node ->
                val actual = node.viewId!!
                actual.equals(target, ignoreCase = true) ||
                    actual.substringAfterLast('/', "").equals(target, ignoreCase = true)
            }
            .map { node ->
                MatchResult(rule, node, MatchResult.CONFIDENCE_VIEW_ID)
            }
            .minByOrNull { it.node.depth }
    }

    /**
     * 按文本 / 描述匹配，单条规则内复用同一套 [MatchMode] 语义。
     *
     * 注意 `matchMode` 对 `VIEW_ID` 与 `COORDINATE` 无效 —— 这两种定位
     * 是精确匹配，没有"包含"或"正则"的语义空间。
     */
    private fun matchByText(
        rule: SkipRule,
        nodes: List<NodeSnapshot>,
        confidence: Int,
    ): MatchResult? {
        val target = rule.targetValue.trim()
        if (target.isEmpty()) return null

        // 正则编译失败不应该让整个匹配抛异常 —— 一条坏规则不应影响其余规则
        val regex = if (rule.matchMode == MatchMode.REGEX) {
            runCatching { Regex(target, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        } else {
            null
        }

        return nodes.asSequence()
            .filter { it.visible }
            .mapNotNull { node ->
                val matched = node.textCandidates.any { candidate ->
                    matches(candidate, target, rule.matchMode, regex)
                }
                if (matched) MatchResult(rule, node, confidence) else null
            }
            .minByOrNull { it.node.depth }
    }

    /** 文本判定。抽成独立函数以便单测直接覆盖三种 MatchMode。 */
    private fun matches(
        candidate: String,
        target: String,
        mode: MatchMode,
        regex: Regex?,
    ): Boolean = when (mode) {
        MatchMode.EXACT -> candidate.trim().equals(target, ignoreCase = true)
        MatchMode.CONTAINS -> matchesContains(candidate, target)
        MatchMode.REGEX -> regex?.containsMatchIn(candidate) == true
    }

    /**
     * 包含匹配。
     *
     * ## 关于误点防护的边界
     *
     * `CONTAINS` 的危险在于"命中正文里的同一串字"（例如规则「跳过」
     * 命中一段正文），而该策略**不在本类处理**。原因是匹配器只应回答
     * "文本关系成不成立"这一个问题 —— 一旦让它掺入安全判断，
     * 规则页做匹配预览时会得到与运行时不同的结果，
     * 用户将无法理解"为什么规则明明命中却不生效"。
     *
     * 因而误点防护由两层承担，各司其职：
     *
     * 1. **规则编写约束**：内置规则文件中 `CONTAINS` 的目标串
     *    必须足够长且专有（由 `BuiltinSkipRulesAssetTest` 断言）。
     *    这是最有效的一层 —— 短词根本不进规则集。
     * 2. **点击层防护**：[AntiMisclickGate] 的冷却与节流，
     *    限制"点错之后连续点错"。
     *
     * 节点层不额外过滤过长的候选文本，因为那会误伤
     * "整块容器文本拼接"的合法场景（跳过按钮与倒计时同在一个
     * 可点击容器内时，其合并文本可能较长）。
     */
    private fun matchesContains(candidate: String, target: String): Boolean =
        candidate.contains(target, ignoreCase = true)

    /**
     * 坐标兜底匹配。
     *
     * 坐标规则不带"节点定位"语义，它描述的是屏幕上一点。
     * 因此不为它寻找真实节点 —— 若强行匹配最近节点，
     * 反而会把点击目标从用户指定坐标改到别处。
     *
     * 这里返回一个**合成的坐标快照**，让下游点击流程保持统一：
     * 若该坐标恰好落在某个节点内，则复用该节点信息（日志更可读）；
     * 否则生成一个仅含坐标的虚拟节点。
     */
    private fun matchByCoordinate(rule: SkipRule, nodes: List<NodeSnapshot>): MatchResult? {
        val (x, y) = rule.coordinate ?: return null
        if (x < 0 || y < 0) return null

        val host = nodes.firstOrNull { node ->
            node.visible && node.hasArea &&
                x >= node.left && x <= node.right &&
                y >= node.top && y <= node.bottom
        }

        val node = host ?: NodeSnapshot(
            index = SYNTHETIC_NODE_INDEX,
            parentIndex = NO_PARENT,
            depth = Int.MAX_VALUE,
            viewId = null,
            text = null,
            contentDescription = null,
            className = null,
            clickable = false,
            visible = true,
            left = x,
            top = y,
            right = x,
            bottom = y,
        )

        return MatchResult(rule, node, MatchResult.CONFIDENCE_COORDINATE)
    }

    companion object {
        /** 合成坐标节点的序号，遍历器不会产出该值 */
        const val SYNTHETIC_NODE_INDEX = -1

        const val NO_PARENT = -1
    }
}
