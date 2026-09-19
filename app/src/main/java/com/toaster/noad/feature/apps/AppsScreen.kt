package com.toaster.noad.feature.apps

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
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.toaster.noad.core.data.repository.ManagedApp
import com.toaster.noad.core.designsystem.EmptyState
import com.toaster.noad.core.navigation.NoAdViewModelFactory
import com.toaster.noad.ui.theme.NoAdTheme

/**
 * 应用管理页。
 *
 * 展示**真实已安装应用**（绿色启动图标的应用），叠加用户的纳管开关。
 * 搜索支持应用名与包名双匹配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsRoute(
    viewModel: AppsViewModel = viewModel(factory = NoAdViewModelFactory.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("应用管理", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = viewModel::manageAllVisible) {
                        Icon(Icons.Outlined.DoneAll, contentDescription = "全部启用")
                    }
                    IconButton(onClick = viewModel::clearAllVisible) {
                        Icon(Icons.Outlined.Clear, contentDescription = "全部取消")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = { Text("搜索应用名或包名") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = uiState.includeSystemApps,
                    onClick = { viewModel.setIncludeSystemApps(!uiState.includeSystemApps) },
                    label = { Text("包含系统应用") },
                )
                Spacer(Modifier.weight(1f))
                if (!uiState.isLoading) {
                    Text(
                        text = "已启用 ${uiState.managedCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                uiState.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.errorMessage != null -> {
                    EmptyState(
                        icon = Icons.Outlined.Android,
                        message = uiState.errorMessage ?: "读取失败",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                uiState.isEmpty -> {
                    EmptyState(
                        icon = Icons.Outlined.Android,
                        message = if (uiState.query.isBlank()) {
                            "未发现可管理的应用"
                        } else {
                            "没有匹配「${uiState.query}」的应用"
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 4.dp,
                            bottom = 20.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = uiState.apps,
                            key = { it.packageName },
                        ) { app ->
                            ManagedAppRow(
                                app = app,
                                onToggle = { viewModel.toggleApp(app.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagedAppRow(app: ManagedApp, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp),
            ) {
                // 占位：后续接入真实 ApplicationInfo 图标加载
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (app.managed) FontWeight.Medium else FontWeight.Normal,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Switch(checked = app.managed, onCheckedChange = { onToggle() })
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppsRoutePreview() {
    NoAdTheme(darkTheme = true) { AppsRoute() }
}
