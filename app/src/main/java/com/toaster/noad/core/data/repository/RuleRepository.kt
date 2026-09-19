package com.toaster.noad.core.data.repository

import com.toaster.noad.core.database.dao.SkipRuleDao
import com.toaster.noad.core.database.entity.SkipRuleEntity
import com.toaster.noad.core.database.toDomain
import com.toaster.noad.core.database.toEntity
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.SkipRuleSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * S1 跳过规则仓库。
 *
 * ## 内置规则的导入语义（本类最需要理解的部分）
 *
 * 内置规则随应用版本更新，而用户会停用或删除其中一些。
 * 这两件事天生冲突，处理不当会出现两类劣化：
 *
 * - **全量重建**：每次升级都 `DELETE` 后 `INSERT`，用户删掉的规则复活，
 *   用户停用的规则重新变为启用。用户会认为开关"不生效"。
 * - **永不更新**：检测到已有内置规则就跳过导入，修复错误规则的
 *   新版本永远装不上，只能靠"清除应用数据"。
 *
 * 因此采用**按业务键合并**（见 [mergeBuiltin]），且业务键刻意不含 `id`
 * 与 `enabled`：
 *
 * | 情况 | 处理 |
 * |---|---|
 * | 库里没有该键 | 插入（新规则） |
 * | 库里有、启用中 | 保留库中行不动（用户的启用状态已表达意图） |
 * | 库里有、已停用 | 保留停用（用户明确不要它） |
 *
 * 注意**没有**"记住用户删除"这一行 —— 见 [deleteById] 的说明。
 *
 * ## 业务键的选择
 *
 * 键 = `packageName` + `targetType` + `targetValue` + `activityName`。
 *
 * 不含 `name`：规则名是展示文案，改文案不应被当成"新增一条规则"。
 * 不含 `priority`：调优先级是同一规则的变化，不是新规则。
 */
class RuleRepository(private val dao: SkipRuleDao) {

    fun observeRules(): Flow<List<SkipRule>> =
        dao.observeAll().map { list -> list.map(SkipRuleEntity::toDomain) }

    fun observeEnabledRules(): Flow<List<SkipRule>> =
        dao.observeEnabled().map { list -> list.map(SkipRuleEntity::toDomain) }

    fun observeEnabledCount(): Flow<Int> = dao.observeEnabledCount()

    /** 一次性读取某来源的全部规则（含停用），导入合并时使用 */
    suspend fun loadBySource(source: SkipRuleSource): List<SkipRule> =
        dao.loadBySource(source.persistedName).map(SkipRuleEntity::toDomain)

    suspend fun countBySource(source: SkipRuleSource): Int = dao.countBySource(source.persistedName)

    suspend fun findApplicable(packageName: String, activityName: String?): List<SkipRule> =
        dao.findApplicable(packageName, activityName).map(SkipRuleEntity::toDomain)

    suspend fun findById(id: Long): SkipRule? = dao.findById(id)?.toDomain()

    // ============ 内置规则导入 ============

    /**
     * 合并导入内置规则。
     *
     * 只插入缺失的规则，**不修改也不删除**任何既有行。
     *
     * @param rules 解析自 assets 的内置规则（`source` 应为 [SkipRuleSource.BUILTIN]）
     * @return 实际新增条数
     */
    suspend fun mergeBuiltin(rules: List<SkipRule>): Int {
        if (rules.isEmpty()) return 0

        val existingKeys = allExistingKeys()
        val incoming = dedupeByKey(rules)

        val toInsert = incoming.filter { it.businessKey() !in existingKeys }
        if (toInsert.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val ids = dao.insertAll(toInsert.map { it.toEntity(now = now, source = SkipRuleSource.BUILTIN) })
        return ids.count { it != NO_ROW_ID }
    }

    /**
     * 判断是否需要导入内置规则。
     *
     * 条件为"库里一条内置规则都没有"。
     *
     * 刻意**不**引入版本号比较：assets 中的 `version` 属于数据文件版本，
     * 而应用版本号已能表达"是否升级过"。两者都参与判断会带来
     * "规则文件改了但忘了改 version"这类静默失效，反而更难排查。
     * 增量更新属于后续独立能力（需要规则集差异比对），不在当前范围。
     */
    suspend fun needsBuiltinImport(): Boolean = countBySource(SkipRuleSource.BUILTIN) == 0

    // ============ 用户规则增删改 ============

    suspend fun add(rule: SkipRule): Long =
        dao.insert(rule.copy(source = SkipRuleSource.USER).toEntity())

    suspend fun addAll(rules: List<SkipRule>): List<Long> =
        dao.insertAll(rules.map { it.copy(source = SkipRuleSource.USER).toEntity() })

    suspend fun update(rule: SkipRule) = dao.update(rule.toEntity())

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(rule: SkipRule) = dao.delete(rule.toEntity())

    /**
     * 删除规则。
     *
     * 允许删除内置规则：用户删掉一条不想要的内置规则，
     * 在这个版本内就不再受其干扰。下个版本若仍包含同一业务键，
     * 它会重新出现 —— 这是**已知且可接受**的行为，
     * 因为"永久记住用户删了哪条内置规则"需要额外的墓碑表，
     * 而收益仅是省去一次重复删除。
     */
    suspend fun deleteById(id: Long) = dao.deleteById(id)

    // ============ 内部工具 ============

    /**
     * 库中**全部来源**规则的业务键集合。
     *
     * 刻意不按 `source` 过滤：去重必须跨来源进行。
     * 若只看内置来源，用户手工建过一条与内置规则定位值相同的规则时，
     * 导入会再插一条 —— 结果是同一界面出现两条等效规则，
     * 两条都会参与匹配与点击，且规则页里看起来是"重复项"。
     *
     * 跨来源去重的后果是"用户规则会阻止内置规则导入"，这是**期望行为**：
     * 用户已经表达了对该定位值的意图，内置规则没必要再插入一份。
     */
    private suspend fun allExistingKeys(): Set<SkipRuleKey> =
        dao.loadAll().mapTo(HashSet()) { it.businessKey() }

    /** 对传入规则按业务键去重，保留首次出现的（优先遵守文件中的顺序） */
    private fun dedupeByKey(rules: List<SkipRule>): List<SkipRule> {
        val seen = HashSet<SkipRuleKey>(rules.size)
        return rules.filter { seen.add(it.businessKey()) }
    }

    private companion object {
        /** Room `insertAll` 在 IGNORE 冲突时返回的哨兵值 */
        const val NO_ROW_ID = -1L
    }
}

/**
 * 内置规则合并所用的业务键。
 *
 * 用 `data class` 而非拼接字符串：字符串拼接需要选分隔符，
 * 而 `viewId` / `targetValue` 中完全可能出现该分隔符，
 * 造成两条不同规则被视为同一条。类型化字段从根上排除这类错误。
 */
private data class SkipRuleKey(
    val packageName: String,
    val activityName: String?,
    val targetType: String,
    val targetValue: String,
)

private fun SkipRule.businessKey(): SkipRuleKey = SkipRuleKey(
    packageName = packageName,
    activityName = activityName,
    targetType = targetType.name,
    // 大小写不敏感：`UiMatcher` 的匹配本身是 ignoreCase 的，
    // 若这里区分大小写，大小写不同的"同一条规则"会被导入两次，
    // 表现为同一条规则在列表里出现两遍。
    targetValue = targetValue.lowercase(),
)

private fun SkipRuleEntity.businessKey(): SkipRuleKey = SkipRuleKey(
    packageName = packageName,
    activityName = activityName,
    targetType = targetType,
    targetValue = targetValue.lowercase(),
)
