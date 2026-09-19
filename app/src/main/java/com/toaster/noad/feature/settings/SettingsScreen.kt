package com.toaster.noad.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toaster.noad.ui.theme.NoAdTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("设置", fontWeight = FontWeight.SemiBold) })
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionTitle("通用")
                    SettingsGroupCard {
                        SettingToggleRow(
                            title = "开机自启动",
                            checked = uiState.autostart,
                            onToggle = viewModel::toggleAutostart,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingToggleRow(
                            title = "拦截通知",
                            checked = uiState.notification,
                            onToggle = viewModel::toggleNotification,
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionTitle("高级")
                    SettingsGroupCard {
                        SettingToggleRow(
                            title = "深色主题",
                            checked = uiState.darkTheme,
                            onToggle = viewModel::toggleDarkTheme,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingClickRow(title = "白名单", subtitle = "已添加 0 个应用")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingClickRow(title = "关于 NoAd", subtitle = "v1.0")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
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
    checked: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = { onToggle() }) },
    )
}

@Composable
private fun SettingClickRow(title: String, subtitle: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsRoutePreview() {
    NoAdTheme(darkTheme = true) { SettingsRoute() }
}
