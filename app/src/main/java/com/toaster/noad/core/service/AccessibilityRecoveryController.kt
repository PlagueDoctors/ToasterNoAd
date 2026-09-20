package com.toaster.noad.core.service

import android.content.ComponentName
import android.content.Context
import com.toaster.noad.core.service.shizuku.AccessibilityAuthorizationRestorer
import com.toaster.noad.core.service.shizuku.AccessibilityRestoreOps
import com.toaster.noad.core.service.shizuku.ShizukuAvailabilityHolder
import com.toaster.noad.core.service.shizuku.ShizukuState

/**
 * 无障碍授权恢复的 feature 层门面（R12，R13 升级为双通道；
 * 与 [SideloadRestrictionController] 同一模式的独立门面 ——
 * 授权恢复与受限设置解除是两个不同的关注点，不共用一个名字撒谎的门面）。
 *
 * ## 什么时候用得上
 *
 * 激进 ROM 的「一键清理 / 上划清除」按 force-stop 语义处理应用，
 * 系统随之撤销无障碍授权记录 —— 用户被迫去系统设置重新开启。
 * 授权记录的恢复需要写 `Settings.Secure` 的特权，本门面把它
 * 翻译成一个用户显式点击的动作。
 *
 * ## 通道优先级（R13）
 *
 * 1. **WRITE_SECURE_SETTINGS**（[SecureSettingsAccessibilityRestorer]）：
 *    用户用 adb 授予一次后终身有效，无需任何常驻组件 —— 有此权限时**优先走它**，
 *    与 Shizuku 是否可用无关；
 * 2. **Shizuku**（[AccessibilityAuthorizationRestorer]，R12）：高级授权不存在时回退；
 * 3. 都没有 → 返回通道态终态，UI 引导手动开启。
 *
 * 通道探测（[hasSecureWritePermission]）是注入的函数而非本类内部
 * `checkSelfPermission`：权限判定依赖 Android `Context`，注入后
 * 「通道优先级」这一最值得测的决策可以在 JVM 上验证。
 *
 * ## 为什么是「点击触发」而不是后台静默恢复
 *
 * 写全局 `Settings.Secure` 属于系统性写操作，R8 评估时明确否决了
 * 「周期性自动写授权记录」的保活用法。R12 修订了这一结论的
 * **适用边界**：用户显式点击的、read-merge-write 保护他人条目的、
 * 一次性恢复动作不在此列 —— R13 的第二条通道遵循同一边界；
 * 「后台静默周期写」依然禁止。
 */
class AccessibilityRecoveryController(
    context: Context,
    private val shizukuHolder: ShizukuAvailabilityHolder,
    private val restorer: AccessibilityAuthorizationRestorer,
    private val secureSettingsRestorer: SecureSettingsAccessibilityRestorer,
    private val hasSecureWritePermission: () -> Boolean,
) {

    /** 恢复动作的终态（feature 层只做文案映射） */
    enum class RestoreOutcome {
        /** 之前不在授权列表，本次写入并回读验证通过 */
        RESTORED,

        /** 授权列表原本就有自己（可能只是绑定问题），本次未改条目 */
        ALREADY_PRESENT,

        /** 两条特权通道都不可用，且 Shizuku 已装未授权：引导授权 */
        NEEDS_PERMISSION,

        /** 高级授权不存在，且 Shizuku 未运行 / 未就绪 */
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
     * 是否具备「无 Shizuku 恢复」能力（R13，供 UI 决定按钮展示与文案）。
     *
     * 权限是 install-time 的：运行期几乎不变（撤销需 adb/电脑），
     * 但仍随每次调用实时查询 —— 不缓存，避免「用户撤销了授权
     * 而界面仍显示可用」的假状态。
     */
    fun canRestoreWithoutShizuku(): Boolean = hasSecureWritePermission()

    /**
     * 尝试恢复无障碍授权记录。
     *
     * 通道按 [canRestoreWithoutShizuku] 优先级选择；Shizuku 通道的
     * 预检在先（未运行/未授权时直接给出对应终态），不让用户对着
     * 「失败」文案去猜是 Shizuku 的问题还是命令的问题。
     */
    suspend fun restoreAuthorization(): RestoreOutcome {
        if (hasSecureWritePermission()) {
            return secureSettingsRestorer.restore(flatComponent).toExternal()
        }
        return when (shizukuHolder.state.value) {
            ShizukuState.NeedsPermission -> RestoreOutcome.NEEDS_PERMISSION
            ShizukuState.Unavailable -> RestoreOutcome.SHIZUKU_UNAVAILABLE
            else -> restorer.restore(flatComponent).toExternal()
        }
    }

    /**
     * 仅走 WRITE_SECURE_SETTINGS 通道执行恢复（R14，自动路径专用）。
     *
     * 与 [restoreAuthorization] 的区别：**绝不回退 Shizuku** ——
     * 静默自动恢复只允许发生在用户以 adb 授权明确表达过
     * 「接受无感恢复」的通道上（详见 [AccessibilityAutoRestorer]）。
     * 调用方应先用 [canRestoreWithoutShizuku] 把门，本方法在
     * 通道不在位时返回 [RestoreOutcome.FAILED]（调用方静默忽略）。
     */
    suspend fun restoreViaSecureChannel(): RestoreOutcome {
        if (!hasSecureWritePermission()) return RestoreOutcome.FAILED
        return secureSettingsRestorer.restore(flatComponent).toExternal()
    }

    /** 决策层终态 → 门面终态（两条通道共用同一映射） */
    private fun AccessibilityRestoreOps.RestoreOutcome.toExternal(): RestoreOutcome =
        when (this) {
            is AccessibilityRestoreOps.RestoreOutcome.Restored -> RestoreOutcome.RESTORED
            is AccessibilityRestoreOps.RestoreOutcome.AlreadyPresent ->
                RestoreOutcome.ALREADY_PRESENT
            is AccessibilityRestoreOps.RestoreOutcome.Failed -> RestoreOutcome.FAILED
        }
}
