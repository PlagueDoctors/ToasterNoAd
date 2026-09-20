package com.toaster.noad.core.service

import com.toaster.noad.core.service.shizuku.AppFirewallOps
import com.toaster.noad.core.service.shizuku.PrivateDnsOps

/**
 * Private DNS 改写门面（方案 §6.4，F4）。
 *
 * ## 定位：S2 的「懒人/兼容」替代方案
 *
 * | | S2 自建 VPN | Private DNS 改写 |
 * |---|---|---|
 * | 占用 VPN | ✅ | ❌ |
 * | 自定义规则 | ✅ NoAd 规则库 | ❌ 只能用服务商规则 |
 * | 加密 | 上游可 DoT | ✅ 强制 DoT |
 *
 * 两者都保留、由用户在 UI 选择：想用 AdGuard DNS / NextDNS 这类
 * 现成过滤服务、又不想开 VPN 权限的用户，这条路径零成本。
 *
 * ## 重要限制（必须传递到 UI）
 *
 * - **全局生效**：影响整机所有 App，不能按包区分；
 * - **用户可能在系统设置里改回来**：状态查询以供 UI 同步；
 * - **DoT 本身可被 App 自带 DoH 绕过**。
 *
 * ## 验证协议
 *
 * 写入后**回读 mode 与 specifier 两者**：部分 ROM 只接受其中一个字段，
 * 只回读 mode 会把「mode=hostname 但 specifier 为空」误判为成功。
 */
class PrivateDnsController(
    private val exec: suspend (List<String>) -> String?,
) {

    data class Status(
        val mode: PrivateDnsOps.DnsMode,
        val specifier: String?,
    )

    sealed interface Outcome {
        data object Applied : Outcome
        data object VerifyMismatch : Outcome
        data object ChannelUnavailable : Outcome
        data object InvalidHostname : Outcome
    }

    /** 读取当前状态；通道不可用返回 null */
    suspend fun status(): Status? = runCatching {
        val modeOut = exec(PrivateDnsOps.queryModeCommand()) ?: return@runCatching null
        if (AppFirewallOps.isFailureOutput(modeOut)) return@runCatching null

        val specOut = exec(PrivateDnsOps.querySpecifierCommand())
        Status(
            mode = PrivateDnsOps.parseMode(modeOut),
            specifier = PrivateDnsOps.parseSpecifier(specOut),
        )
    }.getOrNull()

    /**
     * 切换到「指定主机名」模式（DoT 严格模式）。
     *
     * 前置校验主机名：用户可能直接粘贴带 `https://` 或路径的 URL，
     * 未校验就写入会让 Private DNS 进入严格模式但无法解析 ——
     * 表现是整机断网，属于最严重的可避免事故。
     */
    suspend fun setHostname(hostname: String): Outcome {
        if (!PrivateDnsOps.isValidHostname(hostname)) return Outcome.InvalidHostname

        val commands = PrivateDnsOps.setHostnameCommands(hostname.trim())
        val executed = commands.all { command ->
            val output = exec(command) ?: return Outcome.ChannelUnavailable
            !AppFirewallOps.isFailureOutput(output)
        }
        if (!executed) return Outcome.ChannelUnavailable

        // 回读两个字段：mode 与 specifier 必须同时匹配
        val status = status() ?: return Outcome.ChannelUnavailable
        return if (status.mode == PrivateDnsOps.DnsMode.HOSTNAME &&
            status.specifier == hostname.trim()
        ) {
            Outcome.Applied
        } else {
            Outcome.VerifyMismatch
        }
    }

    /** 关闭 Private DNS（恢复系统默认明文 DNS） */
    suspend fun setOff(): Outcome = applyMode(
        commands = PrivateDnsOps.setOffCommands(),
        expected = PrivateDnsOps.DnsMode.OFF,
    )

    /** 切换到「自动（机会式）」 */
    suspend fun setAuto(): Outcome = applyMode(
        commands = PrivateDnsOps.setAutoCommands(),
        expected = PrivateDnsOps.DnsMode.AUTO,
    )

    private suspend fun applyMode(
        commands: List<List<String>>,
        expected: PrivateDnsOps.DnsMode,
    ): Outcome {
        for (command in commands) {
            val output = exec(command) ?: return Outcome.ChannelUnavailable
            if (AppFirewallOps.isFailureOutput(output)) return Outcome.ChannelUnavailable
        }
        val status = status() ?: return Outcome.ChannelUnavailable
        return if (status.mode == expected) Outcome.Applied else Outcome.VerifyMismatch
    }
}
