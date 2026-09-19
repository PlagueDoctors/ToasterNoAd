package com.toaster.noad.core.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.toaster.noad.NoAdApplication
import com.toaster.noad.core.engine.ui.ClickExecutor
import com.toaster.noad.core.model.AdType
import com.toaster.noad.core.model.InterceptSource
import com.toaster.noad.core.service.event.EventProcessor
import com.toaster.noad.core.service.event.InterceptRecord
import com.toaster.noad.core.service.event.ProcessOutcome
import com.toaster.noad.core.service.event.SkipReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * S1 无障碍拦截服务。
 *
 * ## 这是本应用唯一"能看见界面"的组件
 *
 * 它由**系统**在用户于系统设置中授权后拉起，生命周期完全由系统控制：
 * 用户可能随时关闭、系统可能在内存紧张时解绑。因此本服务**不做保活** ——
 * 无障碍服务是系统级服务，本身就享有很高优先级，
 * 额外加前台服务或 JobScheduler 属于画蛇添足，只会增加耗电与用户困惑。
 *
 * ## 权限边界（必须清楚，避免做出错误设计）
 *
 * 本服务能做的：
 * - 读取当前窗口的节点树（文本、viewId、坐标）
 * - 派发点击（`ACTION_CLICK` 或坐标手势）
 * - 执行全局操作（返回、Home）
 *
 * 本服务**做不到**的：
 * - 读取其他应用的**应用内数据**（只能看到渲染出来的 UI）
 * - 拦截网页内的横幅广告（那是 S2/S3 的职责）
 * - 在应用未出现在前台时"预判"广告
 *
 * ## 线程模型
 *
 * [onAccessibilityEvent] 运行在**主线程**，必须快速返回。
 * 因此所有耗时工作（写库、统计）都在 [ioScope] 中异步完成。
 */
class NoAdAccessibilityService : AccessibilityService() {

    /**
     * 用于日志写库等 IO 操作。
     *
     * 用 [SupervisorJob]：单次写库失败不应导致整个作用域失效，
     * 否则一次数据库异常就会让后续所有拦截记录都无法落库。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var processor: EventProcessor

    /** 上一次事件的包名，用于识别窗口切换 */
    private var lastPackageName: String? = null

    /**
     * 解析应用名的缓存。
     *
     * 每次拦截都调 `PackageManager.getApplicationInfo` 有开销，
     * 而同一应用会反复产生拦截。缓存后热路径只剩一次哈希查表。
     */
    private val appLabelCache = HashMap<String, String>(INITIAL_LABEL_CACHE)

    /**
     * 上一次记录过的跳过原因。
     *
     * 用于"只在原因变化时打日志"，避免内容变化事件（可达每秒数十次）
     * 把主线程日志刷爆。见 [recordSkipReason]。
     */
    private var lastSkipReason: SkipReason? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        // 动态声明服务能力。
        // 不在 XML 中写死 flags 的原因是 flags 需要按运行期条件组合，
        // 且部分厂商 ROM 会忽略 XML 中的部分字段。
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            // 100ms 是官方示例的常用值：更小会增加回调频率但提升及时性。
            // 取 100ms 是"广告出现到被点掉"与"回调开销"的折中。
            notificationTimeout = EVENT_TIMEOUT_MS

            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS

            // 不请求 FLAG_RETRIEVE_INTERACTIVE_WINDOWS：
            // 它会让服务接收所有窗口（包括输入法、系统弹窗）的事件，
            // 既增加开销，也提高误点风险。单个活动窗口足够 S1 使用。
        }

        val container = NoAdApplication.containerOf(this)

        processor = EventProcessor(
            // 复用容器中的缓存实例，而非在服务内新建：
            // 服务可能被系统反复解绑/重连，每次新建会重复订阅 Flow
            ruleCache = container.s1RuleCache,
            clickExecutor = ClickExecutor(this),
            scope = ioScope,
            onIntercepted = ::persistIntercept,
        )

        AccessibilityStateHolder.onConnected()
        AccessibilityStateHolder.refreshFromSystemSettings(this)

        log("S1 服务已连接")
    }

    /**
     * 事件回调（主线程）。
     *
     * 执行顺序刻意按"最便宜的先判断"排列：
     * 包名过滤 -> 事件类型 -> Activity -> 取节点树 -> 匹配 -> 点击。
     * 绝大多数事件在前两步就被丢弃，这是保证不卡顿的关键。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 总开关关闭时直接忽略：用户意图优先于任何优化。
        // 读取的是内存中的 volatile 标记，不是 DataStore 查询。
        if (!ProtectionFlags.accessibilityEnabled) return

        val packageName = event.packageName?.toString()

        // 过滤掉本应用自身的事件，避免"自己点自己"的循环。
        // 注意这里比较的是本应用包名，不是事件包名。
        if (packageName == null || packageName == applicationContext.packageName) return

        // 窗口切换时重置防误点记账。
        // 不做这一步会导致新界面的广告因上一界面的残留冷却而点不掉。
        if (packageName != lastPackageName) {
            processor.onWindowChanged()
            lastPackageName = packageName
        }

        val outcome = runCatching {
            processor.process(event) { rootInActiveWindow }
        }.getOrElse { error ->
            // 事件处理中的任何异常都不应让服务崩溃 ——
            // 无障碍服务崩溃会被系统静默重启，用户只会看到"拦截失灵"
            log("事件处理异常: ${error.javaClass.simpleName}: ${error.message}")
            return
        }

        if (DEBUG_LOG) {
            // 未处理的事件也记录原因，但只统计原因分布而不逐条打日志。
            // 逐条打印会把主线程日志刷爆（内容变化事件每秒可达数十次），
            // 而这个分布恰恰是判断"到底卡在哪一步"的关键证据 ——
            // 例如全是 APP_NOT_MANAGED 说明应用没纳管，
            // 全是 NO_RULE_FOR_PACKAGE 说明内置规则不覆盖该应用。
            when (val result = outcome) {
                is ProcessOutcome.Ignored -> recordSkipReason(result.reason)
                else -> log("事件结果: $result")
            }
        }
    }

    /**
     * 记录一次"事件被跳过"的原因。
     *
     * 只在 [SkipReason] 的**统计值发生变化**时打一条日志：
     * 这样既能通过 logcat 直接看出当前卡在哪一步，
     * 又不会让日志量随界面刷新频率膨胀。
     */
    private fun recordSkipReason(reason: SkipReason) {
        if (reason == lastSkipReason) return
        lastSkipReason = reason
        log("事件跳过: ${reason.name}（${reason.label}）")
    }

    override fun onInterrupt() {
        log("S1 服务被中断")
    }

    /**
     * 服务被解绑（用户在系统设置中关闭、或系统回收）。
     *
     * 必须在此更新状态，否则 UI 会一直显示"运行中"。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityStateHolder.onDisconnected()
        log("S1 服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityStateHolder.onDisconnected()
        ioScope.cancel()
        super.onDestroy()
    }

    /**
     * 写库（在 [ioScope] 的 IO 线程执行）。
     *
     * 之所以不在 [onAccessibilityEvent] 中直接调用：
     * 那会在主线程产生磁盘 IO。本方法由 [EventProcessor] 在自己的
     * 协程中调用，因此这里是单纯的挂起函数，不含任何阻塞调用。
     */
    private suspend fun persistIntercept(record: InterceptRecord) {
        runCatching {
            val container = NoAdApplication.containerOf(this)
            container.logRepository.record(
                source = InterceptSource.ACCESSIBILITY,
                appLabel = resolveAppLabel(record.packageName),
                adType = AdType.OTHER,
                packageName = record.packageName,
                ruleDetail = record.ruleDetail,
            )
        }.onFailure { error ->
            log("写库失败: ${error.javaClass.simpleName}")
        }
    }

    /**
     * 解析应用名。
     *
     * 应用名冗余存进日志表，避免日志页每次渲染都调 PackageManager 联查 ——
     * 日志可能一次展示数百条，逐条查包信息会明显卡顿。
     */
    private fun resolveAppLabel(packageName: String): String {
        appLabelCache[packageName]?.let { return it }

        val label = runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

        appLabelCache[packageName] = label
        return label
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "NoAdAccessibility"

        /** 调试期打开可观察每次事件的判定结果；发布版本应关闭 */
        const val DEBUG_LOG = true

        /**
         * 事件回调节流窗口。
         *
         * 100ms 的含义：系统在 100ms 内的同类事件会被合并，
         * 显著降低回调次数，同时对"广告出现"的响应延迟感知不到。
         */
        const val EVENT_TIMEOUT_MS = 100L

        const val INITIAL_LABEL_CACHE = 8
    }
}
