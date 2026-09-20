package com.toaster.noad.core.service

import com.toaster.noad.core.service.shizuku.AppFirewallOps
import com.toaster.noad.core.service.shizuku.PackageOps

/**
 * 包 / 组件停用门面（F5，方案 §6.6）。
 *
 * ## 定位：S1 的强化选项（根治开屏，而非点掉开屏）
 *
 * S1 无障碍是「等广告出现 → 点掉」；本能力是「让承载广告的组件
 * 根本不会出现」。两者互补：规则页对高频广告组件由用户逐个确认后启用。
 *
 * ## 硬约束（安全边界，代码层面强制）
 *
 * - **禁止对系统应用操作**：系统应用停用可能导致系统功能异常；
 * - 每次操作都回读验证（命令成功 ≠ 生效）；
 * - 恢复路径必须存在且可用（[enableApp] / [enableComponent]）。
 */
class PackageController(
    private val exec: suspend (List<String>) -> String?,
    private val isReady: () -> Boolean,
) {

    sealed interface Outcome {
        data object Applied : Outcome
        data object VerifyMismatch : Outcome
        data object ChannelUnavailable : Outcome

        /** 目标是系统应用 —— 明确拒绝（不是失败，是策略） */
        data object SystemAppForbidden : Outcome
    }

    /**
     * 停用整个应用（等价「冻结」）。
     *
     * @param isSystemApp 由调用方从 PackageManager 判定并传入
     */
    suspend fun disableApp(packageName: String, isSystemApp: Boolean): Outcome {
        if (!PackageOps.isOperationAllowed(isSystemApp)) return Outcome.SystemAppForbidden
        if (!isReady()) return Outcome.ChannelUnavailable

        val output = exec(PackageOps.disableAppCommand(packageName)) ?: return Outcome.ChannelUnavailable
        if (AppFirewallOps.isFailureOutput(output)) return Outcome.ChannelUnavailable

        val disabled = disabledPackages() ?: return Outcome.VerifyMismatch
        return if (packageName in disabled) Outcome.Applied else Outcome.VerifyMismatch
    }

    /** 恢复被停用的应用 */
    suspend fun enableApp(packageName: String): Outcome {
        if (!isReady()) return Outcome.ChannelUnavailable

        val output = exec(PackageOps.enableAppCommand(packageName)) ?: return Outcome.ChannelUnavailable
        if (AppFirewallOps.isFailureOutput(output)) return Outcome.ChannelUnavailable

        val disabled = disabledPackages() ?: return Outcome.VerifyMismatch
        return if (packageName !in disabled) Outcome.Applied else Outcome.VerifyMismatch
    }

    /**
     * 停用单个组件（广告 Activity / Service）。
     *
     * 回读依据：`dumpsys package` 的 `enabledComponents:` 段出现该组件
     * —— 该段只列**被显式改动过**的组件，因此「出现」即代表状态已被我们改写。
     */
    suspend fun disableComponent(
        packageName: String,
        component: String,
        isSystemApp: Boolean,
    ): Outcome {
        if (!PackageOps.isOperationAllowed(isSystemApp)) return Outcome.SystemAppForbidden
        if (!isReady()) return Outcome.ChannelUnavailable

        val output = exec(PackageOps.disableComponentCommand(packageName, component))
            ?: return Outcome.ChannelUnavailable
        if (AppFirewallOps.isFailureOutput(output)) return Outcome.ChannelUnavailable

        val dump = exec(PackageOps.dumpPackageCommand(packageName)) ?: return Outcome.VerifyMismatch
        return if (PackageOps.parseComponentMentioned(dump, packageName, component)) {
            Outcome.Applied
        } else {
            Outcome.VerifyMismatch
        }
    }

    /** 恢复组件 */
    suspend fun enableComponent(packageName: String, component: String): Outcome {
        if (!isReady()) return Outcome.ChannelUnavailable

        val output = exec(PackageOps.enableComponentCommand(packageName, component))
            ?: return Outcome.ChannelUnavailable
        return if (AppFirewallOps.isFailureOutput(output)) {
            Outcome.ChannelUnavailable
        } else {
            // 恢复不回读「段中消失」：部分 ROM 会保留历史条目，
            // 以命令成功为准（失败会体现为异常文本）
            Outcome.Applied
        }
    }

    /** 当前被禁用的包集合（供 UI 展示与恢复校验）；通道不可用返回 null */
    suspend fun disabledPackages(): Set<String>? = runCatching {
        val output = exec(PackageOps.listDisabledPackagesCommand()) ?: return@runCatching null
        if (AppFirewallOps.isFailureOutput(output)) return@runCatching null
        PackageOps.parseDisabledPackages(output)
    }.getOrNull()
}
