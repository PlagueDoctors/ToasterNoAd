package com.toaster.noad.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 顶层导航目的地
 */
enum class NoAdDestination(
    val route: String,
    val label: String,
    val outlinedIcon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home(
        route = "home",
        label = "首页",
        outlinedIcon = Icons.Outlined.Home,
        selectedIcon = Icons.Rounded.Home,
    ),
    Apps(
        route = "apps",
        label = "应用管理",
        outlinedIcon = Icons.Outlined.Apps,
        selectedIcon = Icons.Rounded.Apps,
    ),
    Logs(
        route = "logs",
        label = "拦截日志",
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
    }
}
