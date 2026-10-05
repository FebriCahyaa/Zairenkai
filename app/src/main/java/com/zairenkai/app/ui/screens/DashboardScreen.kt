// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Dashboard: device + ZKFC summary, license/API state, boost status.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.ui.OverviewViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.SectionCard
import com.zairenkai.app.ui.components.StatTile
import com.zairenkai.app.ui.theme.ZkAqua
import com.zairenkai.app.ui.theme.ZkBad
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkWarn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(factory: VmFactory, onOpenSettings: () -> Unit) {
    val vm: OverviewViewModel = viewModel(factory = factory)
    val s by vm.state.collectAsState()

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Zairenkai") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Pengaturan")
                    }
                },
            )
        },
    ) { inner ->
        when {
            s.loading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
            !s.rootAvailable -> RootUnavailable(Modifier.padding(inner)) { vm.refresh() }
            else -> DashboardContent(Modifier.padding(inner), vm)
        }
    }
}

@Composable
private fun DashboardContent(modifier: Modifier, vm: OverviewViewModel) {
    val s by vm.state.collectAsState()
    val info = s.info
    val lic = s.license
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Safe-mode banner (last boot(s) crashed / rebooted unexpectedly)
        if (s.safeMode) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Text("Mode Aman aktif", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "Boot sebelumnya gagal/restart mendadak, jadi profil boot tidak diterapkan. " +
                            "Periksa tweak terakhir sebelum mengaktifkannya lagi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // License / API banner
        val apiOutdated = info?.api?.outdated == true || info?.licenseState == "api_outdated"
        if (apiOutdated) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Text("API ZKFC usang", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "Kernel memakai ZKFC API lama. Perbarui kernel/ZKFC ke versi terbaru " +
                            "agar fitur performa bekerja penuh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "Status ZKFC",
                if (info?.ok == true) "Aktif" else "—",
                if (info?.ok == true) ZkGood else ZkBad,
                Modifier.weight(1f),
                sub = info?.hookMode?.let { "Hook: $it" },
            )
            val licState = lic?.state ?: "—"
            StatTile(
                "Lisensi API",
                licenseLabel(licState),
                licenseColor(licState),
                Modifier.weight(1f),
                sub = lic?.token?.licensee?.ifBlank { null },
            )
        }

        SectionCard("Perangkat") {
            LabeledRow("Arsitektur", info?.arch ?: "—")
            LabeledRow("Tipe kernel", (info?.kernelType ?: "—").uppercase())
            LabeledRow("Kernel", info?.kernelRelease ?: "—")
            LabeledRow("Mode hook", info?.hookMode ?: "—")
            info?.api?.let { LabeledRow("ZKFC API", "${it.major}.${it.minor}.${it.patch}") }
            LabeledRow("Build", info?.buildId ?: "—")
        }

        SectionCard("Performa aktif") {
            val b = s.boost
            LabeledRow("Input boost", if (b?.inputBoostActive == true) "Aktif" else "Idle",
                if (b?.inputBoostActive == true) ZkAqua else null)
            LabeledRow("Task di-boost", (b?.boostedTasks ?: 0).toString())
            LabeledRow("Permintaan cpufreq", (b?.cpufreqRequests ?: 0).toString())
            LabeledRow(
                "Thermal",
                if (b?.thermalTripped == true) "Mitigasi aktif" else "Normal",
                if (b?.thermalTripped == true) ZkWarn else ZkGood,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.resetBoosts() }) { Text("Reset performa") }
        }
    }
}

private fun licenseLabel(state: String) = when (state) {
    "valid" -> "Valid"
    "missing" -> "Belum ada"
    "api_outdated" -> "API usang"
    "expired" -> "Kedaluwarsa"
    "no_owner_key" -> "Tanpa kunci"
    "revoked" -> "Dicabut"
    else -> state
}

private fun licenseColor(state: String) = when (state) {
    "valid" -> ZkGood
    "missing", "no_owner_key" -> ZkWarn
    else -> ZkBad
}
