package com.toaster.noad.core.service.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku 绑定层状态（F1，方案 §6.3.3）。
 *
 * 独立声明在包级别而非嵌套进 [ShizukuAvailabilityHolder]：
 * 门面层（`SideloadRestrictionController`）需要 import 它做模式匹配，
 * 顶层声明让依赖方向保持「消费状态」而非「消费持有者」。
 *
 * 状态迁移严格单向推进（Unavailable → NeedsPermission → Probing →
 * Ready），任何环节失败/异常都直接落回 [Unavailable]，
 * 不做中间态记忆 —— UI 文案与状态一一对应，绝不出现第三种解释。
 */
sealed interface ShizukuState {

    /** 未安装 / 未运行 / 服务器已死亡 / 探测异常 */
    data object Unavailable : ShizukuState

    /** binder 存活但用户尚未授权 */
    data object NeedsPermission : ShizukuState

    /** 已授权，能力探测进行中（短暂过渡态） */
    data object Probing : ShizukuState

    /**
     * 探测完成，携带已验证的能力项。
     *
     * @property canSetAppOps 仅 F2 所需一项：用只读 `cmd appops get`
     *   实打验证过 shell 通道 + op 名识别。其余能力项随 F3-F6 落地
     *   逐项扩展 —— 不做「常量 false 冒充已探测」。
     */
    data class Ready(val canSetAppOps: Boolean) : ShizukuState
}

/**
 * Shizuku 绑定层状态机（R11 / 阶段 F1，方案 §6.3.3 + §6.3.4 + §11.2）。
 *
 * ## 状态语义（与 UI 文案一一对应）
 *
 * | 状态 | 含义 | UI 行为 |
 * |---|---|---|
 * | [ShizukuState.Unavailable] | 未安装 / 未运行 / 服务器已死亡 | 保持手动图文引导 |
 * | [ShizukuState.NeedsPermission] | binder 存活但用户未授权 | 显示「授权 Shizuku」 |
 * | [ShizukuState.Probing] | 已授权，正在探测能力（短暂态） | 按钮置为进行中 |
 * | [ShizukuState.Ready] | 探测完成，携带逐项能力 | ready 项的增强入口可用 |
 *
 * ## 能力探测为什么只有 canSetAppOps 一项
 *
 * 方案 §6.3.4 的 `Capabilities` 共六项，但**只该探测已实现的能力**：
 * 把没实现的能力写成常量 false，与「静态假设有 Shizuku 就有一切」
 * 是同一种脆弱 —— 探测结果必须来自真实的运行时验证。
 * 其余能力项（Chain-3 / SecureSettings / 包管理…）随 F3-F6 落地逐项补入。
 *
 * `canSetAppOps` 的探测 = 用只读的 `cmd appops get` 实际打一条命令：
 * 通道连通性 + 当前 ROM 是否认识该 op 名，一次验证两件事。
 *
 * ## 事件驱动模型
 *
 * 与 `AccessibilityStateHolder` 一致：本类**不自行订阅** Shizuku 回调，
 * 由 NoAdApplication 在 onCreate 注册三个监听并转发（应用持有作用域，
 * 生命周期归属清晰）：
 * sticky binder-received / binder-dead / 权限请求结果。
 */
class ShizukuAvailabilityHolder(
    context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val shell: ShizukuShellClient,
) {

    private val appContext = context.applicationContext

    /** 串行化刷新：sticky 回调 / 权限回调 / 手动刷新可能并发触发 */
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.Unavailable)

    /** 对外只读状态流 */
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    /** Shizuku binder 到达（sticky：注册时若已存活会立即回调） */
    fun onBinderReceived() {
        scope.launch { refresh() }
    }

    /** Shizuku 服务器死亡：传输层归位 + 状态回退，UI 自动落回手动引导 */
    fun onBinderDead() {
        shell.reset()
        _state.value = ShizukuState.Unavailable
    }

    /** 权限请求结果到达（不读结果值，由 refresh 重新核对真实权限） */
    fun onPermissionResult() {
        scope.launch { refresh() }
    }

    /** 发起 Shizuku 权限请求；仅在 [ShizukuState.NeedsPermission] 态有意义 */
    fun requestPermission() {
        if (_state.value != ShizukuState.NeedsPermission) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    /**
     * 完整刷新：binder 存活检查 → 授权检查 → 连接用户服务 → 能力探测。
     *
     * 全程 IO 线程；任何一步异常都落到 [ShizukuState.Unavailable]
     * （宁可显示「未就绪」再触发一次手动引导，也不给一个假的可用品）。
     */
    private suspend fun refresh() = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!Shizuku.pingBinder()) {
                    shell.reset()
                    _state.value = ShizukuState.Unavailable
                    return@withContext
                }

                // 服务器版本异常（0 = 未收到 / 过旧）视同未就绪。
                // getVersion() 即 Shizuku 服务端版本（API 13.x 公开方法）
                val serverVersion = runCatching { Shizuku.getVersion() }.getOrDefault(0)
                if (serverVersion <= 0) {
                    _state.value = ShizukuState.Unavailable
                    return@withContext
                }

                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    _state.value = ShizukuState.NeedsPermission
                    return@withContext
                }

                _state.value = ShizukuState.Probing
                _state.value = ShizukuState.Ready(
                    canSetAppOps = shell.ensureConnected() && probeShellChannel(),
                )
            }.onFailure {
                _state.value = ShizukuState.Unavailable
            }
        }
    }

    /**
     * 探测 shell 通道是否真的可用：
     * 用只读的 get 命令实打一条，若 ROM 的 appops 不认识该 op 名，
     * 输出会带 "Bad operation" / "Unknown operation" 标记 —— 此时
     * 解除流程注定失败，直接报告不可用（§6.3.4「逐项、可失败」）。
     */
    private suspend fun probeShellChannel(): Boolean {
        val output = shell.exec(
            RestrictedSettingsOps.getCommand(appContext.packageName, uidVariant = false),
        ) ?: return false

        return !output.contains(BAD_OPERATION_MARK_1, ignoreCase = true) &&
            !output.contains(BAD_OPERATION_MARK_2, ignoreCase = true)
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 111
        const val BAD_OPERATION_MARK_1 = "bad operation"
        const val BAD_OPERATION_MARK_2 = "unknown operation"
    }
}
