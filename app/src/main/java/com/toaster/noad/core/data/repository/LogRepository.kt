package com.toaster.noad.core.data.repository

import com.toaster.noad.core.database.dao.BlockedAppCount
import com.toaster.noad.core.database.dao.InterceptLogDao
import com.toaster.noad.core.database.entity.InterceptLogEntity
import com.toaster.noad.core.database.toDomain
import com.toaster.noad.core.database.toEntity
import com.toaster.noad.core.model.InterceptLog
import com.toaster.noad.core.model.InterceptSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar

/**
 * 拦截日志仓库。
 *
 * ## 写入约束（重要）
 *
 * S1 的 `onAccessibilityEvent` 运行在主线程，S2/S3 的包处理运行在独立线程。
 * 无论哪条路径，**写入都必须异步**，否则会拖慢事件回调或包转发。
 * 因此本仓库的写方法全部为 `suspend`，由调用方选择合适的调度器。
 *
 * ## 容量控制
 *
 * 日志表会持续增长。本仓库提供 [trim] 用于按容量上限裁剪，
 * 由上层（Application 启动时 + 定期任务）调用，避免无限膨胀。
 */
class LogRepository(private val dao: InterceptLogDao) {

    fun observeRecent(limit: Int = DEFAULT_QUERY_LIMIT): Flow<List<InterceptLog>> =
        dao.observeRecent(limit).map { list -> list.map(InterceptLogEntity::toDomain) }

    fun observeToday(): Flow<List<InterceptLog>> =
        dao.observeSince(startOfToday(), DEFAULT_QUERY_LIMIT)
            .map { list -> list.map(InterceptLogEntity::toDomain) }

    fun observeTodayCount(): Flow<Int> = dao.observeCountSince(startOfToday())

    fun observeTodayCountBySource(source: InterceptSource): Flow<Int> =
        dao.observeCountSinceBySource(startOfToday(), source.name)

    fun observeTopBlockedApps(limit: Int = 10): Flow<List<BlockedAppCount>> =
        dao.observeTopBlockedAppsSince(startOfToday(), limit)

    /** 写入单条记录。调用方负责指定调度器（不可在主线程调用）。 */
    suspend fun record(log: InterceptLog): Long = dao.insert(log.toEntity())

    suspend fun recordAll(logs: List<InterceptLog>): List<Long> =
        dao.insertAll(logs.map { it.toEntity() })

    /** 便捷方法：构造并写入一条记录 */
    suspend fun record(
        source: InterceptSource,
        appLabel: String,
        adType: com.toaster.noad.core.model.AdType,
        packageName: String? = null,
        ruleDetail: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): Long = record(
        InterceptLog(
            packageName = packageName,
            appLabel = appLabel,
            adType = adType,
            source = source,
            ruleDetail = ruleDetail,
            timestamp = timestamp,
        ),
    )

    /** 删除早于 [retentionDays] 天的记录，返回删除条数 */
    suspend fun deleteOlderThanDays(retentionDays: Int): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY
        return dao.deleteOlderThan(cutoff)
    }

    /** 裁剪到最多 [capacity] 条，返回删除条数 */
    suspend fun trim(capacity: Int): Int = dao.trimToLatest(capacity)

    suspend fun clear() = dao.deleteAll()

    companion object {
        const val DEFAULT_QUERY_LIMIT = 200
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /** 今日零点时间戳（本地时区） */
        fun startOfToday(now: Long = System.currentTimeMillis()): Long =
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        /** 昨日零点时间戳（本地时区），用于「较昨日」对比 */
        fun startOfYesterday(now: Long = System.currentTimeMillis()): Long =
            startOfToday(now) - MILLIS_PER_DAY
    }
}
