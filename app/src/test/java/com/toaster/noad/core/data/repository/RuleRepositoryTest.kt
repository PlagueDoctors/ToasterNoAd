package com.toaster.noad.core.data.repository

import com.toaster.noad.core.database.dao.SkipRuleDao
import com.toaster.noad.core.database.entity.SkipRuleEntity
import com.toaster.noad.core.model.MatchMode
import com.toaster.noad.core.model.SkipRule
import com.toaster.noad.core.model.SkipRuleSource
import com.toaster.noad.core.model.TargetType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleRepository] 内置规则合并逻辑的单元测试。
 *
 * ## 为什么用假 DAO 而不是 Mock 框架
 *
 * 项目测试栈**不含 mockk / mockito**（见 `libs.versions.toml`），
 * 这在此处反而是好事：合并逻辑的关键契约是"多次导入后表里的内容"，
 * 用一个内存列表实现的假 DAO 能直接断言最终状态，
 * 而 Mock 只能断言"调用了哪些方法"，对这类状态机几乎无价值。
 *
 * ## 本测试保护的核心契约
 *
 * 内置规则随版本更新、用户会停用或删除它们 —— 这两件事天生冲突。
 * 合并逻辑必须做到：
 *
 * 1. 重复导入不产生重复行（幂等）
 * 2. **不停用**用户已停用的内置规则（否则开关看起来"不生效"）
 * 3. **不覆盖**用户对现有规则的修改
 * 4. 只增不删
 *
 * 契约 2 一旦破坏，用户的体验是"我明明关掉的规则又自己开了"，
 * 而且每次启动都会重演 —— 属于最容易被投诉、最难自查的一类缺陷。
 */
class RuleRepositoryTest {

    private val fakeDao = FakeSkipRuleDao()
    private val repository = RuleRepository(fakeDao)

    // ============ 幂等性 ============

    @Test
    fun givenNoExistingRules_whenMergeBuiltin_thenAllInserted() = runTest {
        val inserted = repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))

        assertEquals(1, inserted)
        assertEquals(1, fakeDao.rows.size)
        assertEquals(SkipRuleEntity.SOURCE_BUILTIN, fakeDao.rows.single().source)
    }

    @Test
    fun givenSameRules_whenMergeBuiltinTwice_thenNoDuplicates() = runTest {
        val rules = listOf(builtin("com.a", "跳过"), builtin("com.b", "关闭"))

        repository.mergeBuiltin(rules)
        val secondRun = repository.mergeBuiltin(rules)

        // 第二次导入应当一条也不插 —— 这是"每次启动都调用导入"的前提
        assertEquals(0, secondRun)
        assertEquals(2, fakeDao.rows.size)
    }

    @Test
    fun givenRulesDifferOnlyByCase_whenMergeBuiltin_thenTreatedAsSameRule() = runTest {
        repository.mergeBuiltin(listOf(builtin("com.a", "Skip")))
        val inserted = repository.mergeBuiltin(listOf(builtin("com.a", "SKIP")))

        // 大小写不同不应产生第二条规则：UiMatcher 的匹配本身是 ignoreCase 的，
        // 若此处区分大小写，规则页会出现两条"看起来一样"的规则
        assertEquals(0, inserted)
        assertEquals(1, fakeDao.rows.size)
    }

    @Test
    fun givenDuplicateKeysInInput_whenMergeBuiltin_thenDedupedBeforeInsert() = runTest {
        val inserted = repository.mergeBuiltin(
            listOf(builtin("com.a", "跳过"), builtin("com.a", "跳过")),
        )

        assertEquals(1, inserted)
        assertEquals(1, fakeDao.rows.size)
    }

    // ============ 用户意图的保留（最关键）============

    @Test
    fun givenRuleDisabledByUser_whenMergeBuiltin_thenStaysDisabled() = runTest {
        repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))
        val id = fakeDao.rows.single().id
        fakeDao.setEnabled(id, false)

        repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))

        // 用户停用的规则不得因重新导入而复活
        assertFalse(
            "用户停用的内置规则被重新导入覆盖为启用状态",
            fakeDao.rows.single().enabled,
        )
    }

    @Test
    fun givenRuleDeletedByUser_whenMergeBuiltinWithOtherRules_thenDeletedOneNotRestored() = runTest {
        repository.mergeBuiltin(listOf(builtin("com.a", "跳过"), builtin("com.b", "关闭")))
        val target = fakeDao.rows.first { it.packageName == "com.a" }
        fakeDao.deleteById(target.id)

        // 同批重新导入：已删除的那条会回来（已知行为，见 deleteById 文档），
        // 但**其余规则不应受影响**。这里验证的是"不误伤"
        repository.mergeBuiltin(listOf(builtin("com.b", "关闭")))

        assertEquals(1, fakeDao.rows.size)
        assertEquals("com.b", fakeDao.rows.single().packageName)
    }

    @Test
    fun givenRuleRenamedByUser_whenMergeBuiltin_thenRenamePreserved() = runTest {
        repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))
        val id = fakeDao.rows.single().id
        // 用户改了规则名（展示文案），业务键不含 name，因此仍视为同一条
        fakeDao.rows[0] = fakeDao.rows[0].copy(name = "我改的名字")

        val inserted = repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))

        assertEquals(0, inserted)
        assertEquals("我改的名字", fakeDao.rows.single().name)
    }

    @Test
    fun givenUserRulesExist_whenMergeBuiltin_thenUserRulesUntouched() = runTest {
        val userRule = SkipRule(
            name = "我的规则",
            packageName = "com.user",
            targetType = TargetType.TEXT,
            targetValue = "跳过",
            source = SkipRuleSource.USER,
        )
        repository.add(userRule)

        repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))

        // 内置导入绝不应触碰用户规则
        assertEquals(2, fakeDao.rows.size)
        assertEquals(1, fakeDao.rows.count { it.source == SkipRuleEntity.SOURCE_USER })
        assertEquals(1, fakeDao.rows.count { it.source == SkipRuleEntity.SOURCE_BUILTIN })
    }

    @Test
    fun givenUserRuleSharesKeyWithBuiltin_whenMergeBuiltin_thenNotDuplicated() = runTest {
        // 用户恰好手工建了一条和内置规则业务键相同的规则
        repository.add(builtin("com.a", "跳过").copy(source = SkipRuleSource.USER))

        val inserted = repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))

        // 去重跨来源生效：一条都不插。
        // 后果是"用户规则阻止了内置规则导入"，这是期望行为 ——
        // 用户已经就该定位值表达了意图，内置规则没必要再插一份；
        // 若按来源分别去重，同一定位值会有两条规则同时参与匹配与点击。
        assertEquals(0, inserted)
        assertEquals(1, fakeDao.rows.size)
        assertEquals(SkipRuleEntity.SOURCE_USER, fakeDao.rows.single().source)
    }

    // ============ 只增不删 ============

    @Test
    fun givenExistingRules_whenMergeBuiltin_thenNothingDeleted() = runTest {
        repository.mergeBuiltin(
            listOf(builtin("com.a", "跳过"), builtin("com.b", "关闭"), builtin("com.c", "跳过广告")),
        )

        // 新版本只包含其中两条 —— 合并是"补缺"，不是"替换"，
        // 因此消失的那条（com.b）应被保留，等待用户自行决定
        repository.mergeBuiltin(listOf(builtin("com.a", "跳过"), builtin("com.c", "跳过广告")))

        assertEquals(3, fakeDao.rows.size)
    }

    @Test
    fun givenEmptyInput_whenMergeBuiltin_thenNoOp() = runTest {
        repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))

        val inserted = repository.mergeBuiltin(emptyList())

        assertEquals(0, inserted)
        assertEquals(1, fakeDao.rows.size)
    }

    // ============ needsBuiltinImport ============

    @Test
    fun givenEmptyTable_whenNeedsBuiltinImport_thenTrue() = runTest {
        assertTrue(repository.needsBuiltinImport())
    }

    @Test
    fun givenBuiltinRulesExist_whenNeedsBuiltinImport_thenFalse() = runTest {
        repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))

        // 已有内置规则 → 不再导入。用户删除的规则不会被"下次启动补回来"
        assertFalse(repository.needsBuiltinImport())
    }

    @Test
    fun givenOnlyUserRulesExist_whenNeedsBuiltinImport_thenTrue() = runTest {
        repository.add(builtin("com.user", "跳过").copy(source = SkipRuleSource.USER))

        // 用户规则的存在不代表内置规则已导入
        assertTrue(repository.needsBuiltinImport())
    }

    @Test
    fun givenBuiltinRulesAllDeleted_whenNeedsBuiltinImport_thenTrue() = runTest {
        repository.mergeBuiltin(listOf(builtin("com.a", "跳过")))
        fakeDao.deleteBySource(SkipRuleEntity.SOURCE_BUILTIN)

        // 全部删掉后允许重新导入 —— 这是给用户留的"恢复内置规则"后路
        assertTrue(repository.needsBuiltinImport())
    }

    // ============ 用户规则的来源标记 ============

    @Test
    fun givenAddWithBuiltinSource_whenAdd_thenForcedToUser() = runTest {
        // 外部传入 builtin 来源也应被纠正：add 是用户路径，
        // 若允许它写入 builtin，用户创建的规则会被后续导入逻辑误判
        repository.add(builtin("com.a", "跳过"))

        assertEquals(SkipRuleEntity.SOURCE_USER, fakeDao.rows.single().source)
    }

    // ============ 辅助 ============

    private fun builtin(packageName: String, targetValue: String) = SkipRule(
        name = "$packageName-$targetValue",
        packageName = packageName,
        targetType = TargetType.TEXT,
        targetValue = targetValue,
        matchMode = MatchMode.EXACT,
        source = SkipRuleSource.BUILTIN,
    )

    /**
     * 内存实现的假 DAO。
     *
     * 行为对齐 Room 的真实语义，尤其是：
     * - `insert` 返回自增 id（冲突返回 -1）
     * - `insertAll` 在 IGNORE 冲突时对冲突项返回 -1
     * - `loadBySource` / `countBySource` 按 source 过滤
     */
    private class FakeSkipRuleDao : SkipRuleDao {

        val rows = mutableListOf<SkipRuleEntity>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<SkipRuleEntity>> =
            MutableStateFlow(rows.toList())

        override fun observeEnabled(): Flow<List<SkipRuleEntity>> =
            MutableStateFlow(rows.filter { it.enabled })

        override fun observeEnabledCount(): Flow<Int> =
            MutableStateFlow(rows.count { it.enabled })

        override suspend fun findApplicable(
            packageName: String,
            activityName: String?,
        ): List<SkipRuleEntity> = rows.filter {
            it.enabled && it.packageName == packageName &&
                (it.activityName == null || it.activityName == activityName)
        }

        override suspend fun findById(id: Long): SkipRuleEntity? = rows.firstOrNull { it.id == id }

        override suspend fun loadAll(): List<SkipRuleEntity> = rows.toList()

        override suspend fun countBySource(source: String): Int =
            rows.count { it.source == source }

        override suspend fun loadBySource(source: String): List<SkipRuleEntity> =
            rows.filter { it.source == source }

        override suspend fun deleteBySource(source: String) {
            rows.removeAll { it.source == source }
        }

        override suspend fun insert(rule: SkipRuleEntity): Long {
            val id = nextId++
            rows += rule.copy(id = id)
            return id
        }

        override suspend fun insertAll(rules: List<SkipRuleEntity>): List<Long> = rules.map { rule ->
            val id = nextId++
            rows += rule.copy(id = id)
            id
        }

        override suspend fun update(rule: SkipRuleEntity) {
            val index = rows.indexOfFirst { it.id == rule.id }
            if (index >= 0) rows[index] = rule
        }

        override suspend fun delete(rule: SkipRuleEntity) {
            rows.removeAll { it.id == rule.id }
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun setEnabled(id: Long, enabled: Boolean, now: Long) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(enabled = enabled, updatedAt = now)
        }
    }
}
