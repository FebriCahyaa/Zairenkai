// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zairenkai runtime architecture screen. This is a diagnostic projection of
 * observed runtime capability; it never invents support from static metadata.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import com.zairenkai.app.ui.components.CollapsingScaffold
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import com.zairenkai.app.ui.icons.Shield
import com.zairenkai.app.ui.icons.Thermostat
import com.zairenkai.app.ui.icons.Memory
import com.zairenkai.app.ui.icons.Storage
import com.zairenkai.app.ui.icons.Public
import com.zairenkai.app.ui.icons.DeveloperBoard
import com.zairenkai.app.ui.icons.ChevronLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.core.identity.SubsystemId
import com.zairenkai.app.core.runtime.kernelLabel
import com.zairenkai.app.core.runtime.platformLabel
import com.zairenkai.app.core.identity.ZairenkaiIdentity
import com.zairenkai.app.core.capability.CapabilityState
import com.zairenkai.app.core.text.asString
import com.zairenkai.app.ui.RuntimeViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.SectionCard
import com.zairenkai.app.ui.theme.ZkBad
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkWarn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeScreen(factory: VmFactory, onBack: () -> Unit = {}) {
    val vm: RuntimeViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    val snap = ui.snapshot

    CollapsingScaffold(
        stringResource(R.string.runtime_title),
        onBack = onBack,
        actions = { IconButton(onClick = vm::refresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.action_reload)) } },
    ) { inner ->
        when {
            ui.loading && snap == null -> Column(Modifier.fillMaxSize().padding(inner), verticalArrangement = Arrangement.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(24.dp))
            }
            else -> Column(
                Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                snap?.let { s ->
                    SectionCard(stringResource(R.string.runtime_identity_title)) {
                        LabeledRow(stringResource(R.string.runtime_label_product), ZairenkaiIdentity.PRODUCT_NAME)
                        LabeledRow(stringResource(R.string.runtime_label_app_id), ZairenkaiIdentity.APP_ID)
                        LabeledRow(stringResource(R.string.runtime_label_core_id), ZairenkaiIdentity.CORE_ID)
                        LabeledRow(stringResource(R.string.runtime_label_core_api), ZairenkaiIdentity.CORE_API.toString())
                        LabeledRow(stringResource(R.string.runtime_label_platform), s.platformLabel.asString())
                        LabeledRow(stringResource(R.string.system_label_kernel), s.kernelLabel.asString())
                    }

                    SectionCard(stringResource(R.string.runtime_thermal_title)) {
                        val t = s.thermal
                        val unknown = stringResource(R.string.runtime_unknown)
                        LabeledRow(stringResource(R.string.runtime_label_envelope), t?.band ?: unknown, when (t?.band) {
                            "Nominal" -> ZkGood
                            "Warm", "Hot", "Unknown" -> ZkWarn
                            "Critical" -> ZkBad
                            else -> ZkWarn
                        })
                        LabeledRow(stringResource(R.string.runtime_label_headroom), t?.headroomPermille?.let { "$it‰" } ?: unknown)
                        LabeledRow(stringResource(R.string.runtime_label_control_zone), t?.controlZone ?: unknown)
                        LabeledRow(stringResource(R.string.runtime_label_critical_trip), t?.criticalTripMdeg?.let { "${it / 1000f} °C" } ?: unknown)
                        LabeledRow(
                            stringResource(R.string.runtime_label_telemetry),
                            stringResource(if (t?.telemetryComplete == true) R.string.runtime_telemetry_complete else R.string.runtime_telemetry_incomplete),
                            if (t?.telemetryComplete == true) ZkGood else ZkWarn)
                        t?.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }

                    val categoryIcons = mapOf(
                        "Runtime" to Icons.Rounded.DeveloperBoard,
                        "Thermal" to Icons.Rounded.Thermostat,
                        "CPU" to Icons.Rounded.Memory,
                        "GPU" to Icons.Rounded.DeveloperBoard,
                        "Memory" to Icons.Rounded.Memory,
                        "Storage" to Icons.Rounded.Storage,
                        "Network" to Icons.Rounded.Public,
                        "System" to Icons.Rounded.Shield,
                    )
                    s.capabilities.byCategory().forEach { (category, caps) ->
                        SectionCard(stringResource(R.string.runtime_capability_section, stringResource(caps.first().id.categoryRes))) {
                            caps.forEach { cap ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(Modifier.weight(1f)) {
                                        Icon(categoryIcons[category] ?: Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(stringResource(cap.id.titleRes), style = MaterialTheme.typography.bodyMedium)
                                            Text("${cap.id.wireId} · ${cap.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    val (label, color) = when (cap.state) {
                                        CapabilityState.AVAILABLE -> stringResource(R.string.runtime_cap_ready) to ZkGood
                                        CapabilityState.UNAVAILABLE -> stringResource(R.string.runtime_cap_unavailable) to ZkWarn
                                        CapabilityState.UNOBSERVED -> stringResource(R.string.runtime_cap_unobserved) to MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }

                    SectionCard(stringResource(R.string.runtime_subsystem_title)) {
                        SubsystemId.entries.forEach { subsystem ->
                            LabeledRow(subsystem.displayName, "${subsystem.wireId} · ${stringResource(subsystem.roleRes)}")
                        }
                    }
                }
                ui.error?.let { Text(it.asString(), color = ZkBad, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
