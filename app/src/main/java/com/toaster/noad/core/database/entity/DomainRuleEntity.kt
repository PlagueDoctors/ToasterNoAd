package com.toaster.noad.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 域名规则表（S2 / S3 / S4 共享）。
 *
 * 索引设计：
 * - `pattern` 唯一索引：避免重复导入同一域名
 * - `is_whitelist` 索引：匹配引擎按白名单优先加载
 */
@Entity(
    tableName = "domain_rule",
    indices = [
        Index(value = ["pattern", "is_whitelist"], unique = true),
        Index(value = ["is_whitelist"]),
        Index(value = ["enabled"]),
    ],
)
data class DomainRuleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 见 [com.toaster.noad.core.model.DomainMatchType] */
    @ColumnInfo(name = "match_type")
    val matchType: String,

    /** 归一化后的域名图案：小写、无尾点、无协议前缀 */
    @ColumnInfo(name = "pattern")
    val pattern: String,

    /** 见 [com.toaster.noad.core.model.DomainCategory] */
    @ColumnInfo(name = "category")
    val category: String,

    /** 是否为白名单。白名单优先于黑名单 */
    @ColumnInfo(name = "is_whitelist")
    val isWhitelist: Boolean = false,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /** 来源标识：builtin / imported / user */
    @ColumnInfo(name = "source")
    val source: String = SOURCE_USER,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SOURCE_BUILTIN = "builtin"
        const val SOURCE_IMPORTED = "imported"
        const val SOURCE_USER = "user"
    }
}
