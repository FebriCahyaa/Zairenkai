// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Appearance step: theme mode, accent and dynamic colour, with a live
 * preview of the app in a phone frame (a miniature that plays: the content
 * scrolls, the gauge fills, the bars move, the selected tab glides), and the
 * features as swipeable cards. Choices are saved as they are made, so the
 * preview and the whole setup follow them at once.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zairenkai.app.R
import com.zairenkai.app.ui.icons.ZkIcons
import com.zairenkai.app.ui.theme.ZkAccents
import kotlin.math.sin

@Composable
internal fun ThemeStep(ui: OnboardingUi, onThemeMode: (String) -> Unit, onAccent: (String) -> Unit, onDynamic: (Boolean) -> Unit) {
    StepTitle(stringResource(R.string.setup_theme_title), stringResource(R.string.setup_theme_body))
    Box(Modifier.fillMaxWidth().reveal(1), contentAlignment = Alignment.Center) { AppPreview() }
    Text(stringResource(R.string.setup_theme_mode), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.reveal(2))
    Row(Modifier.reveal(2), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple("system", R.string.theme_mode_system, ZkIcons.Contrast),
            Triple("light", R.string.theme_mode_light, ZkIcons.Sun),
            Triple("dark", R.string.theme_mode_dark, ZkIcons.Moon),
        ).forEach { (id, label, icon) ->
            ModeOption(stringResource(label), icon, ui.themeMode == id, Modifier.weight(1f)) { onThemeMode(id) }
        }
    }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        modifier = Modifier.reveal(3),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().toggleable(ui.dynamicColor, role = Role.Switch, onValueChange = onDynamic),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_theme_dynamic), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.setup_theme_dynamic_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.dynamicColor, onCheckedChange = null)
            }
            Text(
                stringResource(if (ui.dynamicColor) R.string.setup_theme_accent_off else R.string.setup_theme_accent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZkAccents.forEach { a ->
                    val selected = !ui.dynamicColor && ui.accent == a.id
                    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, tween(200), label = "ring")
                    Box(
                        Modifier
                            .size(40.dp)
                            .border(2.dp, ring, CircleShape)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(a.light, a.dark)))
                            .graphicsLayer { alpha = if (ui.dynamicColor) 0.4f else 1f }
                            .selectable(selected, enabled = !ui.dynamicColor, role = Role.RadioButton) { onAccent(a.id) }
                            .semantics { contentDescription = a.label },
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(ZkIcons.Check, null, Modifier.size(18.dp), tint = Color.White) }
                }
            }
        }
    }
    Text(stringResource(R.string.setup_features_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.reveal(4))
    FeaturePager(Modifier.reveal(4))
}

@Composable
private fun ModeOption(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val border by animateColorAsState(if (selected) cs.primary else cs.outlineVariant, tween(200), label = "border")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceContainerLow.copy(alpha = 0.94f),
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A miniature of the app in a phone frame, drawn with the active colours:
 * the home content scrolls slowly, the thermal gauge fills, the CPU bars
 * move, and the selected tab glides along the navigation bar. Decorative:
 * the caption names it for screen readers.
 */
@Composable
internal fun AppPreview(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val still = reducedMotion()
    val t: State<Float>? = if (still) null else rememberInfiniteTransition(label = "preview").animateFloat(
        0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    val label = stringResource(R.string.setup_theme_preview)
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(176.dp)
                .height(340.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(cs.surface)
                .border(6.dp, cs.inverseSurface.copy(alpha = 0.85f), RoundedCornerShape(30.dp)),
        ) {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val time = t?.value ?: 0.3f
                val w = size.width
                val h = size.height
                val u = w / 100f
                // Content scrolls down and back over the loop.
                val scroll = (sin(time * 2 * Math.PI.toFloat()) * 0.5f + 0.5f) * 60f * u
                val top = 16f * u - scroll
                // Arc backdrop.
                drawCircle(Brush.radialGradient(listOf(cs.primaryContainer.copy(alpha = 0.7f), Color.Transparent), Offset(w / 2, -h * 0.05f), w * 0.9f), w * 0.9f, Offset(w / 2, -h * 0.05f))
                // Greeting and gradient summary.
                drawRoundRect(cs.onSurface, Offset(8 * u, top), Size(56 * u, 7 * u), CornerRadius(3 * u))
                drawRoundRect(Brush.horizontalGradient(listOf(cs.primary, cs.tertiary)), Offset(8 * u, top + 11 * u), Size(70 * u, 5 * u), CornerRadius(3 * u))
                // Status chips.
                for (i in 0 until 4) {
                    drawRoundRect(cs.surfaceContainerHigh, Offset((8 + i * 21) * u, top + 22 * u), Size(18 * u, 7 * u), CornerRadius(4 * u))
                    drawCircle(Color(0xFF2FBF71), 1.6f * u, Offset((12 + i * 21) * u, top + 25.5f * u))
                }
                // Profile card.
                var y = top + 36 * u
                drawRoundRect(cs.primaryContainer, Offset(6 * u, y), Size(88 * u, 30 * u), CornerRadius(8 * u))
                drawRoundRect(cs.onPrimaryContainer.copy(alpha = 0.7f), Offset(12 * u, y + 7 * u), Size(26 * u, 4 * u), CornerRadius(2 * u))
                drawRoundRect(cs.onPrimaryContainer, Offset(12 * u, y + 14 * u), Size(40 * u, 7 * u), CornerRadius(3 * u))
                for (k in 0 until 4) {
                    val yy = y + 9 * u + k * 4 * u
                    val phase = time * 6.28f * 2 + k
                    drawLine(cs.primary, Offset(62 * u, yy + sin(phase) * u), Offset(88 * u, yy + sin(phase + 1.5f) * u), 1.1f * u, StrokeCap.Round)
                }
                // Thermal gauge card.
                y += 36 * u
                drawRoundRect(cs.surfaceContainer, Offset(6 * u, y), Size(88 * u, 34 * u), CornerRadius(8 * u))
                val fill = 0.25f + 0.55f * (sin(time * 2 * Math.PI.toFloat() * 2) * 0.5f + 0.5f)
                val gc = Offset(24 * u, y + 18 * u)
                drawArc(cs.surfaceContainerHighest, 150f, 240f, false, Offset(gc.x - 11 * u, gc.y - 11 * u), Size(22 * u, 22 * u), style = Stroke(3 * u, cap = StrokeCap.Round))
                drawArc(Color(0xFF2FBF71), 150f, 240f * fill, false, Offset(gc.x - 11 * u, gc.y - 11 * u), Size(22 * u, 22 * u), style = Stroke(3 * u, cap = StrokeCap.Round))
                for (k in 0 until 3) drawRoundRect(cs.onSurfaceVariant.copy(alpha = 0.6f), Offset(44 * u, y + (9 + k * 7) * u), Size((40 - k * 8) * u, 3 * u), CornerRadius(2 * u))
                // CPU bars card.
                y += 40 * u
                drawRoundRect(cs.surfaceContainer, Offset(6 * u, y), Size(88 * u, 38 * u), CornerRadius(8 * u))
                for (k in 0 until 8) {
                    val v = 0.25f + 0.7f * (sin(time * 6.28f * 3 + k * 0.9f) * 0.5f + 0.5f)
                    val bh = 26 * u * v
                    drawRoundRect(if (k < 4) cs.primary else cs.tertiary, Offset((12 + k * 10) * u, y + 32 * u - bh), Size(6 * u, bh), CornerRadius(2 * u))
                }
                // Device card.
                y += 44 * u
                drawRoundRect(cs.surfaceContainer, Offset(6 * u, y), Size(88 * u, 30 * u), CornerRadius(8 * u))
                for (k in 0 until 2) for (j in 0 until 2) {
                    drawRoundRect(cs.surfaceContainerHigh, Offset((10 + j * 42) * u, y + (5 + k * 12) * u), Size(38 * u, 9 * u), CornerRadius(3 * u))
                }
                // Navigation bar with a gliding selection.
                val navY = h - 16 * u
                drawRect(cs.surfaceContainer, Offset(0f, navY - 4 * u), Size(w, 20 * u))
                val sel = ((time * 5).toInt() % 5)
                val glide = (time * 5) % 1f
                val selX = (10 + (sel + if (glide > 0.8f) (glide - 0.8f) * 5 else 0f) * 20) * u
                drawRoundRect(cs.primaryContainer, Offset(selX - 7 * u, navY), Size(14 * u, 7 * u), CornerRadius(4 * u))
                for (k in 0 until 5) drawCircle(cs.onSurfaceVariant, 1.8f * u, Offset((10 + k * 20) * u, navY + 3.5f * u))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class Feature(val icon: ImageVector, val title: Int, val body: Int)

private val FEATURES = listOf(
    Feature(ZkIcons.Pulse, R.string.setup_feature_monitor_title, R.string.setup_feature_monitor_body),
    Feature(ZkIcons.Tune, R.string.setup_feature_verified_title, R.string.setup_feature_verified_body),
    Feature(ZkIcons.Shield, R.string.setup_feature_safety_title, R.string.setup_feature_safety_body),
    Feature(ZkIcons.Gamepad, R.string.setup_feature_fps_title, R.string.setup_feature_fps_body),
    Feature(ZkIcons.Phone, R.string.setup_feature_device_title, R.string.setup_feature_device_body),
)

/** The features as swipeable cards with a page indicator. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeaturePager(modifier: Modifier = Modifier) {
    val pager = rememberPagerState { FEATURES.size }
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(pager, pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth()) { page ->
            val f = FEATURES[page]
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = cs.surfaceContainerLow.copy(alpha = 0.94f),
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    // Cards ease back and fade a little as they slide away; the
                    // offset is read here (draw phase), not in composition.
                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    val k = 1f - 0.06f * kotlin.math.abs(offset).coerceAtMost(1f)
                    scaleX = k
                    scaleY = k
                    alpha = 1f - 0.35f * kotlin.math.abs(offset).coerceAtMost(1f)
                },
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(brandGradient(dark = false).take(2))),
                        contentAlignment = Alignment.Center,
                    // The deep (light-theme) stops in both themes, so white reads on them.
                    ) { Icon(f.icon, null, Modifier.size(28.dp), tint = Color.White) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(f.title), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                        Text(stringResource(f.body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(FEATURES.size) { i ->
                val active = pager.currentPage == i
                val c by animateColorAsState(if (active) cs.primary else cs.onSurface.copy(alpha = 0.2f), tween(200), label = "dot")
                Box(Modifier.size(width = if (active) 18.dp else 7.dp, height = 7.dp).clip(CircleShape).background(c))
            }
        }
    }
}
