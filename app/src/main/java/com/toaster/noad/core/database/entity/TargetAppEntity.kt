package com.toaster.noad.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 目标应用表。
 *
 * 只存储用户在 NoAd 中显式纳管的应用（稀疏集合），
 * 而非全量已安装应用 —— 后者由 [com.toaster.noad.core.applist.InstalledAppDataSource] 实时读取。
 */
@Entity(
    tableName = "target_app",
    indices = [
        Index(value = ["sort_order"]),
    ],
)
data class TargetAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "label")
    val label: String,

    /** 是否启用 S1 无障碍拦截 */
    @ColumnInfo(name = "accessibility_enabled")
    val accessibilityEnabled: Boolean = true,

    /** 是否启用 S2/S3 网络层拦截 */
    @ColumnInfo(name = "network_filter_enabled")
    val networkFilterEnabled: Boolean = true,

    /** 是否纳入 S4 应用级断网（Shizuku 增强，默认关闭） */
    @ColumnInfo(name = "app_firewall_enabled")
    val appFirewallEnabled: Boolean = false,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
