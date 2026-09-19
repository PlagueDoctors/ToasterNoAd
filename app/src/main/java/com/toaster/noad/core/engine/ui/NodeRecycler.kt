package com.toaster.noad.core.engine.ui

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点回收兼容封装。
 *
 * ## 背景：API 33 起 `recycle()` 被弃用
 *
 * `AccessibilityNodeInfo.recycle()` 在 Android 13（API 33）被标记为弃用。
 * 官方说明：自 API 33 起节点对象**由框架自行管理生命周期**，
 * 不再需要手动回收；手动调用在新版本上是无害的空操作。
 *
 * 但在 **API 32 及以下**，不回收会造成真实的**原生内存泄漏** ——
 * 每个未回收的节点都会阻止其对应的视图树被释放。
 *
 * ## 为什么不能简单地删掉所有 recycle()
 *
 * 本项目的 `minSdk` 是 30，意味着要覆盖 API 30/31/32 ——
 * 这些版本上"不回收"是一个真实缺陷，而非可以忽略的警告。
 * 更不能直接加 `@Suppress("DEPRECATION")` 一压了事：
 * 那会让"我们确实需要在旧版本回收"这个事实从代码里消失，
 * 后来者看到没有警告可能会删掉回收调用，从而在旧设备上引入泄漏。
 *
 * ## 因此的做法
 *
 * 集中到本对象，用版本判断明确表达意图：
 * - 新版本：什么都不做（框架负责）
 * - 旧版本：显式回收
 *
 * 这样调用点保持简洁，而"为什么"被记录在一处。
 */
internal object NodeRecycler {

    /** API 33 起框架接管节点生命周期，recycle() 成为空操作 */
    private const val API_FRAMEWORK_MANAGED = 33

    private val needsManualRecycle = Build.VERSION.SDK_INT < API_FRAMEWORK_MANAGED

    /**
     * 在 API 33 以下回收节点。
     *
     * 任何异常都被吞掉：节点已被回收、或引用已失效时抛异常是正常的，
     * 不应影响主流程，更不应让无障碍服务崩溃。
     */
    fun recycle(node: AccessibilityNodeInfo?) {
        node ?: return
        if (!needsManualRecycle) return

        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }
}
