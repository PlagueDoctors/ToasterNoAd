package com.toaster.noad.core.service.shizuku

/**
 * AppOps 精细化控制命令（F6，方案 §6.7）。
 *
 * ## 用途（阻广告 SDK 的越界行为）
 *
 * | Op | 阻断什么 |
 * |---|---|
 * | `RUN_IN_BACKGROUND` | 广告 SDK 后台拉素材与保活 |
 * | `SYSTEM_ALERT_WINDOW` | 悬浮广告、摇一摇跳转 |
 * | `GET_DEVICE_ID` | 跨应用广告追踪的设备标识 |
 * | `READ_PHONE_STATE` | 设备标识另一入口 |
 * | `REQUEST_INSTALL_PACKAGES` | 广告诱导下载安装 |
 *
 * ## 🔴 铁律：绝不用数值 opCode
 *
 * 项目在 R11 已确立「cmd appops 用字符串名」：
 * `OP_*` 的数值在不同 Android 版本会漂移，硬编码必然在某版本上
 * 静默改错操作（后果可能是拒绝了一个完全无关的权限）。
 * 本节所有命令都使用字符串操作名。
 *
 * ## 语义诚实（必须传递给 UI）
 *
 * AppOps 的「拒绝」**不是**系统权限拒绝，而是「返回空数据」——
 * App 仍认为自己有权限，只是拿到空白值。
 * 好处：不触发「不给权限就不让用」；限制：强校验场景可能无效。
 */
object AppOpsOps {

    /** 支持的 Op（字符串名，经实机验证可被 `cmd appops` 接受） */
    enum class Op(val opName: String, val label: String) {
        RUN_IN_BACKGROUND("RUN_IN_BACKGROUND", "后台运行"),
        SYSTEM_ALERT_WINDOW("SYSTEM_ALERT_WINDOW", "悬浮窗"),
        GET_DEVICE_ID("GET_DEVICE_ID", "设备标识"),
        READ_PHONE_STATE("READ_PHONE_STATE", "电话状态"),
        REQUEST_INSTALL_PACKAGES("REQUEST_INSTALL_PACKAGES", "安装应用"),
    }

    /** 设置拒绝（ignore）/ 允许（allow）*/
    fun setModeCommand(packageName: String, op: Op, denied: Boolean): List<String> =
        listOf(
            "cmd", "appops", "set",
            packageName,
            op.opName,
            if (denied) MODE_IGNORE else MODE_ALLOW,
        )

    /** 查询单个 Op 的当前模式 */
    fun getModeCommand(packageName: String, op: Op): List<String> =
        listOf("cmd", "appops", "get", packageName, op.opName)

    /**
     * 解析查询输出。
     *
     * 实机输出形态（NX789J / Android 15）：
     * - 未设置过：`No operations.` + `Default mode: allow`
     * - 已设置：`<OP>: ignore` / `<OP>: allow`
     */
    sealed interface ModeResult {
        data class Resolved(val denied: Boolean) : ModeResult
        data object NotSet : ModeResult
        data object Unknown : ModeResult
    }

    fun parseMode(output: String?, op: Op): ModeResult {
        if (output == null) return ModeResult.Unknown
        val text = output.trim()
        if (text.isEmpty()) return ModeResult.Unknown

        val lower = text.lowercase()
        if (lower.contains("no operations")) return ModeResult.NotSet

        // 精确到本 Op 的行：避免把别的 Op 的模式当成目标 Op 的结果
        val opLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(op.opName, ignoreCase = true) }

        return when {
            opLine == null -> ModeResult.Unknown
            opLine.lowercase().contains(MODE_IGNORE) -> ModeResult.Resolved(denied = true)
            opLine.lowercase().contains(MODE_ALLOW) -> ModeResult.Resolved(denied = false)
            else -> ModeResult.Unknown
        }
    }

    /** 判断「拒绝」是否已在回读中确认 */
    fun isDenied(output: String?, op: Op): Boolean =
        (parseMode(output, op) as? ModeResult.Resolved)?.denied == true

    const val MODE_IGNORE = "ignore"
    const val MODE_ALLOW = "allow"
}
