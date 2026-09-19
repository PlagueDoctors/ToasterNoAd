package com.toaster.noad.core.data.repository

import com.toaster.noad.core.database.dao.SkipRuleDao
import com.toaster.noad.core.database.entity.SkipRuleEntity
import com.toaster.noad.core.database.toDomain
import com.toaster.noad.core.database.toEntity
import com.toaster.noad.core.model.SkipRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * S1 跳过规则仓库。
 *
 * 职责：领域模型 ↔ 数据库实体的转换，以及对外暴露业务语义的查询接口。
 * 不包含任何匹配逻辑 —— 匹配属于引擎层（S1 引擎在阶段 B 实现）。
 */
class RuleRepository(private val dao: SkipRuleDao) {

    /** 观察全部规则（含禁用） */
    fun observeRules(): Flow<List<SkipRule>> =
        dao.observeAll().map { list -> list.map(SkipRuleEntity::toDomain) }

    /** 观察启用中的规则 */
    fun observeEnabledRules(): Flow<List<SkipRule>> =
        dao.observeEnabled().map { list -> list.map(SkipRuleEntity::toDomain) }

    fun observeEnabledCount(): Flow<Int> = dao.observeEnabledCount()

    /**
     * S1 引擎热路径：取某界面适用的规则。
     *
     * 由调用方（Service 层）在事件回调中调用，
     * 因此实现必须是 `suspend` 且不阻塞主线程。
     */
    suspend fun findApplicable(packageName: String, activityName: String?): List<SkipRule> =
        dao.findApplicable(packageName, activityName).map(SkipRuleEntity::toDomain)

    suspend fun findById(id: Long): SkipRule? = dao.findById(id)?.toDomain()

    /** 新增规则，返回自增 ID */
    suspend fun add(rule: SkipRule): Long = dao.insert(rule.toEntity())

    suspend fun addAll(rules: List<SkipRule>): List<Long> =
        dao.insertAll(rules.map { it.toEntity() })

    suspend fun update(rule: SkipRule) = dao.update(rule.toEntity())

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(rule: SkipRule) = dao.delete(rule.toEntity())

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
