// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Tema — change the app's appearance: mode, dynamic color, accent, and the
 * Glass UI with its full controls. Everything previews live.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import com.zairenkai.app.ui.icons.ZkIcons
import com.zairenkai.app.ui.components.CollapsingScaffold
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.data.AppSettings
import com.zairenkai.app.ui.SettingsViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.theme.ZkAccents
import com.zairenkai.app.ui.theme.accentById

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(factory: VmFactory, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val s by vm.settings.collectAsState(initial = AppSettings())

    CollapsingScaffold(stringResource(R.string.theme_title), onBack = onBack) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ThemePreview(s)

            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val modes = listOf(
                        Triple("system", R.string.theme_mode_system, ZkIcons.Contrast),
                        Triple("light", R.string.theme_mode_light, ZkIcons.Sun),
                        Triple("dark", R.string.theme_mode_dark, ZkIcons.Moon),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        modes.forEach { (id, label, icon) ->
                            ModeCard(stringResource(label), icon, s.themeMode == id, Modifier.weight(1f)) { vm.setThemeMode(id) }
                        }
                    }
                    val dark = s.themeMode == "dark" || (s.themeMode == "system" && androidx.compose.foundation.isSystemInDarkTheme())
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.theme_amoled), style = MaterialTheme.typography.titleSmall, color = if (dark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                stringResource(if (dark) R.string.theme_amoled_sub else R.string.theme_amoled_dark_only),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = s.amoled, enabled = dark, onCheckedChange = { vm.setAmoled(it) })
                    }
                }
            }

            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp)) {
                    // minSdk 31: dynamic color (Android 12) is always available.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.theme_dynamic_color), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.theme_dynamic_color_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = s.dynamicColor, onCheckedChange = { vm.setDynamicColor(it) })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.theme_accent), style = MaterialTheme.typography.titleMedium,
                        color = if (s.dynamicColor) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ZkAccents.forEach { a ->
                            val c = a.light
                            val selected = s.accent == a.id && !s.dynamicColor
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(c)
                                    .border(
                                        if (selected) 3.dp else 0.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape,
                                    )
                                    .clickable(enabled = !s.dynamicColor) { vm.setAccent(a.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(ZkIcons.Check, null, tint = Color.White)
                            }
                        }
                    }
                    if (s.dynamicColor) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.theme_accent_disabled_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Glass UI
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.theme_glass), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.theme_glass_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = s.glass, onCheckedChange = { vm.setGlass(it) })
                    }
                    AnimatedVisibility(
                        visible = s.glass,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(Modifier.padding(top = 14.dp)) {
                            SliderRow(stringResource(R.string.theme_glass_opacity), s.glassOpacity, 0.35f..0.95f) { vm.setGlassOpacity(it) }
                            SliderRow(stringResource(R.string.theme_glass_blur), s.glassBlur, 0f..40f) { vm.setGlassBlur(it) }
                            SliderRow(stringResource(R.string.theme_glass_tint), s.glassTint, 0f..0.4f) { vm.setGlassTint(it) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A selectable mode tile with an icon and label. */
@Composable
private fun ModeCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null,
        modifier = modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** Live mini preview reflecting the current theme choices. */
@Composable
private fun ThemePreview(s: AppSettings) {
    val accent = accentById(s.accent)
    val dark = when (s.themeMode) {
        "light" -> false
        "dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val seed = if (dark) accent.dark else accent.light
    val lift by animateFloatAsState(if (s.glass) 1f else 0f, label = "lift")

    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(seed.copy(alpha = 0.9f), seed.copy(alpha = 0.5f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)),
                ),
            ),
    ) {
        // floating glass chip to show the effect
        Surface(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.82f).height(72.dp)
                .border(1.dp, Color.White.copy(alpha = 0.18f + 0.1f * lift), RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.14f + 0.14f * lift),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ZkIcons.Bolt, null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Zairenkai", color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (s.glass) R.string.theme_preview_glass_active else R.string.theme_preview),
                        color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
