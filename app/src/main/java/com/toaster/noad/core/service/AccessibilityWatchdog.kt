package com.toaster.noad.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 无障碍服务的进程级「自愈」监听器。
 *
 * ## 它解决的问题
 *
 * 用户报告：**息屏、切换应用、离开界面一段时间后，无障碍拦截就失效了。**
 *
 * 实测与官方文档一致的行为是：系统在这些时机**解绑了服务实例**，
 * 但 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 中的授权记录
 * **依然存在**。也就是说：
 *
 * - 用户看到的"权限被关了"其实是"服务实例被解绑了"
 * - 授权还在，所以**不需要用户重新授权**，系统或本应用的对策即可恢复
 *
 * ## ⚠️ 为什么不做"保活"
 *
 * 无障碍是**授权模型**，不是"启动一个服务让它一直活着"。
 * 官方没有任何 API 能让应用要求系统"保持连接"，
 * 前台服务 / `AlarmManager` / `JobScheduler` 对此**完全无效** ——
 * 它们影响的是进程优先级，而服务实例的绑定由系统的无障碍管理器决定。
 *
 * 因此本类的职责被严格限定为：
 *
 * 1. **尽早发现**服务已断开（系统不发"服务断开"广播，只能自己核对）
 * 2. **如实记录**断开时刻与原因，供 UI 呈现
 * 3. **不阻止**系统重新连接（重新连接是期望行为）
 *
 * 换句话说：**"常驻"由授权记录保证，本类保证的是"断开能被及时发现"。**
 *
 * ## 触发时机（刻意保持低频）
 *
 * | 时机 | 广播 | 为什么选它 |
 * |---|---|---|
 * | 息屏 | `ACTION_SCREEN_ON` | 直接对应"息屏后失效"的报告场景 |
 * | 解锁 | `ACTION_USER_PRESENT` | 用户即将使用设备，此刻恢复最有用 |
 * | 授权变化 | `ACCESSIBILITY_STATE_CHANGED` | 系统在无障碍启用集合变化时发出 |
 *
 * 这三者都是**系统广播**，不受 Android 8+ 的后台广播限制
 * （受限的是隐式自定义广播，系统广播与功耗相关的例外）。
 *
 * **刻意不注册** `ACTION_SCREEN_OFF`：息屏瞬间的服务状态没有意义 ——
 * 屏幕都黑了，用户不需要拦截广告。真正需要"已恢复"的时刻是亮屏与解锁。
 *
 * **刻意不注册** 网络变化广播：S1 是无障碍路径，与网络无关。
 * 等阶段 C 引入 S2/S3 时再按需添加，现在加上只是徒增唤醒。
 *
 * ## 生命周期
 *
 * 由 [com.toaster.noad.NoAdApplication] 在 `onCreate` 中启动，存活期等于进程。
 * 不做停止：进程结束时系统会自动清理注册的接收器，
 * 手动 `unregister` 反而会引入"谁负责停止"的生命周期问题。
 */
class AccessibilityWatchdog(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {

    /**
     * 是否已启动。
     *
     * 防重入：`Application.onCreate` 在正常情况下只调用一次，
     * 但测试、多进程配置或 ROM 异常都可能让它跑到第二次，
     * 重复 `registerReceiver` 会导致同一个广播触发多次刷新。
     */
    @Volatile
    private var started = false

    /**
     * 开始监听。
     *
     * 幂等：重复调用直接返回。
     */
    fun start() {
        if (started) return
        started = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BOOT_COMPLETED)
            if (Build.VERSION.SDK_INT >= ANDROID_12) {
                // 系统在无障碍启用集合变化时发出。
                // 这是"用户刚在设置里开关了服务"的最快信号，
                // 比等下一次息屏要即时得多。
                addAction(ACCESSIBILITY_STATE_CHANGED)
            }
        }

        val registered = runCatching {
            registerCompat(filter)
        }.onFailure { error ->
            // 注册失败不应影响应用启动：这只是自愈能力的增强项，
            // 丢失它最多让状态刷新变慢（还有 onResume 那条路径兜底）。
            log("自愈监听注册失败: ${error.javaClass.simpleName}: ${error.message}")
        }.isSuccess

        if (registered) {
            log("自愈监听已启动（息屏 / 解锁 / 授权变化）")
        }

        // 启动时立即核对一次：进程刚起来时服务可能尚未连接。
        // 这次核对让首屏就能显示真实状态，而不是等到用户解锁才更新。
        refresh()
    }

    /**
     * 主动核对一次系统授权状态。
     *
     * 只读 `Settings.Secure`，不涉及任何写入或权限提升。
     * 放在 [scope] 而非在主线程执行：`Settings.Secure.getString` 是
     * 跨进程查询，在 `onCreate` 的主线程路径上同步调用会拖慢冷启动。
     */
    fun refresh() {
        scope.launch {
            runCatching { AccessibilityStateHolder.refreshFromSystemSettings(appContext) }
        }
    }

    /**
     * 注册广播接收器，兼容 Android 14+ 的导出要求。
     *
     * Android 14（API 34）起，对**非系统来源**的广播注册必须显式声明
     * 是否导出。本接收器只监听系统广播，不需要任何外部应用触发它，
     * 因此必须是 `RECEIVER_NOT_EXPORTED`。
     *
     * 注意那两个 `ACTION_*` 是系统广播，不受此限制约束，
     * 但**统一使用同一个标志**更安全：漏标会在 Android 14+ 直接抛
     * `SecurityException`，而多标 NOT_EXPORTED 对系统广播没有任何副作用
     * （系统广播的发送者是 system_server，不受导出标志影响）。
     */
    private fun registerCompat(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= ANDROID_14) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            log("触发自愈核对: ${intent.action}")

            // 在接收器的后台线程直接启动协程：
            // `onReceive` 本身运行在主线程且有 10 秒超时，
            // 但刷新涉及跨进程设置读取，必须移出主线程。
            refresh()
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "NoAdWatchdog"

        const val ACCESSIBILITY_STATE_CHANGED =
            "android.intent.action.ACCESSIBILITY_STATE_CHANGED"

        const val ANDROID_12 = 31
        const val ANDROID_14 = 34
    }
}
