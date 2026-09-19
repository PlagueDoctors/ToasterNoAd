package com.toaster.noad.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.toaster.noad.core.database.entity.InterceptLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * 拦截日志 DAO。
 *
 * 写入路径完全异步（`suspend`），调用方必须保证不在主线程调用，
 * 避免 S1 的 `onAccessibilityEvent` 回调被磁盘 IO 阻塞。
 */
@Dao
interface InterceptLogDao {

    @Query("SELECT * FROM intercept_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<InterceptLogEntity>>

    @Query(
        """
        SELECT * FROM intercept_log
        WHERE timestamp >= :since
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    fun observeSince(since: Long, limit: Int = 200): Flow<List<InterceptLogEntity>>

    @Query("SELECT COUNT(*) FROM intercept_log WHERE timestamp >= :since")
    fun observeCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM intercept_log WHERE timestamp >= :since AND source = :source")
    fun observeCountSinceBySource(since: Long, source: String): Flow<Int>

    /** 全量拦截条数（随容量裁剪变化：trim 后计数回落到容量上限内） */
    @Query("SELECT COUNT(*) FROM intercept_log")
    fun observeTotalCount(): Flow<Int>

    /** 最近一条拦截记录；表为空时为 null。LIMIT 1，避免为取首条拉全量列表 */
    @Query("SELECT * FROM intercept_log ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<InterceptLogEntity?>

    @Query(
        """
        SELECT package_name AS packageName, app_label AS appLabel, COUNT(*) AS count
        FROM intercept_log
        WHERE timestamp >= :since AND package_name IS NOT NULL
        GROUP BY package_name, app_label
        ORDER BY count DESC
        LIMIT :limit
        """,
    )
    fun observeTopBlockedAppsSince(since: Long, limit: Int = 10): Flow<List<BlockedAppCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: InterceptLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<InterceptLogEntity>): List<Long>

    @Query("DELETE FROM intercept_log WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /**
     * 保留最近 [keep] 条，删除其余。
     * 用于容量上限控制，避免日志表无限膨胀。
     */
    @Query(
        """
        DELETE FROM intercept_log
        WHERE id NOT IN (
            SELECT id FROM intercept_log ORDER BY timestamp DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimToLatest(keep: Int): Int

    @Query("DELETE FROM intercept_log")
    suspend fun deleteAll()
}

/**
 * 「拦截次数最多的应用」投影结果。
 */
data class BlockedAppCount(
    val packageName: String,
    val appLabel: String,
    val count: Int,
)
