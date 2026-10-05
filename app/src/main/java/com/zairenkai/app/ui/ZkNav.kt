// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Root scaffold: bottom navigation + NavHost.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zairenkai.app.AppContainer
import com.zairenkai.app.ui.screens.*

enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Beranda", Icons.Rounded.Dashboard),
    MONITOR("monitor", "Monitor", Icons.Rounded.Timeline),
    TWEAKS("tweaks", "Tweaks", Icons.Rounded.Tune),
    PROFILES("profiles", "Profil", Icons.Rounded.Tune),
    SYSTEM("system", "Sistem", Icons.Rounded.Security),
}

private val bottomDests = listOf(Dest.DASHBOARD, Dest.MONITOR, Dest.TWEAKS, Dest.PROFILES, Dest.SYSTEM)

@Composable
fun ZairenkaiRoot(container: AppContainer) {
    val nav = rememberNavController()
    val factory = remember(container) { VmFactory(container) }

    Scaffold(
        bottomBar = {
            val backStack by nav.currentBackStackEntryAsState()
            val current = backStack?.destination
            NavigationBar {
                bottomDests.forEach { dest ->
                    val selected = current?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Dest.DASHBOARD.route,
            modifier = Modifier.padding(inner),
        ) {
            composable(Dest.DASHBOARD.route) { DashboardScreen(factory, onOpenSettings = { nav.navigate("settings") }) }
            composable(Dest.MONITOR.route) { MonitorScreen(factory) }
            composable(Dest.TWEAKS.route) { TweaksScreen(factory) }
            composable(Dest.PROFILES.route) { ProfilesScreen(factory) }
            composable(Dest.SYSTEM.route) { SystemScreen(factory) }
            composable("settings") { SettingsScreen(factory) }
        }
    }
}
