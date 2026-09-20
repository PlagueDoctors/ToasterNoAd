package com.toaster.noad.core.service

/**
 * 无障碍授权的**自动恢复**入口（R14）。
 *
 * ## 它解决什么
 *
 * R12/R13 把恢复做成了用户显式点击的按钮；但一键清理后用户还得
 * 「打开应用 → 找到按钮 → 点它」，多了一步本可以省掉的操作。
 * 本类把恢复降级为**无感动作**：应用回到前台自检时，发现授权
 * 确实丢失且通道具备，就静默恢复，不再要求用户点任何按钮。
 *
 * ## 为什么自动路径只走 WRITE_SECURE_SETTINGS，绝不自动走 Shizuku
 *
 * 1. **语义**：adb 高级授权是用户主动执行过 `pm grant` 的一次性配置，
 *    它的存在本身就是「我接受无感恢复」的明确表达；Shizuku 通道
 *    依赖 Shizuku 进程存活，且 force-stop 后 binder 已断，
 *    自动静默调用一个可能随时失败的 shell 通道不是用户预期。
 * 2. **零回归**：未做 adb 授权的普通用户，自动路径直接短路返回，
 *    行为与 R13 完全一致。
 * 3. **节流记账**：[AutoRestorePolicy] 只对真实发起的恢复记账，
 *    通道不可用被短路不算尝试（没有写任何东西，不构成拉锯一方）。
 *
 * ## 用户意图的边界（为什么它不主动找活干）
 *
 * 本类只提供「问一次就试一次」的 [maybeRestore]，**不自带任何
 * 触发时机**。触发由调用方（HomeViewModel 的启动/回前台自检）决定 ——
 * 用户打开应用 = 明确想用 = 恢复符合意图。反过来，用户在系统设置里
 * 手动关闭无障碍（不想用了）时不会打开本应用，自动恢复就永远不会
 * 和用户的「关闭」动作对抗。这个意图锚比任何启发式判定都可靠。
 */
class AccessibilityAutoRestorer(
    private val policy: AutoRestorePolicy,
    private val hasSecureWritePermission: () -> Boolean,
    private val secureRestore: suspend () -> AccessibilityRecoveryController.RestoreOutcome,
) {

    /**
     * 若通道在位且策略允许，发起一次静默恢复；否则返回 null（什么都没做）。
     *
     * 返回 null 的三种可能（调用方无需区分——自动路径本就静默）：
     * - WRITE_SECURE_SETTINGS 不在位（未做 adb 高级授权）
     * - [AutoRestorePolicy] 节流拒绝（间隔内 / 超过进程内上限）
     * - （通道在位时）恢复已执行，但结果由返回值携带
     */
    suspend fun maybeRestore(): AccessibilityRecoveryController.RestoreOutcome? {
        if (!hasSecureWritePermission()) return null
        if (!policy.shouldAttempt()) return null
        policy.recordAttempt()
        return secureRestore()
    }
}
