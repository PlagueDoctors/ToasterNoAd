package com.toaster.noad.core.service.shizuku

/**
 * 无障碍授权恢复执行器（R12）。
 *
 * 只是把 [AccessibilityRestoreOps.restore] 的「命令怎么执行」绑定到
 * R11 的 shell 通道上：序列与决策全部在纯层（有完整 JVM 测试），
 * 本类没有可独立测的逻辑，也不做任何解析。
 *
 * 可见性注意：`SideloadRestrictionController` / 容器公开签名会引用本类，
 * **不能标 `internal`**（public 签名暴露 internal 类型直接编译失败，
 * R11 已踩过）。分层约束由「feature 只接触 core/service 门面」保证。
 */
class AccessibilityAuthorizationRestorer(private val shell: ShizukuShellClient) {

    /**
     * 执行恢复序列。
     *
     * @param flat 本应用无障碍服务的组件扁平串（`包名/类名`）
     */
    suspend fun restore(flat: String): AccessibilityRestoreOps.RestoreOutcome =
        AccessibilityRestoreOps.restore(shell::exec, flat)
}
