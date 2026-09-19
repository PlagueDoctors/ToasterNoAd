package com.toaster.noad.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toaster.noad.core.designsystem.EmptyState
import com.toaster.noad.core.designsystem.NoAdSection
import com.toaster.noad.core.designsystem.SectionHeader
import com.toaster.noad.core.designsystem.StatCard
import com.toaster.noad.core.model.AccessibilityState
import com.toaster.noad.core.model.InterceptSource
import com.toaster.noad.core.navigation.NoAdViewModelFactory
import com.toaster.noad.ui.theme.NoAdTheme
import com.toaster.noad.R

/**
 * 首页。
 *
 * 结构：统计卡 → 总开关 → 三策略状态 → 高频拦截应用
 *
 * 设计说明：三策略状态是首页的核心信息 —— 用户需要一眼看出
 * 「无障碍」「网络过滤」各自是否真的在生效，而不是只有一个笼统的总开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    onNavigateToLogs: () -> Unit = {},
    onNavigateToApps: () -> Unit = {},
    onNavigateToNetwork: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = NoAdViewModelFactory.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val blockedBySource by viewModel.blockedBySource.collectAsStateWithLifecycle()

    // 从系统设置返回时重新核对授权状态。
    // 系统没有提供"无障碍服务被开关"的广播，因此只能在回到前台时主动查询。
    // 用 LifecycleEventObserver 而非 LaunchedEffect 的原因：
    // 后者只在首次进入组合时执行一次，无法感知"从设置页返回"。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("NoAd", fontWeight = FontWeight.SemiBold) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    StatCard(
                        value = uiState.todayBlocked.toString(),
                        unit = "次广告",
                        subtitle = "今日累计拦截",
                        progress = 1f,
                    )
                }
            }

            item {
                ProtectionToggleCard(
                    enabled = uiState.protectionEnabled,
                    onToggle = viewModel::toggleProtection,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            // ---- 拦截策略 ----
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SectionHeader(
                        title = "拦截策略",
                        subtitle = "共 ${InterceptSource.entries.size} 种",
                    )
                    Spacer(Modifier.height(12.dp))
                    StrategyStatusList(
                        protectionEnabled = uiState.protectionEnabled,
                        accessibility = uiState.accessibility,
                        bySource = blockedBySource,
                    )
                }
            }

            // ---- 无障碍未开启时的引导 ----
            // 只在「总开关已开但无障碍未生效」时展示，
            // 避免用户尚未启用保护就被引导去改系统设置
            if (uiState.protectionEnabled && !uiState.accessibility.isEffectivelyActive) {
                item {
                    AccessibilityGuideCard(
                        state = uiState.accessibility,
                        onToggleAppSwitch = {
                            viewModel.setAccessibilityEnabled(
                                !uiState.accessibility.appSwitchEnabled,
                            )
                        },
                        onOpenSystemSettings = {
                            onOpenAccessibilitySettings()
                        },
                        onRefresh = viewModel::refreshAccessibilityState,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = viewModel::toggleProtection,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = if (uiState.protectionEnabled) "停止拦截" else "立即拦截",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    OutlinedButton(
                        onClick = onNavigateToNetwork,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("网络过滤")
                    }
                }
            }

            // ---- 高频拦截应用 ----
            if (uiState.topApps.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        SectionHeader(
                            title = "今日拦截排行",
                            subtitle = "受保护 ${uiState.protectedAppCount} / ${uiState.totalAppCount}",
                        )
                    }
                }
                items(
                    items = uiState.topApps,
                    key = { it.packageName },
                ) { app ->
                    BlockedAppRow(app = app, modifier = Modifier.padding(horizontal = 20.dp))
                }
            } else if (!uiState.isLoading) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        NoAdSection(title = "今日拦截排行") {
                            EmptyState(
                                icon = Icons.Rounded.Block,
                                message = "暂无拦截记录\n启用拦截后这里会显示统计",
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                            )
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onNavigateToApps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("管理受保护应用")
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.height(18.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/**
 * 总开关卡片。
 */
@Composable
private fun ProtectionToggleCard(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.toaster.noad.core.designsystem.IconBadge(
                icon = Icons.Rounded.Shield,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                containerColor = if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                size = 48.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) "保护已开启" else "保护未开启",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "实时监测应用启动广告",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { onToggle() })
        }
    }
}

/**
 * 策略状态列表。
 *
 * 诚实标注：即便总开关打开，各策略也各自可能有未生效的原因
 * （无障碍未授权、VPN 让位、Shizuku 未安装）。此处如实展示，
 * 而不是笼统显示「已保护」。
 *
 * S1 已接入真实运行状态；S2/S3/S4 在后续阶段接入，
 * 当前明确显示为「未接入」而不是伪造成「运行中」。
 */
@Composable
private fun StrategyStatusList(
    protectionEnabled: Boolean,
    accessibility: AccessibilityState,
    bySource: Map<InterceptSource, Int>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InterceptSource.entries.forEach { source ->
            val (active, implemented) = when (source) {
                InterceptSource.ACCESSIBILITY ->
                    accessibility.isEffectivelyActive to true

                // 以下三种策略尚未实现，明确标记为未接入
                InterceptSource.DNS,
                InterceptSource.VPN,
                InterceptSource.APP_FIREWALL,
                -> false to false
            }

            StrategyStatusRow(
                source = source,
                active = active,
                implemented = implemented,
                protectionEnabled = protectionEnabled,
                todayCount = bySource[source] ?: 0,
            )
        }
    }
}

@Composable
private fun StrategyStatusRow(
    source: InterceptSource,
    active: Boolean,
    implemented: Boolean,
    protectionEnabled: Boolean,
    todayCount: Int,
) {
    val icon: ImageVector = when (source) {
        InterceptSource.ACCESSIBILITY -> Icons.Rounded.Shield
        InterceptSource.DNS -> Icons.Rounded.Dns
        InterceptSource.VPN -> Icons.Rounded.VpnLock
        InterceptSource.APP_FIREWALL -> Icons.Rounded.Block
    }

    val statusText = when {
        !protectionEnabled -> "未启用"
        active -> "运行中"
        // 区分"已实现但未生效"与"尚未开发"：
        // 前者用户可以通过操作解决，后者只能等待
        implemented -> "待开启"
        else -> "未接入"
    }

    val statusColor = when {
        !protectionEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
        active -> MaterialTheme.colorScheme.primary
        implemented -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.toaster.noad.core.designsystem.IconBadge(
                icon = icon,
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                size = 36.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                if (todayCount > 0) {
                    Text(
                        text = "今日拦截 $todayCount 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 拦截排行行。
 */
@Composable
private fun BlockedAppRow(app: TopBlockedAppItem, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${app.count} 次",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 无障碍开启引导卡片。
 *
 * ## 为什么需要它
 *
 * S1 生效需要**两层**授权，而用户在系统设置的授权环节最容易卡住：
 * 无障碍列表里条目往往很多，且 Android 13+ 对侧载应用隐藏了开关。
 * 单靠文字说明很难让用户走完流程，因此这里把状态、原因、动作放在一起。
 *
 * ## 文案原则
 *
 * 每一步都告诉用户「现在处于哪一环、下一步点哪里」，
 * 不出现"请开启无障碍"这种没有可操作信息的话。
 */
@Composable
private fun AccessibilityGuideCard(
    state: AccessibilityState,
    onToggleAppSwitch: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.toaster.noad.core.designsystem.IconBadge(
                    icon = Icons.Rounded.Shield,
                    tint = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    size = 36.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.a11y_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            state.isEffectivelyActive -> stringResource(R.string.a11y_status_running)
                            state.serviceRunning ->
                                stringResource(R.string.a11y_status_enabled_not_connected)
                            else -> stringResource(R.string.a11y_status_not_enabled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 分步引导：只显示当前缺失的那一步，避免一次性抛出全部信息
            when {
                // 应用内开关未开
                !state.appSwitchEnabled -> {
                    Text(
                        text = stringResource(R.string.a11y_hint_steps_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onOpenSystemSettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.a11y_action_open_settings))
                    }
                }

                // 应用内开关已开但服务未运行：需要去系统设置授权
                state.needsSystemPermission -> {
                    Text(
                        text = stringResource(R.string.a11y_hint_steps_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenSystemSettings,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.a11y_action_open_settings))
                        }
                        OutlinedButton(
                            onClick = onRefresh,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("已开启")
                        }
                    }
                }

                // 其余情况：提供手动开关
                else -> {
                    Button(
                        onClick = onToggleAppSwitch,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.a11y_action_enable))
                    }
                }
            }

            // 侧载受限提示：仅在确实检测到受限且尚未授权时展示
            if (state.restrictedBySideload && !state.serviceRunning) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.a11y_hint_restricted_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.a11y_hint_restricted_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeRoutePreview() {
    NoAdTheme(darkTheme = true) { HomeRoute() }
}
