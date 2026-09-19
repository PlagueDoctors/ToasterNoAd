package com.toaster.noad.core.service.shizuku

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService 实现（R11 / 阶段 F2）。
 *
 * ## 为什么用 UserService（方案 §13.4 的官方推荐层级）
 *
 * 官方已声明："Prepare to remove `Shizuku#newProcess`,
 * developers should have to use UserService instead."
 * 因此特权命令的传输层落在 `UserService > ShizukuBinderWrapper > shell`
 * 层级的最高一级上。UserService 以 shell 身份（UID 2000）运行于
 * Shizuku 服务器拉起的独立进程，具备执行 `cmd appops` 的完整权限。
 *
 * ## 设计约束
 *
 * - **无状态**：每次 `exec` 独立执行，进程内不缓存任何结果 ——
 *   服务可能因 Shizuku 重启而重建，所有状态都在主进程的
 *   [ShizukuAvailabilityHolder] 状态机里
 * - **绝不抛异常 / 绝不挂死**：内部超时兜底，失败并入返回文本；
 *   binder 调用方的超时控制因此可以信任
 * - 进程入口类：Shizuku 服务器按约定反射调用 `(Context)` 构造器；
 *   当前实现不使用该参数，但签名必须保留
 */
class ShellCommandUserService(context: Context) : IShellCommandService.Stub() {

    override fun exec(args: Array<out String>): String = runCatching {
        val process = ProcessBuilder(*args)
            .redirectErrorStream(true)
            .start()

        // 先限时等待退出、再读输出：
        // appops 输出量级很小（远小于管道缓冲），先等退出不会死锁；
        // 反过来先读会在命令永不退出时永远阻塞。
        val finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            val partial = runCatching {
                process.inputStream.readBytes().toString(Charsets.UTF_8)
            }.getOrDefault("")
            return@runCatching "$partial$OUTPUT_TIMEOUT_MARKER\n"
        }

        process.inputStream.readBytes().toString(Charsets.UTF_8)
    }.getOrElse {
        // 例如 ROM 禁止 shell 执行该命令（SecurityException 等）：
        // 以文本标记并入返回值，由解析层按 UNKNOWN 处理并降级
        "$OUTPUT_ERROR_MARKER: ${it.javaClass.simpleName}: ${it.message}\n"
    }

    private companion object {
        const val EXEC_TIMEOUT_SECONDS = 10L
        const val OUTPUT_TIMEOUT_MARKER = "\nNoAd: exec timeout\n"
        const val OUTPUT_ERROR_MARKER = "NoAd: exec error"
    }
}
