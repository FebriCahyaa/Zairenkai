// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Root scaffold: bottom navigation + NavHost.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.ui.icons.ZkIcons
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zairenkai.app.AppContainer
import com.zairenkai.app.R
import com.zairenkai.app.ui.screens.*
import com.zairenkai.app.ui.screens.monitor.BatteryScreen
import com.zairenkai.app.ui.screens.monitor.CpuGpuScreen
import com.zairenkai.app.ui.screens.monitor.FpsScreen
import com.zairenkai.app.ui.screens.monitor.FpsSessionScreen

enum class Dest(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    DASHBOARD("dashboard", R.string.nav_dashboard, ZkIcons.Home),
    MONITOR("monitor", R.string.nav_monitor, ZkIcons.Pulse),
    TWEAKS("tweaks", R.string.nav_tweaks, ZkIcons.Tune),
    PROFILES("profiles", R.string.nav_profiles, ZkIcons.Profiles),
    SYSTEM("system", R.string.nav_system, ZkIcons.Security),
}

private val bottomDests = listOf(Dest.DASHBOARD, Dest.MONITOR, Dest.TWEAKS, Dest.PROFILES, Dest.SYSTEM)

@Composable
fun ZairenkaiRoot(container: AppContainer) {
    val nav = rememberNavController()
    val factory = remember(container) { VmFactory(container) }

    val glass = com.zairenkai.app.ui.theme.LocalGlass.current
    // Inset ownership: this scaffold handles only the bottom (its navigation
    // bar draws behind the system navigation bar). Each screen owns the top —
    // its top app bar draws behind the status bar — so it is never padded twice.
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            val backStack by nav.currentBackStackEntryAsState()
            val current = backStack?.destination
            NavigationBar(
                containerColor = com.zairenkai.app.ui.theme.glassContainerColor(
                    MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary, glass,
                ),
                tonalElevation = 0.dp,
                windowInsets = NavigationBarDefaults.windowInsets,
            ) {
                bottomDests.forEach { dest ->
                    // Detail routes ("monitor/battery") keep their tab highlighted.
                    val selected = current?.hierarchy?.any {
                        it.route == dest.route || it.route?.startsWith(dest.route + "/") == true
                    } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = stringResource(dest.label)) },
                        label = { Text(stringResource(dest.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Dest.DASHBOARD.route,
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
            // Smooth, springy cross-fade + subtle zoom between tabs.
            enterTransition = {
                fadeIn(spring(stiffness = 600f)) + scaleIn(spring(stiffness = 500f), initialScale = 0.96f)
            },
            exitTransition = { fadeOut(spring(stiffness = 800f)) },
            popEnterTransition = {
                fadeIn(spring(stiffness = 600f)) + scaleIn(spring(stiffness = 500f), initialScale = 0.96f)
            },
            popExitTransition = { fadeOut(spring(stiffness = 800f)) },
        ) {
            composable(Dest.DASHBOARD.route) {
                DashboardScreen(
                    factory,
                    onOpenSettings = { nav.navigate("settings") },
                    // Same as tapping the Profiles tab, so its state is kept.
                    onOpenProfiles = {
                        nav.navigate(Dest.PROFILES.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Dest.MONITOR.route) {
                MonitorScreen(
                    factory,
                    onOpenHistory = { nav.navigate("usage") },
                    onOpenCpu = { tab -> nav.navigate("monitor/cpu/$tab") },
                    onOpenBattery = { nav.navigate("monitor/battery") },
                    onOpenFps = { nav.navigate("monitor/fps") },
                )
            }
            composable("monitor/cpu/{tab}") { entry ->
                CpuGpuScreen(
                    factory,
                    initialTab = entry.arguments?.getString("tab")?.toIntOrNull() ?: 0,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("monitor/battery") { BatteryScreen(factory, onBack = { nav.popBackStack() }) }
            composable("monitor/fps") {
                FpsScreen(
                    factory,
                    onBack = { nav.popBackStack() },
                    onOpenSession = { id -> nav.navigate("monitor/fps/$id") },
                )
            }
            composable("monitor/fps/{id}") { entry ->
                FpsSessionScreen(
                    factory,
                    sessionId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("usage") {
                UsageHistoryScreen(
                    factory,
                    onBack = { nav.popBackStack() },
                    onOpenSession = { id -> nav.navigate("session/$id") },
                )
            }
            composable("session/{id}") { entry ->
                SessionDetailScreen(
                    factory,
                    sessionId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Dest.TWEAKS.route) { TweaksScreen(factory) }
            composable(Dest.PROFILES.route) { ProfilesScreen(factory) }
            composable(Dest.SYSTEM.route) {
                SystemScreen(factory, onOpenRuntime = { nav.navigate("runtime") }, onOpenAboutPhone = { nav.navigate("about_phone") })
            }
            composable("about_phone") { com.zairenkai.app.ui.device.AboutPhoneScreen(onBack = { nav.popBackStack() }) }
            composable("runtime") { RuntimeScreen(factory, onBack = { nav.popBackStack() }) }
            composable("settings") {
                SettingsScreen(
                    factory,
                    onOpenTheme = { nav.navigate("theme") },
                    onOpenAbout = { nav.navigate("about") },
                    onOpenAboutPhone = { nav.navigate("about_phone") },
                    onOpenLegal = { nav.navigate("legal") },
                    onOpenLicenses = { nav.navigate("licenses") },
                    onOpenLogs = { nav.navigate("logs") },
                )
            }
            composable("theme") { ThemeScreen(factory, onBack = { nav.popBackStack() }) }
            composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
            composable("legal") {
                com.zairenkai.app.ui.legal.LegalInfoScreen(onBack = { nav.popBackStack() }, onOpenLicenses = { nav.navigate("licenses") })
            }
            composable("licenses") { com.zairenkai.app.ui.legal.OssLicensesScreen(onBack = { nav.popBackStack() }) }
            composable("logs") { LogsScreen(factory, onBack = { nav.popBackStack() }) }
        }
    }
}
