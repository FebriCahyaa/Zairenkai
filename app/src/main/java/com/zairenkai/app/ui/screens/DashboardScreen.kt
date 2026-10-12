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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
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
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.dashboard_settings))
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
                    Text(stringResource(R.string.dashboard_safe_mode_title), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        stringResource(R.string.dashboard_safe_mode_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = vm::clearSafeMode,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(stringResource(R.string.dashboard_safe_mode_exit))
                    }
                }
            }
        }

        // License / API banner
        val apiOutdated = info?.api?.outdated == true || info?.licenseState == "api_outdated"
        if (apiOutdated) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dashboard_api_outdated_title), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        stringResource(R.string.dashboard_api_outdated_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                stringResource(R.string.dashboard_zkfc_status),
                if (info?.ok == true) stringResource(R.string.state_active) else "—",
                if (info?.ok == true) ZkGood else ZkBad,
                Modifier.weight(1f),
                sub = info?.hookMode?.let { stringResource(R.string.dashboard_hook_sub, it) },
            )
            val licState = lic?.state ?: "—"
            StatTile(
                stringResource(R.string.dashboard_license),
                licenseLabel(licState),
                licenseColor(licState),
                Modifier.weight(1f),
                sub = lic?.token?.licensee?.ifBlank { null },
            )
        }

        SectionCard(stringResource(R.string.dashboard_device)) {
            LabeledRow(stringResource(R.string.dashboard_arch), info?.arch ?: "—")
            LabeledRow(stringResource(R.string.dashboard_kernel_type), (info?.kernelType ?: "—").uppercase())
            LabeledRow(stringResource(R.string.dashboard_kernel), info?.kernelRelease ?: "—")
            LabeledRow(stringResource(R.string.dashboard_hook_mode), info?.hookMode ?: "—")
            info?.api?.let { LabeledRow(stringResource(R.string.dashboard_zkfc_api), "${it.major}.${it.minor}.${it.patch}") }
            LabeledRow(stringResource(R.string.dashboard_build), info?.buildId ?: "—")
        }

        SectionCard(stringResource(R.string.dashboard_thermal_envelope)) {
            val t = s.thermal
            val unknown = stringResource(R.string.dashboard_unknown)
            LabeledRow(stringResource(R.string.dashboard_envelope), t?.band ?: unknown, when (t?.band) {
                "Nominal" -> ZkGood
                "Critical" -> ZkBad
                else -> ZkWarn
            })
            LabeledRow(stringResource(R.string.dashboard_headroom), t?.headroomPermille?.let { "$it‰" } ?: unknown)
            LabeledRow(stringResource(R.string.dashboard_control_zone), t?.controlZone ?: unknown)
            LabeledRow(stringResource(R.string.dashboard_critical_trip), t?.criticalTripMdeg?.let { "${it / 1000f} °C" } ?: unknown)
            Text(
                t?.reason?.takeIf { it.isNotBlank() } ?: stringResource(R.string.dashboard_thermal_no_evidence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(stringResource(R.string.dashboard_active_performance)) {
            val b = s.boost
            LabeledRow(stringResource(R.string.dashboard_input_boost), if (b?.inputBoostActive == true) stringResource(R.string.state_active) else stringResource(R.string.dashboard_idle),
                if (b?.inputBoostActive == true) ZkAqua else null)
            LabeledRow(stringResource(R.string.dashboard_boosted_tasks), (b?.boostedTasks ?: 0).toString())
            LabeledRow(stringResource(R.string.dashboard_cpufreq_requests), (b?.cpufreqRequests ?: 0).toString())
            LabeledRow(
                stringResource(R.string.dashboard_thermal),
                if (b?.thermalTripped == true) stringResource(R.string.dashboard_thermal_mitigating) else stringResource(R.string.dashboard_thermal_normal),
                if (b?.thermalTripped == true) ZkWarn else ZkGood,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.resetBoosts() }) { Text(stringResource(R.string.dashboard_reset_boosts)) }
        }
    }
}

@Composable
private fun licenseLabel(state: String): String = when (state) {
    "valid" -> stringResource(R.string.dashboard_license_valid)
    "missing" -> stringResource(R.string.dashboard_license_missing)
    "api_outdated" -> stringResource(R.string.dashboard_license_api_outdated)
    "expired" -> stringResource(R.string.dashboard_license_expired)
    "no_owner_key" -> stringResource(R.string.dashboard_license_no_owner_key)
    "revoked" -> stringResource(R.string.dashboard_license_revoked)
    else -> state
}

private fun licenseColor(state: String) = when (state) {
    "valid" -> ZkGood
    "missing", "no_owner_key" -> ZkWarn
    else -> ZkBad
}
