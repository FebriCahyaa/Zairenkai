// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Dashboard: system check with next steps, device + ZKFC summary,
 * license/API state, boost status. Unreported fields are left out.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.ui.OverviewState
import com.zairenkai.app.ui.icons.ContentCopy
import com.zairenkai.app.ui.onboarding.CheckId
import com.zairenkai.app.ui.onboarding.CheckRow
import com.zairenkai.app.ui.onboarding.CheckState
import com.zairenkai.app.ui.onboarding.diagnosticsReport
import com.zairenkai.app.ui.onboarding.settled
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
        // The system check renders while it runs, so a slow root prompt or a
        // missing module is explained instead of hidden behind a spinner.
        DashboardContent(Modifier.padding(inner), vm)
    }
}

@Composable
private fun DashboardContent(modifier: Modifier, vm: OverviewViewModel) {
    val s by vm.state.collectAsState()
    val info = s.info
    val lic = s.license
    val facts = s.probe.facts
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SystemStatusCard(s, onRetry = vm::refresh)
        if (!s.rootAvailable || s.loading) return@Column

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
                if (info != null) stringResource(R.string.state_active) else stringResource(R.string.dashboard_not_available),
                if (info != null) ZkGood else ZkBad,
                Modifier.weight(1f),
                sub = info?.hookMode?.let { stringResource(R.string.dashboard_hook_sub, it) },
            )
            StatTile(
                stringResource(R.string.dashboard_license),
                lic?.let { licenseLabel(it.state) } ?: "—",
                lic?.let { licenseColor(it.state) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.weight(1f),
                sub = lic?.token?.licensee?.ifBlank { null } ?: if (lic == null) stringResource(R.string.dashboard_needs_zkfc) else null,
            )
        }

        SectionCard(stringResource(R.string.dashboard_device)) {
            // Only fields that were reported: ZKFC's own, else what the shell read.
            val arch = info?.arch?.takeIf { it.isNotBlank() && it != "unknown" } ?: facts?.abi?.takeIf { it.isNotBlank() }
            arch?.let { LabeledRow(stringResource(R.string.dashboard_arch), it) }
            info?.kernelType?.takeIf { it.isNotBlank() }?.let { LabeledRow(stringResource(R.string.dashboard_kernel_type), it.uppercase()) }
            (info?.kernelRelease?.takeIf { it.isNotBlank() } ?: facts?.kernel?.takeIf { it.isNotBlank() })
                ?.let { LabeledRow(stringResource(R.string.dashboard_kernel), it) }
            if (info != null) {
                LabeledRow(stringResource(R.string.dashboard_hook_mode), info.hookMode)
                LabeledRow(stringResource(R.string.dashboard_zkfc_api), "${info.api.major}.${info.api.minor}.${info.api.patch}")
                info.buildId.takeIf { it.isNotBlank() }?.let { LabeledRow(stringResource(R.string.dashboard_build), it) }
            } else {
                Text(
                    stringResource(R.string.dashboard_zkfc_fields_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            if (b == null) {
                Text(
                    stringResource(R.string.dashboard_boost_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@SectionCard
            }
            LabeledRow(stringResource(R.string.dashboard_input_boost), if (b.inputBoostActive) stringResource(R.string.state_active) else stringResource(R.string.dashboard_idle),
                if (b.inputBoostActive) ZkAqua else null)
            LabeledRow(stringResource(R.string.dashboard_boosted_tasks), b.boostedTasks.toString())
            LabeledRow(stringResource(R.string.dashboard_cpufreq_requests), b.cpufreqRequests.toString())
            LabeledRow(
                stringResource(R.string.dashboard_thermal),
                if (b.thermalTripped) stringResource(R.string.dashboard_thermal_mitigating) else stringResource(R.string.dashboard_thermal_normal),
                if (b.thermalTripped) ZkWarn else ZkGood,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.resetBoosts() }) { Text(stringResource(R.string.dashboard_reset_boosts)) }
        }
    }
}

/**
 * What is installed and working, why not, and what to do; Retry re-runs every
 * probe. Raw probe output stays in the collapsible technical details and the
 * copyable report.
 */
@Composable
private fun SystemStatusCard(s: OverviewState, onRetry: () -> Unit) {
    val checks = s.checks
    val allOk = checks.all { it.state == CheckState.OK }
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val report = diagnosticsReport(s.probe, BuildConfig.VERSION_NAME)
    SectionCard(stringResource(R.string.dashboard_system_status)) {
        if (allOk && !s.loading) {
            Text(stringResource(R.string.setup_check_all_ok), style = MaterialTheme.typography.bodyMedium, color = ZkGood)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { checks.forEach { CheckRow(it) } }
            val zkfcMissing = checks.firstOrNull { it.id == CheckId.ZKFC }?.state == CheckState.MISSING
            if (checks.settled() && zkfcMissing) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.dashboard_without_module),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { copied = false; onRetry() }, enabled = checks.settled() || !s.loading) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.setup_recheck))
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.dashboard_details_hide else R.string.dashboard_details_show))
            }
        }
        if (expanded) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    report,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Zairenkai diagnostics", report))
                copied = true
            }) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.dashboard_copy_diagnostics))
            }
            if (copied) {
                Text(stringResource(R.string.info_copied), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
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
