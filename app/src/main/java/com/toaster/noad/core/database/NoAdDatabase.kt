package com.toaster.noad.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
 * 当前为版本 1（初始版本），**尚未提供任何 Migration**。
 * 在进入需要改表结构的阶段前，必须遵守：
 * - 禁止使用 `fallbackToDestructiveMigration()` 掩盖迁移缺失
 * - 每次改表结构须新增 `Migration(n, n+1)` 并补充迁移测试
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
        const val VERSION = 1

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

        private fun build(context: Context): NoAdDatabase =
            Room.databaseBuilder(context, NoAdDatabase::class.java, DATABASE_NAME)
                // 注意：不调用 fallbackToDestructiveMigration()，破坏性迁移必须显式声明
                .build()
    }
}
