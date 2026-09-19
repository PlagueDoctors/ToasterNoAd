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

    /**
     * 一次性读取全部规则（含停用）。
     *
     * 供导入去重使用：去重必须**跨来源**进行，否则用户规则与内置规则
     * 指向同一节点时会双双入库（见 `RuleRepository.allExistingKeys`）。
     * 规则集规模在数百量级，全量读取的代价可忽略。
     */
    @Query("SELECT * FROM skip_rule")
    suspend fun loadAll(): List<SkipRuleEntity>

    @Query("SELECT COUNT(*) FROM skip_rule WHERE enabled = 1")
    fun observeEnabledCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM skip_rule WHERE source = :source")
    suspend fun countBySource(source: String): Int

    /**
     * 取某来源的全部规则（含停用）。
     *
     * 导入流程需要看到**停用**的内置规则：用户停用它意味着
     * "这条内置规则我不要"，重导入时必须保留这一意图。
     * 若只查启用项，停用就成了"下次升级就复活"的假开关。
     */
    @Query("SELECT * FROM skip_rule WHERE source = :source")
    suspend fun loadBySource(source: String): List<SkipRuleEntity>

    /** 删除某来源的全部规则（用于内置规则集整体替换） */
    @Query("DELETE FROM skip_rule WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: SkipRuleEntity): Long

    /**
     * 批量插入，主键冲突时保留既有行。
     *
     * **刻意使用 [OnConflictStrategy.IGNORE] 而非 REPLACE**：
     * REPLACE 会先删后插，导致两个不可接受的后果 ——
     * ① 用户对既有规则的停用状态被静默重置为"启用"；
     * ② 行 id 变化，任何引用该 id 的外部状态（如防误点冷却记账）失效。
     *
     * 导入场景下的正确语义是"补上缺失的"，重复项由 Repository
     * 依据业务键（包名 + 定位值等）判定后跳过，而不是交给数据库覆盖。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
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
