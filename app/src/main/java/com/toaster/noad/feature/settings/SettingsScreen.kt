package com.toaster.noad.feature.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
                            title = "开机自启动",
                            subtitle = "设备重启后自动恢复拦截",
                            checked = settings.autostart,
                            onToggle = { viewModel.setAutostart(!settings.autostart) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingToggleRow(
                            title = "拦截通知",
                            subtitle = "拦截时发送通知提醒",
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
