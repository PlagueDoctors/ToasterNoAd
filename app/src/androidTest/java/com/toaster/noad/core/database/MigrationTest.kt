package com.toaster.noad.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库迁移测试。
 *
 * ## 为什么迁移必须被测试
 *
 * 迁移是**唯一直接作用于用户既有数据、且不可回滚**的代码。
 * `ALTER TABLE` 写错了在开发期毫无症状（新装用户不会触达迁移路径），
 * 只有从旧版本升级上来的用户才会遇到 —— 而此时损坏已经发生，
 * 用户看到的是"配置全部消失"或应用启动即崩溃。
 *
 * ## 覆盖的关键点
 *
 * 1. 迁移语句本身能在真实 SQLite 上执行
 * 2. 存量数据被保留，且 `source` 列的默认值是 `'user'`（不是 `'builtin'`）
 * 3. 迁移后的 schema 与 Room 期望的 v2 schema 完全一致
 *    —— 由 `MigrationTestHelper` 在 `validateMigration` 内部完成比对
 *
 * > 需要真机或模拟器：SQLite 的实际行为无法在 JVM 上复现。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NoAdDatabase::class.java,
    )

    /**
     * v1 建表语句（`skip_rule`，**不含** `source` 列）。
     *
     * 手工书写而非从 schema 文件读取：测的是"迁移能处理真实的 v1 表结构"，
     * 因此这里的 DDL 必须与 v1 实际产出的一致。若这里写错，
     * 测试会通过但真实升级会失败 —— 因此断言后还要靠
     * [validateMigration] 与 Room 的 v2 期望 schema 对账。
     */
    private val createSkipRuleV1 = """
        CREATE TABLE IF NOT EXISTS `skip_rule` (
            `id` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `package_name` TEXT NOT NULL,
            `activity_name` TEXT,
            `target_type` TEXT NOT NULL,
            `target_value` TEXT NOT NULL,
            `match_mode` TEXT NOT NULL,
            `click_delay_ms` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL,
            `priority` INTEGER NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
    """

    @Test
    fun migrate1To2_createsSourceColumn() {
        // 注意：**不预置任何数据**。本测试只验证"列被正确加上、且默认值为 user"，
        // 与数据保留相关的契约由下面两个测试覆盖。空表恰好能干净地证明
        // 默认值来自 DDL 而非来自测试插入的值。
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(createSkipRuleV1)
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            Migrations.MIGRATION_1_2,
        )

        db.query("PRAGMA table_info(skip_rule)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")

            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "source") {
                    found = true
                    assertEquals("'user'", cursor.getString(defaultIndex))
                    assertEquals(1, cursor.getInt(notNullIndex))
                }
            }
            assertTrue("迁移后 skip_rule 表缺少 source 列", found)
        }
        db.close()
    }

    @Test
    fun migrate1To2_appliesUserSourceToRowsInsertedAfterMigration() {
        // 迁移后的新行若不显式给 source，也必须落到 'user'。
        // 这是 DDL 默认值在真实插入路径上的验证 —— 仅检查 PRAGMA
        // 只能证明"DDL 写对了"，不能证明"插入时真的生效"。
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(createSkipRuleV1)
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            Migrations.MIGRATION_1_2,
        )

        db.execSQL(
            """
            INSERT INTO skip_rule
            (name, package_name, activity_name, target_type, target_value,
             match_mode, click_delay_ms, enabled, priority, created_at, updated_at)
            VALUES ('迁移后新增', 'com.example', NULL, 'TEXT', '跳过',
                    'EXACT', 0, 1, 0, 2000, 2000)
            """,
        )

        db.query("SELECT source FROM skip_rule WHERE name = '迁移后新增'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("user", cursor.getString(0))
        }
        db.close()
    }

    @Test
    fun migrate1To2_preservesExistingRowsWithUserSource() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(createSkipRuleV1)
            // 模拟 v1 用户的真实数据。v1 没有任何内置规则导入路径，
            // 因此存量行必然是用户手工产生的 —— 默认值必须是 'user'。
            execSQL(
                """
                INSERT INTO skip_rule
                (id, name, package_name, activity_name, target_type, target_value,
                 match_mode, click_delay_ms, enabled, priority, created_at, updated_at)
                VALUES (1, '我的规则', 'com.example', NULL, 'TEXT', '跳过',
                        'EXACT', 0, 1, 0, 1000, 1000)
                """,
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            Migrations.MIGRATION_1_2,
        )

        db.query(
            "SELECT name, package_name, target_value, source, enabled FROM skip_rule",
        ).use { cursor ->
            assertTrue("迁移后存量数据丢失", cursor.moveToFirst())
            assertEquals(1, cursor.count)
            assertEquals("我的规则", cursor.getString(0))
            assertEquals("com.example", cursor.getString(1))
            assertEquals("跳过", cursor.getString(2))
            // 关键断言：默认值为 user。若为 builtin，这些用户规则
            // 会在首次内置导入的"替换"步骤中被删除。
            assertEquals("user", cursor.getString(3))
            assertEquals(1, cursor.getInt(4))
        }
        db.close()
    }

    @Test
    fun migrate1To2_keepsDisabledStateOfExistingRules() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(createSkipRuleV1)
            execSQL(
                """
                INSERT INTO skip_rule
                (id, name, package_name, activity_name, target_type, target_value,
                 match_mode, click_delay_ms, enabled, priority, created_at, updated_at)
                VALUES (7, '已停用规则', 'com.example', NULL, 'TEXT', '跳过',
                        'EXACT', 0, 0, 0, 1000, 1000)
                """,
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            Migrations.MIGRATION_1_2,
        )

        db.query("SELECT enabled FROM skip_rule WHERE id = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // 用户的停用状态不得被迁移重置
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate1To2_keepsIndicesUsable() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(createSkipRuleV1)
            // v1 的两个索引必须一并建出：ALTER TABLE ADD COLUMN 不应破坏它们
            execSQL("CREATE INDEX IF NOT EXISTS `index_skip_rule_package_name` ON `skip_rule` (`package_name`)")
            execSQL(
                "CREATE INDEX IF NOT EXISTS `index_skip_rule_package_name_activity_name` " +
                    "ON `skip_rule` (`package_name`, `activity_name`)",
            )
            close()
        }

        // validateMigration 会比对索引定义，索引丢失即失败
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            Migrations.MIGRATION_1_2,
        )
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
