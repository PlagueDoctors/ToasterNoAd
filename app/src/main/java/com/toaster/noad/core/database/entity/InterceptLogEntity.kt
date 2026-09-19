package com.toaster.noad.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 拦截日志表。
 *
 * 这是写入最频繁的表（S1 每个广告事件一条、S2/S3 每次命中一条），
 * 因此：
 * 1. 只建必要的索引（timestamp 用于按时间倒序查询与清理）
 * 2. 不建外键，避免写入时的额外校验开销
 * 3. 由 [com.toaster.noad.core.data.repository.LogRepository] 负责容量上限与定期清理
 */
@Entity(
    tableName = "intercept_log",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["package_name"]),
        Index(value = ["source"]),
    ],
)
data class InterceptLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "package_name")
    val packageName: String? = null,

    /** 冗余存应用名，日志页直接展示，避免联表 */
    @ColumnInfo(name = "app_label")
    val appLabel: String,

    /** 见 [com.toaster.noad.core.model.AdType] */
    @ColumnInfo(name = "ad_type")
    val adType: String,

    /** 见 [com.toaster.noad.core.model.InterceptSource] */
    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "rule_detail")
    val ruleDetail: String? = null,

    /** 事件时间戳（毫秒） */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
)
