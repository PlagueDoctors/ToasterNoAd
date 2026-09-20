package com.toaster.noad.core.service.event

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.toaster.noad.core.engine.ui.ClickOutcome
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.TargetApp
import com.toaster.noad.core.model.TargetType
import com.toaster.noad.core.repository.S1RuleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [EventProcessor] 的 JVM 单测（性能重构后新增）。
 *
 * ## 为什么这个类以前没有测试、现在有
 *
 * 重构前，事件处理链在 `process()` 内同步完成扫描，而
 * `AccessibilityEvent`/`AccessibilityNodeInfo` 都是 Android 类 ——
 * JVM 上无法构造，导致**合并、投递、闸门顺序、线程契约**这些
 * 最值得锁定的行为全部不可测。
 *
 * 重构把「可测内核」拆成纯数据入口 [EventProcessor.submit]
 * （后续所有断言都走它），Android 适配层只剩字段提取。
 *
 * ## 锁定四组契约
 *
 * 1. **闸门顺序**：未纳管 → 无规则 → 事件类型 → 预筛 → 执行环境，逐级短路
 * 2. **投递语义**：通过全部闸门 → `Queued`（而非谎言式的 ClickScheduled）
 * 3. **线程契约**：扫描发生在 [scope] 线程，**绝不在调用线程**
 * 4. **合并语义**：后台忙碌期间连续 N 次投递 → 只多扫一次
 *    （这正是「启动期 2 秒延迟」被消除的机理）
 */
class EventProcessorTest {

    private val cacheScope = CoroutineScope(Dispatchers.Unconfined)

    /** 无 Android 依赖的假点击执行：本测试的请求都会因 root=null 走到 NO_ROOT_NODE，不会触发 */
    private val noClick: suspend (AccessibilityNodeInfo?, com.toaster.noad.core.engine.ui.MatchResult) -> ClickOutcome =
        { _, _ -> ClickOutcome.Failed("unused-in-test") }

    private fun ruleCache(
        managedPackages: List<String> = listOf(PKG),
        rules: List<SkipRule> = listOf(textPrefixRule()),
    ): S1RuleCache = S1RuleCache(
        targetAppsFlow = flowOf(managedPackages.map { TargetApp(packageName = it, label = it) }),
        enabledRulesFlow = flowOf(rules),
        scope = cacheScope,
    )

    private fun textPrefixRule() = SkipRule(
        name = "跳过前缀",
        packageName = PKG,
        targetType = TargetType.TEXT,
        targetValue = "跳过",
        matchMode = MatchMode.PREFIX,
    )

    private fun viewIdRule() = SkipRule(
        name = "跳过按钮 id",
        packageName = PKG,
        targetType = TargetType.VIEW_ID,
        targetValue = "*count_down",
    )

    private fun processor(
        cache: S1RuleCache,
        root: () -> AccessibilityNodeInfo? = { null },
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    ): EventProcessor = EventProcessor(
        ruleCache = cache,
        clickAction = noClick,
        scope = scope,
        rootProvider = root,
        // 注入纯 JVM 时钟与静音日志：
        // 生产默认实现依赖 Android 的 SystemClock/Log，
        // 在单测中会抛 "not mocked"（且会被 runCatching 静默吞掉，
        // 表现为「后台从未扫描」——本轮实测踩到过这个坑）
        clock = { System.nanoTime() / 1_000_000 },
        log = { },
    )

    private fun awaitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("条件在 ${timeoutMs}ms 内未满足")
    }

    private companion object {
        /** 测试用包名（内置规则覆盖的应用） */
        const val PKG = "tv.danmaku.bili"
    }

    // ---------------- 闸门顺序 ----------------

    @Test
    fun givenUnknownPackage_whenSubmit_thenIgnoredUnknownPackage() {
        val outcome = processor(ruleCache()).submit(
            packageName = null,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            activityName = null,
            candidates = listOf("跳过 1"),
            hasHandoff = true,
        )
        assertEquals(
            ProcessOutcome.Ignored(SkipReason.UNKNOWN_PACKAGE),
            outcome,
        )
    }

    @Test
    fun givenUnmanagedPackage_whenSubmit_thenIgnoredAppNotManaged() {
        val outcome = processor(ruleCache(managedPackages = listOf("other.app"))).submit(
            packageName = PKG,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            activityName = null,
            candidates = listOf("跳过 1"),
            hasHandoff = true,
        )
        assertEquals(
            ProcessOutcome.Ignored(SkipReason.APP_NOT_MANAGED, PKG),
            outcome,
        )
    }

    @Test
    fun givenManagedPackageWhoseRulesBelongToOtherApp_whenSubmit_thenIgnoredNoRule() {
        // 快照语义（S1RuleCache.buildSnapshot）：apps 与 rules 任一为空 → 整个快照为空
        // （连纳管集合都空，事件快速失败）。因此「有规则、但都不是本应用的」
        // 才是 NO_RULE_FOR_PACKAGE 的真实场景。
        val cache = ruleCache(
            managedPackages = listOf(PKG),
            rules = listOf(
                SkipRule(
                    name = "别的应用的规则",
                    packageName = "com.example.other",
                    targetType = TargetType.TEXT,
                    targetValue = "跳过",
                    matchMode = MatchMode.PREFIX,
                ),
            ),
        )
        val outcome = processor(cache).submit(
            packageName = PKG,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            activityName = null,
            candidates = listOf("跳过 1"),
            hasHandoff = true,
        )
        assertEquals(
            ProcessOutcome.Ignored(SkipReason.NO_RULE_FOR_PACKAGE, PKG),
            outcome,
        )
    }

    @Test
    fun givenIrrelevantEventType_whenSubmit_thenIgnoredIrrelevantEvent() {
        val outcome = processor(ruleCache()).submit(
            packageName = PKG,
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            activityName = null,
            candidates = listOf("跳过 1"),
            hasHandoff = true,
        )
        assertEquals(
            ProcessOutcome.Ignored(SkipReason.IRRELEVANT_EVENT, PKG, 1),
            outcome,
        )
    }

    @Test
    fun givenTextRuleAndNonMatchingCandidates_whenSubmit_thenPreFiltered() {
        // 纯文本规则 + 事件文本不含「跳过」→ 预筛拦截（零扫描）
        val outcome = processor(ruleCache(rules = listOf(textPrefixRule()))).submit(
            packageName = PKG,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            activityName = null,
            candidates = listOf("首页", "推荐"),
            hasHandoff = true,
        )
        assertEquals(
            ProcessOutcome.Ignored(SkipReason.PRE_FILTERED, PKG, 1),
            outcome,
        )
    }

    @Test
    fun givenViewIdRuleAndEmptyCandidates_whenSubmit_thenQueued() {
        // VIEW_ID 规则无法靠文本预筛 → 预筛整体放行 → 投递后台
        val outcome = processor(ruleCache(rules = listOf(viewIdRule()))).submit(
            packageName = PKG,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            activityName = null,
            candidates = emptyList(),
            hasHandoff = true,
        )
        assertTrue(outcome is ProcessOutcome.Queued)
        assertEquals(PKG, (outcome as ProcessOutcome.Queued).packageName)
    }

    @Test
    fun givenAllGatesPassWithoutHandoff_whenSubmit_thenDeferred() {
        val outcome = processor(ruleCache(rules = listOf(viewIdRule()))).submit(
            packageName = PKG,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            activityName = null,
            candidates = emptyList(),
            hasHandoff = false,
        )
        assertTrue(outcome is ProcessOutcome.Deferred)
    }

    // ---------------- 线程契约 ----------------

    @Test
    fun givenQueued_whenBackgroundRuns_thenRootProviderNotCalledOnCallerThread() {
        val callerThread = Thread.currentThread().name
        val scanned = CountDownLatch(1)
        val scannedThread = arrayOfNulls<String>(1)

        val processor = processor(
            cache = ruleCache(rules = listOf(viewIdRule())),
            root = {
                scannedThread[0] = Thread.currentThread().name
                scanned.countDown()
                null // 触发 NO_ROOT_NODE 分支，测试无需构造真实节点
            },
        )

        val outcome = processor.submit(
            packageName = PKG,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            activityName = null,
            candidates = emptyList(),
            hasHandoff = true,
        )
        assertTrue(outcome is ProcessOutcome.Queued)

        // ★ 线程契约（本质断言）：扫描由后台消费者执行，绝不在调用线程。
        // 注意不断言「submit 返回时尚未扫描」—— 后台可能快到抢先完成，
        // 那是调度时序而非契约；契约是**执行者的身份**，不是执行时刻。
        assertTrue("后台应在 2s 内开始扫描", scanned.await(2, TimeUnit.SECONDS))
        assertNotNull(scannedThread[0])
        assertNotEquals("扫描必须在后台线程，绝不在调用线程", callerThread, scannedThread[0])
    }

    // ---------------- 合并语义 ----------------

    @Test
    fun givenWorkerBusy_whenBurstOfSubmits_thenMergedToAtMostOneMoreScan() {
        val enteredFirstScan = CountDownLatch(1)
        val releaseFirstScan = CountDownLatch(1)
        val scanCount = AtomicInteger(0)

        val processor = processor(
            cache = ruleCache(rules = listOf(viewIdRule())),
            root = {
                val n = scanCount.incrementAndGet()
                if (n == 1) {
                    // 第一次扫描卡住不动，模拟「扫描很贵（数百 IPC）」的真实情况
                    enteredFirstScan.countDown()
                    releaseFirstScan.await(3, TimeUnit.SECONDS)
                }
                null
            },
        )

        // 第 1 次投递：后台开始扫描并阻塞
        processor.submit(PKG, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, null, emptyList(), true)
        assertTrue("首次扫描应已开始", enteredFirstScan.await(2, TimeUnit.SECONDS))

        // 扫描进行中，连续投递 5 次（模拟启动期的事件风暴）
        repeat(5) {
            processor.submit(PKG, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, null, emptyList(), true)
        }

        releaseFirstScan.countDown()

        // 合并语义：这 5 次只应产生**再一次**扫描（取最新界面），而不是 5 次
        awaitUntil { scanCount.get() >= 2 }
        Thread.sleep(150) // 给「错误实现」留出多扫的机会，让断言有意义
        assertEquals(
            "忙碌期间的连续投递必须合并为至多一次补充扫描",
            2,
            scanCount.get(),
        )
    }
}
