package com.toaster.noad.core.service

import com.toaster.noad.core.service.shizuku.AppFirewallOps

/**
 * 强制停止门面（F7，方案 §6.8）。
 *
 * ## 定位：S1 的**最后手段**（触发条件必须严格）
 *
 * 当广告弹窗的关闭按钮是恶意的（假按钮/跳转下载）、
 * 坐标随机、或穿透整屏时，「点掉它」不如「直接把 App 停掉」。
 *
 * ## 为什么必须有冷却
 *
 * 强停会**破坏用户正在使用的应用**（进度丢失、回到桌面），
 * 是最容易引起反感的操作。因此：
 * - **不做全自动批量操作**（方案 §6.8.2：建议做成「高风险 App 列表 + 逐个授权」）；
 * - 同一应用在冷却窗口内**绝不重复强停**（本类内建）；
 * - 冷却状态精确到包名，互不影响。
 *
 * ## 命令与回读
 *
 * | 命令 | 说明 |
 * |---|---|
 * | `am force-stop <pkg>` | 强停（无输出）|
 * | `pidof <pkg>` | 输出 PID 或空 —— 用于回读是否真的停了 |
 */
class ProcessController(
    private val exec: suspend (List<String>) -> String?,
    private val isReady: () -> Boolean,
    /** 注入时钟：与 R8/R14 同惯例，便于测试冷却语义 */
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {

    private val lastStopByPackage = HashMap<String, Long>()

    sealed interface Outcome {
        data class Stopped(val wasRunning: Boolean) : Outcome

        /** 命令成功但进程仍在（部分 ROM 强停后会被自启拉起） */
        data object VerifyMismatch : Outcome

        data object ChannelUnavailable : Outcome

        /** 冷却窗口内重复请求 —— 刻意拒绝，不是失败 */
        data class Cooldown(val remainingMs: Long) : Outcome
    }

    /**
     * 强制停止指定应用。
     *
     * @return [Outcome.Cooldown] 表示调用过于频繁（调用方无需报错）
     */
    suspend fun forceStop(packageName: String): Outcome {
        if (!isReady()) return Outcome.ChannelUnavailable

        val now = clock()
        val last = lastStopByPackage[packageName]
        if (last != null && now - last < cooldownMs) {
            return Outcome.Cooldown(cooldownMs - (now - last))
        }

        val wasRunning = isRunning(packageName) ?: false

        val output = exec(listOf("am", "force-stop", packageName))
            ?: return Outcome.ChannelUnavailable
        if (AppFirewallOps.isFailureOutput(output)) return Outcome.ChannelUnavailable

        lastStopByPackage[packageName] = now

        // 回读：强停后进程应消失（pidof 输出为空）
        return if (isRunning(packageName) == false) {
            Outcome.Stopped(wasRunning)
        } else {
            Outcome.VerifyMismatch
        }
    }

    /**
     * 查询应用是否在运行。
     *
     * `pidof` 无匹配时输出为空字符串（rc=1）—— 与「命令不支持」不同，
     * 因此这里不能用 [AppFirewallOps.isFailureOutput] 一刀切。
     */
    suspend fun isRunning(packageName: String): Boolean? = runCatching {
        val output = exec(listOf("pidof", packageName)) ?: return@runCatching null
        if (output.contains("Exception", ignoreCase = true)) return@runCatching null
        output.trim().isNotEmpty()
    }.getOrNull()

    /** 清空冷却记账（用户手动解除限制时调用） */
    fun resetCooldown() {
        lastStopByPackage.clear()
    }

    private companion object {
        /**
         * 冷却窗口：10 秒。
         *
         * 依据：强停是破坏性操作，同一应用在 10 秒内被强停两次
         * 必属异常触发（正常场景强停后应用需要用户重新打开）。
         */
        const val DEFAULT_COOLDOWN_MS = 10_000L
    }
}
