// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zairenkai runtime architecture screen. This is a diagnostic projection of
 * observed runtime capability; it never invents support from static metadata.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

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
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.ChevronLeft
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.core.identity.SubsystemId
import com.zairenkai.app.core.runtime.kernelLabel
import com.zairenkai.app.core.runtime.platformLabel
import com.zairenkai.app.core.identity.ZairenkaiIdentity
import com.zairenkai.app.core.capability.CapabilityState
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

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Zairenkai Runtime") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ChevronLeft, "Kembali") } },
                actions = { IconButton(onClick = vm::refresh) { Icon(Icons.Rounded.Refresh, "Refresh") } },
            )
        },
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
                    SectionCard("Identitas Zairenkai") {
                        LabeledRow("Product", ZairenkaiIdentity.PRODUCT_NAME)
                        LabeledRow("App ID", ZairenkaiIdentity.APP_ID)
                        LabeledRow("Core ID", ZairenkaiIdentity.CORE_ID)
                        LabeledRow("Core API", ZairenkaiIdentity.CORE_API.toString())
                        LabeledRow("Platform", s.platformLabel)
                        LabeledRow("Kernel", s.kernelLabel)
                    }

                    SectionCard("Thermal authority") {
                        val t = s.thermal
                        LabeledRow("Envelope", t?.band ?: "Unknown", when (t?.band) {
                            "Nominal" -> ZkGood
                            "Warm", "Hot", "Unknown" -> ZkWarn
                            "Critical" -> ZkBad
                            else -> ZkWarn
                        })
                        LabeledRow("Headroom", t?.headroomPermille?.let { "$it‰" } ?: "Unknown")
                        LabeledRow("Control zone", t?.controlZone ?: "Unknown")
                        LabeledRow("Critical trip", t?.criticalTripMdeg?.let { "${it / 1000f} °C" } ?: "Unknown")
                        LabeledRow("Telemetry", if (t?.telemetryComplete == true) "Complete" else "Incomplete", if (t?.telemetryComplete == true) ZkGood else ZkWarn)
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
                        SectionCard("Capability · $category") {
                            caps.forEach { cap ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(Modifier.weight(1f)) {
                                        Icon(categoryIcons[category] ?: Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(cap.id.title, style = MaterialTheme.typography.bodyMedium)
                                            Text("${cap.id.wireId} · ${cap.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    val (label, color) = when (cap.state) {
                                        CapabilityState.AVAILABLE -> "READY" to ZkGood
                                        CapabilityState.UNAVAILABLE -> "UNAVAILABLE" to ZkWarn
                                        CapabilityState.UNOBSERVED -> "UNOBSERVED" to MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }

                    SectionCard("Subsystem registry") {
                        SubsystemId.entries.forEach { subsystem ->
                            LabeledRow(subsystem.displayName, "${subsystem.wireId} · ${subsystem.role}")
                        }
                    }
                }
                ui.error?.let { Text(it, color = ZkBad, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
