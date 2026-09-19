package com.toaster.noad.core.data.repository

import com.toaster.noad.core.database.dao.DomainRuleDao
import com.toaster.noad.core.database.entity.DomainRuleEntity
import com.toaster.noad.core.database.toDomain
import com.toaster.noad.core.database.toEntity
import com.toaster.noad.core.engine.DomainRuleEngine
import com.toaster.noad.core.model.DomainPolicy
import com.toaster.noad.core.model.DomainRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 域名规则仓库（S2 / S3 / S4 共享）。
 *
 * ## 编译缓存
 *
 * [DomainRuleEngine] 的构建是 O(规则数)，而匹配是高频操作（每个 DNS 查询一次）。
 * 因此本仓库维护一个已编译引擎的缓存，仅在规则变更时重建：
 *
 * - 读路径：[engine] 直接返回缓存实例，无锁（StateFlow 读取）
 * - 写路径：任何增删改后调用 [rebuildEngine]，在 [engineMutex] 保护下重建
 *
 * ## 线程模型
 *
 * `rebuildEngine` 使用 Mutex 串行化，避免并发重建产生竞争。
 * 引擎本身不可变，因此读线程拿到的实例在使用期间不会被改变。
 */
class DomainRuleRepository(private val dao: DomainRuleDao) {

    private val engineMutex = Mutex()

    private val _engine = MutableStateFlow(DomainRuleEngine.from(DomainPolicy.EMPTY))

    /** 当前生效的匹配引擎。读路径无锁。 */
    val engine: StateFlow<DomainRuleEngine> = _engine.asStateFlow()

    fun observeRules(): Flow<List<DomainRule>> =
        dao.observeAll().map { list -> list.map(DomainRuleEntity::toDomain) }

    fun observeEnabledRules(): Flow<List<DomainRule>> =
        dao.observeEnabled().map { list -> list.map(DomainRuleEntity::toDomain) }

    fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()

    fun observeWhitelistCount(): Flow<Int> = dao.observeWhitelistCount()

    /**
     * 从数据库加载规则并编译引擎。
     *
     * 应在应用启动与规则变更后调用。返回编译后的引擎便于断言与测试。
     */
    suspend fun rebuildEngine(): DomainRuleEngine = engineMutex.withLock {
        val enabled = dao.loadEnabled()
        val policy = DomainPolicy(
            blacklist = enabled.filter { !it.isWhitelist }
                .map(DomainRuleEntity::toDomain),
            whitelist = enabled.filter { it.isWhitelist }
                .map(DomainRuleEntity::toDomain),
        )
        val compiled = DomainRuleEngine.from(policy)
        _engine.value = compiled
        compiled
    }

    /** 批量导入内置规则（幂等：重复图案由唯一索引 + IGNORE 策略跳过） */
    suspend fun importBuiltin(rules: List<DomainRule>): Int {
        if (rules.isEmpty()) return 0
        val entities = rules.map {
            it.toEntity(isWhitelist = false, source = DomainRuleEntity.SOURCE_BUILTIN)
        }
        val ids = dao.insertAll(entities)
        rebuildEngine()
        return ids.count { it != -1L }
    }

    /** 批量导入黑名单（用户导入 / 自定义） */
    suspend fun importBlacklist(rules: List<DomainRule>, source: String): Int {
        if (rules.isEmpty()) return 0
        val entities = rules.map { it.toEntity(isWhitelist = false, source = source) }
        val ids = dao.insertAll(entities)
        rebuildEngine()
        return ids.count { it != -1L }
    }

    /** 批量导入白名单 */
    suspend fun importWhitelist(rules: List<DomainRule>, source: String): Int {
        if (rules.isEmpty()) return 0
        val entities = rules.map { it.toEntity(isWhitelist = true, source = source) }
        val ids = dao.insertAll(entities)
        rebuildEngine()
        return ids.count { it != -1L }
    }

    suspend fun addBlacklist(rule: DomainRule): Long {
        val id = dao.insert(rule.toEntity(isWhitelist = false, source = DomainRuleEntity.SOURCE_USER))
        rebuildEngine()
        return id
    }

    suspend fun addWhitelist(rule: DomainRule): Long {
        val id = dao.insert(rule.toEntity(isWhitelist = true, source = DomainRuleEntity.SOURCE_USER))
        rebuildEngine()
        return id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        rebuildEngine()
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
        rebuildEngine()
    }

    suspend fun countBySource(source: String): Int = dao.countBySource(source)

    /** 是否已完成内置规则导入 */
    suspend fun hasBuiltinRules(): Boolean = dao.countBySource(DomainRuleEntity.SOURCE_BUILTIN) > 0
}
