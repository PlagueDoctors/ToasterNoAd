package com.toaster.noad.core.service.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.toaster.noad.NoAdApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 保活链条的三条「外部触发」入口。
 *
 * | 触发 | 时机 | 决策 |
 * |---|---|---|
 * | [Intent.ACTION_BOOT_COMPLETED] | 设备重启后 | 「后台保活」且「开机自启动」都开 → 恢复 |
 * | [Intent.ACTION_MY_PACKAGE_REPLACED] | 应用更新后 | 「后台保活」开 → 恢复 |
 * | [ACTION_HEARTBEAT] | 心跳闹钟（15 分钟链） | 保活开 → 拉活并续链；关 → 终结链条 |
 *
 * ## 为什么更新后要专门恢复
 *
 * 闹钟由系统侧持有，**进程死亡不会清除**，但**应用更新会**。
 * 更新后无障碍服务虽会自动重连拉起进程，但进程起在后台 ——
 * 此时 Application 观察者的 `startForegroundService` 大概率被
 * 后台启动限制拒绝，而心跳闹钟已被更新清空、无人重排。
 * 不处理 `MY_PACKAGE_REPLASED` 就会出现「更新一次，保活静默死亡」。
 *
 * `MY_PACKAGE_REPLACED` 与 `BOOT_COMPLETED` 一样，都在官方
 * 前台服务后台启动豁免清单里 —— 这两条路径的恢复是**有豁免保证**的，
 * 与心跳闹钟的「尽力而为」不同。
 *
 * ## 为什么用 goAsync + 协程读 DataStore
 *
 * 决策依据是 DataStore（挂起读取）。`BroadcastReceiver.onReceive`
 * 只有约 10 秒，且不该在其主线程做磁盘 IO；`goAsync()` 把接收器
 * 生命周期延长到协程完成，读一个布尔值的耗时（毫秒级）远在窗口内。
 *
 * 广播接收器可能先于 Application 观察者完成镜像同步 ——
 * 因此这里**不读镜像**，直接读 DataStore 权威值。
 *
 * ## 导出策略
 *
 * manifest 中 `exported="true"`：`BOOT_COMPLETED` 与
 * `MY_PACKAGE_REPLACED` 都是**受保护系统广播**（第三方应用无法伪造），
 * 导出无伪造风险；而部分厂商 ROM 对非导出接收器的系统广播投递不可靠。
 * 自定义的心跳 action **不在 intent-filter 中**，经显式 Intent 投递，
 * 第三方无法触发。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(appContext, action)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String) {
        val repository = NoAdApplication.containerOf(context).settingsRepository
        val keepAlive = repository.keepAliveEnabled.first()

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val autostart = repository.autostart.first()
                when (KeepAlivePolicy.onBootCompleted(keepAlive, autostart)) {
                    KeepAlivePolicy.BootAction.RESTORE_KEEP_ALIVE ->
                        restoreKeepAlive(context)

                    KeepAlivePolicy.BootAction.DO_NOTHING -> Unit
                }
            }

            Intent.ACTION_MY_PACKAGE_REPLACED ->
                when (KeepAlivePolicy.onPackageReplaced(keepAlive)) {
                    KeepAlivePolicy.BootAction.RESTORE_KEEP_ALIVE ->
                        restoreKeepAlive(context)

                    KeepAlivePolicy.BootAction.DO_NOTHING -> Unit
                }

            ACTION_HEARTBEAT ->
                when (KeepAlivePolicy.onHeartbeat(keepAlive)) {
                    KeepAlivePolicy.HeartbeatAction.TRY_START_AND_RESCHEDULE ->
                        restoreKeepAlive(context)

                    KeepAlivePolicy.HeartbeatAction.CANCEL_AND_STOP ->
                        KeepAliveService.ensureStopped(context)
                }
        }
    }

    /**
     * 恢复保活：确保前台服务在跑，并重排心跳。
     *
     * 服务已在运行时 `ensureStarted` 是幂等的（重复
     * `startForeground` 只会原位刷新通知）；**无论启动成败都必须
     * `scheduleNext`** —— 闹钟是一次性的，失败不重排就断链，
     * 这是 [KeepAlivePolicy.HeartbeatAction.TRY_START_AND_RESCHEDULE]
     * 的字面契约。
     */
    private fun restoreKeepAlive(context: Context) {
        KeepAliveService.ensureStarted(context)
        KeepAliveScheduler.scheduleNext(context)
    }

    companion object {
        /** 心跳闹钟 action（显式投递，不进 manifest filter） */
        const val ACTION_HEARTBEAT = "com.toaster.noad.action.KEEP_ALIVE_HEARTBEAT"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_HEARTBEAT,
        )
    }
}
