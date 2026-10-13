// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The system check step and its pieces: an animated status badge (a turning
 * arc while checking; a check, cross or "!" drawn in when the probe answers),
 * the check row shared with the home screen, and an alert that says plainly
 * what is missing. Every state comes from the real probes; the animation
 * never decides an outcome.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.R
import com.zairenkai.app.core.text.asString
import com.zairenkai.app.ui.icons.ZkIcons
import com.zairenkai.app.ui.mascot.Mascot
import com.zairenkai.app.ui.mascot.checkMascotState
import com.zairenkai.app.ui.theme.ZkBad
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkWarn
import com.zairenkai.app.ui.theme.statusGraphic
import com.zairenkai.app.ui.theme.statusInk

@Composable
internal fun CheckStep(probe: SetupProbe, checks: List<SetupCheck>, onRecheck: () -> Unit) {
    val settled = checks.settled()
    val allOk = checks.all { it.state == CheckState.OK }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Mascot(checkMascotState(settled, allOk), size = 180.dp)
    }
    StepTitle(stringResource(R.string.setup_check_title), stringResource(R.string.setup_check_body))
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.reveal(2),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            checks.forEachIndexed { i, c ->
                CheckRow(c, Modifier.padding(horizontal = 16.dp, vertical = 10.dp).reveal(3 + i))
                if (i != checks.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 66.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
    AnimatedVisibility(settled, enter = fadeIn(tween(250)) + expandVertically(tween(PAGE_MS)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!allOk) {
                val ready = checks.profilesReady()
                AlertCard(
                    warning = ready,
                    title = stringResource(if (ready) R.string.setup_alert_notes_title else R.string.setup_alert_missing_title),
                    body = stringResource(if (ready) R.string.setup_check_ready_with_notes else R.string.setup_check_not_ready),
                )
            } else {
                Text(
                    stringResource(R.string.setup_check_all_ok),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            var showDetails by rememberSaveable { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onRecheck, shape = CircleShape) {
                    Icon(ZkIcons.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.setup_recheck))
                }
                if (!allOk) TextButton(onClick = { showDetails = !showDetails }) {
                    Text(stringResource(if (showDetails) R.string.dashboard_details_hide else R.string.dashboard_details_show))
                }
            }
            // The diagnostics panel is full width BELOW the row — never a sibling
            // of the button in the row, where it would be squeezed to a sliver.
            if (!allOk && showDetails) TechnicalDetails(probe)
        }
    }
}

/** A plain-language alert: amber for notes, red for something missing. */
@Composable
internal fun AlertCard(warning: Boolean, title: String, body: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = if (warning) cs.tertiaryContainer to cs.onTertiaryContainer else cs.errorContainer to cs.onErrorContainer
    Surface(shape = RoundedCornerShape(24.dp), color = container, contentColor = content, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Icon(ZkIcons.Warning, null, Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Raw probe output for support; rendered full width by the caller, never as a
 *  reason. Horizontally scrollable so long monospace lines are not wrapped. */
@Composable
internal fun TechnicalDetails(probe: SetupProbe) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            diagnosticsReport(probe, BuildConfig.VERSION_NAME),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun stateLabel(state: CheckState): Int = when (state) {
    CheckState.CHECKING -> R.string.setup_state_checking
    CheckState.OK -> R.string.setup_state_ok
    CheckState.WARN -> R.string.setup_state_warn
    CheckState.MISSING -> R.string.setup_state_missing
}

/** One readiness row; the badge animates as the probe answers. Shared with the home screen. */
@Composable
internal fun CheckRow(check: SetupCheck, modifier: Modifier = Modifier) {
    val title = stringResource(check.id.title)
    val detail = check.detail.asString()
    val a11y = stringResource(R.string.setup_check_a11y, title, stringResource(stateLabel(check.state)), detail)
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = a11y }) {
        StatusBadge(check.state)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).animateContentSize(tween(250)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            check.fix?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

/**
 * A round badge: a turning arc while checking; once the probe answers, the
 * ring closes and a check, a cross or a "!" is drawn in (420 ms). Shape and
 * colour both carry the state, so it never relies on colour alone.
 */
@Composable
internal fun StatusBadge(state: CheckState, size: Dp = 36.dp) {
    val color = when (state) {
        CheckState.CHECKING -> MaterialTheme.colorScheme.primary
        CheckState.OK -> statusGraphic(ZkGood)
        CheckState.WARN -> statusGraphic(ZkWarn)
        CheckState.MISSING -> statusGraphic(ZkBad)
    }
    val ink = when (state) {
        CheckState.CHECKING -> MaterialTheme.colorScheme.primary
        CheckState.OK -> statusInk(ZkGood)
        CheckState.WARN -> statusInk(ZkWarn)
        CheckState.MISSING -> statusInk(ZkBad)
    }
    val bg by animateColorAsState(color.copy(alpha = 0.14f), tween(250), label = "badgeBg")
    val still = reducedMotion()
    val draw = remember(state) { Animatable(if (still || state == CheckState.CHECKING) 1f else 0f) }
    LaunchedEffect(state) { if (draw.value < 1f) draw.animateTo(1f, tween(420, easing = EmphasizedDecelerate)) }
    val spin = if (state == CheckState.CHECKING && !still) {
        rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    } else null
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(size * 0.22f)) {
            val w = this.size.minDimension * 0.13f
            when (state) {
                CheckState.CHECKING -> drawArc(color, spin?.value ?: 0f, 270f, false, style = Stroke(w, cap = StrokeCap.Round))
                CheckState.OK -> {
                    drawArc(color, -90f, 360f * draw.value, false, style = Stroke(w * 0.7f, cap = StrokeCap.Round))
                    trimmed(Path().apply { moveTo(0.28f, 0.53f); lineTo(0.44f, 0.68f); lineTo(0.73f, 0.36f) }, draw.value, ink, w)
                }
                CheckState.MISSING -> {
                    trimmed(Path().apply { moveTo(0.32f, 0.32f); lineTo(0.68f, 0.68f) }, (draw.value * 2f).coerceAtMost(1f), ink, w)
                    trimmed(Path().apply { moveTo(0.68f, 0.32f); lineTo(0.32f, 0.68f) }, (draw.value * 2f - 1f).coerceAtLeast(0f), ink, w)
                }
                CheckState.WARN -> {
                    trimmed(Path().apply { moveTo(0.5f, 0.24f); lineTo(0.5f, 0.58f) }, draw.value, ink, w)
                    if (draw.value > 0.7f) drawCircle(ink, w * 0.7f, Offset(this.size.width * 0.5f, this.size.height * 0.76f))
                }
            }
        }
    }
}

/** Draws [unitPath] (0..1 coordinates) scaled to the canvas, up to [fraction] of its length. */
private fun DrawScope.trimmed(unitPath: Path, fraction: Float, color: Color, width: Float) {
    if (fraction <= 0f) return
    val p = Path().apply { addPath(unitPath) }
    p.transform(androidx.compose.ui.graphics.Matrix().apply { scale(size.width, size.height) })
    val measure = PathMeasure().apply { setPath(p, false) }
    val out = Path()
    measure.getSegment(0f, measure.length * fraction, out, true)
    drawPath(out, color, style = Stroke(width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}
