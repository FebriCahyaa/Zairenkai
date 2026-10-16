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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets.Companion.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlin.math.roundToInt
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

    // The bottom navigation slides out of view as content scrolls up (reading
    // down the page) and slides back on the first upward scroll, so long lists
    // get the full height. The bar stays in the layout the whole time — only its
    // translationY changes — so the scaffold padding never moves. That is what
    // keeps scrolling smooth: an earlier version toggled the bar in and out of
    // the layout, which relaid out the content on every toggle and fed back into
    // the scroll, causing the flicker and jitter.
    val backStack by nav.currentBackStackEntryAsState()
    val currentDest = backStack?.destination
    // How far the bar is pushed down out of view, in px (0 = fully shown,
    // barHeightPx = fully hidden). Measured from the bar itself via onSizeChanged.
    var barOffsetPx by remember { mutableFloatStateOf(0f) }
    var barHeightPx by remember { mutableIntStateOf(0) }
    // A new screen always starts with the bar shown.
    LaunchedEffect(currentDest?.route) { barOffsetPx = 0f }
    val barScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // available.y < 0 → content moving up (scrolling down) → hide;
                // available.y > 0 → scrolling up → reveal. 1:1 tracking, no
                // threshold, so the bar follows the finger without snapping.
                if (barHeightPx > 0) {
                    barOffsetPx = (barOffsetPx - available.y).coerceIn(0f, barHeightPx.toFloat())
                }
                return Offset.Zero
            }
        }
    }

    // Inset ownership: this scaffold handles only the bottom (its navigation
    // bar draws behind the system navigation bar). Each screen owns the top —
    // its top app bar draws behind the status bar — so it is never padded twice.
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            val current = currentDest
            // The bottom navigation belongs to the five top-level screens only.
            // Pushed detail screens (monitor/battery, settings, about, …) have
            // their own back button, so the bar would just get in the way there.
            val onTopLevel = bottomDests.any { it.route == current?.route }
            // Detail screens (monitor/battery, settings, …) show no bottom bar at
            // all — and reserve no space for one, so hiding it leaves no empty
            // box. Their own CollapsingScaffold supplies the bottom inset.
            if (onTopLevel) {
                FloatingZairenkaiNavBar(
                    current = current,
                    containerColor = com.zairenkai.app.ui.theme.glassContainerColor(
                        MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.colorScheme.primary,
                        glass,
                    ),
                    modifier = Modifier
                        // Clip the translated bar to its shrinking slot. Measure the
                        // inner content before shrinking so the full height is retained.
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val visible = (placeable.height - barOffsetPx).roundToInt()
                                .coerceIn(0, placeable.height)
                            layout(placeable.width, visible) { placeable.place(0, 0) }
                        }
                        .onSizeChanged { if (it.height > 0) barHeightPx = it.height },
                    onNavigate = { dest ->
                        nav.navigate(dest.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Dest.DASHBOARD.route,
            modifier = Modifier.padding(inner).consumeWindowInsets(inner).nestedScroll(barScroll),
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
                    onOpenMemory = { nav.navigate("monitor/memory") },
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
            composable("monitor/memory") { com.zairenkai.app.ui.screens.monitor.MemoryScreen(onBack = { nav.popBackStack() }) }
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
                    onOpenUpdate = { nav.navigate("update") },
                )
            }
            composable("theme") { ThemeScreen(factory, onBack = { nav.popBackStack() }) }
            composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
            composable("legal") {
                com.zairenkai.app.ui.legal.LegalInfoScreen(onBack = { nav.popBackStack() }, onOpenLicenses = { nav.navigate("licenses") })
            }
            composable("licenses") { com.zairenkai.app.ui.legal.OssLicensesScreen(onBack = { nav.popBackStack() }) }
            composable("logs") { LogsScreen(factory, onBack = { nav.popBackStack() }) }
            composable("update") { UpdateScreen(factory, onBack = { nav.popBackStack() }) }
        }
    }
}

/**
 * Floating, icon-only navigation inspired by KernelSU-Next's moving selection
 * pill, adapted to Zairenkai's Material 3 theme and route model.
 * All top-level destinations remain accessible by touch and TalkBack.
 */
@Composable
private fun FloatingZairenkaiNavBar(
    current: androidx.navigation.NavDestination?,
    containerColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onNavigate: (Dest) -> Unit,
) {
    val destinations = bottomDests
    val selectedIndex = destinations.indexOfFirst { dest ->
        current?.hierarchy?.any { entry ->
            entry.route == dest.route || entry.route?.startsWith(dest.route + "/") == true
        } == true
    }.let { if (it >= 0) it else 0 }

    var dragging by remember { mutableStateOf(false) }
    var dragTargetIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        if (!dragging) dragTargetIndex = selectedIndex
    }
    val latestOnNavigate by rememberUpdatedState(onNavigate)
    val haptic = LocalHapticFeedback.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // Unlike the stock edge-to-edge NavigationBar, this floating pill
            // sits above Android's gesture/navigation area, like KSU-Next.
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
        val horizontalPadding = when {
            maxWidth > 600.dp -> 32.dp
            maxWidth > 400.dp -> 16.dp
            else -> 8.dp
        }
        val itemSpacing = 4.dp
        val containerPadding = 7.dp
        val itemSize = minOf(
            56.dp,
            (maxWidth - horizontalPadding * 2 - containerPadding * 2 - itemSpacing * (destinations.size - 1)) / destinations.size,
        ).coerceAtLeast(44.dp)
        val navBarWidth = itemSize * destinations.size +
            itemSpacing * (destinations.size - 1) + containerPadding * 2
        val itemSizePx = with(density) { itemSize.toPx() }
        val itemSpacingPx = with(density) { itemSpacing.toPx() }
        val containerPaddingPx = with(density) { containerPadding.toPx() }

        val visualIndex: (Int) -> Int = { index ->
            if (isRtl) destinations.lastIndex - index else index
        }
        val displayedIndex = if (dragging) dragTargetIndex else selectedIndex
        val animatedVisualIndex by animateFloatAsState(
            targetValue = visualIndex(displayedIndex).toFloat(),
            animationSpec = if (dragging) {
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            } else {
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            },
            label = "zairenkai-nav-selection",
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.width(navBarWidth),
                shape = RoundedCornerShape(26.dp),
                color = containerColor,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .pointerInput(destinations.size, selectedIndex, isRtl, itemSizePx, itemSpacingPx) {
                            detectDragGestures(
                                onDragStart = { point ->
                                    val visual = ((point.x - containerPaddingPx) / (itemSizePx + itemSpacingPx))
                                        .toInt().coerceIn(0, destinations.lastIndex)
                                    dragTargetIndex = if (isRtl) destinations.lastIndex - visual else visual
                                    dragging = true
                                },
                                onDragEnd = {
                                    val target = destinations.getOrNull(dragTargetIndex)
                                    dragging = false
                                    if (target != null && dragTargetIndex != selectedIndex) {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        latestOnNavigate(target)
                                    }
                                },
                                onDragCancel = { dragging = false },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val visual = ((change.position.x - containerPaddingPx) / (itemSizePx + itemSpacingPx))
                                        .toInt().coerceIn(0, destinations.lastIndex)
                                    dragTargetIndex = if (isRtl) destinations.lastIndex - visual else visual
                                },
                            )
                        },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = containerPadding),
                    ) {
                        val indicatorOffset = ((itemSizePx + itemSpacingPx) * animatedVisualIndex).roundToInt()
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset { IntOffset(indicatorOffset, 0) }
                                .size(itemSize)
                                .graphicsLayer {
                                    val scale = if (dragging) 1.07f else 1f
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                        )
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            destinations.forEachIndexed { index, dest ->
                                val visuallySelected = index == displayedIndex
                                Box(
                                    modifier = Modifier
                                        .size(itemSize)
                                        .clip(RoundedCornerShape(18.dp))
                                        .selectable(
                                            selected = index == selectedIndex,
                                            role = Role.Tab,
                                            onClick = {
                                                if (index != selectedIndex) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                    onNavigate(dest)
                                                }
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = stringResource(dest.label),
                                        tint = if (visuallySelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

