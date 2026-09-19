package com.toaster.noad.feature.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toaster.noad.core.designsystem.EmptyState
import com.toaster.noad.core.designsystem.IconBadge
import com.toaster.noad.core.model.InterceptLog
import com.toaster.noad.core.navigation.NoAdViewModelFactory
import com.toaster.noad.ui.theme.NoAdTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拦截日志页。
 *
 * 展示真实的拦截记录，并标注每条记录来自哪个策略
 * （无障碍 / DNS / VPN / 应用断网），便于用户理解各策略的实际效果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsRoute(
    viewModel: LogsViewModel = viewModel(factory = NoAdViewModelFactory.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("拦截日志", fontWeight = FontWeight.SemiBold)
                        if (uiState.totalToday > 0) {
                            Text(
                                text = "今日 ${uiState.totalToday} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (uiState.logs.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearLogs) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空日志")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isEmpty) {
            EmptyState(
                icon = Icons.Outlined.Block,
                message = "暂无拦截记录\n启用拦截后这里会显示明细",
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = uiState.logs,
                    key = { it.id },
                ) { log ->
                    LogRow(log = log)
                }
            }
        }
    }
}

@Composable
private fun LogRow(log: InterceptLog) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = Icons.Outlined.Block,
                tint = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${log.appLabel} · ${log.adType.label}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(log.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = " · ${log.source.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(timestamp))
}

@Preview(showBackground = true)
@Composable
private fun LogsRoutePreview() {
    NoAdTheme(darkTheme = true) { LogsRoute() }
}
