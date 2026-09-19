package com.toaster.noad.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toaster.noad.core.model.NetworkFilterMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore 扩展属性。
 *
 * 必须在**文件顶层**声明，保证同一进程内只创建一个 DataStore 实例；
 * 否则多个实例同时写同一文件会抛出 `IllegalStateException`。
 */
private val Context.noAdDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "noad_settings",
)

/**
 * 应用设置持久化。
 *
 * 存储内容为**轻量配置**（开关、模式、阈值）。
 * 规则、日志、目标应用等结构化数据一律走 Room，不放这里。
 */
class SettingsRepository(private val context: Context) {

    private val store = context.noAdDataStore

    /** 设置快照（对外暴露的完整状态） */
    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            protectionEnabled = prefs[KEY_PROTECTION_ENABLED] ?: false,
            accessibilityEnabled = prefs[KEY_ACCESSIBILITY_ENABLED] ?: false,
            networkFilterMode = prefs[KEY_NETWORK_FILTER_MODE]
                ?.let { runCatching { NetworkFilterMode.valueOf(it) }.getOrNull() }
                ?: NetworkFilterMode.OFF,
            vpnYielded = prefs[KEY_VPN_YIELDED] ?: false,
            shizukuEnhancementsEnabled = prefs[KEY_SHIZUKU_ENABLED] ?: false,
            autostart = prefs[KEY_AUTOSTART] ?: false,
            keepAliveEnabled = prefs[KEY_KEEP_ALIVE] ?: DEFAULT_KEEP_ALIVE,
            showNotification = prefs[KEY_SHOW_NOTIFICATION] ?: true,
            darkTheme = prefs[KEY_DARK_THEME] ?: true,
            logRetentionDays = prefs[KEY_LOG_RETENTION_DAYS] ?: DEFAULT_RETENTION_DAYS,
            logCapacity = prefs[KEY_LOG_CAPACITY] ?: DEFAULT_LOG_CAPACITY,
        )
    }

    val protectionEnabled: Flow<Boolean> =
        store.data.map { it[KEY_PROTECTION_ENABLED] ?: false }

    /**
     * S1 无障碍拦截是否启用（用户意图）。
     *
     * 供 `ProtectionFlags` 同步到内存镜像 —— 无障碍事件回调在主线程，
     * 无法在此处做磁盘 IO。
     */
    val accessibilityEnabled: Flow<Boolean> =
        store.data.map { it[KEY_ACCESSIBILITY_ENABLED] ?: false }

    val networkFilterMode: Flow<NetworkFilterMode> = store.data.map { prefs ->
        prefs[KEY_NETWORK_FILTER_MODE]
            ?.let { runCatching { NetworkFilterMode.valueOf(it) }.getOrNull() }
            ?: NetworkFilterMode.OFF
    }

    val darkTheme: Flow<Boolean> = store.data.map { it[KEY_DARK_THEME] ?: true }

    /**
     * 后台保活是否开启（前台服务 + 被杀自启）。
     *
     * 默认开启：该功能的存在意义是「用户装了 NoAd 就默认被保护」，
     * 关闭是用户的显式选择。
     *
     * 消费方有两类：
     * - [com.toaster.noad.NoAdApplication] 的观察者（启停前台服务）
     * - [com.toaster.noad.core.service.keepalive.KeepAliveReceiver]
     *   （开机 / 更新 / 心跳时读取权威值做恢复决策）
     */
    val keepAliveEnabled: Flow<Boolean> =
        store.data.map { it[KEY_KEEP_ALIVE] ?: DEFAULT_KEEP_ALIVE }

    /**
     * 开机自启动（恢复保活）是否开启。
     *
     * 独立于 [keepAliveEnabled]：保活管「运行期间不被杀、被杀自启」，
     * 本开关管「设备重启后是否自动恢复保活」。
     * 只被 BOOT_COMPLETED 路径读取。
     */
    val autostart: Flow<Boolean> =
        store.data.map { it[KEY_AUTOSTART] ?: false }

    suspend fun setProtectionEnabled(enabled: Boolean) {
        store.edit { it[KEY_PROTECTION_ENABLED] = enabled }
    }

    suspend fun setAccessibilityEnabled(enabled: Boolean) {
        store.edit { it[KEY_ACCESSIBILITY_ENABLED] = enabled }
    }

    suspend fun setNetworkFilterMode(mode: NetworkFilterMode) {
        store.edit { it[KEY_NETWORK_FILTER_MODE] = mode.name }
    }

    /**
     * 记录「已让位给其他 VPN」。
     *
     * 注意：这是**状态标记**，不是自动恢复开关。
     * 被让位后是否恢复由用户在 UI 上显式触发（见 VpnArbitrator 设计约束）。
     */
    suspend fun setVpnYielded(yielded: Boolean) {
        store.edit { it[KEY_VPN_YIELDED] = yielded }
    }

    suspend fun setShizukuEnhancementsEnabled(enabled: Boolean) {
        store.edit { it[KEY_SHIZUKU_ENABLED] = enabled }
    }

    suspend fun setAutostart(enabled: Boolean) {
        store.edit { it[KEY_AUTOSTART] = enabled }
    }

    suspend fun setKeepAliveEnabled(enabled: Boolean) {
        store.edit { it[KEY_KEEP_ALIVE] = enabled }
    }

    suspend fun setShowNotification(enabled: Boolean) {
        store.edit { it[KEY_SHOW_NOTIFICATION] = enabled }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        store.edit { it[KEY_DARK_THEME] = enabled }
    }

    suspend fun setLogRetentionDays(days: Int) {
        store.edit { it[KEY_LOG_RETENTION_DAYS] = days.coerceIn(1, 365) }
    }

    suspend fun setLogCapacity(capacity: Int) {
        store.edit { it[KEY_LOG_CAPACITY] = capacity.coerceIn(100, 100_000) }
    }

    /** 清空全部设置（恢复默认） */
    suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7
        const val DEFAULT_LOG_CAPACITY = 5_000

        /** 后台保活默认值：开启（关闭是用户的显式选择） */
        const val DEFAULT_KEEP_ALIVE = true

        private val KEY_PROTECTION_ENABLED = booleanPreferencesKey("protection_enabled")
        private val KEY_ACCESSIBILITY_ENABLED = booleanPreferencesKey("accessibility_enabled")
        private val KEY_NETWORK_FILTER_MODE = stringPreferencesKey("network_filter_mode")
        private val KEY_VPN_YIELDED = booleanPreferencesKey("vpn_yielded")
        private val KEY_SHIZUKU_ENABLED = booleanPreferencesKey("shizuku_enhancements_enabled")
        private val KEY_AUTOSTART = booleanPreferencesKey("autostart")
        private val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive_enabled")
        private val KEY_SHOW_NOTIFICATION = booleanPreferencesKey("show_notification")
        private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        private val KEY_LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")
        private val KEY_LOG_CAPACITY = intPreferencesKey("log_capacity")
    }
}

/**
 * 应用设置快照。
 */
data class AppSettings(
    val protectionEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val networkFilterMode: NetworkFilterMode = NetworkFilterMode.OFF,
    val vpnYielded: Boolean = false,
    val shizukuEnhancementsEnabled: Boolean = false,
    val autostart: Boolean = false,
    val keepAliveEnabled: Boolean = SettingsRepository.DEFAULT_KEEP_ALIVE,
    val showNotification: Boolean = true,
    val darkTheme: Boolean = true,
    val logRetentionDays: Int = SettingsRepository.DEFAULT_RETENTION_DAYS,
    val logCapacity: Int = SettingsRepository.DEFAULT_LOG_CAPACITY,
)
