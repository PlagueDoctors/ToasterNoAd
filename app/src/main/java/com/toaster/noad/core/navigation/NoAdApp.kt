package com.toaster.noad.core.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.toaster.noad.core.service.AccessibilitySettingsLauncher
import com.toaster.noad.feature.apps.AppsRoute
import com.toaster.noad.feature.home.HomeRoute
import com.toaster.noad.feature.logs.LogsRoute
import com.toaster.noad.feature.network.NetworkRoute
import com.toaster.noad.feature.rules.RulesRoute
import com.toaster.noad.feature.settings.SettingsRoute

/**
 * 顶层应用容器：Scaffold + 底部导航 + NavHost。
 *
 * 底部导航栏仅在底栏目的地之间切换；[NoAdDestination.Network] 等
 * 非底栏页面仍通过 NavHost 导航，此时底栏保持可见但无选中项。
 */
@Composable
fun NoAdApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // 无障碍设置跳转需要 Context。用 LocalContext 而非把 Context 传进
    // 各 Screen：后者会把 Android 依赖带进 UI 层，破坏可预览性。
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NoAdBottomBar(
                currentDestination = currentDestination,
                onNavigate = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NoAdDestination.Start.route,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            composable(NoAdDestination.Home.route) {
                HomeRoute(
                    onNavigateToLogs = {
                        navController.navigate(NoAdDestination.Logs.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToApps = {
                        navController.navigate(NoAdDestination.Apps.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToNetwork = {
                        navController.navigate(NoAdDestination.Network.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenAccessibilitySettings = {
                        // 返回值表示是否成功跳转。失败时不弹提示：
                        // 极少数精简 ROM 会移除该入口，此时用户回到设置页
                        // 手动寻找的路径依然存在，弹窗反而增加困惑。
                        AccessibilitySettingsLauncher.openAccessibilitySettings(context)
                    },
                )
            }
            composable(NoAdDestination.Apps.route) { AppsRoute() }
            composable(NoAdDestination.Rules.route) { RulesRoute() }
            composable(NoAdDestination.Network.route) { NetworkRoute() }
            composable(NoAdDestination.Logs.route) { LogsRoute() }
            composable(NoAdDestination.Settings.route) {
                SettingsRoute(
                    onNavigateToRules = {
                        navController.navigate(NoAdDestination.Rules.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NoAdBottomBar(
    currentDestination: androidx.navigation.NavDestination?,
    onNavigate: (NoAdDestination) -> Unit,
) {
    NavigationBar {
        NoAdDestination.bottomBarEntries.forEach { destination ->
            val selected = currentDestination?.hierarchy
                ?.any { it.route == destination.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(destination) },
                icon = {
                    val icon: ImageVector =
                        if (selected) destination.selectedIcon else destination.outlinedIcon
                    Icon(icon, contentDescription = destination.label)
                },
                label = { Text(destination.label) },
            )
        }
    }
}
