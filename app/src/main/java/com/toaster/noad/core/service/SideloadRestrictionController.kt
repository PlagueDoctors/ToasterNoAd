package com.toaster.noad.core.service

import android.content.Context
import com.toaster.noad.core.service.shizuku.RestrictedSettingsFixer
import com.toaster.noad.core.service.shizuku.RestrictedSettingsOps
import com.toaster.noad.core.service.shizuku.ShizukuAvailabilityHolder
import com.toaster.noad.core.service.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 侧载限制解除的 feature 层门面（R11 / 阶段 F2，方案 §6.5.3 + §3 分层约束）。
 *
 * ## 为什么存在这一层
 *
 * 方案 §3 要求 `core/shizuku` 不被 `feature/` 直接引用 ——
 * Shizuku 必须是可选能力而非 UI 层耦合。本门面把两个东西翻译成
 * feature 层可以安全消费的形态：
 *
 * 1. **状态**：`ShizukuState` 密封类 → 只含布尔量的 [ShizukuSupport]
 * 2. **动作**：fixer 的 `FixOutcome` → [ResolveOutcome] 枚举，
 *    并在成功时接管 `AccessibilityStateHolder` 的受限标志更新
 *
 * feature 层（HomeViewModel / HomeScreen）只接触本类，
 * 不感知 Shizuku API 的存在；未安装 Shizuku 的设备上，
 * support 恒为默认值，UI 自然退回手动图文引导（§13.1 可选增强）。
 */
class SideloadRestrictionController(
    context: Context,
    private val shizukuHolder: ShizukuAvailabilityHolder,
    private val fixer: RestrictedSettingsFixer,
    scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    /**
     * feature 层可安全消费的 Shizuku 支持状态。
     *
     * @property ready 通道就绪且 shell 探测通过：可以展示「自动解除」入口
     * @property needsPermission binder 存活但未授权：展示「授权」入口
     */
    data class ShizukuSupport(
        val ready: Boolean = false,
        val needsPermission: Boolean = false,
    )

    /** 解除动作的终态（feature 层只做文案映射，不做逻辑分支） */
    enum class ResolveOutcome {
        /** 回读即已解除，本次未做修改 */
        ALREADY_ALLOWED,

        /** 已成功解除（回读验证） */
        FIXED,

        /** Shizuku 已装未授权：引导授权 */
        NEEDS_PERMISSION,

        /** Shizuku 未运行 / 未就绪：保持手动引导 */
        SHIZUKU_UNAVAILABLE,

        /** 尝试了全部命令变体仍未解除：保持手动引导 */
        FAILED,
    }

    val support: StateFlow<ShizukuSupport> = shizukuHolder.state
        .map { value ->
            ShizukuSupport(
                ready = value is ShizukuState.Ready && value.canSetAppOps,
                needsPermission = value is ShizukuState.NeedsPermission,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ShizukuSupport(),
        )

    /** 发起 Shizuku 权限请求（结果经 Application 的监听回流刷新状态机） */
    fun grantPermission() {
        shizukuHolder.requestPermission()
    }

    /**
     * 尝试解除本应用的「受限设置」限制。
     *
     * 成功时同步把 `AccessibilityStateHolder` 的受限标志置为已解除 ——
     * 否则受限判定仍按「侧载 + 未启用」的保守逻辑继续提示。
     */
    suspend fun resolve(): ResolveOutcome {
        if (shizukuHolder.state.value is ShizukuState.NeedsPermission) {
            return ResolveOutcome.NEEDS_PERMISSION
        }

        return when (val outcome = fixer.fix(appContext.packageName)) {
            is RestrictedSettingsOps.FixOutcome.AlreadyAllowed -> {
                AccessibilityStateHolder.markRestrictedSettingCleared()
                ResolveOutcome.ALREADY_ALLOWED
            }

            is RestrictedSettingsOps.FixOutcome.Fixed -> {
                AccessibilityStateHolder.markRestrictedSettingCleared()
                ResolveOutcome.FIXED
            }

            is RestrictedSettingsOps.FixOutcome.NeedsShizuku -> ResolveOutcome.SHIZUKU_UNAVAILABLE

            is RestrictedSettingsOps.FixOutcome.Failed -> ResolveOutcome.FAILED
        }
    }
}
