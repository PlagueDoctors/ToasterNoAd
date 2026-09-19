package com.toaster.noad.core.service

import android.content.ComponentName
import android.content.Context
import com.toaster.noad.core.service.shizuku.AccessibilityAuthorizationRestorer
import com.toaster.noad.core.service.shizuku.AccessibilityRestoreOps
import com.toaster.noad.core.service.shizuku.ShizukuAvailabilityHolder
import com.toaster.noad.core.service.shizuku.ShizukuState

/**
 * 无障碍授权恢复的 feature 层门面（R12，与 [SideloadRestrictionController]
 * 同一模式的独立门面 —— 授权恢复与受限设置解除是两个不同的关注点，
 * 不共用一个名字撒谎的门面）。
 *
 * ## 什么时候用得上
 *
 * 激进 ROM 的「一键清理 / 上划清除」按 force-stop 语义处理应用，
 * 系统随之撤销无障碍授权记录 —— 用户被迫去系统设置重新开启。
 * 授权记录的恢复需要 shell 权限（写 `Settings.Secure`），本门面把
 * R11 的 Shizuku 通道翻译成一个用户显式点击的动作。
 *
 * ## 为什么是「点击触发」而不是后台静默恢复
 *
 * 写全局 `Settings.Secure` 属于系统性写操作，R8 评估时明确否决了
 * 「周期性自动写授权记录」的保活用法。本次交付改写这一结论的
 * **适用边界**：用户显式点击的、read-merge-write 保护他人条目的、
 * 一次性恢复动作不在此列 —— 但「后台静默周期写」依然禁止。
 */
class AccessibilityRecoveryController(
    context: Context,
    private val shizukuHolder: ShizukuAvailabilityHolder,
    private val restorer: AccessibilityAuthorizationRestorer,
) {

    /** 恢复动作的终态（feature 层只做文案映射） */
    enum class RestoreOutcome {
        /** 之前不在授权列表，本次写入并回读验证通过 */
        RESTORED,

        /** 授权列表原本就有自己（可能只是绑定问题），本次未改条目 */
        ALREADY_PRESENT,

        /** Shizuku 已装未授权：引导授权 */
        NEEDS_PERMISSION,

        /** Shizuku 未运行 / 未就绪 */
        SHIZUKU_UNAVAILABLE,

        /** 序列执行完仍未验证到条目 */
        FAILED,
    }

    /** 本应用无障碍服务的组件扁平串（与 AccessibilityStateHolder 的判定口径一致） */
    private val flatComponent: String

    init {
        val component = ComponentName(context, NoAdAccessibilityService::class.java)
        flatComponent = "${component.packageName}/${component.className}"
    }

    /**
     * 尝试恢复无障碍授权记录。
     *
     * 通道预检在先：未运行/未授权时直接给出对应终态，
     * 不让用户对着「失败」文案去猜是 Shizuku 的问题还是命令的问题。
     */
    suspend fun restoreAuthorization(): RestoreOutcome = when (shizukuHolder.state.value) {
        ShizukuState.NeedsPermission -> RestoreOutcome.NEEDS_PERMISSION
        ShizukuState.Unavailable -> RestoreOutcome.SHIZUKU_UNAVAILABLE
        else -> when (val outcome = restorer.restore(flatComponent)) {
            is AccessibilityRestoreOps.RestoreOutcome.Restored -> RestoreOutcome.RESTORED
            is AccessibilityRestoreOps.RestoreOutcome.AlreadyPresent ->
                RestoreOutcome.ALREADY_PRESENT
            is AccessibilityRestoreOps.RestoreOutcome.Failed -> RestoreOutcome.FAILED
        }
    }
}
