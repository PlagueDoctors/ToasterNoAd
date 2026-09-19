package com.toaster.noad.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.toaster.noad.core.database.entity.SkipRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SkipRuleDao {

    @Query("SELECT * FROM skip_rule ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<SkipRuleEntity>>

    @Query("SELECT * FROM skip_rule WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    fun observeEnabled(): Flow<List<SkipRuleEntity>>

    /**
     * S1 引擎热路径：按包名取启用规则。
     * 只返回已启用且 activity 匹配（activity 为 NULL 视为通配）的规则。
     */
    @Query(
        """
        SELECT * FROM skip_rule
        WHERE enabled = 1
          AND package_name = :packageName
          AND (activity_name IS NULL OR activity_name = :activityName)
        ORDER BY priority DESC, id ASC
        """,
    )
    suspend fun findApplicable(packageName: String, activityName: String?): List<SkipRuleEntity>

    @Query("SELECT * FROM skip_rule WHERE id = :id")
    suspend fun findById(id: Long): SkipRuleEntity?

    @Query("SELECT COUNT(*) FROM skip_rule WHERE enabled = 1")
    fun observeEnabledCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: SkipRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<SkipRuleEntity>): List<Long>

    @Update
    suspend fun update(rule: SkipRuleEntity)

    @Delete
    suspend fun delete(rule: SkipRuleEntity)

    @Query("DELETE FROM skip_rule WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE skip_rule SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())
}
