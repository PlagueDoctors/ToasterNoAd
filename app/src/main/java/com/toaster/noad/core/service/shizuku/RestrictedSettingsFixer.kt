package com.toaster.noad.core.service.shizuku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 侧载「受限设置」解除执行器（R11 / 阶段 F2，方案 §6.5.2 + §6.5.3）。
 *
 * ## 流程（全部决策来自纯层，本类只负责"跑命令"）
 *
 * ```
 * get（包级）→ 解析初始状态
 *   ├─ ALLOWED → AlreadyAllowed（一次写操作都不做）
 *   └─ 其余 → 按计划逐个变体尝试：
 *         set（变体）→ get（同变体回读验证）→ 验证到 allow 即 Fixed
 *   └─ 全部失败 → Failed（携带诊断文本）
 * ```
 *
 * ## 为什么成功判定是「回读验证」而不是「set 没报错」
 *
 * set 成功但模式没生效（ROM 行为差异、包级 vs UID 级差异）是
 * 真实存在的失败形态；只有回读到 allow 才能对用户说「已解除」。
 * 这也是方案 §6.3.4「运行时探测 + 失败降级」原则的落实。
 */
class RestrictedSettingsFixer(private val shell: ShizukuShellClient) {

    /**
     * 尝试解除 [packageName] 的「受限设置」限制。
     *
     * 必须在持有 Shizuku 授权的前提下调用；通道未就绪时返回
     * [RestrictedSettingsOps.FixOutcome.NeedsShizuku] 而不是抛异常。
     */
    suspend fun fix(packageName: String): RestrictedSettingsOps.FixOutcome = withContext(Dispatchers.IO) {
        val initialText = shell.exec(
            RestrictedSettingsOps.getCommand(packageName, uidVariant = false),
        ) ?: return@withContext RestrictedSettingsOps.FixOutcome.Failed(null)

        val initial = RestrictedSettingsOps.parseOpState(initialText)

        val verified = mutableListOf<Boolean>()
        for (uidVariant in RestrictedSettingsOps.buildAttemptPlan(initial)) {
            shell.exec(RestrictedSettingsOps.setCommand(packageName, uidVariant))

            val verifyText = shell.exec(
                RestrictedSettingsOps.getCommand(packageName, uidVariant),
            ) ?: continue

            verified += RestrictedSettingsOps.parseOpState(verifyText) ==
                RestrictedSettingsOps.OpState.ALLOWED
        }

        RestrictedSettingsOps.decideOutcome(
            initial = initial,
            verified = verified,
            // 诊断只保留末行（错误信息通常在最后），截断防长输出
            diagnostics = initialText.lineSequence().lastOrNull { it.isNotBlank() }?.takeLast(200),
        )
    }
}
