package com.toaster.noad.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.toaster.noad.core.database.entity.DomainRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DomainRuleDao {

    @Query("SELECT * FROM domain_rule ORDER BY is_whitelist DESC, pattern ASC")
    fun observeAll(): Flow<List<DomainRuleEntity>>

    /** 匹配引擎加载用：只取启用规则 */
    @Query("SELECT * FROM domain_rule WHERE enabled = 1")
    suspend fun loadEnabled(): List<DomainRuleEntity>

    @Query("SELECT * FROM domain_rule WHERE enabled = 1")
    fun observeEnabled(): Flow<List<DomainRuleEntity>>

    @Query("SELECT * FROM domain_rule WHERE is_whitelist = 1 AND enabled = 1")
    suspend fun loadEnabledWhitelist(): List<DomainRuleEntity>

    @Query("SELECT * FROM domain_rule WHERE is_whitelist = 0 AND enabled = 1")
    suspend fun loadEnabledBlacklist(): List<DomainRuleEntity>

    @Query("SELECT COUNT(*) FROM domain_rule")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM domain_rule WHERE is_whitelist = 1")
    fun observeWhitelistCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM domain_rule WHERE source = :source")
    suspend fun countBySource(source: String): Int

    /** 内置规则里属于白名单的条数，用于校验误杀防护是否已随黑名单一同导入 */
    @Query("SELECT COUNT(*) FROM domain_rule WHERE source = :source AND is_whitelist = 1")
    suspend fun countWhitelistBySource(source: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rules: List<DomainRuleEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rule: DomainRuleEntity): Long

    @Update
    suspend fun update(rule: DomainRuleEntity)

    @Delete
    suspend fun delete(rule: DomainRuleEntity)

    @Query("UPDATE domain_rule SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM domain_rule WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM domain_rule WHERE id = :id")
    suspend fun deleteById(id: Long)
}
