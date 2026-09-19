package com.toaster.noad.core.service.event

import com.toaster.noad.core.engine.ui.UiMatcher
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetType

/**
 * 事件廉价预筛。
 *
 * ## 它解决的问题
 *
 * 应用启动时会连续产生数十次 `TYPE_WINDOW_CONTENT_CHANGED`。
 * 每一次都遍历整棵节点树（一个 500 节点的界面 = 数百次 `getChild`
 * 跨进程调用，单次轻松超过 100ms）意味着：这些耗时全部累加在
 * **主线程**上，用户感知到的就是"启动任何应用都卡一下"。
 *
 * 而事件对象自身已经带了 `text` / `contentDescription`
 * （读取无需任何 IPC）。先用这份廉价信息判断"这次事件有没有可能命中"，
 * 就能把大量事件在遍历之前丢掉。
 *
 * ## ⚠️ 只放宽、不收紧（最重要的约束）
 *
 * 返回 false 只表示"这次连遍历都不必做"。因此
 * **任何不确定的情形都必须返回 true（放行）**。
 *
 * 这条约束不是风格偏好，而是**安全边界**：
 * 预筛一旦比 [UiMatcher] 更严格，就会出现"事件被预筛丢弃、
 * 永远走不到匹配"的**静默漏拦** —— 现象与"规则写错了"完全一致
 * （无拦截、无日志、统计恒 0），是最难排查的缺陷形态。
 *
 * 本对象**没有 Android 依赖**，因此这套判据可以在 JVM 上被完整验证。
 * 见 `EventPreFilterTest`。
 */
object EventPreFilter {

    /**
     * 判断事件是否值得进入节点树遍历。
     *
     * @param rules 当前应用可用的规则（调用方已确保非空、已启用）
     * @param candidates 事件自带的文本候选（`text` / `contentDescription`）
     * @param activityName 事件携带的界面类名。
     *   **当前未参与判定**（见下方"界面限定不做预筛"的说明），
     *   保留该参数是为了让调用方无需在添加界面相关优化时改签名 ——
     *   但若要用它做过滤，必须先确认不会比权威实现更严格。
     * @return true 表示"可能命中，需要遍历"；false 表示"必然不命中，可丢弃"
     */
    @Suppress("UNUSED_PARAMETER")
    fun mayMatch(
        rules: List<SkipRule>,
        candidates: List<String>,
        activityName: String?,
    ): Boolean {
        if (rules.isEmpty()) return false

        // 只要存在一条"无法靠文本预筛"的规则，就整体放行。
        // 这保证了预筛永远不会让某类规则失效。
        if (rules.any(::isUnfilterable)) return true

        // 事件没带可用信息 → 无法判断，放行。
        // 很多 ROM 只在 TYPE_WINDOW_STATE_CHANGED 上给包名、内容全空，
        // 而开屏广告第一次出现恰恰是这种事件 —— 不放行就等于开屏永远不会被跳过。
        if (candidates.isEmpty()) return true

        // 界面限定：**不做预筛**，只要求事件文本匹配。
        //
        // ## 为什么不在这里实现 activity 判定
        //
        // `EventProcessor.filterByActivity` 已有一份权威实现。预筛若再实现
        // 一遍，就会出现"两份判据各说各话"的风险 —— 而只要预筛那一份更严格，
        // 带 activity 限定的规则就会被静默漏拦。
        //
        // activity 限定的过滤是**廉价**的（纯字符串比较，无 IPC），
        // 交给权威实现去做不会有性能损失。预筛唯一的价值是省掉
        // 昂贵的节点树遍历，因此它只需回答"文本上有没有可能"。
        //
        // 代价：带 activity 限定但当前界面不符的规则，仍会触发一次遍历。
        // 这是刻意的取舍 —— 多遍历一次的代价远低于漏拦。
        return rules.any { rule -> matchesAnyCandidate(rule, candidates) }
    }

    /**
     * 该规则能否靠事件自带信息预筛。
     *
     * 返回 true 表示**不能**，必须整体放行：
     * - `VIEW_ID`：viewId 是节点属性，事件里根本没有这个字段
     * - `COORDINATE`：与文本完全无关
     * - `REGEX`：语义复杂且编译有开销
     */
    private fun isUnfilterable(rule: SkipRule): Boolean =
        rule.targetType == TargetType.VIEW_ID ||
            rule.targetType == TargetType.COORDINATE ||
            rule.matchMode == MatchMode.REGEX

    private fun matchesAnyCandidate(rule: SkipRule, candidates: List<String>): Boolean {
        val target = rule.targetValue.trim()
        if (target.isEmpty()) return false
        return candidates.any { candidate -> cheapTextHit(candidate, target, rule.matchMode) }
    }

    /**
     * 预筛用的文本判定。
     *
     * ## 为什么不能直接复用 `UiMatcher.matches`
     *
     * 匹配器的判定是**权威的**：它决定"点不点"。预筛是**启发式的**：
     * 它只决定"要不要再遍历一次"。两者混用会让"预筛比匹配器严格"
     * 这一风险难以察觉 —— 而那正是静默漏拦的成因。
     *
     * 因此除 `PREFIX` 外，预筛一律采用**比匹配器更宽松**的判据：
     *
     * | 模式 | 预筛判据 | 与匹配器的关系 |
     * |---|---|---|
     * | `PREFIX` | 前缀 + 长度上限 | 严格一致（见下） |
     * | `EXACT` | 相等 | 一致 |
     * | `CONTAINS` | 包含 | 一致 |
     * | `REGEX` | 一律放行 | 更宽松 |
     *
     * `PREFIX` 是唯一需要"严格一致"的：若这里不带长度上限，
     * `跳过此步可在设置中重新开启` 这类长引导文案的事件会频繁
     * 触发全树遍历，预筛就失去了意义 —— 而它恰恰是最常见的
     * 长文本之一（挂在权限引导页上）。
     */
    private fun cheapTextHit(candidate: String, target: String, mode: MatchMode): Boolean {
        val trimmed = candidate.trim()
        return when (mode) {
            MatchMode.PREFIX -> trimmed.length <= UiMatcher.MAX_PREFIX_CANDIDATE_LENGTH &&
                trimmed.startsWith(target, ignoreCase = true)

            MatchMode.EXACT -> trimmed.equals(target, ignoreCase = true)

            MatchMode.CONTAINS -> trimmed.contains(target, ignoreCase = true)

            // 已在 isUnfilterable 中整体放行，不会走到这里
            MatchMode.REGEX -> true
        }
    }
}
