// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Root scaffold: bottom navigation + NavHost.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
    PROFILES("profiles", "Profil", Icons.Rounded.ViewCarousel),
    SYSTEM("system", "Sistem", Icons.Rounded.Security),
}

private val bottomDests = listOf(Dest.DASHBOARD, Dest.MONITOR, Dest.TWEAKS, Dest.PROFILES, Dest.SYSTEM)

@Composable
fun ZairenkaiRoot(container: AppContainer) {
    val nav = rememberNavController()
    val factory = remember(container) { VmFactory(container) }

    val glass = com.zairenkai.app.ui.theme.LocalGlass.current
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
            modifier = Modifier.padding(inner),
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
            composable(Dest.DASHBOARD.route) { DashboardScreen(factory, onOpenSettings = { nav.navigate("settings") }) }
            composable(Dest.MONITOR.route) {
                MonitorScreen(factory, onOpenHistory = { nav.navigate("usage") })
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
            composable(Dest.SYSTEM.route) { SystemScreen(factory, onOpenRuntime = { nav.navigate("runtime") }) }
            composable("runtime") { RuntimeScreen(factory, onBack = { nav.popBackStack() }) }
            composable("settings") {
                SettingsScreen(
                    factory,
                    onOpenTheme = { nav.navigate("theme") },
                    onOpenAbout = { nav.navigate("about") },
                )
            }
            composable("theme") { ThemeScreen(factory, onBack = { nav.popBackStack() }) }
            composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
