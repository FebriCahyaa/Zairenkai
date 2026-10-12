// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Profiles: the engine's modes, one tap each, plus Auto mode and the user's
 * custom profiles (a built-in mode plus overrides, stored by zperfd).
 *
 * The active profile, the effective mode and any baseline hold come from
 * `zperfd status`; each card describes what the device profile requests
 * (`zperfd modes`). Local settings are only shown, marked unverified, when
 * the engine cannot be reached.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.zairenkai.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.domain.Profile
import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.domain.Profiles
import com.zairenkai.app.data.CustomDraft
import com.zairenkai.app.data.CustomFields
import com.zairenkai.app.data.CustomProfile
import com.zairenkai.app.data.FieldKind
import com.zairenkai.app.data.text
import com.zairenkai.app.ui.ProfilesUi
import com.zairenkai.app.ui.ProfilesViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.holdMessage
import com.zairenkai.app.ui.modeSummaryLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(factory: VmFactory) {
    val vm: ProfilesViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    val busy = ui.busy
    val status = ui.status

    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("Profil") }) }) { inner ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ui.engineAvailable) {
                val cached = ui.cachedProfile?.let { Profiles.byId(it).name }
                ProfileBanner(
                    "zperfd tidak aktif — status profil tidak dapat diverifikasi." +
                        (cached?.let { "\nTerakhir dipilih di aplikasi: $it (tidak terverifikasi)." } ?: ""),
                    MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                    action = "Muat ulang" to fun() { vm.refresh() },
                )
            }
            holdMessage(status?.hold, ui.selectedName)?.let { message ->
                val confirm = status?.revalidationRequired == true && (ui.selected != null || ui.selectedCustom != null)
                ProfileBanner(
                    message,
                    MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                    action = if (confirm) "Konfirmasi & terapkan ${ui.selectedName}" to vm::confirmSaved else null,
                    enabled = !busy,
                )
            }
            ui.error?.let {
                ProfileBanner(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                    action = "Tutup" to vm::dismissMessages)
            }
            ui.notice?.let {
                ProfileBanner(it, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                    action = "Tutup" to vm::dismissMessages)
            }
            if (status != null && status.hold == null) {
                EngineStateRow(selectedName = ui.selectedName, desired = status.desired, effective = status.effective,
                    effectiveName = ui.modes[status.effective]?.label)
            }

            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoMode, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Profiles.AUTO.name, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(Profiles.AUTO.tagline, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    if (ui.applying == ProfileId.AUTO) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(checked = ui.auto, onCheckedChange = { vm.setAuto(it) },
                            enabled = ui.engineAvailable && !busy)
                    }
                }
            }

            Profiles.all.filter { it.id != ProfileId.AUTO }.forEach { p ->
                val unsupported = ui.unsupported(p)
                ProfileCard(
                    profile = p,
                    summary = ui.modes[p.mode]?.let(::modeSummaryLine),
                    active = ui.selected?.id == p.id,
                    applying = ui.applying == p.id,
                    unsupported = unsupported,
                    enabled = ui.engineAvailable && !busy && !unsupported,
                    onApply = { vm.apply(p) },
                )
            }
            Text(
                "Nilai di atas adalah permintaan profil perangkat; frekuensi diselaraskan ke tabel OPP " +
                    "dan dapat dikurangi oleh envelope termal saat diterapkan.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (ui.engineAvailable) {
                CustomProfilesSection(ui, vm, busy)
            }
        }
    }

    ui.editor?.let { draft ->
        CustomProfileEditor(
            draft = draft,
            ui = ui,
            onChange = vm::updateDraft,
            onSave = { vm.saveDraft() },
            onDismiss = vm::closeEditor,
        )
    }
}

@Composable
private fun EngineStateRow(selectedName: String?, desired: String, effective: String, effectiveName: String?) {
    val selectedText = selectedName ?: "Belum dipilih (default engine: $desired)"
    val effectiveProfile = Profiles.byMode(effective)?.name ?: effectiveName ?: effective
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Dipilih: $selectedText", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (effective != desired) {
                Text("Berjalan sebagai: $effectiveProfile (dipilih oleh Otomatis/aturan per-aplikasi)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileBanner(
    message: String,
    container: Color,
    content: Color,
    action: Pair<String, () -> Unit>? = null,
    enabled: Boolean = true,
) {
    Surface(color = container, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = content)
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                    Text(label, color = content)
                }
            } ?: Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    summary: String?,
    active: Boolean,
    applying: Boolean,
    unsupported: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    val container = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        modifier = Modifier.semantics {
            contentDescription = profile.name + (if (active) ", aktif" else "") +
                (if (unsupported) ", tidak tersedia di perangkat ini" else "")
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(profile.icon), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill("Aktif", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Text(profile.tagline, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    unsupported -> Text("Tidak tersedia di profil perangkat ini (mode ${profile.mode}).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    summary != null -> Text(summary, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            if (applying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else FilledTonalButton(onClick = onApply, enabled = enabled) { Text("Terapkan") }
        }
    }
}

private fun iconFor(key: String): ImageVector = when (key) {
    "sports_esports" -> Icons.Rounded.SportsEsports
    "speed" -> Icons.Rounded.Speed
    "battery_saver" -> Icons.Rounded.BatterySaver
    "balance" -> Icons.Rounded.Balance
    else -> Icons.Rounded.AutoMode
}

@Composable
private fun CustomProfilesSection(ui: ProfilesUi, vm: ProfilesViewModel, busy: Boolean) {
    val customs = ui.customs
    val profiles = customs?.profiles.orEmpty()
    val canCreate = customs != null && customs.bases.isNotEmpty() && profiles.size < customs.max
    var pendingDelete by remember { mutableStateOf<CustomProfile?>(null) }

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Profil kustom", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (customs == null) "Daftar profil kustom tidak dapat dibaca dari zperfd."
                else "Turunan mode bawaan dengan nilai Anda sendiri (${profiles.size}/${customs.max}).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = vm::newCustom, enabled = canCreate && !busy) { Text("Buat") }
    }
    if (customs != null && profiles.isEmpty()) {
        Text(
            "Belum ada profil kustom. Pilih mode dasar, lalu ubah hanya nilai yang perlu; " +
                "sisanya mengikuti mode dasar.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    profiles.forEach { p ->
        CustomProfileCard(
            profile = p,
            ui = ui,
            active = ui.selectedCustom?.id == p.id,
            applying = ui.applyingCustom == p.id,
            enabled = !busy,
            onApply = { vm.applyCustom(p) },
            onEdit = { vm.editCustom(p) },
            onDelete = { pendingDelete = p },
        )
    }
    if (profiles.isNotEmpty()) {
        Text(
            "Profil kustom hanya diterapkan manual; Otomatis dan aturan per-aplikasi tetap memakai mode bawaan.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus ${p.label}?") },
            text = { Text("Profil kustom ini dihapus dari engine. Profil yang sedang dipilih tidak bisa dihapus.") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; vm.deleteCustom(p) }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun CustomProfileCard(
    profile: CustomProfile,
    ui: ProfilesUi,
    active: Boolean,
    applying: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val container = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val changes = CustomDraft.fromProfile(profile).overrideCount
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        modifier = Modifier.semantics {
            contentDescription = profile.label + (if (active) ", aktif" else "") +
                (if (!profile.valid) ", tidak valid" else "")
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (active) {
                            Spacer(Modifier.width(8.dp))
                            StatusPill("Aktif", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Text(
                        "Turunan ${ui.baseName(profile.base)} · $changes perubahan",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        !profile.valid -> Text(
                            "Tidak dapat diterapkan: ${profile.error ?: "tidak valid"}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                        )
                        else -> ui.modes[profile.id]?.let(::modeSummaryLine)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (applying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else FilledTonalButton(onClick = onApply, enabled = enabled && profile.valid) { Text("Terapkan") }
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onEdit, enabled = enabled && profile.base in ui.customs?.bases.orEmpty().map { it.id }) {
                    Text("Ubah")
                }
                TextButton(onClick = onDelete, enabled = enabled && !active) { Text("Hapus") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomProfileEditor(
    draft: CustomDraft,
    ui: ProfilesUi,
    onChange: (CustomDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val baseValues = ui.baseValues(draft.base)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (draft.isNew) "Profil kustom baru" else "Ubah ${draft.label}",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = draft.label,
                onValueChange = { onChange(draft.copy(label = it.take(32))) },
                label = { Text("Nama") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Mode dasar", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.customs?.bases.orEmpty().forEach { base ->
                    FilterChip(
                        selected = draft.base == base.id,
                        onClick = { onChange(draft.copy(base = base.id)) },
                        label = { Text(ui.baseName(base.id)) },
                    )
                }
            }
            Text(
                "Kosongkan kolom untuk mengikuti mode dasar. Nilai diselaraskan ke tabel OPP, divalidasi zperfd, " +
                    "dan tetap dibatasi Sentinel serta envelope termal saat diterapkan.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CustomFields.sections.forEach { (section, title) ->
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                CustomFields.all.filter { it.section == section }.forEach { field ->
                    val inherited = baseValues.text(field.key) ?: "default kernel"
                    val range = field.range?.takeIf { it.last < Int.MAX_VALUE }?.let { " · ${it.first}–${it.last}" }.orEmpty()
                    OutlinedTextField(
                        value = draft.value(field.key),
                        onValueChange = { onChange(draft.with(field.key, it.take(128))) },
                        label = { Text(field.title) },
                        placeholder = { Text(inherited) },
                        supportingText = {
                            Text("Dasar: $inherited$range" + (field.hint?.let { " · $it" } ?: ""))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.kind == FieldKind.INT && (field.range?.first ?: 0) >= 0) {
                                KeyboardType.Number
                            } else KeyboardType.Text,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ui.editorError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !ui.saving) { Text("Batal") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave, enabled = !ui.saving) {
                    if (ui.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Simpan")
                }
            }
        }
    }
}
