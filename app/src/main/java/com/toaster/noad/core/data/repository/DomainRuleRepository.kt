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

    /**
     * 批量导入内置规则（幂等：重复图案由唯一索引 + IGNORE 策略跳过）。
     *
     * @param blacklist 内置黑名单
     * @param whitelist 内置白名单。**必须与黑名单一同导入**，
     *   否则内置规则里「因误杀风险而显式放行」的域名会失效。
     */
    suspend fun importBuiltin(
        blacklist: List<DomainRule>,
        whitelist: List<DomainRule> = emptyList(),
    ): BuiltinImportResult {
        val blacklistEntities = blacklist.map {
            it.toEntity(isWhitelist = false, source = DomainRuleEntity.SOURCE_BUILTIN)
        }
        val whitelistEntities = whitelist.map {
            it.toEntity(isWhitelist = true, source = DomainRuleEntity.SOURCE_BUILTIN)
        }

        val blacklistInserted = if (blacklistEntities.isEmpty()) {
            0
        } else {
            dao.insertAll(blacklistEntities).count { it != -1L }
        }
        val whitelistInserted = if (whitelistEntities.isEmpty()) {
            0
        } else {
            dao.insertAll(whitelistEntities).count { it != -1L }
        }

        rebuildEngine()
        return BuiltinImportResult(
            blacklistInserted = blacklistInserted,
            whitelistInserted = whitelistInserted,
        )
    }

    /**
     * 用新的内置规则集**替换**旧的内置规则。
     *
     * 用于规则集升级：先删除全部 `source = builtin` 的记录再插入，
     * 因此被移除的旧规则不会残留。用户自定义与导入的规则不受影响。
     *
     * ⚠️ 注意：这会清掉用户对内置规则的启用/禁用状态。
     * 当前版本没有保存该状态，属于已知取舍；若后续需要保留，
     * 应在删除前按 pattern 快照 `enabled` 并在插入后回填。
     */
    suspend fun replaceBuiltin(
        blacklist: List<DomainRule>,
        whitelist: List<DomainRule>,
    ): BuiltinImportResult {
        dao.deleteBySource(DomainRuleEntity.SOURCE_BUILTIN)
        return importBuiltin(blacklist, whitelist)
    }

    /** 清空内置规则（保留用户规则） */
    suspend fun clearBuiltin() {
        dao.deleteBySource(DomainRuleEntity.SOURCE_BUILTIN)
        rebuildEngine()
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

    /** 已导入的内置规则条数 */
    suspend fun builtinCount(): Int = dao.countBySource(DomainRuleEntity.SOURCE_BUILTIN)

    /** 已导入的内置白名单条数 */
    suspend fun builtinWhitelistCount(): Int =
        dao.countWhitelistBySource(DomainRuleEntity.SOURCE_BUILTIN)
}

/**
 * 内置规则导入结果。
 *
 * 分开统计黑白名单插入数，因为白名单缺失会导致误杀防护失效，
 * 是需要被察觉的异常情况，不能与黑名单的成功混为一谈。
 */
data class BuiltinImportResult(
    val blacklistInserted: Int,
    val whitelistInserted: Int,
) {
    val totalInserted: Int get() = blacklistInserted + whitelistInserted
}
