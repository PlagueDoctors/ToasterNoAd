package com.toaster.noad.core.service

import com.toaster.noad.core.service.shizuku.AppFirewallOps
import com.toaster.noad.core.service.shizuku.AppOpsOps

/**
 * AppOps 精细化权限控制门面（F6，方案 §6.7）。
 *
 * ## 语义诚实（必须原样传递到 UI）
 *
 * AppOps 的「拒绝」**不是**系统权限拒绝，而是**返回空数据**：
 * App 仍然认为自己拿到了权限，只是拿到空白值。
 * - 好处：不会触发「不给权限就不让用」；
 * - 限制：对强校验场景无效。
 *
 * 本类只能**拒绝**，不能帮助 App 获得权限（Shizuku ≈ ADB shell 子集，
 * 无法授予 dangerous 级权限）——方向是单向的，UI 不得暗示相反。
 *
 * ## 实现要点
 *
 * - 全部使用**字符串操作名**（`RUN_IN_BACKGROUND` 等），
 *   绝不硬编码数值 opCode（版本漂移会静默改错权限）；
 * - 写入后回读该 Op 的模式确认，`No operations.` 视为「尚未设置」。
 */
class AppOpsController(
    private val exec: suspend (List<String>) -> String?,
    private val isReady: () -> Boolean,
) {

    sealed interface Outcome {
        data object Applied : Outcome
        data object VerifyMismatch : Outcome
        data object ChannelUnavailable : Outcome
    }

    /** 设置某应用某 Op 为「拒绝/允许」并回读确认 */
    suspend fun setDenied(packageName: String, op: AppOpsOps.Op, denied: Boolean): Outcome {
        if (!isReady()) return Outcome.ChannelUnavailable

        val output = exec(AppOpsOps.setModeCommand(packageName, op, denied))
            ?: return Outcome.ChannelUnavailable
        if (AppFirewallOps.isFailureOutput(output)) return Outcome.ChannelUnavailable

        val readBack = exec(AppOpsOps.getModeCommand(packageName, op)) ?: return Outcome.VerifyMismatch
        val resolved = AppOpsOps.parseMode(readBack, op)
        return when {
            resolved is AppOpsOps.ModeResult.Resolved && resolved.denied == denied -> Outcome.Applied
            else -> Outcome.VerifyMismatch
        }
    }

    /** 查询某 Op 是否已被拒绝；不可判定返回 null */
    suspend fun isDenied(packageName: String, op: AppOpsOps.Op): Boolean? = runCatching {
        val readBack = exec(AppOpsOps.getModeCommand(packageName, op)) ?: return@runCatching null
        (AppOpsOps.parseMode(readBack, op) as? AppOpsOps.ModeResult.Resolved)?.denied
    }.getOrNull()

    /**
     * 批量拒绝一组 Op（「一键削弱广告 SDK」场景）。
     *
     * @return 确认生效的数量
     */
    suspend fun denyAll(packageName: String, ops: List<AppOpsOps.Op>): Int {
        var applied = 0
        for (op in ops) {
            if (setDenied(packageName, op, denied = true) == Outcome.Applied) applied++
        }
        return applied
    }

    /** 批量恢复某一组 Op 为允许 */
    suspend fun allowAll(packageName: String, ops: List<AppOpsOps.Op>): Int {
        var applied = 0
        for (op in ops) {
            if (setDenied(packageName, op, denied = false) == Outcome.Applied) applied++
        }
        return applied
    }

    /** 广告 SDK 相关的默认削弱组合（UI 一键操作使用） */
    fun defaultAdHardeningOps(): List<AppOpsOps.Op> = listOf(
        AppOpsOps.Op.RUN_IN_BACKGROUND,
        AppOpsOps.Op.SYSTEM_ALERT_WINDOW,
        AppOpsOps.Op.GET_DEVICE_ID,
        AppOpsOps.Op.REQUEST_INSTALL_PACKAGES,
    )
}
