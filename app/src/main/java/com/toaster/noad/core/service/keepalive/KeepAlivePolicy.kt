package com.toaster.noad.core.service.keepalive

/**
 * 保活决策的纯逻辑层。
 *
 * ## 为什么要把决策抽成纯函数
 *
 * 保活链路横跨 5 个 Android 组件（前台服务、广播接收器、闹钟、
 * Application 观察者、设置仓库），每个组件只能在自己生命周期内做一小段事。
 * 决策规则如果散落在各组件里，将**无法在 JVM 单测中验证** ——
 * 组件全部依赖 Android 框架类（`Service` / `BroadcastReceiver` / `AlarmManager`），
 * 而 `android.jar` 空壳使它们在单测中不可构造。
 *
 * 抽成纯函数后，保活体系最关键的两条不变式可以被测试锁死：
 *
 * 1. **用户明确关闭后，任何路径都不得自复活**
 *    （经典 bug：关掉保活，几分钟后台服务又悄悄回来了 —— 「关不掉」）
 * 2. **用户开启后，链条上的每个触发点都应尝试恢复**
 *    （被系统杀死 → 粘性重启恢复；粘性失败 → 心跳闹钟恢复；
 *    重启手机 → BOOT_COMPLETED 恢复；全部失效只剩强制停止一种情况）
 */
internal object KeepAlivePolicy {

    /**
     * 粘性重启（`onStartCommand` 收到 `intent == null`）时的处置。
     *
     * ## 触发场景
     *
     * `START_STICKY` 服务被系统杀死后，系统会在内存压力缓解时**自行重启**
     * 服务实例 —— 这一次重启不走 `startForegroundService()`，
     * 因此不受后台启动限制约束（系统发起的重启本身是豁免的）。
     * `onStartCommand` 会收到 `null` intent，这是它与显式启动的唯一区别。
     *
     * ## 乐观恢复策略（`userEnabled == null` 时选择恢复）
     *
     * 镜像未同步（进程刚被系统拉起、Application 的观察者流尚未发出
     * 首个值）时选择**乐观恢复**，理由：
     *
     * - 误恢复的代价极小：若用户实际是关闭状态，Application 观察者的
     *   首次发射（进程启动后毫秒级）就会调用 `ensureStopped` 纠正，
     *   窗口期以秒计，用户无感知
     * - 误停止的代价大：若用户实际是开启状态，服务被误停后只能等
     *   下一次心跳闹钟（最长 15 分钟）才恢复 —— 这 15 分钟里进程
     *   失去前台服务保护，恰恰是被杀风险最高的时段
     *
     * 唯一的硬约束是 [KeepAliveRuntime.keepAliveEnabled] **明确为 false**
     * 时必须停止 —— 这是「关不掉」bug 的唯一入口。
     */
    enum class StickyAction {
        /** 恢复前台状态并重排心跳闹钟（链条续上） */
        RESUME_FOREGROUND,

        /** 立即 stopSelf 且不再粘性重启（用户已关闭） */
        STOP_SELF,
    }

    fun onStickyRestart(userEnabled: Boolean?): StickyAction =
        if (userEnabled == false) StickyAction.STOP_SELF else StickyAction.RESUME_FOREGROUND

    /**
     * `BOOT_COMPLETED` 时的处置。
     *
     * 由「后台保活」与「开机自启动」两个**独立开关**共同决定：
     *
     * - 保活关闭 + 自启开启 → 不动作（没有保活服务可恢复；
     *   无障碍拦截本身由系统授权模型自动重连，与这两个开关无关）
     * - 保活开启 + 自启关闭 → 不动作（用户明确表示重启后别自己动；
     *   直到用户手动打开应用，观察者流才会重新拉起服务）
     * - 两者都开 → 恢复前台服务 + 重排心跳（闹钟在重启后会被系统清空，
     *   必须由 BOOT 路径重建链条）
     *
     * ## 关于「无障碍拦截在重启后自动恢复」
     *
     * 已授权的无障碍服务在重启后由系统自动重新绑定（TalkBack 等
     * 读屏软件正是依赖这一行为）。因此「开机自启动」开关的**真实职责**
     * 只是恢复前台服务保活，而不是恢复拦截本身 —— 设置页副标题
     * 必须按此表述，不能写成「恢复拦截」误导用户。
     */
    enum class BootAction {
        /** 恢复前台服务并重建心跳闹钟链 */
        RESTORE_KEEP_ALIVE,

        /** 什么都不做（等待用户手动打开应用） */
        DO_NOTHING,
    }

    fun onBootCompleted(keepAlive: Boolean, autostart: Boolean): BootAction =
        if (keepAlive && autostart) BootAction.RESTORE_KEEP_ALIVE else BootAction.DO_NOTHING

    /**
     * `MY_PACKAGE_REPLACED`（应用更新完成）时的处置。
     *
     * 只看保活开关、**不受「开机自启动」门控**：更新是用户主动行为
     * （与「重启后别自己动」的用户意图无关），且更新会清除全部闹钟 ——
     * 不在此恢复，心跳链条就永久断了（详见 KeepAliveReceiver 的说明）。
     */
    fun onPackageReplaced(keepAlive: Boolean): BootAction =
        if (keepAlive) BootAction.RESTORE_KEEP_ALIVE else BootAction.DO_NOTHING

    /**
     * 心跳闹钟触发时的处置。
     *
     * 心跳是链条的最后防线：**无论本次启动前台服务是否成功，
     * 只要保活开启就必须重排下一次心跳**。
     *
     * ## 为什么失败也要重排
     *
     * 心跳闹钟是一次性的（`setAndAllowWhileIdle` 非重复闹钟）。
     * 若某次触发时 `startForegroundService` 因后台启动限制被拒
     * （Android 12+ 对非精确闹钟的豁免不保证），闹钟已经消费掉了 ——
     * 此刻不重排，链条就永久断裂，保活在静默中死亡。
     * 失败 → 下一次心跳重试，是唯一的自愈路径。
     *
     * ## 为什么关掉时要顺带取消
     *
     * 防御性设计：如果用户在两次心跳之间关闭了保活，但停止路径
     * （Application 观察者 → `ensureStopped`）因进程死亡没能执行完，
     * 这次心跳就是链条的最后一次触发 —— 必须在此终结闹钟并停掉服务，
     * 否则出现「关了开关，后台服务还在」的僵尸状态。
     */
    enum class HeartbeatAction {
        /** 尝试启动前台服务，并无论成败都重排下一次心跳 */
        TRY_START_AND_RESCHEDULE,

        /** 取消闹钟并停止前台服务（链条终结） */
        CANCEL_AND_STOP,
    }

    fun onHeartbeat(keepAlive: Boolean): HeartbeatAction =
        if (keepAlive) HeartbeatAction.TRY_START_AND_RESCHEDULE
        else HeartbeatAction.CANCEL_AND_STOP
}
