package com.toaster.noad.feature.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toaster.noad.R
import com.toaster.noad.core.model.AccessibilityState
import com.toaster.noad.core.navigation.NoAdViewModelFactory
import com.toaster.noad.ui.theme.NoAdTheme

/**
 * 设置页。
 *
 * 全部开关读写 DataStore，持久化生效。
 * 规则数量等只读信息从 Repository 实时读取，作为设置的「效果反馈」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onNavigateToRules: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = NoAdViewModelFactory.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings

    val context = LocalContext.current

    // 通知权限（Android 13+）：保活服务必须挂常驻通知，开启保活时顺手请求。
    // 拒绝不阻断保活 —— FGS 照常运行，仅通知不可见，无需二次引导。
    // 实现为普通 lambda 而非 @Composable 函数：调用点在 onToggle 回调
    // （非组合作用域）内，只有 lambda 属性能在那里被合法引用。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 授权结果无需处理，拒绝即静默 */ }
    val requestNotificationPermissionIfNeeded: () -> Unit = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("设置", fontWeight = FontWeight.SemiBold) })
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    SectionTitle("通用")
                    SettingsGroupCard {
                        SettingToggleRow(
                            title = stringResource(R.string.keep_alive_toggle_title),
                            subtitle = stringResource(
                                if (settings.keepAliveEnabled) {
                                    R.string.keep_alive_toggle_subtitle_on
                                } else {
                                    R.string.keep_alive_toggle_subtitle_off
                                },
                            ),
                            checked = settings.keepAliveEnabled,
                            onToggle = {
                                // SettingToggleRow 的回调无参，新值 = 当前值取反；
                                // 仅在「新状态为开启」时请求通知权限。
                                val enabled = !settings.keepAliveEnabled
                                viewModel.setKeepAliveEnabled(enabled)
                                if (enabled) requestNotificationPermissionIfNeeded()
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        BatteryExemptionRow()
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingToggleRow(
                            title = "开机自启动",
                            // 不能写「恢复拦截」：无障碍拦截由系统授权模型
                            // 在重启后自动重连，与本开关无关。
                            // 本开关的真实职责只是恢复前台服务保活。
                            subtitle = stringResource(R.string.keep_alive_autostart_subtitle),
                            checked = settings.autostart,
                            onToggle = { viewModel.setAutostart(!settings.autostart) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingToggleRow(
                            title = stringResource(R.string.intercept_status_toggle_title),
                            // 语义：控制常驻通知是否实时显示拦截动态（R10 接活）。
                            // 常驻通知本身是保活 FGS 的系统要求，不可移除；
                            // 关闭本开关只回退为静态保活文案。
                            subtitle = stringResource(R.string.intercept_status_toggle_subtitle),
                            checked = settings.showNotification,
                            onToggle = {
                                viewModel.setShowNotification(!settings.showNotification)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingToggleRow(
                            title = "深色主题",
                            subtitle = "当前主题偏好",
                            checked = settings.darkTheme,
                            onToggle = { viewModel.setDarkTheme(!settings.darkTheme) },
                        )
                    }
                }
            }

            item {
                Column {
                    SectionTitle("拦截策略")
                    SettingsGroupCard {
                        SettingToggleRow(
                            title = stringResource(R.string.a11y_card_title),
                            subtitle = accessibilitySubtitle(uiState.accessibility),
                            checked = uiState.settings.accessibilityEnabled,
                            onToggle = {
                                viewModel.setAccessibilityEnabled(
                                    !uiState.settings.accessibilityEnabled,
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingClickRow(
                            title = "域名过滤（S2/S3）",
                            subtitle = "阶段 C/D 接入",
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingClickRow(
                            title = "应用级断网（S4）",
                            subtitle = "阶段 F 接入，需 Shizuku",
                        )
                    }
                }
            }

            item {
                Column {
                    SectionTitle("规则")
                    SettingsGroupCard {
                        SettingClickRow(
                            title = "无障碍跳过规则",
                            subtitle = "${uiState.skipRuleCount} 条已启用",
                            onClick = onNavigateToRules,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingClickRow(
                            title = "域名黑名单",
                            subtitle = "${uiState.domainBlacklistCount} 条",
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingClickRow(
                            title = "域名白名单",
                            subtitle = "${uiState.domainWhitelistCount} 条（优先于黑名单）",
                        )
                    }
                }
            }

            item {
                Column {
                    SectionTitle("增强能力")
                    SettingsGroupCard {
                        SettingToggleRow(
                            title = "Shizuku 增强",
                            subtitle = "启用应用级断网等特权能力（需安装 Shizuku）",
                            checked = settings.shizukuEnhancementsEnabled,
                            onToggle = {
                                viewModel.setShizukuEnhancementsEnabled(
                                    !settings.shizukuEnhancementsEnabled,
                                )
                            },
                        )
                    }
                }
            }

            item {
                Column {
                    SectionTitle("关于")
                    SettingsGroupCard {
                        SettingClickRow(
                            title = "日志保留",
                            subtitle = "${settings.logRetentionDays} 天，上限 ${settings.logCapacity} 条",
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingClickRow(title = "关于 NoAd", subtitle = "v1.0")
                    }
                }
            }
        }
    }
}

/**
 * 无障碍开关的副标题。
 *
 * ## 为什么要写成三段式文案
 *
 * 这里的开关只是「应用内开关」，真正的生效还依赖系统授权。
 * 若只显示"开启/关闭"，用户打开开关后若没去系统设置授权，
 * 会以为已经生效 —— 这是最容易造成误解的地方。
 * 因此副标题必须说清当前卡在哪一环。
 */
@Composable
private fun accessibilitySubtitle(state: AccessibilityState): String = when {
    state.isEffectivelyActive ->
        stringResource(R.string.a11y_status_running)

    state.serviceRunning ->
        "服务已授权，但应用内开关未开启"

    // 已授权但服务实例被系统解绑 —— 与"没授权"是两回事，必须分开说。
    // 写成同一句会让用户多跑一趟设置，而其实他什么都不用做。
    state.isDisconnectedButAuthorized ->
        stringResource(R.string.a11y_status_enabled_not_connected)

    state.appSwitchEnabled ->
        "需要在系统设置中授权无障碍服务"

    else ->
        "自动关闭开屏与弹窗广告"
}

@Composable
private fun SectionTitle(text: String) {    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * 电池优化豁免入口行。
 *
 * 豁免后本应用进入官方「FGS 后台启动豁免名单」，且在激进省电 ROM
 * （如厂商一键清理）下保活链的存活概率显著提升 —— 这是 R9 保活的
 * 关键加固项，但状态由系统掌管，应用内开关只能展示、不能写入。
 */
@SuppressLint("BatteryLife")
@Composable
private fun BatteryExemptionRow() {
    val context = LocalContext.current
    var exempted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // ON_RESUME 刷新：用户点了本行去系统页授权，返回时状态才真正变化。
    // 用 LifecycleEventObserver 而非 LaunchedEffect 的原因同 HomeScreen ——
    // 后者只在首次组合时执行一次，感知不到「从系统设置返回」。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempted = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.keep_alive_battery_title)) },
        supportingContent = {
            Text(
                stringResource(
                    if (exempted) {
                        R.string.keep_alive_battery_exempted
                    } else {
                        R.string.keep_alive_battery_not_exempted
                    },
                ),
            )
        },
        modifier = Modifier.clickable {
            // 优先拉起针对本应用的精准授权对话框（需
            // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，Manifest 已声明）；
            // 少数 ROM 移除了该对话框，失败时退回系统豁免列表页。
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}")),
                )
            }.onFailure {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        },
    )
}

/** 查询本应用是否已列入电池优化豁免名单。系统服务异常时按「未豁免」处理。 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
}

@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { text -> { Text(text) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = { onToggle() }) },
    )
}

/**
 * 可点击的设置行。
 *
 * @param onClick 为 `null` 时渲染为**不可点击**的纯展示行。
 *
 * 这个区分是有意保留的：域名黑名单、白名单、日志保留、关于 等行
 * 目前确实没有可跳转的目标页。给它们传一个空 lambda 会让整行
 * 出现涟漪反馈却毫无反应 —— 用户会认为"点了没生效"，
 * 比明确地不可点击更糟。
 */
@Composable
private fun SettingClickRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    if (onClick == null) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
        )
    } else {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsRoutePreview() {
    NoAdTheme(darkTheme = true) { SettingsRoute() }
}
