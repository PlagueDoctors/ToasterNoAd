package com.toaster.noad.feature.home

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toaster.noad.R
import com.toaster.noad.core.database.dao.BlockedAppCount
import com.toaster.noad.core.data.repository.LogRepository
import com.toaster.noad.core.data.repository.TargetAppRepository
import com.toaster.noad.core.data.settings.SettingsRepository
import com.toaster.noad.core.model.AccessibilityState
import com.toaster.noad.core.model.InterceptSource
import com.toaster.noad.core.service.AccessibilityAutoRestorer
import com.toaster.noad.core.service.AccessibilityRecoveryController
import com.toaster.noad.core.service.AccessibilitySettingsLauncher
import com.toaster.noad.core.service.AccessibilityStateHolder
import com.toaster.noad.core.service.ProtectionFlags
import com.toaster.noad.core.service.SideloadRestrictionController
import com.toaster.noad.core.vpn.VpnStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 今日拦截排行条目。
 */
data class TopBlockedAppItem(
    val packageName: String,
    val label: String,
    val count: Int,
)

/**
 * 首页 UI 状态。
 */
data class HomeUiState(
    val todayBlocked: Int = 0,
    val protectionEnabled: Boolean = false,
    val protectedAppCount: Int = 0,
    val totalAppCount: Int = 0,
    val isLoading: Boolean = true,
    /** 今日拦截最多的应用（前 N） */
    val topApps: List<TopBlockedAppItem> = emptyList(),
    /**
     * S1 无障碍的**真实运行状态**。
     *
     * 与设置里的开关（用户意图）是两件事：
     * 用户可能在设置中授权了服务，但服务尚未被系统连接；
     * 也可能服务正在运行，但用户在应用内关闭了拦截开关。
     */
    val accessibility: AccessibilityState = AccessibilityState(),
    /**
     * Shizuku 已就绪（已授权且探测通道可用），可执行 F2 自动解除。
     *
     * 由 [SideloadRestrictionController] 的状态机映射而来，
     * feature 层不直接接触 core/shizuku（分层约束，方案 §3）。
     */
    val shizukuReady: Boolean = false,
    /** Shizuku 已安装但尚未授权：展示「授权」入口而非「自动解除」 */
    val shizukuNeedsPermission: Boolean = false,
    /** F2 解除流程进行中：按钮禁用 + 文案切换，防止重复点击 */
    val fixingRestricted: Boolean = false,
    /**
     * 已具备 adb 高级授权（WRITE_SECURE_SETTINGS，R13）：
     * 恢复按钮无需 Shizuku 也可展示，且文案不带「Shizuku」字样
     * （实际走的是 ContentResolver 直写通道）。
     */
    val secureRestoreAvailable: Boolean = false,
    /**
     * S2 DNS 过滤是否真正生效 = 用户意图（模式选中且占 VPN）× 运行事实
     * （VpnStateHolder，由 NoAdVpnService 发布）。二者缺一不可。
     */
    val dnsActive: Boolean = false,
)

/**
 * 首页 ViewModel。
 *
 * ## 数据来源
 *
 * 全部来自 Repository 的真实 Flow。原先的硬编码假数据已移除。
 *
 * ## 关于「较昨日对比」
 *
 * 该指标需要昨日区间的计数，而当前 DAO 只提供「自某时刻起」的计数。
 * 与其用近似值给出可能误导的数字，此处**先不展示该指标** ——
 * 待 DAO 补齐区间查询后再恢复（记录在待办中）。
 */
class HomeViewModel(
    application: Application,
    targetAppRepository: TargetAppRepository,
    logRepository: LogRepository,
    private val settingsRepository: SettingsRepository,
    private val sideloadRestrictionController: SideloadRestrictionController,
    private val accessibilityRecoveryController: AccessibilityRecoveryController,
    private val accessibilityAutoRestorer: AccessibilityAutoRestorer,
) : AndroidViewModel(application) {

    private val todayCount = logRepository.observeTodayCount()
    private val protectedCount = targetAppRepository.observeAccessibilityEnabledCount()
    private val totalCount = targetAppRepository.observeTotalCount()
    private val topApps = logRepository.observeTopBlockedApps()
    private val settings = settingsRepository.settings
    private val accessibilityState = AccessibilityStateHolder.state

    /** Shizuku 支持状态（门面已映射为纯布尔，feature 层不接触 core/shizuku） */
    private val shizukuSupport = sideloadRestrictionController.support

    /** F2 解除流程防重入标志 */
    private val fixInFlight = MutableStateFlow(false)

    /**
     * adb 高级授权（WRITE_SECURE_SETTINGS）在位标志（R13）。
     *
     * 不是 Flow 派生而是手动刷新：该权限是 install-time 的，
     * 运行期几乎不变（撤销需要电脑），不值得为它挂常驻观察；
     * 刷新点与授权核对同源（[refreshAccessibilityState]，init +
     * ON_RESUME），省一类刷新时机。
     */
    private val secureRestoreAvailable = MutableStateFlow(false)

    /**
     * 应用内数据（计数 + 排行），5 路以内。
     *
     * Kotlin 的 `combine` 只提供到 5 个 Flow 的类型化重载，
     * 超过即退化为 `combine(vararg)` 的 `Array<Any>` 版本 ——
     * 那样会丢失全部类型信息。因此这里先合并到 5 路，
     * 再与外部的无障碍状态、Shizuku 支持状态、解除进行中标志做二次合并。
     */
    private val appData = combine(
        todayCount,
        protectedCount,
        totalCount,
        topApps,
        settings,
    ) { today, protected_, total, top, settings ->
        AppData(
            todayBlocked = today,
            protectionEnabled = settings.protectionEnabled,
            protectedAppCount = protected_,
            totalAppCount = total,
            topApps = top.map { it.toItem() },
        )
    }

    /** 恢复相关 UI 状态的聚合（combine 5 路上限内腾位给 DNS 运行状态） */
    private val restoreUi = combine(
        fixInFlight,
        secureRestoreAvailable,
    ) { fixing, secureRestore -> RestoreUi(fixing, secureRestore) }

    /**
     * S2 是否真正生效 = **用户意图 × 运行事实**（plan §7.1 ProtectionState 语义）。
     *
     * 只看运行事实会有两个错显：模式已选「关闭」但运行标志尚未清零的
     * 窗口内误显运行中；以及服务异常残留时永远「运行中」。意图关了
     * 就必须显示关闭 —— 这是对用户唯一诚实的与逻辑。
     */
    private val dnsActive = combine(
        settingsRepository.networkFilterMode,
        VpnStateHolder.running,
    ) { mode, running -> running && mode.occupiesVpn }

    val uiState: StateFlow<HomeUiState> = combine(
        appData,
        accessibilityState,
        shizukuSupport,
        restoreUi,
        dnsActive,
    ) { data, a11y, support, restore, dnsOn ->
        HomeUiState(
            todayBlocked = data.todayBlocked,
            protectionEnabled = data.protectionEnabled,
            protectedAppCount = data.protectedAppCount,
            totalAppCount = data.totalAppCount,
            isLoading = false,
            topApps = data.topApps,
            accessibility = a11y,
            shizukuReady = support.ready,
            shizukuNeedsPermission = support.needsPermission,
            fixingRestricted = restore.fixing,
            secureRestoreAvailable = restore.secureRestore,
            dnsActive = dnsOn,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    /** 中间聚合结果，避免 6 路 combine 导致类型退化 */
    private data class AppData(
        val todayBlocked: Int,
        val protectionEnabled: Boolean,
        val protectedAppCount: Int,
        val totalAppCount: Int,
        val topApps: List<TopBlockedAppItem>,
    )

    private data class RestoreUi(
        val fixing: Boolean,
        val secureRestore: Boolean,
    )

    init {
        // 从系统设置刷新一次真实授权状态。
        //
        // 这里只是"首屏尽早拿到状态"，不是完整的刷新机制：
        // - 后台时机（息屏/解锁/授权变化）由 AccessibilityWatchdog 的广播覆盖
        // - 回到前台（含从设置页返回）由 HomeScreen 的 ON_RESUME 覆盖
        refreshAccessibilityState()
    }

    /**
     * 重新核对系统无障碍设置中的授权状态。
     *
     * ## 这不只是"读一下状态"
     *
     * 它同时承担**发现服务断开**的职责。Android 不发"服务断开"广播，
     * 只能靠核对 `Settings.Secure` 与内存中的 `serviceRunning` 对齐来反推。
     * 核对之后：
     *
     * - `isDisconnectedButAuthorized` 为真 → 服务被系统解绑，授权还在，
     *   **用户不需要做任何事**，界面对此如实展示即可
     * - `isNotAuthorized` 为真 → 确实没授权，需要引导用户去设置
     *
     * 两者在 UI 上的文案与动作完全不同，因此必须区分。
     */
    fun refreshAccessibilityState() {
        viewModelScope.launch(Dispatchers.IO) {
            // 用 application 上下文：Settings.Secure 查询是跨进程调用，
            // 传 Activity 上下文可能因 Activity 销毁而持有无效引用
            AccessibilityStateHolder.refreshFromSystemSettings(getApplication())
            // R13：顺带核对高级授权在位状态（同一刷新时机，不单设观察）
            secureRestoreAvailable.value =
                accessibilityRecoveryController.canRestoreWithoutShizuku()
            // R14：自检后顺带尝试静默自动恢复（内部自带全部门槛与节流）
            maybeAutoRestoreAuthorization()
        }
    }

    /**
     * 静默自动恢复无障碍授权（R14）。
     *
     * ## 触发条件（全部满足才动手）
     *
     * 1. **应用内 S1 开关开着**（[AppSettings.accessibilityEnabled]）——
     *    这是用户意图锚：用户想用拦截，授权丢失几乎必然是 ROM 清理；
     *    开关关着说明用户不想用，此时**绝不动系统授权**
     *    （用户去系统设置手动关闭时不会打开本应用，这个锚天然成立）；
     * 2. 授权确实丢失（`isNotAuthorized`：服务没跑且设置记录没了）——
     *    `isDisconnectedButAuthorized`（仅解绑）不触发，系统会自行重连；
     * 3. 高级授权通道在位且 [AccessibilityAutoRestorer] 内部节流允许；
     * 4. 无其他特权操作进行中（复用 [fixInFlight] 防重入）。
     *
     * ## 静默语义
     *
     * 只有**确实恢复了一条丢失的授权**（RESTORED）才提示用户 ——
     * ALREADY_PRESENT 只是服务重绑的时序差，弹提示是噪音；失败也
     * 静默：首页卡片本就会显示「未授权」状态与手动按钮兜底。
     * 手动按钮路径不受自动节流限制，两者互不干扰。
     */
    private suspend fun maybeAutoRestoreAuthorization() {
        if (fixInFlight.value) return
        val switchOn = runCatching {
            settingsRepository.settings.first().accessibilityEnabled
        }.getOrDefault(false)
        if (!switchOn) return
        if (!AccessibilityStateHolder.state.value.isNotAuthorized) return

        when (accessibilityAutoRestorer.maybeRestore()) {
            AccessibilityRecoveryController.RestoreOutcome.RESTORED -> {
                toast(R.string.restore_toast_auto_success)
                // 不在此处嵌套 refresh：写设置后系统重绑服务有延迟，
                // 立刻核对大概率仍读到旧值；holder 的下一次
                // ON_RESUME / Watchdog 刷新会纠正，服务重连本身
                // 也会经 onServiceConnected 推送真实状态。
            }
            else -> Unit
        }
    }

    /**
     * 切换 S1 无障碍拦截开关。
     *
     * 注意：这里切换的是**应用内的拦截开关**，
     * 不是系统设置里的服务授权 —— 后者只能由用户手动开启。
     * 二者是"AND"关系：都开启才真正生效。
     */
    fun setAccessibilityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAccessibilityEnabled(enabled)
            // 同步内存镜像，使无障碍服务在事件回调中立即可见
            ProtectionFlags.setAccessibilityEnabled(enabled)
        }
    }

    /** 发起 Shizuku 授权（结果经 Application 的监听回流刷新状态机与 UI） */
    fun grantShizukuPermission() {
        sideloadRestrictionController.grantPermission()
    }

    /**
     * 尝试用 Shizuku 自动解除「受限设置」（F2，方案 §6.5.3）。
     *
     * 成功（含原本已解除）后**直接打开系统无障碍设置** ——
     * 侧载用户被挡住的正是这一步；失败则停留本页，
     * 受限提示卡继续展示手动图文引导。
     */
    fun resolveRestrictedSettings() {
        if (fixInFlight.value) return
        viewModelScope.launch {
            fixInFlight.value = true
            try {
                when (sideloadRestrictionController.resolve()) {
                    SideloadRestrictionController.ResolveOutcome.ALREADY_ALLOWED -> {
                        toast(R.string.shizuku_fix_toast_already)
                        openAccessibilitySettings()
                    }

                    SideloadRestrictionController.ResolveOutcome.FIXED -> {
                        toast(R.string.shizuku_fix_toast_success)
                        openAccessibilitySettings()
                    }

                    SideloadRestrictionController.ResolveOutcome.NEEDS_PERMISSION ->
                        toast(R.string.shizuku_fix_toast_need_permission)

                    SideloadRestrictionController.ResolveOutcome.SHIZUKU_UNAVAILABLE ->
                        toast(R.string.shizuku_fix_toast_unavailable)

                    SideloadRestrictionController.ResolveOutcome.FAILED ->
                        toast(R.string.shizuku_fix_toast_failed)
                }
            } finally {
                fixInFlight.value = false
            }
        }
    }

    /**
     * 一键恢复无障碍授权（R12 Shizuku 通道 + R13 adb 高级授权通道）。
     *
     * 背景：激进 ROM 的「一键清理 / 上划清除」按 force-stop 语义处理应用，
     * 系统随之撤销无障碍授权记录 —— 用户被迫去系统设置重新开启。
     * 通道选择在门面内部：有 WRITE_SECURE_SETTINGS 走 ContentResolver
     * 直写，否则回退 Shizuku；二者都是「read-merge-write + 回读验证」。
     *
     * 与 [resolveRestrictedSettings] 共用 [fixInFlight] 防重入：
     * 两者都是特权操作，不并发执行足以覆盖互斥需求，
     * 也让按钮禁用逻辑保持单一状态源。
     */
    fun restoreAccessibilityAuthorization() {
        if (fixInFlight.value) return
        viewModelScope.launch {
            fixInFlight.value = true
            try {
                when (accessibilityRecoveryController.restoreAuthorization()) {
                    AccessibilityRecoveryController.RestoreOutcome.RESTORED -> {
                        toast(R.string.shizuku_restore_toast_success)
                        // 系统监听到设置变化会自动重绑服务，立刻核对一次状态；
                        // 若有延迟，ON_RESUME 的刷新会兜底纠正 UI
                        refreshAccessibilityState()
                    }

                    AccessibilityRecoveryController.RestoreOutcome.ALREADY_PRESENT -> {
                        toast(R.string.shizuku_restore_toast_already)
                        refreshAccessibilityState()
                    }

                    AccessibilityRecoveryController.RestoreOutcome.NEEDS_PERMISSION ->
                        toast(R.string.shizuku_fix_toast_need_permission)

                    AccessibilityRecoveryController.RestoreOutcome.SHIZUKU_UNAVAILABLE ->
                        toast(R.string.shizuku_fix_toast_unavailable)

                    AccessibilityRecoveryController.RestoreOutcome.FAILED ->
                        toast(R.string.shizuku_restore_toast_failed)
                }
            } finally {
                fixInFlight.value = false
            }
        }
    }

    private fun toast(resId: Int) {
        // 可能从 Dispatchers.IO 协程调用（R14 自动恢复挂在刷新的 IO 协程内）：
        // Toast 要求带 Looper 的线程，直接在子线程调用会 FATAL 崩溃并杀掉
        // 整个进程（实机取证：进程死 → VPN 服务随之被杀）。统一 post 主线程。
        val app = getApplication<Application>()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(app, resId, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        AccessibilitySettingsLauncher.openAccessibilitySettings(getApplication())
    }

    /** 按来源分解的统计（S1 / S2 / S3 / S4 各自拦截了多少） */
    val blockedBySource: StateFlow<Map<InterceptSource, Int>> = combine(
        logRepository.observeTodayCountBySource(InterceptSource.ACCESSIBILITY),
        logRepository.observeTodayCountBySource(InterceptSource.DNS),
        logRepository.observeTodayCountBySource(InterceptSource.VPN),
        logRepository.observeTodayCountBySource(InterceptSource.APP_FIREWALL),
    ) { accessibility, dns, vpn, firewall ->
        mapOf(
            InterceptSource.ACCESSIBILITY to accessibility,
            InterceptSource.DNS to dns,
            InterceptSource.VPN to vpn,
            InterceptSource.APP_FIREWALL to firewall,
        ).filterValues { it > 0 }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyMap(),
    )

    /** 切换总开关。真实的服务启停逻辑在后续阶段接入 */
    fun toggleProtection() {
        viewModelScope.launch {
            settingsRepository.setProtectionEnabled(!uiState.value.protectionEnabled)
        }
    }

    private fun BlockedAppCount.toItem() = TopBlockedAppItem(
        packageName = packageName,
        label = appLabel,
        count = count,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
