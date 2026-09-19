package com.toaster.noad.core.repository

import com.toaster.noad.core.data.repository.RuleRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicReference

/**
 * S1 规则内存缓存。
 *
 * ## 为什么必须存在这一层
 *
 * `onAccessibilityEvent` 运行在主线程，一次界面切换会连续回调多次。
 * 若每次都在回调里查 Room，会带来两个必然后果：
 *
 * 1. **卡顿**：Room 查询即使命中缓存也有协程调度开销，
 *    而回调是同步方法 —— 要么阻塞主线程，要么让匹配变成异步，
 *    后者会导致节点树在等待期间失效。
 * 2. **竞态**：异步查询返回时事件早已处理完毕，匹配结果无意义。
 *
 * 因此采用**内存快照**：规则变更时（用户编辑规则、启用/禁用应用）
 * 由 Flow 自动重建快照，事件回调只做一次哈希查表。
 *
 * ## 数据结构的取舍
 *
 * 按包名分组的 `Map<String, List<SkipRule>>`，而非一个大 List 后过滤：
 * 主流用户的纳管应用在 10 个以内，但事件几乎全部来自**未纳管**的应用
 * （用户正常使用手机时），因此"按包名直接命中或快速失败"是绝对热路径。
 *
 * ## 线程安全
 *
 * 用 [AtomicReference] 持有不可变快照：写入方整体替换，
 * 读取方拿到的是某个完整版本，不存在"读到半个更新"的问题。
 */
class S1RuleCache(
    targetAppRepository: TargetAppRepository,
    ruleRepository: RuleRepository,
    scope: CoroutineScope,
) {

    private val snapshotRef = AtomicReference(RuleSnapshot.EMPTY)

    init {
        // 两个数据源任一变化都重建快照：
        // - 规则变化（新增/编辑/启停）
        // - 纳管应用变化（用户在应用管理页勾选）
        combine(
            targetAppRepository.observeEnabledForAccessibility(),
            ruleRepository.observeEnabledRules(),
        ) { apps, rules ->
            buildSnapshot(apps, rules)
        }
            .onEach { snapshotRef.set(it) }
            .launchIn(scope)
    }

    /**
     * 取某包名适用且已启用的规则。
     *
     * 返回不可变列表，调用方不应修改。
     * 未纳管的应用返回空列表 —— 这是事件处理的第一道快速失败。
     */
    fun rulesForPackage(packageName: String): List<SkipRule> =
        snapshotRef.get().byPackage[packageName] ?: emptyList()

    /** 该包名是否被纳管（供 UI 与调试使用） */
    fun isManaged(packageName: String): Boolean =
        snapshotRef.get().managedPackages.contains(packageName)

    /** 当前快照的规则总数，供设置页展示"生效中规则数" */
    val enabledRuleCount: Int get() = snapshotRef.get().totalRules

    /** 当前纳管应用数 */
    val managedPackageCount: Int get() = snapshotRef.get().managedPackages.size

    /**
     * 由数据源构建快照。
     *
     * 关键规则：**只有同时满足「应用被纳管且启用无障碍」与「规则启用」时才进入快照**。
     * 应用未纳管时其规则即使存在也不生效 —— 这是"用户显式授权"的体现，
     * 避免因内置规则存在就默认对所有应用生效。
     */
    private fun buildSnapshot(apps: List<TargetApp>, rules: List<SkipRule>): RuleSnapshot {
        if (apps.isEmpty() || rules.isEmpty()) return RuleSnapshot.EMPTY

        val managed = apps.mapTo(HashSet(apps.size)) { it.packageName }

        // 按包名分组；只保留纳管应用的规则
        val byPackage = HashMap<String, MutableList<SkipRule>>(managed.size)
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.packageName !in managed) continue

            byPackage.getOrPut(rule.packageName) { ArrayList(INITIAL_RULES_PER_APP) }
                .add(rule)
        }

        if (byPackage.isEmpty()) {
            return RuleSnapshot(
                byPackage = emptyMap(),
                managedPackages = managed,
                totalRules = 0,
            )
        }

        // 组内按 priority 降序，使 matchBest 遍历顺序更贴近期望优先级
        val frozen = byPackage.mapValues { (_, list) ->
            list.sortedByDescending { it.priority }
        }

        return RuleSnapshot(
            byPackage = frozen,
            managedPackages = managed,
            totalRules = frozen.values.sumOf { it.size },
        )
    }

    /**
     * 不可变快照。
     *
     * `managedPackages` 与 `byPackage` 的键集**不保证一致**：
     * 纳管了某应用但还没为它配置规则是常见状态，
     * 此时它是 managed 但不在 byPackage 中。
     */
    private data class RuleSnapshot(
        val byPackage: Map<String, List<SkipRule>>,
        val managedPackages: Set<String>,
        val totalRules: Int,
    ) {
        companion object {
            val EMPTY = RuleSnapshot(
                byPackage = emptyMap(),
                managedPackages = emptySet(),
                totalRules = 0,
            )
        }
    }

    private companion object {
        const val INITIAL_RULES_PER_APP = 4
    }
}
