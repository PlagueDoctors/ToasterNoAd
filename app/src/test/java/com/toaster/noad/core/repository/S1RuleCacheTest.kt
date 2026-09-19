package com.toaster.noad.core.repository

import com.toaster.noad.core.model.GLOBAL_RULE_PACKAGE
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.SkipRuleSource
import com.toaster.noad.core.model.TargetApp
import com.toaster.noad.core.model.TargetType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [S1RuleCache] 快照构建与通用规则合并的单元测试。
 *
 * ## 本测试保护的核心契约
 *
 * 通用规则层（`packageName == "*"`）是本轮修复的核心新增能力：
 * 穿山甲/快手 SDK 的跳过按钮 id 在所有接入方**完全一致**，
 * 一条通用规则可以覆盖数十个应用。
 *
 * 但它同时也是最危险的改动 —— 通用规则一旦泄漏到未纳管应用，
 * 应用就会在用户没授权的地方执行点击。因此以下两条必须被测试钉死：
 *
 * 1. **通用规则只作用于已纳管应用**（用户显式授权不可绕过）
 * 2. **专属规则与通用规则正确合并且按 priority 降序**（决定匹配顺序）
 *
 * 这两条一旦破坏，现象分别是「侵犯用户授权」与「低优先级规则先命中」，
 * 前者是安全缺陷，后者表现为"跳过按钮点错位置"，都极难从现象反推原因。
 *
 * ## 为什么直接传 Flow 而非假仓库
 *
 * `S1RuleCache` 的次构造函数接受两个仓库，但主构造函数只吃两个 Flow。
 * 测试走主构造函数：假仓库虽然也能写，但那是在为**测试**增加一层
 * 与生产逻辑无关的中间代码，而本类只用到这两个 Flow 的语义。
 * 依赖收窄到最小接口，测试只需给出两个可变数据源。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S1RuleCacheTest {

    // ==================================================================
    // 通用规则：只作用于已纳管应用
    // ==================================================================

    @Test
    fun givenGlobalRuleAndManagedApp_whenRulesForPackage_thenGlobalReturned() = runTest {
        val cache = cacheOf(
            apps = listOf(app("com.target")),
            rules = listOf(global("tt_splash_skip_btn")),
        )

        val rules = cache.rulesForPackage("com.target")

        assertEquals(1, rules.size)
        assertEquals("tt_splash_skip_btn", rules.single().targetValue)
    }

    @Test
    fun givenGlobalRuleOnly_whenRulesForUnmanagedPackage_thenEmpty() = runTest {
        val cache = cacheOf(
            apps = listOf(app("com.target")),
            rules = listOf(global("tt_splash_skip_btn")),
        )

        // ★ 关键安全契约：通用规则绝不可作用于未纳管应用。
        // 若这里返回了规则，用户没勾选的应用也会被自动点击 ——
        // 这是必须被测试钉死的行为。
        assertTrue(cache.rulesForPackage("com.not.managed").isEmpty())
        assertFalse(cache.isManaged("com.not.managed"))
    }

    @Test
    fun givenGlobalRules_whenUnmanagedAppQueried_thenIsManagedStaysFalse() = runTest {
        val cache = cacheOf(
            apps = listOf(app("com.target")),
            rules = listOf(global("tt_splash_skip_btn")),
        )

        // 通用规则的存在不应让任何应用"看起来被纳管"
        assertTrue(cache.isManaged("com.target"))
        assertFalse(cache.isManaged("com.other"))
    }

    // ==================================================================
    // 合并与排序
    // ==================================================================

    @Test
    fun givenOwnAndGlobalRules_whenRulesForPackage_thenMergedAndSortedByPriorityDesc() = runTest {
        val cache = cacheOf(
            apps = listOf(app("tv.danmaku.bili")),
            rules = listOf(
                // 通用规则：低优先级
                global("tt_splash_skip_btn", priority = 20),
                // 专属规则：高优先级
                own("tv.danmaku.bili", "count_down", priority = 100),
                own("tv.danmaku.bili", "跳过", priority = 60),
            ),
        )

        val rules = cache.rulesForPackage("tv.danmaku.bili")

        assertEquals(3, rules.size)
        // 顺序决定 matchBest 的遍历次序，必须严格降序 ——
        // 高优先级（精确 viewId）先试，低优先级（通用文本兜底）最后试
        assertEquals(listOf(100, 60, 20), rules.map { it.priority })
    }

    @Test
    fun givenOnlyOwnRules_whenRulesForPackage_thenSortedDescending() = runTest {
        val cache = cacheOf(
            apps = listOf(app("com.a")),
            rules = listOf(
                own("com.a", "low", priority = 10),
                own("com.a", "high", priority = 90),
            ),
        )

        assertEquals(listOf(90, 10), cache.rulesForPackage("com.a").map { it.priority })
    }

    @Test
    fun givenManagedAppWithNoOwnRulesButGlobalRules_whenRulesForPackage_thenGlobalOnly() = runTest {
        val cache = cacheOf(
            apps = listOf(app("com.a"), app("com.b")),
            // 只为 com.a 配了专属规则；com.b 只应拿到通用规则
            rules = listOf(own("com.a", "id/a", priority = 50), global("sdk_id", priority = 20)),
        )

        val rules = cache.rulesForPackage("com.b")

        assertEquals(1, rules.size)
        assertEquals("sdk_id", rules.single().targetValue)
    }

    @Test
    fun givenOwnRuleForUnmanagedApp_whenRulesForPackage_thenNotReturned() = runTest {
        val cache = cacheOf(
            apps = listOf(app("com.managed")),
            rules = listOf(own("com.unmanaged", "id/x", priority = 50)),
        )

        // 专属规则同样受纳管约束
        assertTrue(cache.rulesForPackage("com.unmanaged").isEmpty())
    }

    // ==================================================================
    // 启用开关
    // ==================================================================

    @Test
    fun givenDisabledGlobalRule_whenRulesForPackage_thenExcluded() = runTest {
        val cache = cacheOf(
            apps = listOf(app("com.a")),
            rules = listOf(global("disabled_id", priority = 20).copy(enabled = false)),
        )

        assertTrue(cache.rulesForPackage("com.a").isEmpty())
        assertEquals(0, cache.globalRuleCount)
    }

    @Test
    fun givenDisabledGlobalRuleAndEnabledOwn_whenRulesForPackage_thenOnlyOwnReturned() = runTest {
        val cache = cacheOf(
            apps = listOf(app("com.a")),
            rules = listOf(
                global("disabled_id", priority = 20).copy(enabled = false),
                own("com.a", "enabled_id", priority = 50),
            ),
        )

        val rules = cache.rulesForPackage("com.a")

        assertEquals(1, rules.size)
        assertEquals("enabled_id", rules.single().targetValue)
    }

    @Test
    fun givenNoManagedApps_whenRulesForPackage_thenEmpty() = runTest {
        // 上游 observeEnabledForAccessibility() 只返回"已勾选无障碍"的应用，
        // 因此空列表等价于"用户一个都没开"
        val cache = cacheOf(
            apps = emptyList(),
            rules = listOf(own("com.a", "id/x", priority = 50), global("sdk_id", priority = 20)),
        )

        assertTrue(cache.rulesForPackage("com.a").isEmpty())
        assertEquals(0, cache.enabledRuleCount)
        assertEquals(0, cache.globalRuleCount)
    }

    // ==================================================================
    // 计数
    // ==================================================================

    @Test
    fun givenMixedRules_whenCounting_thenGlobalCountedSeparately() = runTest {
        val cache = cacheOf(
            apps = listOf(app("com.a")),
            rules = listOf(
                own("com.a", "id/1", priority = 50),
                own("com.a", "id/2", priority = 40),
                global("sdk_1", priority = 20),
                global("sdk_2", priority = 18),
            ),
        )

        assertEquals(2, cache.globalRuleCount)
        assertEquals(1, cache.managedPackageCount)
        // 总数 = 专属 2 + 通用 2
        assertEquals(4, cache.enabledRuleCount)
    }

    @Test
    fun givenEmptyInputs_whenCounting_thenAllZero() = runTest {
        val cache = cacheOf(apps = emptyList(), rules = emptyList())

        assertEquals(0, cache.enabledRuleCount)
        assertEquals(0, cache.globalRuleCount)
        assertEquals(0, cache.managedPackageCount)
        assertTrue(cache.rulesForPackage("com.any").isEmpty())
    }

    @Test
    fun givenRulesButNoApps_whenCounting_thenSnapshotEmpty() = runTest {
        val cache = cacheOf(
            apps = emptyList(),
            rules = listOf(global("sdk_id", priority = 20)),
        )

        // 无任何纳管应用时，通用规则无处附着
        assertEquals(0, cache.enabledRuleCount)
        assertTrue(cache.rulesForPackage("com.any").isEmpty())
    }

    // ==================================================================
    // 快照随数据源变化重建
    // ==================================================================

    @Test
    fun givenAppBecomesManaged_whenFlowEmits_thenRulesBecomeAvailable() = runTest {
        val appsFlow = MutableStateFlow<List<TargetApp>>(emptyList())
        val rulesFlow = MutableStateFlow(listOf(global("sdk_id", priority = 20)))
        val cache = S1RuleCache(
            targetAppsFlow = appsFlow,
            enabledRulesFlow = rulesFlow,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

        // 初始：未纳管 → 通用规则不生效
        assertTrue(cache.rulesForPackage("com.a").isEmpty())

        // 用户在应用管理页勾选该应用
        appsFlow.value = listOf(app("com.a"))

        // 快照应随 Flow 自动重建，无需重新构造缓存 ——
        // 这正是"缓存必须由容器持有而非在服务里新建"的原因
        assertEquals(1, cache.rulesForPackage("com.a").size)
        assertTrue(cache.isManaged("com.a"))
    }

    @Test
    fun givenRuleDisabledLater_whenFlowEmits_thenRemovedFromSnapshot() = runTest {
        val appsFlow = MutableStateFlow(listOf(app("com.a")))
        val globalRule = global("sdk_id", priority = 20)
        val rulesFlow = MutableStateFlow(listOf(globalRule))
        val cache = S1RuleCache(
            targetAppsFlow = appsFlow,
            enabledRulesFlow = rulesFlow,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

        assertEquals(1, cache.rulesForPackage("com.a").size)

        // 用户在规则页关掉这条规则
        rulesFlow.value = listOf(globalRule.copy(enabled = false))

        assertTrue(cache.rulesForPackage("com.a").isEmpty())
        assertEquals(0, cache.globalRuleCount)
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 构造已加载完成的缓存。
     *
     * 用 [UnconfinedTestDispatcher] 而非默认调度器：`S1RuleCache` 在 `init` 中
     * 订阅 Flow 并立即 `onEach` 重建快照，配合 unconfined 调度器可以在
     * 构造返回时就完成首次快照填充，测试无需 `advanceUntilIdle`。
     * 这让断言直接针对"快照已就绪"这一事实，而不是靠时序假设。
     */
    private fun TestScope.cacheOf(
        apps: List<TargetApp>,
        rules: List<SkipRule>,
    ): S1RuleCache = S1RuleCache(
        targetAppsFlow = MutableStateFlow(apps),
        enabledRulesFlow = MutableStateFlow(rules),
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
    )

    private fun app(packageName: String) = TargetApp(
        packageName = packageName,
        label = packageName,
        accessibilityEnabled = true,
    )

    private fun own(packageName: String, targetValue: String, priority: Int) = SkipRule(
        name = "$packageName-$targetValue",
        packageName = packageName,
        targetType = TargetType.VIEW_ID,
        targetValue = targetValue,
        priority = priority,
        source = SkipRuleSource.BUILTIN,
    )

    private fun global(targetValue: String, priority: Int = 20) = SkipRule(
        name = "通用-$targetValue",
        packageName = GLOBAL_RULE_PACKAGE,
        targetType = TargetType.VIEW_ID,
        targetValue = targetValue,
        matchMode = MatchMode.PREFIX,
        priority = priority,
        source = SkipRuleSource.BUILTIN,
    )
}
