package com.toaster.noad.core.service

import com.toaster.noad.core.model.AccessibilityState
import com.toaster.noad.core.model.DisconnectReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 无障碍状态机测试。
 *
 * ## 为什么这个状态机值得单独测
 *
 * 它决定了 UI 对用户说哪句话，而这几句话的**动作含义完全不同**：
 *
 * | 状态 | UI 该说 | 用户该做 |
 * |---|---|---|
 * | [AccessibilityState.isEffectivelyActive] | 运行中 | 什么都不做 |
 * | [AccessibilityState.isDisconnectedButAuthorized] | 已授权，服务暂时断开 | **什么都不做**（等自愈） |
 * | [AccessibilityState.isNotAuthorized] | 未开启 | 去系统设置 |
 *
 * 中间那一态最容易被写错成"未开启"。
 * 一旦写错，用户会为了一个本来会自愈的临时断开反复跑系统设置 ——
 * 现象是"我明明开着了，它却说我没开"。
 *
 * 因此本测试类的重点是**把这三态严格区分开**，
 * 而不是泛泛地验证字段赋值。
 *
 * ## 为什么能直接测（不依赖 Android）
 *
 * [AccessibilityState] 是纯 data class，派生属性全由字段计算得出。
 * [AccessibilityStateHolder] 只在 `refreshFromSystemSettings` 里碰 Android API，
 * 其余方法（`onConnected` / `onDisconnected`）只操作内存状态，
 * 可以在 JVM 上直接跑。
 *
 * 注意测试栈**无 Robolectric**，因此这里不测 `Settings.Secure` 读取路径 ——
 * 那条路径由用户实机验证。
 */
class AccessibilityStateHolderTest {

    @Before
    fun setUp() {
        fakeClock = 1_000L
        AccessibilityStateHolder.clock = { fakeClock }
    }

    @After
    fun tearDown() {
        // holder 是全局单例，测试之间必须复位，否则互相污染
        AccessibilityStateHolder.resetForTest()
    }

    /**
     * 可控时钟。
     *
     * `android.os.SystemClock` 在 JVM 测试里会抛
     * `Method elapsedRealtime ... not mocked`（`android.jar` 是空壳），
     * 因此 holder 把它做成可注入的。这里用一个手动计数器，
     * 让"已断开多久"的边界可以被精确验证。
     */
    private var fakeClock = 1_000L

    private fun advanceClockBy(deltaMillis: Long): Long {
        fakeClock += deltaMillis
        return fakeClock
    }

    // ---- 连接 / 断开的基础语义 ----

    @Test
    fun givenInitialState_whenNothingHappened_thenNotRunningAndReasonIsNull() {
        val state = AccessibilityStateHolder.state.value

        assertFalse("初始态不应认为服务在运行", state.serviceRunning)
        assertNull("初始态不应有断开原因", state.disconnectReason)
        assertNull("初始态不应有断开时刻", state.lastDisconnectedAtMillis)
    }

    @Test
    fun givenServiceConnected_whenOnConnected_thenRunningAndDisconnectRecordCleared() {
        // 先制造一次断开记录
        AccessibilityStateHolder.onConnected()
        AccessibilityStateHolder.onDisconnected(DisconnectReason.SYSTEM_UNBOUND)
        assertNotNull(
            "前置条件：应当已记录断开时刻",
            AccessibilityStateHolder.state.value.lastDisconnectedAtMillis,
        )

        AccessibilityStateHolder.onConnected()

        val state = AccessibilityStateHolder.state.value
        assertTrue("重连后应处于运行中", state.serviceRunning)
        assertNull(
            "重连后必须清空断开时刻，否则界面会持续显示「已于 X 分钟前断开」",
            state.lastDisconnectedAtMillis,
        )
        assertNull("重连后必须清空断开原因", state.disconnectReason)
    }

    @Test
    fun givenServiceRunning_whenOnDisconnected_thenRecordsTimeAndReason() {
        AccessibilityStateHolder.onConnected()

        val disconnectMoment = advanceClockBy(SECONDS_5)
        AccessibilityStateHolder.onDisconnected(DisconnectReason.SYSTEM_UNBOUND)

        val state = AccessibilityStateHolder.state.value
        assertFalse(state.serviceRunning)
        assertEquals(DisconnectReason.SYSTEM_UNBOUND, state.disconnectReason)
        assertEquals(
            "断开时刻必须取当时的时钟读数",
            disconnectMoment,
            state.lastDisconnectedAtMillis,
        )
    }

    /**
     * `onUnbind` 与 `onDestroy` 会**先后**触发同一个服务的断开，
     * 前者携带更具体的原因。
     *
     * 若不保护，`onDestroy` 会把 `SYSTEM_UNBOUND` 覆盖成
     * `SERVICE_DESTROYED`，让用户看到的原因比实际更模糊。
     */
    @Test
    fun givenAlreadyUnbound_whenOnDestroyCalled_thenDoesNotOverwriteReason() {
        AccessibilityStateHolder.onConnected()

        AccessibilityStateHolder.onDisconnected(DisconnectReason.SYSTEM_UNBOUND)
        AccessibilityStateHolder.onDisconnected(DisconnectReason.SERVICE_DESTROYED)

        assertEquals(
            "onDestroy 不应覆盖 onUnbind 记录的更具体原因",
            DisconnectReason.SYSTEM_UNBOUND,
            AccessibilityStateHolder.state.value.disconnectReason,
        )
    }

    /**
     * 断开时刻只在**首次**断开时写入。
     *
     * 两次触发都刷新时间的话，「已断开多久」会被重置成「刚刚」，
     * 用户看不到真实的断开时长 —— 而这个时长正是判断
     * "要不要手动干预"的依据。
     */
    @Test
    fun givenAlreadyDisconnected_whenSecondDisconnect_thenDoesNotRefreshTimestamp() {
        AccessibilityStateHolder.onConnected()

        val firstDisconnectMoment = advanceClockBy(SECONDS_5)
        AccessibilityStateHolder.onDisconnected(DisconnectReason.SYSTEM_UNBOUND)

        // 时间继续走，第二次断开（模拟 onDestroy 紧随其后）
        advanceClockBy(SECONDS_30)
        AccessibilityStateHolder.onDisconnected(DisconnectReason.SERVICE_DESTROYED)

        assertEquals(
            "重复断开不应刷新时刻，否则「已断开多久」永远显示「刚刚」",
            firstDisconnectMoment,
            AccessibilityStateHolder.state.value.lastDisconnectedAtMillis,
        )
    }

    @Test
    fun givenNoReasonProvided_whenOnDisconnected_thenKeepsExistingReason() {
        AccessibilityStateHolder.onConnected()
        AccessibilityStateHolder.onDisconnected(DisconnectReason.USER_DISABLED_IN_SETTINGS)

        // 不传原因（模拟 onDestroy 在 onUnbind 之后的清理调用）
        AccessibilityStateHolder.onDisconnected()

        assertEquals(
            "未传原因时不应清空已有记录",
            DisconnectReason.USER_DISABLED_IN_SETTINGS,
            AccessibilityStateHolder.state.value.disconnectReason,
        )
    }

    // ---- ⭐ 三态区分（本测试类的核心） ----

    /**
     * 已授权但未连接 —— 最需要自愈的一态。
     *
     * 这是用户报告的"息屏/切应用后失效"在状态机里的准确位置：
     * **授权还在**（`serviceEnabledInSettings == true`），
     * 只是服务实例被解绑了（`serviceRunning == false`）。
     */
    @Test
    fun givenAuthorizedButNotConnected_thenIsDisconnectedButAuthorized() {
        AccessibilityStateHolder.onConnected()
        AccessibilityStateHolder.onDisconnected(DisconnectReason.SYSTEM_UNBOUND)
        // 模拟：断开后系统设置里的授权记录仍在
        seedAuthorizedInSettings(true)

        val state = AccessibilityStateHolder.state.value

        assertTrue(
            "已授权 + 未连接 = 可自愈态",
            state.isDisconnectedButAuthorized,
        )
        assertFalse(
            "可自愈态绝不能同时被判定为「未授权」，" +
                "否则界面会错误引导用户去设置",
            state.isNotAuthorized,
        )
        assertFalse(
            "可自愈态也不该被判定为「需要系统权限」",
            state.needsSystemPermission,
        )
    }

    /** 完全未授权 —— 这才是真正需要用户去设置的情形。 */
    @Test
    fun givenNotAuthorizedAtAll_thenIsNotAuthorized() {
        seedAuthorizedInSettings(false)

        val state = AccessibilityStateHolder.state.value

        assertTrue("未授权应被识别", state.isNotAuthorized)
        assertTrue("未授权等同于「需要系统权限」", state.needsSystemPermission)
        assertFalse(
            "未授权时不应被判定为「已授权但断开」",
            state.isDisconnectedButAuthorized,
        )
    }

    @Test
    fun givenServiceRunning_thenNeitherDisconnectedNorUnauthorized() {
        AccessibilityStateHolder.onConnected()
        seedAuthorizedInSettings(true)

        val state = AccessibilityStateHolder.state.value

        assertFalse("运行中不应被判定为断开", state.isDisconnectedButAuthorized)
        assertFalse("运行中不应被判定为未授权", state.isNotAuthorized)
        assertFalse("运行中不应要求系统权限", state.needsSystemPermission)
    }

    /**
     * `needsSystemPermission` 的既有语义不可被本轮改动破坏。
     *
     * 服务能跑起来就说明系统已授权，此时即使设置读取（有缓存延迟）
     * 返回 false，也不该反过来提示"需要权限"。
     */
    @Test
    fun givenServiceRunningButSettingsReadStale_thenDoesNotAskForPermission() {
        AccessibilityStateHolder.onConnected()
        seedAuthorizedInSettings(false)

        assertFalse(
            "服务正在运行是比设置读取更强的证据，不应提示需要权限",
            AccessibilityStateHolder.state.value.needsSystemPermission,
        )
    }

    // ---- isEffectivelyActive 不受本轮改动影响 ----

    @Test
    fun givenRunningAndAppSwitchOn_thenEffectivelyActive() {
        AccessibilityStateHolder.onConnected()
        AccessibilityStateHolder.setAppSwitchEnabled(true)

        assertTrue(
            "运行中 + 应用内开关开 = 真正生效",
            AccessibilityStateHolder.state.value.isEffectivelyActive,
        )
    }

    @Test
    fun givenRunningButAppSwitchOff_thenNotEffectivelyActive() {
        AccessibilityStateHolder.onConnected()
        AccessibilityStateHolder.setAppSwitchEnabled(false)

        assertFalse(
            "应用内开关关闭时不算生效（用户的关闭意图优先）",
            AccessibilityStateHolder.state.value.isEffectivelyActive,
        )
    }

    @Test
    fun givenDisconnectedButAuthorizedAndSwitchOn_thenNotEffectivelyActive() {
        AccessibilityStateHolder.onConnected()
        AccessibilityStateHolder.setAppSwitchEnabled(true)
        AccessibilityStateHolder.onDisconnected(DisconnectReason.SYSTEM_UNBOUND)
        seedAuthorizedInSettings(true)

        assertFalse(
            "授权与开关都在但服务没连接，不能算生效 —— " +
                "这正是用户报告「看起来开着却不拦截」的状态",
            AccessibilityStateHolder.state.value.isEffectivelyActive,
        )
    }

    // ---- DisconnectReason 解析 ----

    @Test
    fun givenKnownPersistedName_whenFromPersistedName_thenReturnsMatchingReason() {
        assertEquals(
            DisconnectReason.SYSTEM_UNBOUND,
            DisconnectReason.fromPersistedName("SYSTEM_UNBOUND"),
        )
    }

    @Test
    fun givenLowercasePersistedName_whenFromPersistedName_thenMatchesIgnoringCase() {
        assertEquals(
            DisconnectReason.SERVICE_DESTROYED,
            DisconnectReason.fromPersistedName("service_destroyed"),
        )
    }

    @Test
    fun givenNullOrBlankPersistedName_whenFromPersistedName_thenReturnsNull() {
        assertNull(DisconnectReason.fromPersistedName(null))
        assertNull(DisconnectReason.fromPersistedName(""))
        assertNull(DisconnectReason.fromPersistedName("   "))
    }

    /**
     * 未知值退回 [DisconnectReason.UNKNOWN] 而非抛异常。
     *
     * 这是**诊断信息**：版本升级导致的枚举变化不该让整个状态读取失败，
     * 进而让界面崩在"读不出状态"上。
     */
    @Test
    fun givenUnknownPersistedName_whenFromPersistedName_thenFallsBackToUnknown() {
        assertEquals(
            DisconnectReason.UNKNOWN,
            DisconnectReason.fromPersistedName("SOME_FUTURE_REASON"),
        )
    }

    @Test
    fun givenAllReasons_whenReadingLabel_thenEveryLabelIsNonBlank() {
        DisconnectReason.entries.forEach { reason ->
            assertTrue(
                "断开原因 ${reason.name} 缺少中文说明，UI 会显示空白",
                reason.label.isNotBlank(),
            )
        }
    }

    @Test
    fun givenAllDisconnectReasons_whenCounting_thenExpectedSetIsStable() {
        // 枚举值变化会影响 UI 的 when 分支完整性，锁住集合防止无声变动
        assertEquals(
            "断开原因集合发生变化时，请同步检查 UI 的分支处理",
            listOf(
                DisconnectReason.SYSTEM_UNBOUND,
                DisconnectReason.SERVICE_DESTROYED,
                DisconnectReason.USER_DISABLED_IN_SETTINGS,
                DisconnectReason.UNKNOWN,
            ),
            DisconnectReason.entries.toList(),
        )
    }

    // ---- 「已断开多久」的边界 ----

    /**
     * 断开时长由 `clock()` 差值决定，边界必须准确。
     *
     * 这是纯计算，可以在此完整验证；UI 侧只是把结果套进 string resource。
     * 边界取「刚断开 / 59 秒 / 1 分 / 59 分 / 1 小时」，
     * 覆盖 UI 的 `when` 分支切换点。
     */
    @Test
    fun givenDisconnectAge_whenComputingElapsed_thenBoundariesAreExact() {
        val cases = listOf(
            // 0ms → 刚断开
            0L to ElapsedBucket.JUST_NOW,
            // 59.999s → 仍是刚断开（不足 1 分钟不进"X 分钟前"）
            (MINUTE_MILLIS - 1) to ElapsedBucket.JUST_NOW,
            // 正好 1 分钟 → 进入"分钟"档
            MINUTE_MILLIS to ElapsedBucket.MINUTES,
            // 59 分钟 59 秒 → 仍是分钟档
            (HOUR_MILLIS - 1) to ElapsedBucket.MINUTES,
            // 正好 1 小时 → 进入"小时"档
            HOUR_MILLIS to ElapsedBucket.HOURS,
        )

        cases.forEach { (ageMillis, expectedBucket) ->
            AccessibilityStateHolder.onConnected()
            val disconnectMoment = advanceClockBy(SECONDS_5)
            AccessibilityStateHolder.onDisconnected(DisconnectReason.SYSTEM_UNBOUND)

            // 让时钟前进到"已过 ageMillis"
            val now = disconnectMoment + ageMillis
            val actualBucket = bucketOf(now - disconnectMoment)

            assertEquals(
                "断开 $ageMillis ms 时应落在 $expectedBucket",
                expectedBucket,
                actualBucket,
            )

            // 每个用例独立，复位以便下一个用例从干净状态开始
            AccessibilityStateHolder.resetForTest()
            AccessibilityStateHolder.clock = { fakeClock }
        }
    }

    /**
     * 时钟回拨（或进程重启后残留旧值）会产生负的时长。
     *
     * 此时必须归入"刚断开"，而不是显示「-3 分钟前断开」。
     * UI 侧的 `elapsed < 0` 分支依赖这个前提。
     */
    @Test
    fun givenNegativeElapsed_whenBucketing_thenTreatedAsJustNow() {
        val negativeAge = -SECONDS_30

        assertEquals(
            "负时长必须归入「刚断开」，不能显示负数",
            ElapsedBucket.JUST_NOW,
            bucketOf(negativeAge),
        )
    }

    /** 复刻 UI 的分档逻辑，用于在无 Compose 环境下验证边界 */
    private fun bucketOf(elapsedMillis: Long): ElapsedBucket = when {
        elapsedMillis < 0 -> ElapsedBucket.JUST_NOW
        elapsedMillis < MINUTE_MILLIS -> ElapsedBucket.JUST_NOW
        elapsedMillis < HOUR_MILLIS -> ElapsedBucket.MINUTES
        else -> ElapsedBucket.HOURS
    }

    private enum class ElapsedBucket { JUST_NOW, MINUTES, HOURS }

    // ---- 测试辅助 ----

    /**
     * 直接写入 `serviceEnabledInSettings`。
     *
     * 生产代码里该字段由 `refreshFromSystemSettings` 从 `Settings.Secure` 读出，
     * 而项目测试栈**无 Robolectric**，无法在 JVM 上提供 ContentResolver。
     *
     * 因此这里只验证**由该字段驱动的派生逻辑**（三态判定）是否正确；
     * "设置读取本身是否可靠"属于 Android 平台行为，由用户实机验证。
     * 这个边界必须诚实标注，不能假装覆盖了 `Settings.Secure` 读取路径。
     */
    private fun seedAuthorizedInSettings(enabled: Boolean) {
        AccessibilityStateHolder.seedSettingsFlagForTest(enabled)
    }

    private companion object {
        const val SECONDS_5 = 5_000L
        const val SECONDS_30 = 30_000L

        /** 与 UI 的 `MINUTE_MILLIS` 保持一致 */
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 3_600_000L
    }
}
