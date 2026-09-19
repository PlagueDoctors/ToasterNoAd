package com.toaster.noad.core.repository

import com.toaster.noad.core.data.repository.RuleRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.model.GLOBAL_RULE_PACKAGE
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
    targetAppsFlow: Flow<List<TargetApp>>,
    enabledRulesFlow: Flow<List<SkipRule>>,
    scope: CoroutineScope,
) {

    /**
     * 生产用构造入口：从两个仓库取数据流。
     *
     * ## 为什么不直接依赖仓库
     *
     * 本类只用到「纳管应用流」与「启用规则流」两样东西，
     * 但 `TargetAppRepository` 的构造函数需要 `InstalledAppDataSource`，
     * 后者又需要 `Context` —— 而单元测试没有 `Context`
     * （项目测试栈不含 Robolectric）。
     *
     * 若把仓库作为构造参数，本类最核心的快照构建逻辑
     * （尤其是**通用规则不得泄漏到未纳管应用**这条安全契约）
     * 就永远无法在 JVM 上被验证。让依赖收窄到 Flow，
     * 假仓库只需给出两个 Flow 即可测。
     */
    constructor(
        targetAppRepository: TargetAppRepository,
        ruleRepository: RuleRepository,
        scope: CoroutineScope,
    ) : this(
        targetAppsFlow = targetAppRepository.observeEnabledForAccessibility(),
        enabledRulesFlow = ruleRepository.observeEnabledRules(),
        scope = scope,
    )

    private val snapshotRef = AtomicReference(RuleSnapshot.EMPTY)

    init {
        // 两个数据源任一变化都重建快照：
        // - 规则变化（新增/编辑/启停）
        // - 纳管应用变化（用户在应用管理页勾选）
        combine(
            targetAppsFlow,
            enabledRulesFlow,
        ) { apps, rules ->
            buildSnapshot(apps, rules)
        }
            .onEach { snapshotRef.set(it) }
            .launchIn(scope)
    }

    /**
     * 取某包名适用且已启用的规则。
     *
     * 返回**专属规则 + 通用规则**的合并结果，已按 priority 降序。
     * 未纳管的应用返回空列表 —— 这是事件处理的第一道快速失败。
     *
     * ## 为什么通用规则在此处合并而非预烤进 map
     *
     * 预烤会让每个纳管应用各存一份通用规则的副本，
     * 快照重建的内存与时间开销随「纳管应用数 × 通用规则数」放大。
     * 而通用规则通常只有个位数，`+` 一次列表拼接的成本远低于上述开销。
     *
     * 合并后需重新排序：通用规则 priority 较低（20 上下），
     * 应用专属规则较高（60–100），混排后 `UiMatcher.matchBest` 的
     * 遍历顺序才符合"先试精确规则"的预期。
     */
    /**
     * 取某包名适用且已启用的规则。
     *
     * 返回**专属规则 + 通用规则**的合并结果，已按 priority 降序。
     * 未纳管的应用返回空列表 —— 这是事件处理的第一道快速失败。
     *
     * ## 纳管检查必须放在最前
     *
     * 这里的顺序**不是风格偏好，而是一条安全契约**。
     * 曾出现过这样的写法：
     *
     * ```kotlin
     * if (global.isEmpty()) return own ?: emptyList()
     * if (own.isNullOrEmpty()) return global   // ← 缺陷
     * ```
     *
     * 未纳管应用既不在 `byPackage` 中（`own` 为 null）、又因通用规则
     * 存在而入围第二个分支，于是**拿到了本不该生效的通用规则**。
     * 后果是：用户只勾选了 B 站，淘宝开屏时通用规则命中，
     * 应用在用户从未授权的应用上执行了点击。
     *
     * 因此先判 `isManaged` 再谈规则。这也是 [SkipRule] 中关于
     * "通用规则同样受用户显式授权约束"那句话的落地位置。
     *
     * ## 为什么通用规则在此处合并而非预烤进 map
     *
     * 预烤会让每个纳管应用各存一份通用规则的副本，
     * 快照重建的内存与时间开销随「纳管应用数 × 通用规则数」放大。
     * 而通用规则通常只有个位数，`+` 一次列表拼接的成本远低于上述开销。
     *
     * 合并后需重新排序：通用规则 priority 较低（20 上下），
     * 应用专属规则较高（60–100），混排后 `UiMatcher.matchBest` 的
     * 遍历顺序才符合"先试精确规则"的预期。
     */
    fun rulesForPackage(packageName: String): List<SkipRule> {
        val snapshot = snapshotRef.get()

        // ★ 安全闸门：未纳管一律为空，通用规则也不例外。
        // 必须放在读取 own / global 之前。
        if (packageName !in snapshot.managedPackages) return emptyList()

        val own = snapshot.byPackage[packageName]
        val global = snapshot.globalRules

        if (global.isEmpty()) return own ?: emptyList()
        if (own.isNullOrEmpty()) return global

        return (own + global).sortedByDescending { it.priority }
    }

    /** 该包名是否被纳管（供 UI 与调试使用） */
    fun isManaged(packageName: String): Boolean =
        snapshotRef.get().managedPackages.contains(packageName)

    /** 当前快照的规则总数，供设置页展示"生效中规则数" */
    val enabledRuleCount: Int get() = snapshotRef.get().totalRules

    /** 当前纳管应用数 */
    val managedPackageCount: Int get() = snapshotRef.get().managedPackages.size

    /** 通用规则条数（不含在各应用的计数里，单独暴露供 UI 说明） */
    val globalRuleCount: Int get() = snapshotRef.get().globalRules.size

    /**
     * 由数据源构建快照。
     *
     * 关键规则：**只有同时满足「应用被纳管且启用无障碍」与「规则启用」时才进入快照**。
     * 应用未纳管时其规则即使存在也不生效 —— 这是"用户显式授权"的体现，
     * 避免因内置规则存在就默认对所有应用生效。
     *
     * 通用规则（`packageName == "*"`）是例外：它们不绑定具体应用，
     * 而是**附加到每个已纳管应用**上。这仍然尊重"用户显式授权"——
     * 用户没纳管某应用时，通用规则同样不会作用于它。
     */
    private fun buildSnapshot(apps: List<TargetApp>, rules: List<SkipRule>): RuleSnapshot {
        if (apps.isEmpty() || rules.isEmpty()) return RuleSnapshot.EMPTY

        val managed = apps.mapTo(HashSet(apps.size)) { it.packageName }

        // 通用规则与专属规则分开：前者随后附加到每个纳管应用
        val globalRules = ArrayList<SkipRule>()
        val byPackage = HashMap<String, MutableList<SkipRule>>(managed.size)

        for (rule in rules) {
            if (!rule.enabled) continue

            if (rule.packageName == GLOBAL_RULE_PACKAGE) {
                globalRules += rule
                continue
            }

            if (rule.packageName !in managed) continue

            byPackage.getOrPut(rule.packageName) { ArrayList(INITIAL_RULES_PER_APP) }
                .add(rule)
        }

        // 无任何专属规则且无通用规则时，快照为空
        if (byPackage.isEmpty() && globalRules.isEmpty()) {
            return RuleSnapshot(
                byPackage = emptyMap(),
                globalRules = emptyList(),
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
            globalRules = globalRules.sortedByDescending { it.priority },
            managedPackages = managed,
            totalRules = frozen.values.sumOf { it.size } + globalRules.size,
        )
    }

    /**
     * 不可变快照。
     *
     * `managedPackages` 与 `byPackage` 的键集**不保证一致**：
     * 纳管了某应用但还没为它配置规则是常见状态，
     * 此时它是 managed 但不在 byPackage 中
     * （不过只要有通用规则，byPackage 仍会包含它）。
     *
     * [globalRules] 是通用规则层（穿山甲/快手 SDK id、跳过文案前缀），
     * 独立存放而非复制进每个应用，避免内存与快照重建开销随应用数放大。
     */
    private data class RuleSnapshot(
        val byPackage: Map<String, List<SkipRule>>,
        val globalRules: List<SkipRule>,
        val managedPackages: Set<String>,
        val totalRules: Int,
    ) {
        companion object {
            val EMPTY = RuleSnapshot(
                byPackage = emptyMap(),
                globalRules = emptyList(),
                managedPackages = emptySet(),
                totalRules = 0,
            )
        }
    }

    private companion object {
        const val INITIAL_RULES_PER_APP = 4
    }
}
