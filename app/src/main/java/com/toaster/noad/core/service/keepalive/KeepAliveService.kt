package com.toaster.noad.core.service.keepalive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.toaster.noad.NoAdApplication
import com.toaster.noad.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 应用进程保活前台服务（specialUse 类型）。
 *
 * ## 它解决什么问题
 *
 * 与 [com.toaster.noad.core.service.AccessibilityWatchdog]（R8）的分工：
 *
 * | 组件 | 层面 | 职责 |
 * |---|---|---|
 * | AccessibilityWatchdog | 无障碍服务**连接** | 发现「已授权但被解绑」并如实呈现 |
 * | KeepAliveService | 应用**进程**存活 | 抬高进程优先级，进程死后多通道自启 |
 *
 * R8 已定论：无障碍是授权模型，「服务实例的常驻」由系统重连保证、
 * 不需要也不应该用前台服务去保。但**进程**是另一回事 ——
 * 进程一旦死亡，Watchdog 的广播监听、内存镜像、一切内存态全部清零；
 * 激进省电 ROM（如本项目的目标设备努比亚 Android 15）还会把缓存进程
 * 连同授权服务的重连一并延迟。前台服务把进程从「可随时回收的缓存态」
 * 提升为「前台服务态」，是 Android 官方框架内唯一合法的进程级保活手段。
 *
 * ## 恢复通道（按时效排序）
 *
 * 1. **粘性重启**（秒级）：`START_STICKY` 使系统在杀掉服务后自行重启
 *    实例。系统发起的重启不受后台启动限制约束，`onStartCommand` 收到
 *    `null` intent，按 [KeepAlivePolicy.onStickyRestart] 处置
 * 2. **心跳闹钟**（≤15 分钟）：进程死亡不清除系统侧闹钟，
 *    [KeepAliveReceiver] 醒来后重拉服务（见 KeepAliveScheduler 的豁免说明）
 * 3. **BOOT_COMPLETED**（重启手机后）：由「开机自启动」开关控制，
 *    `specialUse` 类型在 Android 15 的 BOOT 白名单内（官方兼容框架
 *    FGS_BOOT_COMPLETED_RESTRICTIONS 明确列出）
 *
 * ## 诚实边界
 *
 * - **强制停止**（设置里的「强行停止」或部分 ROM 的一键加速）会冻结
 *   应用的一切接收器与闹钟，**任何应用都无法自启** —— 这是 Android
 *   的安全设计，不是缺陷。只能等用户手动打开应用
 * - 前台服务**必须**挂常驻通知（系统强制），使用低优先级静默渠道，
 *   不发声、不弹横幅
 * - 常驻通知的内容由 [KeepAliveNotificationContent] 决定（纯决策层）：
 *   默认实时展示「累计拦截次数 + 最近一条拦截」，且仅在通知栏更新、
 *   不弹横幅；用户可通过「拦截动态」开关回退为静态保活文案
 *
 * ## 为什么不在这里 cancel 闹钟
 *
 * 闹钟的终结权在 [KeepAlivePolicy.HeartbeatAction.CANCEL_AND_STOP]
 * 与 `ensureStopped`（用户关闭路径）。`onDestroy` 里取消会把
 * 「系统杀死服务」也一并掐断心跳 —— 恰恰相反，心跳必须活得比服务久，
 * 否则没有东西去拉活它。
 */
class KeepAliveService : Service() {

    /**
     * 服务级协程作用域：承载拦截动态的观察者流。
     *
     * 生命周期与服务实例绑定 —— onDestroy 时取消，避免服务销毁后
     * Room Flow 仍在后台做无效查询与通知刷新。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        observeInterceptionStats()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (intent == null) {
            // 系统粘性重启：信任镜像决策（unknown → 乐观恢复，观察者流秒级纠正）
            when (KeepAlivePolicy.onStickyRestart(KeepAliveRuntime.keepAliveEnabled)) {
                KeepAlivePolicy.StickyAction.RESUME_FOREGROUND -> {
                    enterForeground()
                    KeepAliveScheduler.scheduleNext(this)
                    START_STICKY
                }

                KeepAlivePolicy.StickyAction.STOP_SELF -> {
                    // 用户已明确关闭 —— 不恢复前台、不再粘性，链条交由 ensureStopped 收尾
                    stopSelf()
                    START_NOT_STICKY
                }
            }
        } else {
            // 显式启动：发起方（Application 观察者 / KeepAliveReceiver）
            // 都已核对过用户设置，此处直接兑现
            enterForeground()
            KeepAliveScheduler.scheduleNext(this)
            START_STICKY
        }
    }

    /**
     * 进入前台状态。
     *
     * API 34+ 必须传入与清单声明一致的类型（`specialUse`），
     * 否则抛 `InvalidForegroundServiceTypeException`；
     * API 34 以下使用双参重载（`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
     * 常量本身在 API 34 才引入）。
     */
    private fun enterForeground() {
        // 首帧用静态文案：观察者流的首次发射在毫秒级之后到达，
        // 此刻拉起前台不能等任何异步数据（startForeground 必须同步完成）
        val notification = buildNotification(
            KeepAliveNotificationContent.resolve(
                showStats = false,
                totalCount = 0,
                latestAppLabel = null,
                latestAdTypeLabel = "",
            ),
        )
        if (Build.VERSION.SDK_INT >= ANDROID_14) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 观察拦截动态并实时刷新常驻通知。
     *
     * 三路合流：「拦截动态」开关 + 全量拦截数 + 最近一条记录。
     * Room 的 Flow 在 `intercept_log` 表变化时自动重发 —— 每次新拦截
     * 入库后通知即更新，无需任何手动触发。刷新走 `notify()` 覆盖
     * 同 ID 通知，渠道为 `IMPORTANCE_LOW`，**不会弹横幅、不发声**，
     * 只在下拉通知栏中呈现 —— 这正是本需求的表述约束。
     *
     * 决策逻辑全部在 [KeepAliveNotificationContent]（纯函数，单测覆盖）；
     * 此处只做格式化与投递。
     */
    private fun observeInterceptionStats() {
        val container = NoAdApplication.containerOf(this)
        scope.launch {
            combine(
                container.settingsRepository.showNotification,
                container.logRepository.observeTotalCount(),
                container.logRepository.observeLatest(),
            ) { showStats, totalCount, latest ->
                KeepAliveNotificationContent.resolve(
                    showStats = showStats,
                    totalCount = totalCount,
                    latestAppLabel = latest?.appLabel,
                    latestAdTypeLabel = latest?.adType?.label ?: "",
                )
            }.collect { content ->
                if (canPostNotifications()) {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(content))
                }
            }
        }
    }

    /** Android 13+ 未授予通知权限时更新会被静默丢弃，跳过无效投递 */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < ANDROID_13 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 构建/更新常驻通知。
     *
     * `getText` / `getString` 直接使用继承自 Context 的实现 ——
     * 不要定义同名私有函数，会遮蔽继承方法并造成自递归。
     */
    private fun buildNotification(content: KeepAliveNotificationContent.Content): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getText(content.titleRes, content.titleArgs))
            .setContentText(getText(content.textRes, content.textArgs))
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    /** 把 [ResId] 参数解析为字符串，其余参数（如拦截次数）原样交给格式化 */
    private fun getText(resId: Int, args: List<Any>): CharSequence =
        if (args.isEmpty()) {
            getText(resId)
        } else {
            getString(resId, *args.map { if (it is ResId) getText(it.id) else it }.toTypedArray())
        }

    /**
     * 创建低优先级通知渠道。
     *
     * `IMPORTANCE_LOW`：不发声、不浮动横幅，只出现在通知栏。
     * 保活通知是用户「知情」用的，不是「提醒」用的 ——
     * 用默认优先级会在每次服务启动时打扰用户。
     */
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getText(R.string.keep_alive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.keep_alive_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {

        private const val TAG = "NoAdKeepAlive"
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1001
        private const val ANDROID_13 = 33
        private const val ANDROID_14 = 34

        /**
         * 启动保活前台服务（幂等）。
         *
         * ## 为什么全量 try/catch
         *
         * 调用点（Application 观察者）可能在应用处于后台时执行 ——
         * 例如进程被无障碍服务重连拉起、而用户没有打开过应用。
         * Android 12+ 的后台前台服务限制会抛
         * `ForegroundServiceStartNotAllowedException`；
         * 这不是错误，是「此刻不允许」—— 静默记录，等待心跳闹钟重试。
         *
         * 不让保活的失败路径影响任何调用方是硬要求：
         * 观察者流还承担着镜像同步之外的初始化职责。
         */
        fun ensureStarted(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "前台服务启动被拒绝（等待心跳重试）: " +
                            "${error.javaClass.simpleName}: ${error.message}",
                    )
                }
        }

        /**
         * 停止保活并终结心跳链条。
         *
         * `stopService` 由本应用自身发起，不受后台启动限制约束；
         * 服务被销毁时系统自动移除常驻通知。
         */
        fun ensureStopped(context: Context) {
            KeepAliveScheduler.cancel(context)
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
