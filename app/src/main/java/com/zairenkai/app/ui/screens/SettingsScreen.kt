// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Settings: lite mode, device mitigation, theme, live log level + viewer.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.runtime.saveable.rememberSaveable
import com.zairenkai.app.ui.icons.ZkIcons
import com.zairenkai.app.ui.locale.LanguageSheet
import com.zairenkai.app.ui.locale.currentLanguageLabel
import com.zairenkai.app.ui.components.CollapsingScaffold
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import com.zairenkai.app.ui.icons.ChevronRight
import androidx.compose.material.icons.rounded.Info
import com.zairenkai.app.ui.icons.Palette
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.data.AppSettings
import com.zairenkai.app.ui.SettingsViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.SectionCard

private val logLevels = listOf("verbose", "debug", "info", "warn", "error", "silent")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    factory: VmFactory,
    onOpenTheme: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val settings by vm.settings.collectAsState(initial = AppSettings())
    val log by vm.log.collectAsState()
    var showLanguage by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        vm.startLog()
        onDispose { vm.stopLog() }
    }

    CollapsingScaffold(stringResource(R.string.settings_title), onBack = null) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(stringResource(R.string.settings_section_appearance)) {
                    NavRow(Icons.Rounded.Palette, stringResource(R.string.theme_title), stringResource(R.string.settings_theme_sub), onOpenTheme)
                    NavRow(ZkIcons.Globe, stringResource(R.string.language_title), currentLanguageLabel()) { showLanguage = true }
                    NavRow(Icons.Rounded.Info, stringResource(R.string.about_title), stringResource(R.string.settings_about_sub), onOpenAbout)
                }
            }
            item {
                SectionCard(stringResource(R.string.settings_section_general)) {
                    ToggleRow(stringResource(R.string.settings_lite_title), stringResource(R.string.settings_lite_sub),
                        settings.liteMode) { vm.setLite(it) }
                    ToggleRow(stringResource(R.string.settings_mitigation_title), stringResource(R.string.settings_mitigation_sub),
                        settings.deviceMitigation) { vm.setMitigation(it) }
                    ToggleRow(stringResource(R.string.settings_dynamic_color_title), stringResource(R.string.settings_dynamic_color_sub),
                        settings.dynamicColor) { vm.setDynamicColor(it) }
                }
            }
            item {
                SectionCard(stringResource(R.string.settings_log_level_title)) {
                    Text(stringResource(R.string.settings_log_level_desc), style = MaterialTheme.typography.bodySmall,
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
                Text(stringResource(R.string.settings_live_log), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (log.isEmpty()) item {
                Text(stringResource(R.string.settings_log_empty),
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
    if (showLanguage) LanguageSheet(onDismiss = { showLanguage = false })
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
