package com.toaster.noad.core.service.keepalive

/**
 * 保活开关的进程内镜像。
 *
 * ## 为什么需要它
 *
 * 权威来源是 DataStore（挂起读取 + 磁盘 IO），而消费场景是
 * `KeepAliveService.onStartCommand` 的**主线程同步回调** ——
 * 粘性重启的瞬间必须立即决定「恢复前台」还是「自杀」，
 * 没有等待挂起读取的余地。
 *
 * 与 [com.toaster.noad.core.service.ProtectionFlags] 是同一模式：
 * Application 的观察者流负责写入镜像，热路径只读一个 volatile。
 *
 * ## 三态语义
 *
 * - `true` —— 用户已开启保活（镜像已同步）
 * - `false` —— 用户已关闭保活（镜像已同步）
 * - `null` —— 进程刚启动，镜像尚未同步
 *
 * `null` 的处理策略见 [KeepAlivePolicy.onStickyRestart]：
 * 乐观恢复，由观察者流秒级纠正。
 */
internal object KeepAliveRuntime {

    @Volatile
    private var _keepAliveEnabled: Boolean? = null

    /** 当前镜像值；`null` 表示未知（进程刚启动，尚未从 DataStore 同步） */
    val keepAliveEnabled: Boolean?
        get() = _keepAliveEnabled

    /** 由 Application 的设置观察者在每次值变化时调用 */
    fun update(enabled: Boolean) {
        _keepAliveEnabled = enabled
    }

    /** 仅供测试重置 */
    fun resetForTest() {
        _keepAliveEnabled = null
    }
}
