package com.toaster.noad.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 顶层导航目的地。
 *
 * @param showInBottomBar 是否出现在底部导航栏。
 *   网络过滤页从首页进入而非放入底栏，避免底栏项过多（Material 建议 ≤ 5）。
 */
enum class NoAdDestination(
    val route: String,
    val label: String,
    val outlinedIcon: ImageVector,
    val selectedIcon: ImageVector,
    val showInBottomBar: Boolean = true,
) {
    Home(
        route = "home",
        label = "首页",
        outlinedIcon = Icons.Outlined.Home,
        selectedIcon = Icons.Rounded.Home,
    ),
    Apps(
        route = "apps",
        label = "应用",
        outlinedIcon = Icons.Outlined.Apps,
        selectedIcon = Icons.Rounded.Apps,
    ),
    Rules(
        route = "rules",
        label = "跳过规则",
        outlinedIcon = Icons.AutoMirrored.Outlined.Rule,
        selectedIcon = Icons.AutoMirrored.Rounded.Rule,
        showInBottomBar = false,
    ),
    Network(
        route = "network",
        label = "网络过滤",
        outlinedIcon = Icons.Outlined.Shield,
        selectedIcon = Icons.Rounded.Shield,
        showInBottomBar = false,
    ),
    Logs(
        route = "logs",
        label = "日志",
        outlinedIcon = Icons.Outlined.Insights,
        selectedIcon = Icons.Rounded.Insights,
    ),
    Settings(
        route = "settings",
        label = "设置",
        outlinedIcon = Icons.Outlined.Settings,
        selectedIcon = Icons.Rounded.Settings,
    );

    companion object {
        val Start = Home

        /** 底部导航栏使用的目的地 */
        val bottomBarEntries: List<NoAdDestination> = entries.filter { it.showInBottomBar }
    }
}
