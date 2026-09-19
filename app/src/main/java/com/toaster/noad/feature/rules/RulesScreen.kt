package com.toaster.noad.feature.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toaster.noad.core.designsystem.EmptyState
import com.toaster.noad.core.navigation.NoAdViewModelFactory
import com.toaster.noad.core.model.SkipRuleSource

@Composable
fun RulesRoute(
    viewModel: RulesViewModel = viewModel(factory = NoAdViewModelFactory.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RulesScreen(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onToggle = viewModel::setEnabled,
        onDelete = viewModel::delete,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RulesScreen(
    uiState: RulesUiState,
    onQueryChange: (String) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    // 待确认删除的规则 id。用 id 而非整个对象：
    // 弹窗展示期间列表可能因数据刷新而重建，持有对象会让弹窗显示陈旧内容。
    var pendingDeleteId by remember { mutableLongStateOf(NO_PENDING_DELETE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("跳过规则")
                        Text(
                            text = if (uiState.totalRules == 0) {
                                "暂无规则"
                            } else {
                                "${uiState.totalRules} 条 · 覆盖 ${uiState.totalApps} 个应用"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索应用或规则") },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                },
                singleLine = true,
            )

            when {
                uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.isEmpty -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Rule,
                    message = "暂无跳过规则\n内置规则会随应用更新自动补充",
                )

                uiState.isEmptySearch -> EmptyState(
                    icon = Icons.Outlined.Search,
                    message = "未找到匹配的规则",
                )

                else -> RuleList(
                    groups = uiState.groups,
                    diagnostics = uiState.diagnostics,
                    onToggle = onToggle,
                    onRequestDelete = { pendingDeleteId = it },
                )
            }
        }
    }

    if (pendingDeleteId != NO_PENDING_DELETE) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = NO_PENDING_DELETE },
            title = { Text("删除这条规则？") },
            text = {
                Text("删除后该规则不再生效。若它是内置规则，应用更新时可能会重新出现。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(pendingDeleteId)
                        pendingDeleteId = NO_PENDING_DELETE
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = NO_PENDING_DELETE }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RuleList(
    groups: List<RuleGroup>,
    diagnostics: DiagnosticsItem,
    onToggle: (Long, Boolean) -> Unit,
    onRequestDelete: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 诊断卡片置顶：用户看不到效果时的第一个问题就是"到底卡在哪"，
        // 把它埋在列表末尾等于没做
        item(key = DIAGNOSTICS_CARD_KEY) {
            DiagnosticsCard(diagnostics)
        }

        items(items = groups, key = { it.packageName }) { group ->
            AppRuleCard(
                group = group,
                onToggle = onToggle,
                onRequestDelete = onRequestDelete,
            )
        }
    }
}

/**
 * S1 实时匹配诊断卡片。
 *
 * ## 为什么不用 Snackbar / Toast
 *
 * 诊断信息需要**反复对照规则列表**阅读（"规则数 0 → 去看内置规则在不在"），
 * 瞬时提示会消失，用户不得不反复操作复现。
 *
 * ## 配色语义
 *
 * 只有三种状态，且必须互斥可辨：
 * - 尚未收到事件 → 中性（`surfaceContainerHigh`）
 * - 已收到事件 → 正常（`secondaryContainer`）
 * - 已点击成功   → 成功（`tertiaryContainer`）
 *
 * 不用红色：诊断卡片出现红色会让用户以为应用坏了，
 * 而"没命中规则"是**正常的待排查状态**，不是故障。
 */
@Composable
private fun DiagnosticsCard(item: DiagnosticsItem) {
    val container = when {
        !item.hasReceivedEvent -> MaterialTheme.colorScheme.surfaceContainerHigh
        item.totalClicks > 0 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        !item.hasReceivedEvent -> MaterialTheme.colorScheme.onSurface
        item.totalClicks > 0 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MonitorHeart,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "匹配诊断",
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "事件 ${item.totalEvents} · 点击 ${item.totalClicks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = content,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )

            // 逐事件耗时：这是判断"启动应用卡一下"是否由本应用造成的直接证据。
            // 只在已收到事件时展示，否则会显示无意义的 0ms。
            if (item.hasReceivedEvent) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("单次处理耗时：最近 ${item.lastCostMs}ms")
                        if (item.maxCostMs > item.lastCostMs) append(" · 峰值 ${item.maxCostMs}ms")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = content.copy(alpha = COST_TEXT_ALPHA),
                )
                if (item.isSlow) {
                    // 用弱化的提示语而非报错口吻：轻度超标是正常的，
                    // 夸大其词会让用户以为应用坏了。
                    Text(
                        text = "峰值超过一帧预算（16ms），可能造成轻微卡顿",
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = COST_TEXT_ALPHA),
                    )
                }
            }

            // 规则数为 0 是"规则根本没生效"的强信号，单独强调
            if (item.hasReceivedEvent && item.ruleCount == 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "⚠ 该应用可用规则为 0 条。若内置规则应覆盖它，请确认版本已更新到含内置规则库的构建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
            }

            item.advice?.let { advice ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = DIAGNOSTICS_SURFACE_ALPHA),
                ) {
                    Text(
                        text = "下一步：$advice",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }

            item.packageName?.let { pkg ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = pkg,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AppRuleCard(
    group: RuleGroup,
    onToggle: (Long, Boolean) -> Unit,
    onRequestDelete: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.appLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = group.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!group.managed) {
                    // 未纳管 = 规则不会生效。必须显式提示，
                    // 否则用户会困惑"规则明明在列表里却没作用"。
                    NotManagedBadge()
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            group.rules.forEachIndexed { index, rule ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                RuleRow(
                    rule = rule,
                    onToggle = { enabled -> onToggle(rule.id, enabled) },
                    onDelete = { onRequestDelete(rule.id) },
                )
            }
        }
    }
}

@Composable
private fun NotManagedBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = "应用未纳管",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RuleRow(
    rule: RuleItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                SourceBadge(isBuiltin = rule.isBuiltin)
            }

            Spacer(Modifier.height(2.dp))

            // 定位值与定位方式：这是排查"规则为何没命中"时唯一有用的信息，
            // 用等宽字体展示，避免 0/O、1/l 之类的字符混淆
            Text(
                text = buildString {
                    append(rule.targetTypeLabel)
                    rule.matchModeLabel?.let { append(" · ").append(it) }
                    append("：")
                } + rule.targetValue,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            rule.activityName?.let { activity ->
                Text(
                    text = "限定界面：${activity.substringAfterLast('.')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "删除规则",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SourceBadge(isBuiltin: Boolean) {
    val container = if (isBuiltin) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = if (isBuiltin) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(
            text = if (isBuiltin) "内置" else "自定义",
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 无待删除项的哨兵值。规则 id 恒为正，因此 -1 不会与真实 id 冲突 */
private const val NO_PENDING_DELETE = -1L

/** 诊断卡片在 LazyColumn 中的稳定 key。它不来自数据源，故用一个不可能与包名冲突的字符串 */
private const val DIAGNOSTICS_CARD_KEY = "__diagnostics__"

/**
 * 诊断卡片内层提示框的透明度。
 *
 * 卡片底色随状态变化（中性/次要/第三），内层框必须能浮起来又不喧宾夺主。
 * 取 0.35 是实测下深色主题里既能分辨边界、又不与正文抢对比度的值。
 */
private const val DIAGNOSTICS_SURFACE_ALPHA = 0.35f

/**
 * 耗时行文字相对卡片正文的透明度。
 *
 * 取 0.8：它比正文弱（是补充信息），但必须比"下一步"提示更清晰 ——
 * 这是用户判断"卡顿是不是我造成的"时唯一要看的数据。
 */
private const val COST_TEXT_ALPHA = 0.8f
