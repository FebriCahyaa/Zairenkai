// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Dashboard (Beranda): connects the OverviewViewModel to the stateless home
 * screen (ui/home/HomeScreen.kt).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.ui.OverviewViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.home.HomeScreen

@Composable
fun DashboardScreen(factory: VmFactory, onOpenSettings: () -> Unit, onOpenProfiles: () -> Unit) {
    val vm: OverviewViewModel = viewModel(factory = factory)
    val s by vm.state.collectAsState()
    // The system check renders while it runs, so a slow root prompt or a
    // missing module is explained instead of hidden behind a spinner.
    HomeScreen(
        s,
        onRetry = vm::refresh,
        onResetBoosts = { vm.resetBoosts() },
        onExitSafeMode = { vm.clearSafeMode() },
        onOpenSettings = onOpenSettings,
        onOpenProfiles = onOpenProfiles,
    )
}
