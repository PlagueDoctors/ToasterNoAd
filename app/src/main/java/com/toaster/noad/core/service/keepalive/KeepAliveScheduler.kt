package com.toaster.noad.core.service.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 保活心跳闹钟的排期器。
 *
 * ## 机制选择：为什么是「一次性闹钟链」而不是 `setRepeating`
 *
 * `setRepeating` 在 API 19+ 全部退化为非精确触发，且无法在每次触发时
 * 重新评估「保活是否仍开启」；而保活的核心需求恰恰是**每次心跳都要
 * 重新读一次用户设置**（用户可能已关闭开关）。
 *
 * 因此采用自续期链条：每次触发后由 [KeepAliveReceiver] 决定是否排下一次。
 * 链条在用户关闭时由 [cancel] 终结。
 *
 * ## 为什么用 `setAndAllowWhileIdle`（非精确）
 *
 * - **精确闹钟**（`setExactAndAllowWhileIdle`）在 Android 13+ 需要
 *   `SCHEDULE_EXACT_ALARM` 特殊权限，默认被拒，需要用户去
 *   「闹钟和提醒」特殊访问页手动授予 —— 对保活这种非用户感知功能，
 *   这个授权门槛不可接受，且 Google 明确表示精确闹钟
 *   「只应用于用户可感知的功能」
 * - **非精确闹钟**免权限；代价是触发时刻有分钟级漂移，
 *   且在 Doze 下被合并到维护窗口（每 app 最长 9 分钟一次）——
 *   对 15 分钟量级的心跳来说漂移无关紧要
 *
 * ## 后台启动豁免的现实
 *
 * 官方豁免清单写的是「精确闹钟」，非精确闹钟触发时的
 * `startForegroundService` **不保证**豁免。因此接收器侧的启动
 * 全部包裹 try/catch：失败不崩溃、不放弃 —— 下一次心跳继续重试。
 * 这就是「失败也必须重排」的 [KeepAlivePolicy.HeartbeatAction]
 * `TRY_START_AND_RESCHEDULE` 语义。
 *
 * ## 闹钟与进程死亡
 *
 * `PendingIntent` 由系统侧持有，**进程被杀不会取消已排定的闹钟**
 * （只有「强制停止」和卸载会取消）。这正是心跳能作为
 * 「进程被杀后的自启通道」的原因。
 *
 * ## 时钟选择
 *
 * `ELAPSED_REALTIME_WAKEUP` + 单调时钟：不受用户手动改系统时间、
 * 时区切换影响。心跳只关心「距上次过了多久」，不关心「现在是几点」。
 */
internal object KeepAliveScheduler {

    /**
     * 心跳间隔。
     *
     * 取 15 分钟：
     * - 大于 Doze 模式下每应用 9 分钟一次的闹钟合并窗口，
     *   不会被系统截断
     * - 一天最多 96 次唤醒，单次只做一次设置读取 + 一次
     *   （通常失败的、幂等的）服务启动，功耗可忽略
     * - 进程被杀且粘性重启失败的最坏恢复延迟 = 一个心跳周期，
     *   对「保活」场景可接受
     */
    const val HEARTBEAT_INTERVAL_MS: Long = 15 * 60_000L

    private const val HEARTBEAT_REQUEST_CODE = 2001

    /** 排定下一次心跳（覆盖旧的，幂等） */
    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val elapsed = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            elapsed,
            heartbeatPendingIntent(context),
        )
    }

    /** 终结心跳链条（用户关闭保活时调用） */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(heartbeatPendingIntent(context))
    }

    /**
     * 心跳的 PendingIntent。
     *
     * 用**显式 Intent**（`setClass` 指定接收器组件）而非依赖
     * manifest intent-filter 匹配：
     *
     * - 同应用内的显式投递不受接收器是否注册 filter 影响
     * - 自定义 action 若放进 exported 接收器的 filter，
     *   任何第三方应用都能广播它（虽然无害，但没有暴露的理由）
     */
    private fun heartbeatPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            HEARTBEAT_REQUEST_CODE,
            Intent(context, KeepAliveReceiver::class.java).setAction(
                KeepAliveReceiver.ACTION_HEARTBEAT,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
