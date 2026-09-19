package com.toaster.noad.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * S1 跳过规则表。
 *
 * 索引设计：按包名查询是 S1 热路径（每次无障碍事件都需要按包名取规则），
 * 因此对 packageName 建索引。
 */
@Entity(
    tableName = "skip_rule",
    indices = [
        Index(value = ["package_name"]),
        Index(value = ["package_name", "activity_name"]),
    ],
)
data class SkipRuleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "activity_name")
    val activityName: String? = null,

    /** 见 [com.toaster.noad.core.model.TargetType]，以 String 存储便于迁移与可读性 */
    @ColumnInfo(name = "target_type")
    val targetType: String,

    @ColumnInfo(name = "target_value")
    val targetValue: String,

    /** 见 [com.toaster.noad.core.model.MatchMode] */
    @ColumnInfo(name = "match_mode")
    val matchMode: String,

    @ColumnInfo(name = "click_delay_ms")
    val clickDelayMs: Long = 0L,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    @ColumnInfo(name = "priority")
    val priority: Int = 0,

    /**
     * 规则来源，见 [com.toaster.noad.core.model.SkipRuleSource]。
     *
     * 默认 `user` 而非 `builtin`：手工插入的数据在语义上都属于用户资产。
     * 内置导入流程必须显式传 `SOURCE_BUILTIN`，避免"忘了标记"导致
     * 内置规则被误当作可安全重建的集合。
     */
    @ColumnInfo(name = "source")
    val source: String = SOURCE_USER,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SOURCE_BUILTIN = "builtin"
        const val SOURCE_IMPORTED = "imported"
        const val SOURCE_USER = "user"
    }
}
