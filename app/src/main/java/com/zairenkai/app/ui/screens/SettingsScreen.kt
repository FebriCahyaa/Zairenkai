// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Settings: lite mode, device mitigation, theme, live log level + viewer.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.data.AppSettings
import com.zairenkai.app.ui.SettingsViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.SectionCard

private val logLevels = listOf("verbose", "debug", "info", "warn", "error", "silent")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(factory: VmFactory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val settings by vm.settings.collectAsState(initial = AppSettings())
    val log by vm.log.collectAsState()

    DisposableEffect(Unit) {
        vm.startLog()
        onDispose { vm.stopLog() }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Pengaturan") }) }) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("Umum") {
                    ToggleRow("Mode Lite", "Sembunyikan tweak berat, kurangi overhead.",
                        settings.liteMode) { vm.setLite(it) }
                    ToggleRow("Mitigasi perangkat", "Batasi boost untuk perangkat panas/SoC lawas.",
                        settings.deviceMitigation) { vm.setMitigation(it) }
                    ToggleRow("Warna dinamis (Material You)", "Ikuti wallpaper (Android 12+).",
                        settings.dynamicColor) { vm.setDynamicColor(it) }
                }
            }
            item {
                SectionCard("Log level") {
                    Text("Level log ZKFC yang direkam kernel.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        logLevels.forEachIndexed { i, lvl ->
                            SegmentedButton(
                                selected = settings.logLevel == lvl,
                                onClick = { vm.setLogLevel(lvl) },
                                shape = SegmentedButtonDefaults.itemShape(i, logLevels.size),
                                label = { Text(lvl.take(3)) },
                            )
                        }
                    }
                }
            }
            item {
                Text("Live log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (log.isEmpty()) item {
                Text("Belum ada log. Pastikan modul ZKFC termuat.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(log.reversed(), key = { it.seq }) { rec ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(rec.level.take(1).uppercase(), style = MaterialTheme.typography.labelLarge,
                            color = levelColor(rec.level))
                        Spacer(Modifier.width(10.dp))
                        Text(rec.msg, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun levelColor(level: String) = when (level) {
    "error" -> MaterialTheme.colorScheme.error
    "warn" -> com.zairenkai.app.ui.theme.ZkWarn
    "info" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
