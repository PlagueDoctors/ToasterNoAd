package com.toaster.noad.core.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.toaster.noad.core.model.AccessibilityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍服务运行状态发布器（进程内单例）。
 *
 * ## 为什么必须是进程内单例 + StateFlow
 *
 * 无障碍服务由系统在**独立于 UI 的时机**启动和停止
 * （用户可能在系统设置里开关，App 进程甚至可能不在前台）。
 * UI 必须能**实时**反映"服务到底在不在跑"，而不是靠读设置里的开关值 ——
 * 用户意图（DataStore 开关）与实际运行状态是两件事，混为一谈就会出现
 * 「界面显示已开启，实际没在拦截」这种最误导人的状态。
 *
 * ## 为什么不直接读 Settings.Secure
 *
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 确实能查，
 * 但它是**跨进程读取的字符串解析**，且系统有缓存延迟，
 * 开关后不一定立刻反映。因此采用双轨：
 *
 * - **权威来源**：服务自身在 `onServiceConnected` / `onUnbind` 中写入
 * - **兜底来源**：[refreshFromSystemSettings] 解析系统设置，
 *   用于服务尚未被系统拉起、但用户已在设置中授权的过渡态
 *
 * 两者取"或"：任一为真即认为已授权，避免出现假阴性导致用户
 * 明明开了却看到"未开启"。
 *
 * ## 关于 serviceRunning 的诚实性
 *
 * `serviceRunning` 只代表**本进程的服务实例已被系统连接**。
 * 这是 UI 唯一能确切知道的运行时事实，不做任何推测性放大。
 */
object AccessibilityStateHolder {

    private val _state = MutableStateFlow(AccessibilityState())

    /** 对外只读状态流 */
    val state: StateFlow<AccessibilityState> = _state.asStateFlow()

    /** 由服务在 `onServiceConnected` 中调用 */
    fun onConnected() {
        _state.value = _state.value.copy(serviceRunning = true)
    }

    /**
     * 由服务在 `onUnbind` / `onDestroy` 中调用。
     *
     * 注意不清除 `serviceEnabledInSettings`：
     * 服务被解绑不代表用户在设置里关闭了它
     * （系统可能在内存紧张时解绑后重新连接）。
     */
    fun onDisconnected() {
        _state.value = _state.value.copy(serviceRunning = false)
    }

    /**
     * 更新应用内 S1 开关状态。
     *
     * 由 `ProtectionFlags` 的同步逻辑或 ViewModel 在切换开关时调用。
     * 之所以不由本 holder 自己订阅 DataStore：
     * holder 是 `object`，没有生命周期，自行订阅会缺少取消时机；
     * 由持有作用域的调用方推送更安全。
     */
    fun setAppSwitchEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(appSwitchEnabled = enabled)
    }

    /**
     * 从系统设置刷新"是否已在设置中启用"。
     *
     * 必须在**非主线程**调用：`Settings.Secure.getString` 是跨进程查询，
     * 在冷启动的主线程调用会拖慢首帧。实际上它由 ViewModel 在协程中调用。
     *
     * @param context 任意 Context（内部取 applicationContext）
     */
    fun refreshFromSystemSettings(context: Context) {
        val appContext = context.applicationContext
        val enabledInSettings = runCatching {
            isServiceEnabledInSettings(appContext)
        }.getOrDefault(false)

        // 侧载限制只在 Android 13+ 存在（API 33）
        val restricted = runCatching {
            isRestrictedBySideload(appContext, enabledInSettings)
        }.getOrDefault(false)

        _state.value = _state.value.copy(
            serviceEnabledInSettings = enabledInSettings,
            restrictedBySideload = restricted,
        )
    }

    /** 仅供测试与调试重置 */
    internal fun resetForTest() {
        _state.value = AccessibilityState()
    }

    /**
     * 解析 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
     * 判断本服务是否在其中。
     *
     * 该设置是一个以 `:` 分隔的 `包名/类名` 列表，
     * 分隔符历史上有 `:` 与 `:` 混用的情况，因此两种都切。
     */
    private fun isServiceEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context, NoAdAccessibilityService::class.java)
        val flat = "${expected.packageName}/${expected.className}"

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        if (enabledServices.isBlank()) return false

        return enabledServices
            .split(':')
            .any { entry -> entry.equals(flat, ignoreCase = true) }
    }

    /**
     * 判断是否被 Android 13+ 的「受限设置」挡住。
     *
     * ## 判定逻辑
     *
     * 受限设置的表现为：用户在无障碍设置里**看不到开关**，或看到但无法开启。
     * 系统没有提供公开 API 直接查询该状态，因此采用间接判定：
     *
     * - Android 13 以下：不存在此限制，恒为 false
     * - Android 13+ 且**尚未启用**：无法区分"用户没去开"与"被限制"，
     *   因此**不武断标记为受限**，只在确有迹象时才提示
     *
     * 这里的策略是**保守**：宁可少提示，也不要在用户已经开启服务后
     * 还显示"可能被限制"的错误告警 —— 那会直接损害 UI 可信度。
     */
    private fun isRestrictedBySideload(context: Context, enabledInSettings: Boolean): Boolean {
        if (android.os.Build.VERSION.SDK_INT < ANDROID_13) return false

        // 已启用则限制不成立
        if (enabledInSettings) return false

        // 未启用时，仅在应用确为侧载安装的情况下才提示
        return isSideloaded(context)
    }

    /**
     * 判断应用是否为侧载安装（非商店来源）。
     *
     * 通过 `PackageManager.getInstallSourceInfo` 检查安装来源包名：
     * 商店安装的来源通常为 `com.android.vending` 等，
     * 侧载安装来源为空或为文件管理器。
     */
    private fun isSideloaded(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName)
        } else {
            @Suppress("DEPRECATION")
            null
        }
        val installer = info?.installingPackageName
        installer.isNullOrBlank()
    }.getOrDefault(false)

    private const val ANDROID_13 = 33
}
