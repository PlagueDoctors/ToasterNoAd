package com.toaster.noad.core.service.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Shizuku UserService 传输层（R11 / 阶段 F2）。
 *
 * ## 职责边界
 *
 * 只做三件事：绑定用户服务、转发命令、返回文本。
 * **不做任何解析与决策** —— 那些都在 [RestrictedSettingsOps] 纯层，
 * 本类无法也不需要在 JVM 上测试（含 Shizuku API 调用）。
 *
 * ## 连接生命周期
 *
 * - `bindUserService` 是异步的：连接结果经 [ServiceConnection] 回来，
 *   这里用 `CompletableDeferred` 把它变成可等待的挂起点，
 *   并用超时兜底（Shizuku 服务器假死时不能把 UI 卡在「正在解除」）
 * - 连接建立后复用（UserService 进程随绑定存活，重复绑定只会浪费 shell 进程）
 * - [onBinderDead] 由 holder 在 Shizuku 服务器死亡时调用：引用归位，
 *   下次使用前重新绑定（§11.2「重启后失效」风险的传输层应对）
 */
class ShizukuShellClient(private val context: Context) {

    @Volatile
    private var service: IShellCommandService? = null

    @Volatile
    private var pendingConnection: CompletableDeferred<IShellCommandService?>? = null

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = binder?.let { IShellCommandService.Stub.asInterface(it) }
            service = connected
            pendingConnection?.complete(connected)
            pendingConnection = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Shizuku 服务器死亡 / 用户服务进程退出：清空引用。
            // 后续 exec 直接返回 null，由上层按「通道未就绪」处理；
            // 下一次 ensureConnected 会重新绑定。
            service = null
        }
    }

    /** 建立（或复用）用户服务连接；返回连接是否可用 */
    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        service?.let { return@withContext true }

        val deferred = CompletableDeferred<IShellCommandService?>()
        pendingConnection = deferred

        val bindRequested = runCatching {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(
                    ComponentName(context, ShellCommandUserService::class.java),
                ).version(SERVICE_VERSION),
                connection,
            )
        }.isSuccess

        if (!bindRequested) {
            pendingConnection = null
            return@withContext false
        }

        val connected = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        pendingConnection = null
        service = connected
        connected != null
    }

    /**
     * 执行一条命令，返回合并输出（stdout+stderr）。
     *
     * 通道未连接或执行异常（如服务器恰好死亡）时返回 `null` ——
     * 调用方据此判定「本次尝试未产生可信结果」，而不是「限制未解除」。
     */
    suspend fun exec(args: List<String>): String? = withContext(Dispatchers.IO) {
        val current = service ?: return@withContext null
        runCatching { current.exec(args.toTypedArray()) }.getOrNull()
    }

    /** Shizuku 服务器死亡时归位（由 holder 的 dead 监听触发） */
    fun reset() {
        service = null
        pendingConnection?.complete(null)
        pendingConnection = null
    }

    private companion object {
        /**
         * UserService 实现变更时必须 +1：
         * Shizuku 依据版本号决定是否重启旧的用户服务进程。
         */
        const val SERVICE_VERSION = 1

        /** 绑定超时：Shizuku 服务器假死时保证上层流程可以收敛 */
        const val BIND_TIMEOUT_MS = 8_000L
    }
}
