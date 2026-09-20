package com.toaster.noad.core.service

import com.toaster.noad.core.service.shizuku.AppFirewallOps

/**
 * 应用级断网门面（方案 §6.1 Chain-3，F3）。
 *
 * ## 价值定位
 *
 * 这是 S4 中**唯一能同时满足三条**的能力：
 * 不建立 TUN、不占用系统 VPN、可与用户自己的 VPN 共存。
 * 也是让位降级链路（§6.9）的落点：「让位 = 降级为应用级断网」。
 *
 * ## 语义边界（必须诚实）
 *
 * Chain-3 是**UID 级**防火墙：只能表达「这个 App 的联网我全不要」，
 * **不能**表达「这个 App 里的广告域名我不要」。后者是 S2/S3 的职责。
 *
 * ## 执行模型（沿用 R12/R13 确立的模式）
 *
 * 命令序列由 [AppFirewallOps] 唯一持有 → 本类只负责
 * 「检查通道 → 下发命令 → **回读验证** → 判定终态」。
 * 回读验证不是可选项：命令成功不等于设置生效，
 * 把「命令没报错」当作成功是这类操作最常见的假阳性来源。
 */
class AppFirewallController(
    private val exec: suspend (List<String>) -> String?,
    private val isReady: () -> Boolean,
    private val selfPackageName: String,
) {

    /** 一次断网/恢复操作的终态 */
    sealed interface BlockOutcome {
        /** 命令执行且回读确认生效 */
        data object Applied : BlockOutcome

        /** 命令返回成功但回读不符（部分 ROM 会静默忽略） */
        data object VerifyMismatch : BlockOutcome

        /** 通道未就绪或命令不支持（不产生任何设置变更） */
        data object Unsupported : BlockOutcome
    }

    /**
     * 设置某应用是否断网。
     *
     * @param blocked true = 断网，false = 恢复联网
     */
    suspend fun setBlocked(packageName: String, blocked: Boolean): BlockOutcome {
        if (!isReady()) return BlockOutcome.Unsupported
        return runCatching {
            // 前置：确保 Chain-3 链已启用（幂等；无规则时不产生任何影响）
            val enableOut = exec(AppFirewallOps.enableChain3Command())
            if (AppFirewallOps.isFailureOutput(enableOut)) return@runCatching BlockOutcome.Unsupported

            val setOut = exec(AppFirewallOps.setBlockedCommand(packageName, blocked))
                ?: return@runCatching BlockOutcome.Unsupported
            if (AppFirewallOps.isFailureOutput(setOut)) return@runCatching BlockOutcome.Unsupported

            when (val query = AppFirewallOps.parseQuery(exec(AppFirewallOps.queryCommand(packageName)))) {
                is AppFirewallOps.QueryResult.Resolved ->
                    if (query.blocked == blocked) BlockOutcome.Applied else BlockOutcome.VerifyMismatch

                AppFirewallOps.QueryResult.Unsupported -> BlockOutcome.Unsupported
                AppFirewallOps.QueryResult.Unknown -> BlockOutcome.VerifyMismatch
            }
        }.getOrDefault(BlockOutcome.Unsupported)
    }

    /** 查询某应用当前是否已被断网；不可判定返回 null */
    suspend fun isBlocked(packageName: String): Boolean? = runCatching {
        when (val query = AppFirewallOps.parseQuery(exec(AppFirewallOps.queryCommand(packageName)))) {
            is AppFirewallOps.QueryResult.Resolved -> query.blocked
            else -> null
        }
    }.getOrNull()

    /**
     * 批量断网（让位降级链路用）。
     *
     * @return 实际生效（[BlockOutcome.Applied]）的应用数
     */
    suspend fun blockAll(packages: List<String>): Int {
        var applied = 0
        for (pkg in packages.distinct()) {
            if (setBlocked(pkg, blocked = true) == BlockOutcome.Applied) applied++
        }
        return applied
    }

    /** 批量恢复联网（用户关闭 S4 / 卸载前清理） */
    suspend fun releaseAll(packages: List<String>): Int {
        var applied = 0
        for (pkg in packages.distinct()) {
            if (setBlocked(pkg, blocked = false) == BlockOutcome.Applied) applied++
        }
        return applied
    }

    /** 探测 Chain-3 是否可用（供能力探测与仲裁降级判定使用） */
    suspend fun probeSupported(): Boolean = runCatching {
        isReady() && AppFirewallOps.probeSupported(
            exec(AppFirewallOps.queryCommand(selfPackageName)),
        )
    }.getOrDefault(false)
}
