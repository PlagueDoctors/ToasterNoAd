package com.toaster.noad.core.applist

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一条已安装应用信息。
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    /** 是否为系统应用 */
    val isSystemApp: Boolean,
    /** 用户可见（有启动入口） */
    val isLaunchable: Boolean,
)

/**
 * 已安装应用数据源。
 *
 * ## 权限要求
 *
 * Android 11（API 30）起引入包可见性限制。
 * 若要读取全量应用列表，必须在 Manifest 声明 `QUERY_ALL_PACKAGES`。
 * 本项目的使用场景（按应用配置拦截规则）属于该权限的合理用途。
 *
 * ## 线程
 *
 * `PackageManager` 查询是 IPC 且可能较慢（数百个应用时可达数百毫秒），
 * 因此强制在 [Dispatchers.IO] 执行，绝不阻塞主线程。
 */
class InstalledAppDataSource(private val context: Context) {

    private val packageManager: PackageManager
        get() = context.packageManager

    /**
     * 读取全部已安装应用（过滤掉自身）。
     *
     * @param includeSystemApps 是否包含系统应用。默认排除 —— 系统应用通常无广告，
     *   且对其做拦截容易引发系统异常。
     * @param launchableOnly 是否只保留有启动入口的应用。这类应用才可能有开屏广告。
     */
    suspend fun loadInstalledApps(
        includeSystemApps: Boolean = false,
        launchableOnly: Boolean = true,
    ): List<InstalledApp> = withContext(Dispatchers.IO) {
        val selfPackage = context.packageName
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)

        // 有启动入口的应用
        val launchable = packageManager
            .queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .associateBy { it.packageName }

        if (launchableOnly) {
            launchable.values
                .asSequence()
                .filter { it.packageName != selfPackage }
                .filter { includeSystemApps || !it.isSystemApp() }
                .map { it.toInstalledApp(isLaunchable = true) }
                .sortedBy { it.label }
                .toList()
        } else {
            packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != selfPackage }
                .filter { includeSystemApps || !it.isSystemApp() }
                .map { info ->
                    info.toInstalledApp(isLaunchable = launchable.containsKey(info.packageName))
                }
                .sortedBy { it.label }
                .toList()
        }
    }

    /**
     * 查询单个应用的展示名。
     *
     * 用于补全 TargetApp 的 label（当数据库记录的 label 缺失或应用改名时）。
     */
    suspend fun loadLabel(packageName: String): String? = withContext(Dispatchers.IO) {
        runCatching { packageManager.getApplicationLabel(getApplicationInfo(packageName)) }
            .getOrNull()
            ?.toString()
    }

    /** 该包名当前是否已安装 */
    suspend fun isInstalled(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { getApplicationInfo(packageName) }.isSuccess
    }

    /** 已安装应用的全部包名（用于清理已卸载的目标应用） */
    suspend fun loadAllPackageNames(): List<String> = withContext(Dispatchers.IO) {
        packageManager.getInstalledApplications(0).map { it.packageName }
    }

    private fun getApplicationInfo(packageName: String): ApplicationInfo =
        packageManager.getApplicationInfo(packageName, 0)

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun ApplicationInfo.toInstalledApp(isLaunchable: Boolean): InstalledApp =
        InstalledApp(
            packageName = packageName,
            label = runCatching { packageManager.getApplicationLabel(this).toString() }
                .getOrDefault(packageName),
            isSystemApp = isSystemAppCompat(),
            isLaunchable = isLaunchable,
        )

    private fun ApplicationInfo.isSystemAppCompat(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0
}
