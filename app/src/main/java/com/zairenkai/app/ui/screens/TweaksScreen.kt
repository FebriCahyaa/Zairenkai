// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Tweaks: grouped device optimizations with inline editors.
 *
 * Every editor is driven by the runtime descriptor zperfd publishes: choices
 * come from the device's own allowlist and ranges from the engine's validator,
 * so the UI never offers a value the engine would reject. When zperfd is not
 * running the list is shown read-only.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.core.text.asString
import com.zairenkai.app.data.Tweak
import com.zairenkai.app.ui.TweaksViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.CollapsingScaffold
import com.zairenkai.app.ui.icons.ZkIcons
import kotlin.math.roundToLong

/** An icon for each tweak category; a generic tune icon otherwise. */
private fun categoryIcon(cat: String): ImageVector = when (cat.lowercase()) {
    "cpu" -> ZkIcons.Memory
    "gpu" -> ZkIcons.Chip
    "vm", "memory", "mem" -> ZkIcons.Storage
    "io", "i/o", "storage" -> ZkIcons.Storage
    "net", "network" -> ZkIcons.Globe
    "display", "screen" -> ZkIcons.Sun
    "thermal" -> ZkIcons.Thermometer
    else -> ZkIcons.Tune
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweaksScreen(factory: VmFactory) {
    val vm: TweaksViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    var editing by remember { mutableStateOf<Tweak?>(null) }

    CollapsingScaffold(stringResource(R.string.tweaks_title), onBack = null) { inner ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@CollapsingScaffold
        }
        val available = ui.tweaks.filter { it.available }
        val grouped = available.groupBy { it.category }
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ui.loadError?.let { message ->
                item {
                    Banner(message.asString(), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
                item { TextButton(onClick = { vm.load() }) { Text(stringResource(R.string.tweaks_retry)) } }
            }
            if (ui.loadError == null && !ui.writable) item {
                Banner(
                    stringResource(R.string.tweaks_readonly_banner),
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (ui.lite) item {
                Banner(stringResource(R.string.tweaks_lite_banner),
                    MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
            ui.error?.let { message ->
                item {
                    Banner(message.asString(), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                        onDismiss = vm::dismissMessages)
                }
            }
            ui.notice?.let { message ->
                item {
                    Banner(message.asString(), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                        onDismiss = vm::dismissMessages)
                }
            }
            if (ui.loadError == null && available.isEmpty()) item {
                Text(stringResource(R.string.tweaks_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            grouped.forEach { (cat, list) ->
                item { CategoryHeader(cat) }
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            list.forEachIndexed { i, t ->
                                val editable = ui.writable && t.lockedReason == null && ui.busyId == null
                                TweakRow(t, busy = ui.busyId == t.id, editable = editable) { editing = t }
                                if (i != list.lastIndex) HorizontalDivider(
                                    Modifier.padding(start = 16.dp, end = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { t ->
        TweakEditor(
            tweak = t,
            onDismiss = { editing = null },
            onApply = { v -> vm.set(t.id, v); editing = null },
        )
    }
}

@Composable
private fun Banner(message: String, container: Color, content: Color, onDismiss: (() -> Unit)? = null) {
    Surface(color = container, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = content)
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = content) }
        }
    }
}

@Composable
private fun CategoryHeader(cat: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
            Icon(categoryIcon(cat), null, Modifier.padding(7.dp).size(16.dp))
        }
        Text(cat.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TweakRow(t: Tweak, busy: Boolean, editable: Boolean, onClick: () -> Unit) {
    val shownValue = t.value ?: stringResource(R.string.tweaks_a11y_value_unread)
    val description = t.lockedReason?.let { stringResource(R.string.tweaks_a11y_row_locked, t.title, shownValue, it) }
        ?: stringResource(R.string.tweaks_a11y_row, t.title, shownValue)
    val clickMod = if (editable) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        Modifier.fillMaxWidth().then(clickMod).semantics { contentDescription = description }.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(t.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            t.lockedReason?.let {
                Text(stringResource(R.string.tweaks_locked, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)) {
                Text(
                    t.value ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        if (editable) Icon(ZkIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TweakEditor(tweak: Tweak, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val isChoice = tweak.kind == "choice"
    val rangeMin = tweak.min
    val rangeMax = tweak.max
    val range = if (tweak.kind == "range" && rangeMin != null && rangeMax != null && rangeMax > rangeMin) {
        rangeMin..rangeMax
    } else {
        null
    }
    val isRange = range != null
    var text by remember { mutableStateOf(tweak.value ?: "") }
    val parsed = text.trim().toLongOrNull()
    val error: String? = when {
        isChoice && tweak.choices.isEmpty() -> stringResource(R.string.tweaks_error_no_choices)
        isChoice && text !in tweak.choices -> stringResource(R.string.tweaks_error_pick_supported)
        range != null && parsed == null -> stringResource(R.string.tweaks_error_integer)
        range != null && parsed !in range -> stringResource(R.string.tweaks_error_out_of_range, range.first, range.last)
        !isChoice && !isRange && text.isBlank() -> stringResource(R.string.tweaks_error_empty)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tweak.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tweaks_current, tweak.value ?: "—"), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    isChoice -> ChoiceChips(tweak.choices, selected = text) { text = it }
                    range != null -> {
                        val min = range.first
                        val max = range.last
                        Slider(
                            value = (parsed ?: min).coerceIn(min, max).toFloat(),
                            onValueChange = { text = it.roundToLong().coerceIn(min, max).toString() },
                            valueRange = min.toFloat()..max.toFloat(),
                        )
                        OutlinedTextField(
                            value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '-' } },
                            singleLine = true, label = { Text(stringResource(R.string.tweaks_value_range, min, max)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text(stringResource(R.string.tweaks_value)) }, modifier = Modifier.fillMaxWidth())
                }
                if (tweak.liteHidden) {
                    Text(stringResource(R.string.tweaks_heavy_warning),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(text.trim()) }, enabled = error == null && text.trim() != tweak.value) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            FilterChip(selected = opt == selected, onClick = { onPick(opt) }, label = { Text(opt) })
        }
    }
}
