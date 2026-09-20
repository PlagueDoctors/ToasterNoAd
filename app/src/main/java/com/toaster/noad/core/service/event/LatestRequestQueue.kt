package com.toaster.noad.core.service.event

import java.util.concurrent.atomic.AtomicReference

/**
 * 「最新覆盖」请求队列（S1 性能重构的合并语义内核）。
 *
 * ## 为什么需要它
 *
 * 应用启动期会连续产生数十次 `TYPE_WINDOW_CONTENT_CHANGED`。
 * 但扫描的对象是**当前界面快照**（`rootInActiveWindow`），不是事件对象 ——
 * 因此连续 N 个待处理请求中，**只有最后一个是有意义的**：
 * 它对应的扫描能看到前 N-1 个请求发生时界面上的一切。
 *
 * 用一个「只保留最新」的槽位替代 FIFO 队列，就把启动期
 * 「数十次全树扫描」压成「1~2 次」，且**不丢任何可观测信息**。
 *
 * ## 语义契约
 *
 * - [offer] 永远不阻塞、永远成功（覆盖式）
 * - [poll] 取走并清空槽位；空时返回 null
 * - 并发下 poll 与 offer 之间的竞态**只会多处理一次、绝不少处理**：
 *   poll 返回 null 之后 offer 进来的请求，会由调用方的
 *   「收尾重查」逻辑重新调度（见 EventProcessor 的 drain 循环）
 *
 * 本类无 Android 依赖，合并语义在 `LatestRequestQueueTest` 中被直接锁定。
 */
internal class LatestRequestQueue<T> {

    private val slot = AtomicReference<T?>(null)

    /** 覆盖式投递：新请求替换尚未被取走的旧请求 */
    fun offer(item: T) {
        slot.set(item)
    }

    /** 取走并清空；无待处理请求时返回 null */
    fun poll(): T? = slot.getAndSet(null)

    /** 是否存在尚未取走的请求（用于 drain 循环的收尾重查） */
    val hasPending: Boolean
        get() = slot.get() != null
}
