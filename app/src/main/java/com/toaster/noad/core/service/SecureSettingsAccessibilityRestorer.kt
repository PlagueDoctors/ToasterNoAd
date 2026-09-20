package com.toaster.noad.core.service

import android.content.ContentResolver
import android.provider.Settings
import com.toaster.noad.core.service.shizuku.AccessibilityRestoreOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「高级授权」恢复执行器（R13）：持有 `WRITE_SECURE_SETTINGS` 的应用
 * 用 ContentResolver 直接读写 `Settings.Secure`，**无需 Shizuku** 即可
 * 完成与 R12 相同的授权恢复。
 *
 * ## 为什么存在第二条通道
 *
 * R12 的恢复走 Shizuku shell，前提是用户安装并启动了 Shizuku ——
 * 对大多数用户是额外负担。而 `adb pm grant` 授予的
 * `WRITE_SECURE_SETTINGS` 是 install-time 权限：**一次授权终身有效**
 * （重启不清、清数据不丢，仅卸载重装失效），授权成本远低于
 * 长期维护 Shizuku server。两条通道不是替代关系：
 * 有 adb 高级授权走本类，否则回退 Shizuku，都没有则手动引导。
 *
 * ## 为什么复用 shell 的命令序列而不是重写一套
 *
 * 恢复的**决策序列**（read → 已存在则跳过写 → merge → put →
 * put master → 回读验证）已经被 [AccessibilityRestoreOps.restore]
 * 的 31 个单测锁死。本类把 ContentResolver 的读写**伪装成 shell 命令**
 * 解释执行：`settings get secure <key>` → getString，
 * `settings put secure <key> <value>` → putString。
 *
 * 关键语义对齐：
 * - `getString` 返回 `null` 表示**键不存在**（与 shell `settings get`
 *   输出 `null` 字面量同义），映射为字面量交给 [AccessibilityRestoreOps.parseEntries]
 *   容忍 —— 决策层看到的形态与 shell 完全一致；
 * - 真正的 IO 层失败（权限被撤时的 `SecurityException` 等）让异常
 *   传播到 [restore] 统一捕获为 `Failed` —— 对应 shell 通道的
 *   「exec 返回 null」语义。
 *
 * ## 与 R8 禁止表的关系
 *
 * 本类只做**用户显式点击的一次性恢复**（与 R12 相同的边界修订），
 * 不提供任何周期性/后台静默写入入口 —— 那依然是禁止的。
 */
class SecureSettingsAccessibilityRestorer(
    private val readSetting: (String) -> String?,
    private val writeSetting: (String, String) -> Boolean,
) {

    /**
     * 生产构造：包装 [ContentResolver]。
     *
     * `putString` 的返回值刻意不检查 —— 与 shell 通道一致，
     * 写入是否生效由 restore 序列末尾的**回读验证**判定
     * （provider 拒绝、ROM 纠值等静默失败都会在那里被捕获）。
     */
    constructor(resolver: ContentResolver) : this(
        readSetting = { key -> Settings.Secure.getString(resolver, key) },
        writeSetting = { key, value -> Settings.Secure.putString(resolver, key, value) },
    )

    /**
     * 恢复无障碍授权记录（序列与 [AccessibilityRestoreOps.restore] 完全一致）。
     *
     * 全部 IO 收敛到 [Dispatchers.IO]：`Settings.Secure` 读写是跨进程
     * binder 调用，主线程执行会触发 StrictMode 甚至 ANR。
     */
    suspend fun restore(flat: String): AccessibilityRestoreOps.RestoreOutcome =
        withContext(Dispatchers.IO) {
            try {
                AccessibilityRestoreOps.restore(::execSecureSettings, flat)
            } catch (_: SecurityException) {
                // WRITE_SECURE_SETTINGS 被用户/系统撤回时的标准失败形态
                AccessibilityRestoreOps.RestoreOutcome.Failed(null)
            } catch (_: IllegalArgumentException) {
                // ContentResolver 对非法键值的拒绝；与 shell 语义一样按失败处理
                AccessibilityRestoreOps.RestoreOutcome.Failed(null)
            }
        }

    /**
     * 把决策层发出的 shell 形命令翻译为 ContentResolver 调用。
     *
     * 只识别 [AccessibilityRestoreOps] 自己构造的三种命令；
     * 其余形态返回 `null`（等同通道不可用，序列会落 `Failed`）。
     */
    private fun execSecureSettings(command: List<String>): String? = when {
        command.size == 4 &&
            command[1] == "get" &&
            command[2] == AccessibilityRestoreOps.NAMESPACE_SECURE ->
            // 键不存在时 getString 返回 null：映射为 shell 的 "null" 字面量，
            // 让决策层对「空设置」与「首次恢复」走同一条路径
            readSetting(command[3]) ?: AccessibilityRestoreOps.SETTING_VALUE_NULL

        command.size == 5 &&
            command[1] == "put" &&
            command[2] == AccessibilityRestoreOps.NAMESPACE_SECURE -> {
            writeSetting(command[3], command[4])
            // put 的输出在 restore 序列中无人消费（成功与否由回读验证判定），
            // 返回非 null 表示「命令已投递」
            AccessibilityRestoreOps.MASTER_SWITCH_ON
        }

        else -> null
    }
}
