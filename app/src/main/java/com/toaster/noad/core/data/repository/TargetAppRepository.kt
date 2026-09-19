package com.toaster.noad.core.data.repository

import com.toaster.noad.core.applist.InstalledAppDataSource
import com.toaster.noad.core.database.dao.TargetAppDao
import com.toaster.noad.core.database.entity.TargetAppEntity
import com.toaster.noad.core.database.toDomain
import com.toaster.noad.core.database.toEntity
import com.toaster.noad.core.model.TargetApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 目标应用仓库。
 *
 * ## 两种数据的关系
 *
 * - **已安装应用**（[InstalledAppDataSource]）：全量、实时、来自系统
 * - **目标应用**（本仓库）：稀疏、持久、来自用户选择
 *
 * 应用管理页需要把两者合并：以已安装列表为底，叠加用户对每个应用的纳管开关。
 * 该合并逻辑放在 [merge] 中，避免 UI 层重复实现。
 */
class TargetAppRepository(
    private val dao: TargetAppDao,
    private val installedAppDataSource: InstalledAppDataSource,
) {

    fun observeTargetApps(): Flow<List<TargetApp>> =
        dao.observeAll().map { list -> list.map(TargetAppEntity::toDomain) }

    fun observeAccessibilityEnabledCount(): Flow<Int> = dao.observeAccessibilityEnabledCount()

    /**
     * 观察已启用无障碍拦截的纳管应用（含完整字段）。
     *
     * 供 S1 内存规则缓存订阅。与 [observeAccessibilityEnabledCount] 的区别：
     * 后者只返回数量用于统计展示，本方法返回实体用于构建匹配快照。
     */
    fun observeEnabledForAccessibility(): Flow<List<TargetApp>> =
        dao.observeAccessibilityEnabled().map { list -> list.map(TargetAppEntity::toDomain) }

    fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()

    suspend fun loadAll(): List<TargetApp> = dao.loadAll().map(TargetAppEntity::toDomain)

    suspend fun findByPackage(packageName: String): TargetApp? =
        dao.findByPackage(packageName)?.toDomain()

    suspend fun loadAccessibilityEnabledPackages(): List<String> =
        dao.loadAccessibilityEnabledPackages()

    suspend fun loadAppFirewallEnabledPackages(): List<String> =
        dao.loadAppFirewallEnabledPackages()

    /** 纳管一个应用（若已存在则覆盖） */
    suspend fun upsert(app: TargetApp) = dao.upsert(app.toEntity())

    suspend fun setAccessibilityEnabled(packageName: String, enabled: Boolean) =
        dao.setAccessibilityEnabled(packageName, enabled)

    suspend fun setAppFirewallEnabled(packageName: String, enabled: Boolean) =
        dao.setAppFirewallEnabled(packageName, enabled)

    suspend fun remove(packageName: String) = dao.deleteByPackage(packageName)

    /**
     * 清理已卸载的应用。
     *
     * 应在应用启动时调用：用户可能在 NoAd 之外卸载了某应用，
     * 若不清理由其产生的拦截统计与规则会残留。
     */
    suspend fun pruneUninstalled(): Int {
        val installed = installedAppDataSource.loadAllPackageNames()
        return if (installed.isEmpty()) 0 else dao.pruneUninstalled(installed)
    }

    /**
     * 合并「已安装应用」与「用户纳管状态」，供应用管理页展示。
     *
     * @param query 搜索关键词，匹配应用名或包名；空串表示不过滤
     */
    suspend fun merge(
        query: String = "",
        includeSystemApps: Boolean = false,
    ): List<ManagedApp> {
        val installed = installedAppDataSource.loadInstalledApps(
            includeSystemApps = includeSystemApps,
            launchableOnly = true,
        )
        val managed = dao.loadAll().associateBy { it.packageName }

        return installed
            .asSequence()
            .filter { app ->
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .map { app ->
                val record = managed[app.packageName]
                ManagedApp(
                    packageName = app.packageName,
                    label = app.label,
                    isSystemApp = app.isSystemApp,
                    managed = record != null,
                    accessibilityEnabled = record?.accessibilityEnabled ?: false,
                    appFirewallEnabled = record?.appFirewallEnabled ?: false,
                )
            }
            .sortedWith(compareByDescending<ManagedApp> { it.managed }.thenBy { it.label })
            .toList()
    }
}

/**
 * 应用管理页的合并视图模型：已安装应用 + 用户纳管状态。
 */
data class ManagedApp(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    /** 用户是否已纳管该应用 */
    val managed: Boolean,
    /** S1 无障碍拦截是否启用 */
    val accessibilityEnabled: Boolean,
    /** S4 应用级断网是否启用 */
    val appFirewallEnabled: Boolean,
)
