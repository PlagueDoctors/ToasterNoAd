package com.toaster.noad.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.toaster.noad.core.database.entity.TargetAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetAppDao {

    @Query("SELECT * FROM target_app ORDER BY sort_order DESC, label ASC")
    fun observeAll(): Flow<List<TargetAppEntity>>

    @Query("SELECT * FROM target_app WHERE package_name = :packageName")
    suspend fun findByPackage(packageName: String): TargetAppEntity?

    @Query("SELECT * FROM target_app")
    suspend fun loadAll(): List<TargetAppEntity>

    @Query("SELECT package_name FROM target_app WHERE accessibility_enabled = 1")
    suspend fun loadAccessibilityEnabledPackages(): List<String>

    @Query("SELECT package_name FROM target_app WHERE app_firewall_enabled = 1")
    suspend fun loadAppFirewallEnabledPackages(): List<String>

    @Query("SELECT COUNT(*) FROM target_app WHERE accessibility_enabled = 1")
    fun observeAccessibilityEnabledCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM target_app")
    fun observeTotalCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: TargetAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<TargetAppEntity>)

    @Query("UPDATE target_app SET accessibility_enabled = :enabled, updated_at = :now WHERE package_name = :packageName")
    suspend fun setAccessibilityEnabled(
        packageName: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE target_app SET app_firewall_enabled = :enabled, updated_at = :now WHERE package_name = :packageName")
    suspend fun setAppFirewallEnabled(
        packageName: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM target_app WHERE package_name = :packageName")
    suspend fun deleteByPackage(packageName: String)

    /** 清理已卸载应用：仅保留当前仍安装的包 */
    @Query("DELETE FROM target_app WHERE package_name NOT IN (:installedPackages)")
    suspend fun pruneUninstalled(installedPackages: List<String>): Int

    @Query("DELETE FROM target_app")
    suspend fun deleteAll()
}
