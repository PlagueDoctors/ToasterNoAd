package com.toaster.noad.feature.home

import android.os.SystemClock
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

    // 回到前台时重新核对服务连接状态。
    //
    // ## 为什么这里还需要它（明明已经有自愈监听器了）
    //
    // [com.toaster.noad.core.service.AccessibilityWatchdog] 负责**后台**的自愈：
    // 息屏、解锁、授权变化都会触发核对。但用户"从系统设置页返回"这一时机
    // 它感知不到 —— 从设置返回时屏幕既没亮起也没解锁，广播不会发。
    //
    // 两层配合，覆盖全部需要刷新的时机：
    // - 后台：息屏 / 解锁 / 授权变化（Watchdog 的广播）
    // - 前台：从设置页返回（本处的 ON_RESUME）
    //
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
                        dnsActive = uiState.dnsActive,
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
                        shizukuReady = uiState.shizukuReady,
                        shizukuNeedsPermission = uiState.shizukuNeedsPermission,
                        fixInFlight = uiState.fixingRestricted,
                        secureRestoreAvailable = uiState.secureRestoreAvailable,
                        onAutoFixRestricted = viewModel::resolveRestrictedSettings,
                        onGrantShizuku = viewModel::grantShizukuPermission,
                        onRestoreAccessibility = viewModel::restoreAccessibilityAuthorization,
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
 * S1 已接入真实运行状态；S2 已接入（阶段 D，DNS 过滤 = VPN TUN 在跑）；
 * S3/S4 在后续阶段接入，当前明确显示为「未接入」而不是伪造成「运行中」。
 */
@Composable
private fun StrategyStatusList(
    protectionEnabled: Boolean,
    accessibility: AccessibilityState,
    bySource: Map<InterceptSource, Int>,
    dnsActive: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InterceptSource.entries.forEach { source ->
            val (active, implemented) = when (source) {
                InterceptSource.ACCESSIBILITY ->
                    accessibility.isEffectivelyActive to true

                // S2 已接线：意图（模式选中）× 运行事实（VpnStateHolder）
                InterceptSource.DNS -> dnsActive to true

                // 以下两种策略尚未实现，明确标记为未接入
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
 *
 * ## F2 侧载受限自动解除（方案 §6.5.3）
 *
 * 当检测到受限设置挡路时，卡片按 Shizuku 状态提供三种互斥形态：
 *
 * - **就绪**：「自动解除」主按钮（[onAutoFixRestricted]），
 *   流程进行中禁用并切换文案（[fixInFlight]）；成功后由
 *   ViewModel 直接打开系统无障碍设置
 * - **已装未授权**：「授权」入口（[onGrantShizuku]）
 * - **其余**（未装 Shizuku / 探测未过）：维持手动图文引导，
 *   不新增按钮 —— 功能缺失不该挤占本就紧张的提示空间
 *
 * ## R12/R13 授权恢复
 *
 * ROM「一键清理」按 force-stop 语义撤销无障碍授权后，用户被迫重跑设置。
 * 在「去系统设置」分支旁提供一键恢复（[onRestoreAccessibility]）：
 * read-merge-write 保护其他应用条目，回读验证后才报成功。
 * 展示条件与文案跟随**实际通道**（R13）：有 adb 高级授权
 * （[secureRestoreAvailable]）时无需 Shizuku 也会展示、文案不带
 * Shizuku 字样；否则 Shizuku 就绪时展示。
 */
@Composable
private fun AccessibilityGuideCard(
    state: AccessibilityState,
    shizukuReady: Boolean,
    shizukuNeedsPermission: Boolean,
    fixInFlight: Boolean,
    secureRestoreAvailable: Boolean,
    onAutoFixRestricted: () -> Unit,
    onGrantShizuku: () -> Unit,
    onRestoreAccessibility: () -> Unit,
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
                            state.isDisconnectedButAuthorized ->
                                stringResource(R.string.a11y_status_enabled_not_connected)
                            else -> stringResource(R.string.a11y_status_not_enabled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 服务断开提示（已授权但当前未连接）----
            //
            // 必须排在分步引导之前：用户此时**什么都不缺**，
            // 强行推"去开启"的引导会让他以为是自己没设置好。
            if (state.isDisconnectedButAuthorized) {
                DisconnectNotice(
                    reasonLabel = state.disconnectReason?.label,
                    disconnectedAtMillis = state.lastDisconnectedAtMillis,
                    onOpenSystemSettings = onOpenSystemSettings,
                    onRefresh = onRefresh,
                )
                Spacer(Modifier.height(12.dp))
            }

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

                // 已授权且应用内开关已开，但服务实例被系统解绑。
                //
                // 这个分支必须显式存在，否则会落到下面的 else，
                // 给用户显示「开启无障碍拦截」——而它本来就是开着的。
                // 那会让用户以为是自己没设置好，反复去点一个无意义的按钮。
                state.isDisconnectedButAuthorized && state.appSwitchEnabled -> {
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.a11y_action_recheck))
                    }
                }

                // 应用内开关已开但确实没授权：需要去系统设置
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
                            Text(stringResource(R.string.a11y_action_recheck))
                        }
                    }

                    // 授权恢复（R12 Shizuku / R13 adb 高级授权）：
                    // ROM「一键清理」按 force-stop 撤销授权后的一键修复。
                    // 展示条件与文案跟随实际通道（与门面的通道优先级一致：
                    // WRITE_SECURE_SETTINGS 优先于 Shizuku）；
                    // 与「自动解除」共用 fixInFlight 防重入。
                    if (shizukuReady || secureRestoreAvailable) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRestoreAccessibility,
                            enabled = !fixInFlight,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                stringResource(
                                    when {
                                        fixInFlight -> R.string.shizuku_restore_running
                                        // 已具备高级授权 → 实际走的是无 Shizuku 通道，
                                        // 文案不能谎称「用 Shizuku」
                                        secureRestoreAvailable -> R.string.restore_action_plain
                                        else -> R.string.shizuku_restore_action
                                    },
                                ),
                            )
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

            // 侧载受限提示：仅在确实检测到受限且尚未授权时展示。
            //
            // 三种动作形态互斥（F2，方案 §6.5.3）：
            // - Shizuku 就绪 → 「自动解除」主入口（成功后由 ViewModel
            //   直接打开系统无障碍设置，即"成功：直接开启"）
            // - 已装未授权 → 「授权」入口
            // - 其余（未装 Shizuku / 探测未过）→ 维持手动图文，不新增按钮
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

                        if (shizukuReady) {
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = onAutoFixRestricted,
                                enabled = !fixInFlight,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    stringResource(
                                        if (fixInFlight) {
                                            R.string.shizuku_fix_running
                                        } else {
                                            R.string.shizuku_fix_action
                                        },
                                    ),
                                )
                            }
                        } else if (shizukuNeedsPermission) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onGrantShizuku,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(stringResource(R.string.shizuku_grant_permission))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 服务断开提示块。
 *
 * ## 与「未开启」引导的本质区别
 *
 * 用户在这里**什么都不缺** —— 设置里的开关还开着，只是系统的服务实例
 * 被解绑了。因此本块刻意：
 *
 * - 不用 error 配色（那是"出错了"的语义，会让用户紧张）
 * - 主按钮是「前往设置检查」而非「去开启」（后者暗示用户漏了操作）
 * - 文案明确写出"不需要重新授权"，从根上消除用户的困惑
 *
 * ## 时间显示为什么要`记得`起始时刻
 *
 * `SystemClock.elapsedRealtime()` 是**开机以来的时长**，不是时间戳。
 * 由于 UI 只在状态变化时重组，这里展示的是"断开发生时算出的时长"，
 * 不会随停留时间自动增长 —— 这对一个诊断提示来说可以接受
 * （用户离开再回来时 `onResume` 会刷新）。真要每秒递增就得起一个
 * 计时器，为一条诊断信息付出持续重组的代价不值得。
 */
@Composable
private fun DisconnectNotice(
    reasonLabel: String?,
    disconnectedAtMillis: Long?,
    onOpenSystemSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.a11y_disconnect_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.a11y_disconnect_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            // 断开多久 + 什么原因：这两条信息决定用户该等待还是该动手
            val detail = disconnectDetailText(reasonLabel, disconnectedAtMillis)
            if (detail != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(
                        alpha = DISCONNECT_DETAIL_ALPHA,
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenSystemSettings,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(stringResource(R.string.a11y_disconnect_action_settings))
                }
                OutlinedButton(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(stringResource(R.string.a11y_action_recheck))
                }
            }
        }
    }
}

/**
 * 拼装断开详情文案（纯函数，便于单测与保持 Composable 精简）。
 *
 * @return `null` 表示没有任何可展示的明细（原因与时间都缺失）
 */
@Composable
private fun disconnectDetailText(reasonLabel: String?, disconnectedAtMillis: Long?): String? {
    val parts = mutableListOf<String>()

    reasonLabel?.takeIf { it.isNotBlank() }?.let {
        parts += stringResource(R.string.a11y_disconnect_reason, it)
    }

    disconnectedAtMillis?.let { at ->
        parts += disconnectElapsedText(at)
    }

    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * 把「断开时刻」换算成「已断开多久」。
 *
 * 输入是 `SystemClock.elapsedRealtime()` 的取值，因此用同样的时钟求差 ——
 * 用 `System.currentTimeMillis()` 相减会因两者的时间基准不同而得到荒谬结果。
 */
@Composable
private fun disconnectElapsedText(disconnectedAtMillis: Long): String {
    val elapsed = SystemClock.elapsedRealtime() - disconnectedAtMillis

    return when {
        // 负数意味着时钟异常（例如进程重启后残留的旧值），
        // 此时不展示具体时长而不是显示"-3 分钟前"
        elapsed < 0 -> stringResource(R.string.a11y_disconnect_time_just_now)
        elapsed < MINUTE_MILLIS -> stringResource(R.string.a11y_disconnect_time_just_now)
        elapsed < HOUR_MILLIS ->
            stringResource(
                R.string.a11y_disconnect_elapsed_minutes,
                (elapsed / MINUTE_MILLIS).toInt(),
            )
        else ->
            stringResource(
                R.string.a11y_disconnect_elapsed_hours,
                (elapsed / HOUR_MILLIS).toInt(),
            )
    }
}

/** 断开明细的次要文字透明度（与诊断卡片的耗时行保持一致） */
private const val DISCONNECT_DETAIL_ALPHA = 0.8f

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 3_600_000L

@Preview(showBackground = true)
@Composable
private fun HomeRoutePreview() {
    NoAdTheme(darkTheme = true) { HomeRoute() }
}
