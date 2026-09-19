package com.toaster.noad.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * NoAd 数据库迁移集合。
 *
 * ## 为什么要单独成文件
 *
 * 迁移语句是**一次性、不可回滚、直接作用于用户既有数据**的代码。
 * 它们必须在应用整个生命周期内保留（用户可能从任意旧版本升级），
 * 因此不适合内联在 [NoAdDatabase] 里随其他改动一起被重写。
 *
 * ## 硬性约束
 *
 * 1. **禁止 `fallbackToDestructiveMigration()`**。该 API 会让缺迁移时
 *    静默清空用户数据。对 NoAd 而言，被清空的是用户的应用纳管列表与
 *    自定义规则 —— 用户会看到"配置莫名全部消失"，且无从恢复。
 * 2. 每个迁移都必须有对应的**同版本号 schema 文件**作为契约证据
 *    （`app/schemas/.../<n>.json`），并由迁移测试覆盖。
 * 3. `ALTER TABLE ... ADD COLUMN` 的 `DEFAULT` 必须与实体默认值一致，
 *    否则新老数据会出现"同一字段两种含义"。
 */
object Migrations {

    /**
     * v1 → v2：`skip_rule` 增加 `source` 列。
     *
     * ## 背景
     *
     * v1 的 `skip_rule` 表没有来源标记，导致内置规则与用户规则无法区分，
     * 而内置规则的导入逻辑（随版本更新、可整批替换）必须能识别它们。
     *
     * ## 默认值为什么是 `'user'`
     *
     * 存量数据全部来自 v1 —— 而 v1 **没有任何内置规则导入路径**
     * （`RuleRepository.add/addAll` 零调用点，assets 中也无 S1 规则文件）。
     * 因此升级上来的每一行都必然是用户手工产生的，标为 `'user'` 是
     * 对事实的准确描述，而不是一个"安全兜底"。
     *
     * 这一点很重要：若默认值写成 `'builtin'`，这些用户规则会在
     * 首次内置导入的"替换"步骤中被删除。
     *
     * ## 为什么不用 `NOT NULL DEFAULT` 后重建表
     *
     * SQLite 的 `ALTER TABLE ADD COLUMN` 支持带 `NOT NULL DEFAULT`，
     * 不触发 Room 要求的表重建。相比"建新表 → 拷数据 → 删旧表 → 改名"，
     * 它少一次全表复制，也不会在迁移过程中丢失索引定义。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `skip_rule` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'user'",
            )
        }
    }

    /**
     * 全部迁移，按版本顺序供 [NoAdDatabase] 注册。
     *
     * 用列表而非逐个 `.addMigrations()`：新增迁移时不易漏注册，
     * 且顺序在类型层面一目了然。
     */
    val ALL: List<Migration> = listOf(MIGRATION_1_2)
}
