package com.toaster.noad.feature.network

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toaster.noad.core.designsystem.KeyValueRow
import com.toaster.noad.core.designsystem.NoAdSection
import com.toaster.noad.core.model.NetworkFilterMode
import com.toaster.noad.core.navigation.NoAdViewModelFactory
import com.toaster.noad.ui.theme.NoAdTheme

/**
 * 网络过滤页。
 *
 * ## 核心设计：把互斥约束直接表达在 UI 上
 *
 * S2 与 S3 互斥（系统只允许一个 VpnService），因此本页**不是**
 * 一组可任意叠加的开关，而是一个单选的分组 —— 从交互层面杜绝了
 * 用户选出互相冲突的配置。
 *
 * 每种模式都明确标注是否占用系统 VPN，让用户在选之前就知道代价。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkRoute(
    viewModel: NetworkViewModel = viewModel(factory = NoAdViewModelFactory.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 系统级 VPN 授权对话框（VpnService.prepare 返回的 Intent）。
    // 授权结果回流 ViewModel：成功 → 启动服务；取消 → 让位横幅说明。
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    LaunchedEffect(uiState.vpnPermissionIntent) {
        uiState.vpnPermissionIntent?.let { intent -> vpnPermissionLauncher.launch(intent) }
    }

    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("网络过滤", fontWeight = FontWeight.SemiBold) })
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
            // ---- 让位提示（仅在有让位时展示） ----
            if (uiState.yielded) {
                item {
                    YieldBanner(onAcknowledge = viewModel::acknowledgeYield)
                }
            }

            // ---- 模式选择 ----
            item {
                NoAdSection(title = "过滤模式", subtitle = "同一时间只能启用一种 VPN 模式") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ModeOption(
                            mode = NetworkFilterMode.OFF,
                            selected = uiState.mode == NetworkFilterMode.OFF,
                            title = "关闭",
                            description = "仅使用无障碍拦截弹窗广告",
                            warning = null,
                            onSelect = { viewModel.setMode(NetworkFilterMode.OFF) },
                        )
                        ModeOption(
                            mode = NetworkFilterMode.DNS_ONLY,
                            selected = uiState.mode == NetworkFilterMode.DNS_ONLY,
                            title = "DNS 过滤",
                            description = "只接管域名解析，性能影响极小，可拦截网页广告与追踪域名",
                            warning = "会占用系统 VPN（与其他 VPN 互斥）",
                            onSelect = { viewModel.setMode(NetworkFilterMode.DNS_ONLY) },
                        )
                        ModeOption(
                            mode = NetworkFilterMode.FULL_TRAFFIC,
                            selected = uiState.mode == NetworkFilterMode.FULL_TRAFFIC,
                            title = "全流量过滤",
                            description = "接管全部流量，拦截能力最强，但性能开销与耗电更高",
                            warning = "会占用系统 VPN，且流量全经过本应用处理",
                            onSelect = { viewModel.setMode(NetworkFilterMode.FULL_TRAFFIC) },
                        )
                        ModeOption(
                            mode = NetworkFilterMode.APP_FIREWALL,
                            selected = uiState.mode == NetworkFilterMode.APP_FIREWALL,
                            title = "应用级断网",
                            description = "彻底切断指定应用的联网。需要 Shizuku，且不占用 VPN",
                            warning = "需要安装并启动 Shizuku",
                            onSelect = { viewModel.setMode(NetworkFilterMode.APP_FIREWALL) },
                        )
                    }
                }
            }

            // ---- 规则统计 ----
            item {
                NoAdSection(title = "规则库") {
                    KeyValueRow("黑名单域名", "${uiState.blacklistCount} 条")
                    KeyValueRow("白名单域名", "${uiState.whitelistCount} 条")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "白名单优先于黑名单：可避免误伤登录等关键域名。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 能力边界说明 ----
            item {
                NoAdSection(title = "能力说明") {
                    LimitNote("直连 IP 不经过 DNS 解析，DNS 模式无法拦截")
                    LimitNote("应用自带 DoH/DoT 会绕过系统 DNS")
                    LimitNote("已缓存的域名解析不会立即受规则更新影响")
                    LimitNote("信息流原生广告与正文同源，三种策略均无法拦截")
                }
            }
        }
    }
}

/**
 * 让位提示横幅。
 *
 * 文案要求：明确说明「已让位给其他 VPN」而非静默失效，
 * 并说明恢复条件 —— 不提供「强制夺回」按钮，避免抢占循环。
 */
@Composable
private fun YieldBanner(onAcknowledge: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已让位给其他 VPN",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "检测到其他 VPN 正在运行，网络过滤已自动停止。" +
                        "其他 VPN 关闭后可在此手动恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * 单个模式选项。
 */
@Composable
private fun ModeOption(
    mode: NetworkFilterMode,
    selected: Boolean,
    title: String,
    description: String,
    warning: String?,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (mode) {
                            NetworkFilterMode.OFF -> Icons.Rounded.Info
                            NetworkFilterMode.DNS_ONLY -> Icons.Rounded.Dns
                            else -> Icons.Rounded.VpnLock
                        },
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (warning != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⚠ $warning",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun LimitNote(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NetworkRoutePreview() {
    NoAdTheme(darkTheme = true) { NetworkRoute() }
}
