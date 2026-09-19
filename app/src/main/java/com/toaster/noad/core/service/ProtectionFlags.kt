package com.toaster.noad.core.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 保护开关的内存镜像。
 *
 * ## 为什么需要它
 *
 * 数据源是 DataStore，读取是**挂起函数 + 磁盘 IO**。
 * 而使用场景是 `onAccessibilityEvent` 的**主线程同步回调** ——
 * 在那里读 DataStore 有三个不可接受的问题：
 *
 * 1. **阻塞**：主线程磁盘 IO，直接造成掉帧甚至 ANR
 * 2. **无法等待**：回调是同步的，无法 `suspend`
 * 3. **频率过高**：一次界面变化可能触发数次回调
 *
 * 因此改为：由 ViewModel 侧在开关变化时写入本镜像，
 * 热路径只读一个 [AtomicBoolean]。
 *
 * ## 关于「双写」的一致性
 *
 * 开关的**权威来源**始终是 DataStore；本镜像只是缓存。
 * 启动时由 [syncFrom] 从 DataStore 初始化；
 * UI 切换开关时同时写 DataStore（持久化）与本镜像（热路径）。
 *
 * 若二者不一致（例如进程被杀死后重启），以 DataStore 为准 ——
 * 因为 [syncFrom] 会在读取到首个值时覆盖镜像。
 */
object ProtectionFlags {

    private val _accessibilityEnabled = AtomicBoolean(false)
    private val _protectionEnabled = AtomicBoolean(false)

    /**
     * S1 是否应在事件回调中生效。
     *
     * 这是**读操作，无锁**，可在主线程高频调用。
     */
    val accessibilityEnabled: Boolean get() = _accessibilityEnabled.get()

    /** 总开关是否开启 */
    val protectionEnabled: Boolean get() = _protectionEnabled.get()

    /** 由 UI 在开关变化时调用（与写 DataStore 同时进行） */
    fun setAccessibilityEnabled(enabled: Boolean) {
        _accessibilityEnabled.set(enabled)
    }

    fun setProtectionEnabled(enabled: Boolean) {
        _protectionEnabled.set(enabled)
    }

    /**
     * 从 Flow 持续同步。
     *
     * 在 Application 启动时调用，保证即使 UI 从未打开过，
     * 镜像也能反映用户此前的设置（例如开机自启场景）。
     */
    fun syncFrom(
        scope: CoroutineScope,
        protectionEnabledFlow: Flow<Boolean>,
        accessibilityEnabledFlow: Flow<Boolean>,
    ) {
        protectionEnabledFlow
            .onEach { _protectionEnabled.set(it) }
            .launchIn(scope)

        accessibilityEnabledFlow
            .onEach { _accessibilityEnabled.set(it) }
            .launchIn(scope)
    }

    /** 仅供测试重置 */
    internal fun resetForTest() {
        _accessibilityEnabled.set(false)
        _protectionEnabled.set(false)
    }
}
