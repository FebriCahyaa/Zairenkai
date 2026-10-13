// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Settings, grouped like a system settings page: a hero with the mascot and
 * version, then Device, Appearance, Performance, Permissions & privacy,
 * Diagnostics and About. Rows are grouped in rounded cards with an icon
 * tile, title, subtitle and a chevron or switch. The ZKFC log has its own
 * page (level + live log), so the settings list stays calm.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.R
import com.zairenkai.app.data.AppSettings
import com.zairenkai.app.ui.SettingsViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.CollapsingScaffold
import com.zairenkai.app.ui.device.DeviceEntryCard
import com.zairenkai.app.ui.icons.ZkIcons
import com.zairenkai.app.ui.locale.LanguageSheet
import com.zairenkai.app.ui.locale.currentLanguageLabel
import com.zairenkai.app.ui.mascot.Mascot
import com.zairenkai.app.ui.mascot.MascotState
import com.zairenkai.app.ui.onboarding.PermissionsStep
import com.zairenkai.app.ui.onboarding.rememberPermissions
import com.zairenkai.app.ui.theme.ZkWarn

private val logLevels = listOf("verbose", "debug", "info", "warn", "error", "silent")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    factory: VmFactory,
    onOpenTheme: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenAboutPhone: () -> Unit = {},
    onOpenLegal: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val settings by vm.settings.collectAsState(initial = AppSettings())
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var showPermissions by rememberSaveable { mutableStateOf(false) }
    val (permissions, onPermission) = rememberPermissions()
    SettingsContent(
        settings, permissions,
        onOpenTheme = onOpenTheme, onOpenAbout = onOpenAbout, onOpenAboutPhone = onOpenAboutPhone,
        onOpenLegal = onOpenLegal, onOpenLicenses = onOpenLicenses, onOpenLogs = onOpenLogs,
        onLanguage = { showLanguage = true }, onPermissions = { showPermissions = true },
        onDynamic = { vm.setDynamicColor(it) }, onLite = { vm.setLite(it) }, onMitigation = { vm.setMitigation(it) },
    )
    if (showLanguage) LanguageSheet(onDismiss = { showLanguage = false })
    if (showPermissions) {
        ModalBottomSheet(onDismissRequest = { showPermissions = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { PermissionsStep(permissions, onPermission) }
        }
    }
}

/** Settings for a given state; stateless, so tests and previews render it directly. */
@Composable
internal fun SettingsContent(
    settings: AppSettings,
    permissions: Map<com.zairenkai.app.ui.onboarding.AppPermission, Boolean>,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAboutPhone: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenLogs: () -> Unit,
    onLanguage: () -> Unit,
    onPermissions: () -> Unit,
    onDynamic: (Boolean) -> Unit,
    onLite: (Boolean) -> Unit,
    onMitigation: (Boolean) -> Unit,
) {
    val granted = permissions.count { it.value }
    CollapsingScaffold(stringResource(R.string.settings_title), onBack = null) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Hero(onOpenAbout) }
            item { Header(R.string.settings_section_device) }
            item { DeviceEntryCard(onOpenAboutPhone) }

            item { Header(R.string.settings_section_appearance) }
            item {
                Group {
                    NavRow(ZkIcons.Palette, stringResource(R.string.theme_title), stringResource(R.string.settings_theme_sub), onOpenTheme)
                    Divider()
                    NavRow(ZkIcons.Globe, stringResource(R.string.language_title), currentLanguageLabel(), onLanguage)
                    Divider()
                    ToggleRow(ZkIcons.Contrast, stringResource(R.string.settings_dynamic_color_title), stringResource(R.string.settings_dynamic_color_sub), settings.dynamicColor, onDynamic)
                }
            }

            item { Header(R.string.settings_section_performance) }
            item {
                Group {
                    ToggleRow(ZkIcons.Bolt, stringResource(R.string.settings_lite_title), stringResource(R.string.settings_lite_sub), settings.liteMode, onLite)
                    Divider()
                    ToggleRow(ZkIcons.Thermometer, stringResource(R.string.settings_mitigation_title), stringResource(R.string.settings_mitigation_sub), settings.deviceMitigation, onMitigation)
                }
            }

            item { Header(R.string.settings_section_privacy) }
            item {
                Group {
                    NavRow(ZkIcons.Bell, stringResource(R.string.settings_permissions_title),
                        pluralStringResource(R.plurals.settings_permissions_sub, permissions.size, granted, permissions.size), onPermissions)
                    Divider()
                    NavRow(ZkIcons.Lock, stringResource(R.string.legal_privacy_title), stringResource(R.string.legal_privacy_summary), onOpenLegal)
                }
            }

            item { Header(R.string.settings_section_diagnostics) }
            item {
                Group {
                    NavRow(ZkIcons.Terminal, stringResource(R.string.settings_logs_title), stringResource(R.string.settings_logs_sub, settings.logLevel), onOpenLogs)
                }
            }

            // The About entry is the Hero card at the top of this screen, so the
            // section here carries only the legal and licence pages — no second
            // "About" row that would just repeat the card's destination.
            item { Header(R.string.settings_section_about) }
            item {
                Group {
                    NavRow(ZkIcons.Document, stringResource(R.string.legal_title), stringResource(R.string.settings_legal_sub), onOpenLegal)
                    Divider()
                    NavRow(ZkIcons.Package, stringResource(R.string.oss_title), stringResource(R.string.legal_third_party_summary), onOpenLicenses)
                }
            }
        }
    }
}

/** The mascot, the app name and its version; opens About. */
@Composable
private fun Hero(onOpenAbout: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onOpenAbout,
        shape = RoundedCornerShape(28.dp),
        color = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mascot(MascotState.IDLE, size = 96.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.settings_hero_sub, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(ZkIcons.ChevronRight, null)
        }
    }
}

@Composable
private fun Header(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun IconTile(icon: ImageVector) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Icon(icon, null, Modifier.padding(8.dp).size(20.dp))
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(ZkIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Settings → ZKFC log: the level the kernel records, and the live log. */
@Composable
fun LogsScreen(factory: VmFactory, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val settings by vm.settings.collectAsState(initial = AppSettings())
    val log by vm.log.collectAsState()
    DisposableEffect(Unit) {
        vm.startLog()
        onDispose { vm.stopLog() }
    }
    CollapsingScaffold(stringResource(R.string.settings_logs_title), onBack = onBack) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Group {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_log_level_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.settings_log_level_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        @OptIn(ExperimentalMaterial3Api::class)
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
            }
            item { Text(stringResource(R.string.settings_live_log), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (log.isEmpty()) item {
                Text(stringResource(R.string.settings_log_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(log.reversed(), key = { it.seq }) { rec ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(rec.level.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = levelColor(rec.level))
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
private fun levelColor(level: String): Color = when (level) {
    "error" -> MaterialTheme.colorScheme.error
    "warn" -> com.zairenkai.app.ui.theme.statusInk(ZkWarn)
    "info" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
