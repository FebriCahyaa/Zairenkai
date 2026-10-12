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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.domain.Profile
import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.domain.Profiles
import com.zairenkai.app.R
import com.zairenkai.app.core.text.asString
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

    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text(stringResource(R.string.profiles_title)) }) }) { inner ->
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
                val cached = ui.cachedProfile?.let { stringResource(Profiles.byId(it).nameRes) }
                ProfileBanner(
                    stringResource(R.string.profiles_engine_down) +
                        (cached?.let { "\n" + stringResource(R.string.profiles_engine_down_cached, it) } ?: ""),
                    MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                    action = stringResource(R.string.action_reload) to fun() { vm.refresh() },
                )
            }
            holdMessage(status?.hold, ui.selectedName)?.let { message ->
                val confirm = status?.revalidationRequired == true && (ui.selected != null || ui.selectedCustom != null)
                ProfileBanner(
                    message.asString(),
                    MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                    action = if (confirm) {
                        stringResource(R.string.profiles_confirm_apply, ui.selectedName?.asString().orEmpty()) to vm::confirmSaved
                    } else null,
                    enabled = !busy,
                )
            }
            ui.error?.let {
                ProfileBanner(it.asString(), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                    action = stringResource(R.string.action_close) to vm::dismissMessages)
            }
            ui.notice?.let {
                ProfileBanner(it.asString(), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                    action = stringResource(R.string.action_close) to vm::dismissMessages)
            }
            if (status != null && status.hold == null) {
                EngineStateRow(selectedName = ui.selectedName?.asString(), desired = status.desired, effective = status.effective,
                    effectiveName = ui.modes[status.effective]?.label)
            }

            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoMode, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Profiles.AUTO.nameRes), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(stringResource(Profiles.AUTO.taglineRes), style = MaterialTheme.typography.bodySmall,
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
                    summary = ui.modes[p.mode]?.let(::modeSummaryLine)?.asString(),
                    active = ui.selected?.id == p.id,
                    applying = ui.applying == p.id,
                    unsupported = unsupported,
                    enabled = ui.engineAvailable && !busy && !unsupported,
                    onApply = { vm.apply(p) },
                )
            }
            Text(
                stringResource(R.string.profiles_values_note),
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
    val selectedText = selectedName ?: stringResource(R.string.profiles_none_selected, desired)
    val effectiveProfile = Profiles.byMode(effective)?.let { stringResource(it.nameRes) } ?: effectiveName ?: effective
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.profiles_selected, selectedText), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (effective != desired) {
                Text(stringResource(R.string.profiles_running_as, effectiveProfile),
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
    val name = stringResource(profile.nameRes)
    val activeSuffix = stringResource(R.string.a11y_suffix_active)
    val unsupportedSuffix = stringResource(R.string.a11y_suffix_unsupported)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        modifier = Modifier.semantics {
            contentDescription = name + (if (active) activeSuffix else "") + (if (unsupported) unsupportedSuffix else "")
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(profile.icon), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill(stringResource(R.string.state_active), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Text(stringResource(profile.taglineRes), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    unsupported -> Text(stringResource(R.string.profiles_unsupported, profile.mode),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    summary != null -> Text(summary, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            if (applying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else FilledTonalButton(onClick = onApply, enabled = enabled) { Text(stringResource(R.string.action_apply)) }
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
            Text(stringResource(R.string.custom_section_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (customs == null) stringResource(R.string.custom_list_unavailable)
                else stringResource(R.string.custom_section_subtitle, profiles.size, customs.max),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = vm::newCustom, enabled = canCreate && !busy) { Text(stringResource(R.string.action_create)) }
    }
    if (customs != null && profiles.isEmpty()) {
        Text(
            stringResource(R.string.custom_empty),
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
            stringResource(R.string.custom_manual_only),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.custom_delete_title, p.label)) },
            text = { Text(stringResource(R.string.custom_delete_body)) },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; vm.deleteCustom(p) }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
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
    val activeSuffix = stringResource(R.string.a11y_suffix_active)
    val invalidSuffix = stringResource(R.string.a11y_suffix_invalid)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        modifier = Modifier.semantics {
            contentDescription = profile.label + (if (active) activeSuffix else "") + (if (!profile.valid) invalidSuffix else "")
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
                            StatusPill(stringResource(R.string.state_active), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Text(
                        pluralStringResource(R.plurals.custom_card_subtitle, changes, ui.baseName(profile.base).asString(), changes),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        !profile.valid -> Text(
                            stringResource(R.string.custom_card_invalid, profile.error ?: stringResource(R.string.custom_invalid)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                        )
                        else -> ui.modes[profile.id]?.let(::modeSummaryLine)?.asString()?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (applying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else FilledTonalButton(onClick = onApply, enabled = enabled && profile.valid) { Text(stringResource(R.string.action_apply)) }
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onEdit, enabled = enabled && profile.base in ui.customs?.bases.orEmpty().map { it.id }) {
                    Text(stringResource(R.string.action_edit))
                }
                TextButton(onClick = onDelete, enabled = enabled && !active) { Text(stringResource(R.string.action_delete)) }
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
                if (draft.isNew) stringResource(R.string.custom_editor_new) else stringResource(R.string.custom_editor_edit, draft.label),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = draft.label,
                onValueChange = { onChange(draft.copy(label = it.take(32))) },
                label = { Text(stringResource(R.string.custom_editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.custom_editor_base), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.customs?.bases.orEmpty().forEach { base ->
                    FilterChip(
                        selected = draft.base == base.id,
                        onClick = { onChange(draft.copy(base = base.id)) },
                        label = { Text(ui.baseName(base.id).asString()) },
                    )
                }
            }
            Text(
                stringResource(R.string.custom_editor_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CustomFields.sections.forEach { (section, title) ->
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                CustomFields.all.filter { it.section == section }.forEach { field ->
                    val inherited = baseValues.text(field.key) ?: stringResource(R.string.custom_editor_kernel_default)
                    val range = field.range?.takeIf { it.last < Int.MAX_VALUE }?.let { " · ${it.first}–${it.last}" }.orEmpty()
                    OutlinedTextField(
                        value = draft.value(field.key),
                        onValueChange = { onChange(draft.with(field.key, it.take(128))) },
                        label = { Text(stringResource(field.title)) },
                        placeholder = { Text(inherited) },
                        supportingText = {
                            val hint = field.hint?.let { " · " + stringResource(it) }.orEmpty()
                            Text(stringResource(R.string.custom_editor_base_value, inherited) + range + hint)
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
                Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !ui.saving) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave, enabled = !ui.saving) {
                    if (ui.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
