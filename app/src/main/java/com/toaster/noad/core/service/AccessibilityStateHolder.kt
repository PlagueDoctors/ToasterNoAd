package com.toaster.noad.core.service

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.toaster.noad.core.model.AccessibilityState
import com.toaster.noad.core.model.DisconnectReason
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
 *
 * ## ⚠️ 授权 ≠ 运行：为什么不需要"保活"
 *
 * 无障碍是**授权模型**，不是"启动一个服务就一直跑着"。
 * 系统在息屏、切换应用、内存紧张时都会解绑服务实例，而设置里的授权记录
 * 依然保留 —— 这就是它随后能被自动重连的原因。
 *
 * 因此本 holder 的职责不是"防止断开"（做不到，也没有 API 能做到），
 * 而是**尽快、准确地发现断开，并把它如实告知 UI**，
 * 让系统或用户能及时把服务拉回来。所谓"常驻"是由**授权记录**保证的，
 * 不是由任何保活手段保证的。
 */
object AccessibilityStateHolder {

    /**
     * 单调时钟读数（毫秒）。
     *
     * ## 为什么要注入而不是直接调 `SystemClock.elapsedRealtime()`
     *
     * `android.os.SystemClock` 在 **JVM 单元测试里会抛异常**
     * （`Method elapsedRealtime in android.os.SystemClock not mocked`）——
     * AGP 提供的 `android.jar` 只是空壳，方法体全是 `throw`。
     *
     * 直接调用它会让整个状态机**无法测试**，而这里的状态机决定了
     * UI 对用户说"去设置"还是"等一等"，是最不能靠猜的部分。
     *
     * 因此改为可替换的函数引用：生产用 `SystemClock.elapsedRealtime()`，
     * 测试注入一个可控计数器。这也顺带让"已断开多久"的边界
     * （刚断开 / 几分钟 / 几小时）变得可验证。
     *
     * ## 为什么用单调时钟而非 `currentTimeMillis()`
     *
     * 单调时钟不受用户改系统时间、时区切换影响。这里只关心"过了多久"，
     * 不关心"是几点"，用墙上时钟会因时间被回拨而算出负数。
     */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    private val _state = MutableStateFlow(AccessibilityState())

    /** 对外只读状态流 */
    val state: StateFlow<AccessibilityState> = _state.asStateFlow()

    /** 由服务在 `onServiceConnected` 中调用 */
    fun onConnected() {
        // 连接成功时清空断开记录：留着旧的"已于 X 分钟前断开"会让用户
        // 以为现在还是坏的。断开时间只在真正断开期间有意义。
        _state.value = _state.value.copy(
            serviceRunning = true,
            lastDisconnectedAtMillis = null,
            disconnectReason = null,
        )
    }

    /**
     * 由服务在 `onUnbind` / `onDestroy` 中调用。
     *
     * 注意不清除 `serviceEnabledInSettings`：
     * 服务被解绑不代表用户在设置里关闭了它
     * （系统可能在内存紧张时解绑后重新连接）。
     * 这一点是 `isDisconnectedButAuthorized` 能成立、自愈逻辑能工作的前提。
     *
     * ## 首因优先（first-reason-wins）
     *
     * **一次断开只记一个原因，且以最先观察到的为准。**
     *
     * `onUnbind` 与 `onDestroy` 会因为同一次断开而**先后**触发，
     * 前者携带的信息更具体（`SYSTEM_UNBOUND` 表示系统解绑），
     * 后者的 `SERVICE_DESTROYED`（"服务被回收"）只是前者的清理动作，
     * 信息量更低。
     *
     * 因此这里不接受"用新原因覆盖旧原因" —— 那会让用户看到的原因
     * 比实际更模糊。要改已记录的原因，只有 [markUserDisabledInSettings]
     * 这一条明确知道自己在做什么的路径。
     *
     * **判定依据是"是否已有记录"，不是"参数是否为 null"。**
     * 早期实现写成 `reason ?: current.disconnectReason`，等价于
     * "有值就覆盖"，`onDestroy` 依然会盖掉 `onUnbind` 的原因 ——
     * 这个缺陷正是由 `givenAlreadyUnbound_whenOnDestroyCalled_thenDoesNotOverwriteReason`
     * 捕获的。
     *
     * @param reason 本次观察到的断开原因
     */
    fun onDisconnected(reason: DisconnectReason? = null) {
        val current = _state.value

        _state.value = current.copy(
            serviceRunning = false,
            // 只在尚未记录过断开时间时写入：
            // 重复写入会把「已断开多久」刷成「刚刚」，
            // 而这个时长正是用户判断"要不要手动干预"的依据。
            lastDisconnectedAtMillis = current.lastDisconnectedAtMillis
                ?: clock(),
            // 首因优先：已有记录就不动，无论本次传入什么
            disconnectReason = current.disconnectReason ?: reason,
        )
    }

    /**
     * 记录"已确认用户在系统设置里关闭了本服务"。
     *
     * 由 [refreshFromSystemSettings] 在发现授权记录消失时调用。
     * 这是唯一能确定"用户主动关的"的路径 ——
     * 系统不提供解绑原因 API，只能靠授权记录是否还在来反推。
     *
     * ## 这是 [onDisconnected] 的「首因优先」规则之外唯一的例外
     *
     * [onDisconnected] 不允许后来者覆盖已记录的原因，因为它只能拿到
     * "服务被解绑"这一层信息。而本方法拿到的是**用户意图**这个更强的信号 ——
     * 授权记录都消失了，说明是用户自己关的，比"系统解绑"更值得展示。
     *
     * 因此这里**刻意覆盖**已有原因。判断依据是
     * `serviceEnabledInSettings == false && serviceRunning == false`
     * （由调用方保证），而不是靠原因字段猜测。
     */
    private fun markUserDisabledInSettings() {
        val current = _state.value

        // 服务仍在运行时不动记录：那说明只是设置读取有缓存延迟，
        // 不该误报成"用户关闭"。这条判断同时保护了
        // onDisconnected 刚记录的原因。
        if (current.serviceRunning) return

        _state.value = current.copy(
            lastDisconnectedAtMillis = current.lastDisconnectedAtMillis ?: clock(),
            disconnectReason = DisconnectReason.USER_DISABLED_IN_SETTINGS,
        )
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
     * ## 自愈语义
     *
     * 这个方法不只是"查一下状态"，它同时承担**发现断开**的职责：
     * 每次调用都会把 `serviceEnabledInSettings` 与 `serviceRunning`
     * 对齐，从而让 [AccessibilityState.isDisconnectedButAuthorized]
     * 成为一个可信信号。息屏/解锁/回到前台时调用它，
     * 就能在用户还没察觉时把"服务已断开"这件事摆到界面上。
     *
     * @param context 任意 Context（内部取 applicationContext）
     */
    fun refreshFromSystemSettings(context: Context) {
        val appContext = context.applicationContext
        val enabledInSettings = runCatching {
            isServiceEnabledInSettings(appContext)
        }.getOrDefault(false)

        // 侧载限制只在 Android 13+ 存在（API 33）。
        // restrictedSettingCleared 是 F2 解除成功后的进程内记忆（见其 KDoc）：
        // 下面的保守判定只看「SDK + 是否已启用 + 是否侧载」，无法感知
        // appop 已被放行，需要这一层覆盖。
        val restricted = runCatching {
            isRestrictedBySideload(appContext, enabledInSettings)
        }.getOrDefault(false) && !restrictedSettingCleared

        _state.value = _state.value.copy(
            serviceEnabledInSettings = enabledInSettings,
            restrictedBySideload = restricted,
        )

        // 授权记录消失 = 用户主动在系统设置里关了它。
        //
        // 这是唯一能确定"是用户关的"的路径：系统不提供解绑原因 API，
        // 只能靠授权记录是否还在反推。服务仍在运行时不动记录 ——
        // 那说明只是设置读取有缓存延迟，不该误报成"用户关闭"。
        if (!enabledInSettings && !_state.value.serviceRunning) {
            markUserDisabledInSettings()
        }
    }

    /** 仅供测试与调试重置 */
    internal fun resetForTest() {
        _state.value = AccessibilityState()
        restrictedSettingCleared = false
        clock = { SystemClock.elapsedRealtime() }
    }

    /**
     * 测试专用：直接写入「设置中已授权」标志。
     *
     * ## 为什么需要这个后门
     *
     * 生产代码里该字段只能由 [refreshFromSystemSettings] 从
     * `Settings.Secure` 读出，而那需要 `ContentResolver` ——
     * 项目测试栈**无 Robolectric**，在 JVM 上拿不到。
     *
     * 没有它，`isDisconnectedButAuthorized` / `isNotAuthorized` 这两个
     * 决定 UI 文案与用户动作的派生属性就**永远无法被验证**，
     * 而它们恰恰是最容易写错、代价最大的部分
     * （写错会让用户为可自愈的临时断开反复跑设置）。
     *
     * 因此这里选择"暴露一个测试写入点、换来三态判定可测"。
     * **不覆盖**的是设置读取路径本身 —— 那属于 Android 平台行为，
     * 由用户实机验证，这一点已在测试类注释中如实标注。
     *
     * 标 `internal` 而非 `@VisibleForTesting`：项目目前没有引入
     * AndroidX annotations 的依赖，用 `internal` 已足够限制可见性
     * （测试源集与主源集同模块，可见）。
     */
    internal fun seedSettingsFlagForTest(enabled: Boolean) {
        _state.value = _state.value.copy(serviceEnabledInSettings = enabled)
    }

    /**
     * 测试专用：直接写入「受限设置」提示标志。
     *
     * 理由同 [seedSettingsFlagForTest]：生产路径只能从 `Settings.Secure`
     * 与安装来源推断，JVM（无 Robolectric）上拿不到；而「解除成功后
     * 撤下提示」是用户可见的核心行为，必须可测。
     */
    internal fun seedRestrictedFlagForTest(restricted: Boolean) {
        _state.value = _state.value.copy(restrictedBySideload = restricted)
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

    /**
     * F2「受限设置解除成功」的进程内记忆（R11，方案 §6.5）。
     *
     * ## 为什么不持久化
     *
     * appop `ACCESS_RESTRICTED_SETTINGS` 的放行状态由**系统**持有，
     * Shizuku 只是在本次安装上把它从 default 改成 allow。
     * 应用重启后无法在不依赖 Shizuku 的前提下可靠地重新读到该状态
     * （读它本身就需要 shell 权限），因此这里选择诚实的策略：
     * **重启后回到保守判定** —— 若限制仍然存在，UI 会重新出现提示卡，
     * 用户可再次一键解除；若确实已解除，保守判定在
     * 「已启用 / 未侧载」路径上本就不会误报。
     *
     * 进程内记忆已足够覆盖主流程：解除成功 → 直接打开系统设置 →
     * 授权服务 → 回到首页，全程不经过进程重启。
     */
    @Volatile private var restrictedSettingCleared = false

    /**
     * 记录「受限设置限制已通过 Shizuku 解除」。
     *
     * 由 [SideloadRestrictionController.resolve] 在 ALREADY_ALLOWED /
     * FIXED 两种结果下调用。置位后：
     *
     * - [refreshFromSystemSettings] 的保守判定不再覆盖真实结果
     *   （`&& !restrictedSettingCleared`）
     * - 若提示卡当前正在展示，立即撤下（UI 即时反馈）
     *
     * 撤显用 `copy(restrictedBySideload = false)` 而不是等下一次 refresh：
     * refresh 依赖 ContentResolver 跨进程查询，时机不可控；而用户刚点完
     * 按钮，超过一拍才消失就会显得"点了没反应"。
     */
    fun markRestrictedSettingCleared() {
        restrictedSettingCleared = true
        val current = _state.value
        if (current.restrictedBySideload) {
            _state.value = current.copy(restrictedBySideload = false)
        }
    }

    private const val ANDROID_13 = 33
}
