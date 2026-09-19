package com.toaster.noad.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.TypeConverters
import com.toaster.noad.core.database.dao.DomainRuleDao
import com.toaster.noad.core.database.dao.InterceptLogDao
import com.toaster.noad.core.database.dao.SkipRuleDao
import com.toaster.noad.core.database.dao.TargetAppDao
import com.toaster.noad.core.database.entity.DomainRuleEntity
import com.toaster.noad.core.database.entity.InterceptLogEntity
import com.toaster.noad.core.database.entity.SkipRuleEntity
import com.toaster.noad.core.database.entity.TargetAppEntity

/**
 * NoAd 本地数据库。
 *
 * ## 迁移策略
 *
 * - 禁止使用 `fallbackToDestructiveMigration()` 掩盖迁移缺失
 * - 每次改表结构须新增 `Migration(n, n+1)`（写在 [Migrations] 中）并补充迁移测试
 * - schema 导出目录见 `app/schemas`（由 `room.schemaLocation` 指定）
 */
@Database(
    entities = [
        SkipRuleEntity::class,
        DomainRuleEntity::class,
        InterceptLogEntity::class,
        TargetAppEntity::class,
    ],
    version = NoAdDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class NoAdDatabase : RoomDatabase() {

    abstract fun skipRuleDao(): SkipRuleDao

    abstract fun domainRuleDao(): DomainRuleDao

    abstract fun interceptLogDao(): InterceptLogDao

    abstract fun targetAppDao(): TargetAppDao

    companion object {
        /**
         * 当前 schema 版本。
         *
         * 改动历史：
         * - v1：初始版本（四张表）
         * - v2：`skip_rule` 增加 `source` 列，区分内置/导入/用户规则
         *
         * 版本号只在此处定义，[Migrations] 中的迁移对象按此对照。
         */
        const val VERSION = 2

        private const val DATABASE_NAME = "noad.db"

        @Volatile
        private var instance: NoAdDatabase? = null

        /**
         * 获取数据库单例。
         *
         * 使用双重检查锁保证并发安全。数据库实例在整个进程生命周期内应唯一，
         * 因为 Room 内部维护连接池，重复创建会显著增加内存与 IO 开销。
         */
        fun getInstance(context: Context): NoAdDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * 构造数据库实例。
         *
         * `internal` 可见性是为了让迁移测试能自建实例（测试需要使用
         * 临时文件库并显式触发迁移），生产代码只走 [getInstance]。
         *
         * ## 为什么显式指定 WAL
         *
         * Room 默认 `JournalMode.AUTOMATIC`，在部分 OEM ROM 上会落到 TRUNCATE
         * 模式：每次写事务都要重建 journal 文件，并在提交时做一次 fsync 落盘。
         * 拦截日志恰好是「启动时高频小写入」的负载特征，这笔开销叠加在应用冷启动
         * 路径上就是可感知的卡顿。
         *
         * WAL（Write-Ahead Logging）把随机写转为对 `.wal` 文件的顺序追加，且读写
         * 互不阻塞——拦截写日志的同时 UI 查询规则/统计不再排队。对 50MB 级、
         * 单进程访问的本地库，这是纯粹的收益，不引入额外复杂度。
         *
         * 注意：WAL 会额外产生 `noad.db-wal` 与 `noad.db-shm` 两个附属文件，这是
         * 正常现象，不需要也不应该手动清理。
         */
        internal fun build(context: Context): NoAdDatabase =
            Room.databaseBuilder(context, NoAdDatabase::class.java, DATABASE_NAME)
                // 注意：不调用 fallbackToDestructiveMigration()，破坏性迁移必须显式声明
                .addMigrations(*Migrations.ALL.toTypedArray())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
