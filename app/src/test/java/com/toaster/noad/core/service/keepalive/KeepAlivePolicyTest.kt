package com.toaster.noad.core.service.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeepAlivePolicy] 单元测试。
 *
 * ## 为什么这个类必须被重点测试
 *
 * 保活链路横跨 5 个 Android 组件，但全部 Android 依赖（Service /
 * BroadcastReceiver / AlarmManager）在 JVM 单测中不可构造。决策层
 * [KeepAlivePolicy] 是唯一能在单测中验证的环节，因此它承载了保活
 * 体系**最危险的两类 bug** 的防线：
 *
 * 1. **「关不掉」**（用户关闭保活后服务自复活）—— 红线测试：
 *    用户明确 `false` 的所有路径都必须停止。这类 bug 的观感等同
 *    恶意软件行为，是绝对不可接受的。
 * 2. **「保不住」**（用户开启保活但链条静默断裂）—— 每个触发点
 *    在开启状态下都必须尝试恢复；心跳必须「失败也重排」，
 *    否则一次性闹钟链断掉后保活在静默中死亡。
 *
 * ## 测试组的划分依据
 *
 * 按保活链条的四个触发点分组：
 * 1. 粘性重启（三态：null / true / false —— 镜像乐观策略的依据）
 * 2. BOOT_COMPLETED（双开关 4 组合 —— 门控 = keepAlive && autostart）
 * 3. MY_PACKAGE_REPLACED（两态 —— 只看保活，不受自启门控）
 * 4. 心跳（两态 + 失败重排契约 + Doze 窗口不变式）
 */
class KeepAlivePolicyTest {

    // ==================================================================
    // 第 1 组：粘性重启 —— 唯一硬约束是「明确 false 必须停止」
    // ==================================================================

    @Test
    fun givenStickyRestart_whenUserExplicitlyDisabled_thenStopSelf() {
        // 红线：「关不掉」bug 的唯一入口。任何重构都不允许改变此断言。
        assertEquals(
            "用户明确关闭保活后，粘性重启必须停止服务",
            KeepAlivePolicy.StickyAction.STOP_SELF,
            KeepAlivePolicy.onStickyRestart(userEnabled = false),
        )
    }

    @Test
    fun givenStickyRestart_whenUserExplicitlyEnabled_thenResumeForeground() {
        assertEquals(
            KeepAlivePolicy.StickyAction.RESUME_FOREGROUND,
            KeepAlivePolicy.onStickyRestart(userEnabled = true),
        )
    }

    @Test
    fun givenStickyRestart_whenMirrorNotSynced_thenOptimisticallyResume() {
        // null = 镜像未同步（进程刚被系统拉起，观察者流尚未发射首值）。
        // 乐观恢复的理由：误恢复由观察者流毫秒级纠正；误停止则要等
        // 最长一个心跳周期（15 分钟）才恢复，恰是被杀风险最高时段。
        assertEquals(
            "镜像未知（null）时必须乐观恢复，而非保守停止",
            KeepAlivePolicy.StickyAction.RESUME_FOREGROUND,
            KeepAlivePolicy.onStickyRestart(userEnabled = null),
        )
    }

    // ==================================================================
    // 第 2 组：BOOT_COMPLETED —— 双开关 4 组合，门控为 AND
    // ==================================================================

    @Test
    fun givenBootCompleted_whenBothSwitchesOn_thenRestoreKeepAlive() {
        assertEquals(
            "保活与自启都开启时，重启后必须重建保活链（闹钟已被重启清空）",
            KeepAlivePolicy.BootAction.RESTORE_KEEP_ALIVE,
            KeepAlivePolicy.onBootCompleted(keepAlive = true, autostart = true),
        )
    }

    @Test
    fun givenBootCompleted_whenAutostartDisabled_thenDoNothing() {
        // 用户明确表示「重启后别自己动」，即使保活开着 —— 这是
        // 自启开关作为独立用户意图的表达，不得被保活开关覆盖。
        assertEquals(
            KeepAlivePolicy.BootAction.DO_NOTHING,
            KeepAlivePolicy.onBootCompleted(keepAlive = true, autostart = false),
        )
    }

    @Test
    fun givenBootCompleted_whenKeepAliveDisabled_thenDoNothing() {
        // 没有保活服务可恢复。无障碍拦截的重连由系统授权模型负责，
        // 与本决策无关 —— 这里不做任何事是正确行为。
        assertEquals(
            KeepAlivePolicy.BootAction.DO_NOTHING,
            KeepAlivePolicy.onBootCompleted(keepAlive = false, autostart = true),
        )
    }

    @Test
    fun givenBootCompleted_whenBothSwitchesOff_thenDoNothing() {
        assertEquals(
            KeepAlivePolicy.BootAction.DO_NOTHING,
            KeepAlivePolicy.onBootCompleted(keepAlive = false, autostart = false),
        )
    }

    // ==================================================================
    // 第 3 组：MY_PACKAGE_REPLACED —— 只看保活，不受自启门控
    // ==================================================================

    @Test
    fun givenPackageReplaced_whenKeepAliveEnabled_thenRestore() {
        // 与 BOOT 的关键差异：更新是用户主动行为，与「重启后别自己动」
        // 的意图无关。且更新会清除全部闹钟 —— 不恢复则心跳链永久断裂。
        assertEquals(
            "应用更新后保活开启必须恢复（更新清空了闹钟链）",
            KeepAlivePolicy.BootAction.RESTORE_KEEP_ALIVE,
            KeepAlivePolicy.onPackageReplaced(keepAlive = true),
        )
    }

    @Test
    fun givenPackageReplaced_whenKeepAliveDisabled_thenDoNothing() {
        assertEquals(
            KeepAlivePolicy.BootAction.DO_NOTHING,
            KeepAlivePolicy.onPackageReplaced(keepAlive = false),
        )
    }

    // ==================================================================
    // 第 4 组：心跳 —— 失败重排契约与 Doze 窗口不变式
    // ==================================================================

    @Test
    fun givenHeartbeat_whenKeepAliveEnabled_thenTryStartAndReschedule() {
        // 字面契约：TRY_START **AND** RESCHEDULE。即使本次
        // startForegroundService 失败，也必须重排下一次心跳 ——
        // 一次性闹钟不重排即断链，这是链条唯一的自愈路径。
        assertEquals(
            KeepAlivePolicy.HeartbeatAction.TRY_START_AND_RESCHEDULE,
            KeepAlivePolicy.onHeartbeat(keepAlive = true),
        )
    }

    @Test
    fun givenHeartbeat_whenKeepAliveDisabled_thenCancelAndStop() {
        // 防御性设计：若停止路径因进程死亡没执行完，这次心跳就是
        // 链条最后一次触发 —— 必须在此终结，否则出现「关了开关，
        // 后台服务还在」的僵尸状态。
        assertEquals(
            KeepAlivePolicy.HeartbeatAction.CANCEL_AND_STOP,
            KeepAlivePolicy.onHeartbeat(keepAlive = false),
        )
    }

    @Test
    fun givenHeartbeatInterval_whenComparedToDozeWindow_thenNotTruncated() {
        // 不变式：间隔必须不小于 Doze 每应用 9 分钟的闹钟合并窗口。
        // 短于 9 分钟的间隔不会更频繁地触发（被系统合并），白白浪费
        // 排程语义；长于 15 分钟则最坏恢复延迟超出设计预期。
        val dozeWindowMs = 9 * 60_000L
        assertTrue(
            "心跳间隔(${KeepAliveScheduler.HEARTBEAT_INTERVAL_MS}ms)必须 >= Doze 合并窗口(${dozeWindowMs}ms)，否则被系统截断",
            KeepAliveScheduler.HEARTBEAT_INTERVAL_MS >= dozeWindowMs,
        )
    }
}
