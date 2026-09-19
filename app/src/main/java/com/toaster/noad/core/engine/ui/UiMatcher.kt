package com.toaster.noad.core.engine.ui

import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetType
import com.toaster.noad.core.model.VIEW_ID_SUFFIX_PREFIX

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
     * viewId 形如 `com.example:id/btn_skip`。规则支持三种写法：
     *
     * 1. **完整 id**：`com.example:id/btn_skip` —— 精确相等
     * 2. **短名**：`btn_skip` —— 冒号后短名相等，换包名后规则仍可用
     * 3. **SDK 通用 id**（`*` 开头）：`*tt_splash_skip_btn` —— **后缀**匹配
     *
     * ## 为什么需要第 3 种（这是收益最高的一类规则）
     *
     * 广告 SDK 的跳过按钮 id 在**所有接入该 SDK 的应用中是同一个**，例如：
     * - 穿山甲：`tt_splash_skip_btn`
     * - 快手联盟：`ksad_splash_circle_skip_view`
     *
     * 但它的**包名前缀不固定** —— 可能是 SDK 自身包名
     * （`com.byted.pangle:id/tt_splash_skip_btn`），
     * 也可能是宿主包名（`com.cainiao.wireless:id/tt_splash_skip_btn`，
     * 宿主覆写了 SDK 资源）。
     *
     * 因此用「短名相等」是匹配不上的（短名是 `tt_splash_skip_btn`，
     * 但规则若写成完整 id 就绑死了某一个宿主）。
     * 用后缀匹配可以一条规则覆盖**所有**接入该 SDK 的应用。
     *
     * 社区规则库普遍这么做，例如 GKD 的 `id$="tt_splash_skip_btn"`。
     */
    private fun matchByViewId(rule: SkipRule, nodes: List<NodeSnapshot>): MatchResult? {
        val target = rule.targetValue.trim()
        if (target.isEmpty()) return null

        val suffixTarget = target.removePrefix(VIEW_ID_SUFFIX_PREFIX)
        val bySuffix = target.startsWith(VIEW_ID_SUFFIX_PREFIX)
        if (bySuffix && suffixTarget.isEmpty()) return null

        return nodes.asSequence()
            .filter { it.viewId != null }
            .filter { node ->
                val actual = node.viewId!!
                if (bySuffix) {
                    // 后缀匹配：`com.byted.pangle:id/tt_splash_skip_btn` 命中 `*tt_splash_skip_btn`
                    actual.endsWith(suffixTarget, ignoreCase = true)
                } else {
                    actual.equals(target, ignoreCase = true) ||
                        actual.substringAfterLast('/', "").equals(target, ignoreCase = true)
                }
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

    /** 文本判定。抽成独立函数以便单测直接覆盖各种 MatchMode。 */
    private fun matches(
        candidate: String,
        target: String,
        mode: MatchMode,
        regex: Regex?,
    ): Boolean = when (mode) {
        MatchMode.EXACT -> candidate.trim().equals(target, ignoreCase = true)
        MatchMode.PREFIX -> matchesPrefix(candidate, target)
        MatchMode.CONTAINS -> matchesContains(candidate, target)
        MatchMode.REGEX -> regex?.containsMatchIn(candidate) == true
    }

    /**
     * 前缀匹配。
     *
     * ## 这是本应用曾经完全失效的直接原因
     *
     * 开屏广告的跳过按钮几乎都带倒计时（真机抓包：B站 `"跳过 1"`）。
     * 早期规则用 [MatchMode.EXACT] + `"跳过"`，`"跳过 1" != "跳过"`，
     * **永远匹配不上** —— 表现为「无障碍已授权、应用已纳管、但零拦截、零日志」。
     *
     * ## 为什么前缀还不够，必须再加长度上限
     *
     * 我原先的理由是「正文不会以『跳过』开头」。**这个理由经不起检验** ——
     * 「跳过此步可在设置中重新开启」正是一句以「跳过」开头的引导文案，
     * 且它挂在指南针/权限引导页上，点下去会跳走。
     * 该反例由 `BuiltinSkipRulesAssetTest` 的零假阳性用例暴露。
     *
     * 因此这里采用社区（GKD 全局规则）验证过的组合：
     * **前缀 + 长度上限**。
     *
     * ```
     * "跳过 1"                      长度 4  → 命中
     * "跳过广告 5s"                  长度 8  → 命中
     * "跳过此步可在设置中重新开启"      长度 13 → 拒绝
     * ```
     *
     * 长度上限之所以是安全的判据：**跳过按钮为了不遮挡广告，文案必然极短**，
     * 而引导/说明类文案为了把话说清楚必然较长。
     *
     * ## 为什么长度判断放匹配器里，而 CONTAINS 的防误点不放
     *
     * 两者性质不同：
     * - 这里判断的是**"前缀匹配"这一语义本身是否成立** ——
     *   一个 13 字的前缀命中不是"激进"，而是"这不是跳过按钮"。
     *   它属于匹配语义的一部分，放这里才不会让规则页预览与运行时不一致。
     * - `CONTAINS` 的误点防护则是**规则编写质量问题**（目标串选得太泛），
     *   必须靠约束规则文件来解决，否则会出现"规则看起来对但不生效"。
     *
     * 对应李跳跳规则语法的 `+` 修饰符（`+跳过`），
     * 长度上限对应 GKD 的 `[text.length<10]`。
     */
    private fun matchesPrefix(candidate: String, target: String): Boolean {
        val trimmed = candidate.trim()
        if (trimmed.length > MAX_PREFIX_CANDIDATE_LENGTH) return false
        return trimmed.startsWith(target, ignoreCase = true)
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

        /**
         * [MatchMode.PREFIX] 允许的候选文本最大长度（按字符计）。
         *
         * 定这个值的依据（社区 GKD 全局规则用 `text.length<10`）：
         *
         * | 文案 | 长度 | 期望 |
         * |---|---|---|
         * | `跳过` | 2 | 命中 |
         * | `跳过 1` | 4 | 命中 |
         * | `跳过广告 5s` | 8 | 命中 |
         * | `点击跳过 3` | 6 | 命中 |
         * | `跳过此广告` | 5 | 命中 |
         * | `跳过此步可在设置中重新开启` | 13 | 拒绝 |
         *
         * 取 10 而非 GKD 的 9：中文跳过按钮偶有「跳过广告 10s」这类
         * 两位数倒计时（长度 9–10），留一格余量。
         * 而上限再放宽就有让引导文案混入的风险 —— 13 字的反例已经出现。
         */
        const val MAX_PREFIX_CANDIDATE_LENGTH = 10
    }
}
