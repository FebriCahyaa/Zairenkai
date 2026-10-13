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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.R
import com.zairenkai.app.data.AppSettings
import com.zairenkai.app.ui.SettingsViewModel
import com.zairenkai.app.ui.VmFactory
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
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
    var detail by rememberSaveable { mutableStateOf<SettingDetail?>(null) }
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
                    DetailToggleRow(
                        ZkIcons.Bolt, stringResource(R.string.settings_lite_title), stringResource(R.string.settings_lite_sub),
                        settings.liteMode, onLite, onDetail = { detail = SettingDetail.LITE },
                    )
                    Divider()
                    DetailToggleRow(
                        ZkIcons.Thermometer, stringResource(R.string.settings_mitigation_title), stringResource(R.string.settings_mitigation_sub),
                        settings.deviceMitigation, onMitigation, onDetail = { detail = SettingDetail.MITIGATION },
                    )
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
        detail?.let { which ->
            SettingDetailSheet(
                detail = which,
                checked = if (which == SettingDetail.LITE) settings.liteMode else settings.deviceMitigation,
                onToggle = if (which == SettingDetail.LITE) onLite else onMitigation,
                onDismiss = { detail = null },
            )
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

/** The two performance settings whose effect is explained in a detail sheet. */
private enum class SettingDetail(
    val icon: ImageVector,
    val title: Int,
    val summary: Int,
    val bullets: List<Int>,
    val note: Int,
) {
    LITE(
        ZkIcons.Bolt, R.string.settings_lite_title, R.string.settings_lite_detail_summary,
        listOf(R.string.settings_lite_detail_b1, R.string.settings_lite_detail_b2, R.string.settings_lite_detail_b3),
        R.string.settings_lite_detail_note,
    ),
    MITIGATION(
        ZkIcons.Thermometer, R.string.settings_mitigation_title, R.string.settings_mitigation_detail_summary,
        listOf(R.string.settings_mitigation_detail_b1, R.string.settings_mitigation_detail_b2, R.string.settings_mitigation_detail_b3),
        R.string.settings_mitigation_detail_note,
    ),
}

/** A toggle row with an info button that opens the setting's detail sheet. */
@Composable
private fun DetailToggleRow(
    icon: ImageVector,
    title: String,
    sub: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    onDetail: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clickable(onClick = onDetail), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Icon(ZkIcons.Info, stringResource(R.string.settings_detail_open), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingDetailSheet(detail: SettingDetail, checked: Boolean, onToggle: (Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(detail.icon)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(detail.title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
            Text(stringResource(detail.summary), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_detail_what_happens), style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                detail.bullets.forEach { b ->
                    Row {
                        Icon(ZkIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(b), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ZkIcons.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(detail.note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch) { onToggle(it) },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (checked) R.string.settings_detail_on else R.string.settings_detail_off),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    )
                    Switch(checked = checked, onCheckedChange = null)
                }
            }
        }
    }
}

/** Settings → ZKFC log: the record level, a live log with a level filter, and
 * actions to copy it or export a diagnostics .zip bundle. */
@Composable
fun LogsScreen(factory: VmFactory, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val settings by vm.settings.collectAsState(initial = AppSettings())
    val log by vm.log.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        vm.startLog()
        onDispose { vm.stopLog() }
    }

    var filter by rememberSaveable { mutableStateOf("all") }
    var status by remember { mutableStateOf<String?>(null) }
    var bundle by remember { mutableStateOf<java.io.File?>(null) }
    val savedMsg = stringResource(R.string.settings_log_saved)
    val failMsg = stringResource(R.string.settings_log_save_failed)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = bundle
        if (uri != null && file != null) scope.launch {
            status = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                savedMsg
            }.getOrDefault(failMsg)
        }
    }
    val copiedMsg = stringResource(R.string.settings_log_copied)
    val buildingMsg = stringResource(R.string.settings_log_building)

    val shown = remember(log, filter) { if (filter == "all") log else log.filter { it.level == filter } }

    CollapsingScaffold(stringResource(R.string.settings_logs_title), onBack = onBack) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Group {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        status = buildingMsg
                                        val f = runCatching { vm.buildLogBundle() }.getOrNull()
                                        if (f == null) { status = failMsg; return@launch }
                                        bundle = f
                                        status = null
                                        saver.launch(f.name)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(ZkIcons.Package, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_log_generate), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.logText())); status = copiedMsg }) {
                                Icon(ZkIcons.Copy, null, Modifier.size(18.dp))
                            }
                        }
                        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_live_log), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        pluralStringResource(R.plurals.settings_log_count, shown.size, shown.size),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val options = listOf("all") + logLevels
                    options.forEach { lvl ->
                        FilterChip(
                            selected = filter == lvl,
                            onClick = { filter = lvl },
                            label = { Text(if (lvl == "all") stringResource(R.string.settings_log_filter_all) else lvl.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
            if (shown.isEmpty()) item {
                Text(stringResource(R.string.settings_log_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown.reversed(), key = { it.seq }) { rec ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(10.dp)) {
                        Surface(shape = RoundedCornerShape(6.dp), color = levelColor(rec.level).copy(alpha = 0.16f)) {
                            Text(
                                rec.level.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = levelColor(rec.level),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
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
