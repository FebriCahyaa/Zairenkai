// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Visual language of the setup flow: a soft luminous backdrop and one
 * continuous "thread" — a flowing line that lives across every step and
 * changes shape with the step's state (curling on welcome, lively while the
 * system check runs, calm once it settles, its energy following the chosen
 * profile, drawing itself in on completion).
 *
 * The thread is a closed-form curve: every phase term uses an integer
 * multiple of one 0..1 cycle, so the infinite animation wraps with no visible
 * reset. Shape parameters animate with springs, and the animated values are
 * read only in the draw phase, so motion never recomposes the screen. The
 * Path object is reused frame to frame.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zairenkai.app.domain.ProfileId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Shape of the thread for one moment of the flow. Pure, so it is unit tested. */
data class ThreadShape(
    /** Vertical wave height as a share of the canvas height. */
    val amplitude: Float,
    /** Curl radius as a share of the canvas width; above ~0.15 the line forms loops. */
    val curl: Float,
    /** Vertical centre as a share of the canvas height. */
    val center: Float,
    /** Stroke width in dp. */
    val width: Float,
    val alpha: Float,
)

/** The thread for a step, from real state only: checking vs settled, the chosen profile. */
fun threadShape(step: SetupStep, checksSettled: Boolean, choice: ProfileId?): ThreadShape = when (step) {
    SetupStep.WELCOME -> ThreadShape(amplitude = 0.22f, curl = 0.19f, center = 0.5f, width = 3f, alpha = 1f)
    SetupStep.CHECK -> if (checksSettled) {
        ThreadShape(amplitude = 0.08f, curl = 0.04f, center = 0.5f, width = 2.5f, alpha = 0.8f)
    } else {
        ThreadShape(amplitude = 0.18f, curl = 0.13f, center = 0.5f, width = 2.5f, alpha = 0.9f)
    }
    SetupStep.PROFILE -> when (choice) {
        ProfileId.BATTERY -> ThreadShape(0.05f, 0.02f, 0.5f, 2.5f, 0.85f)
        ProfileId.BALANCED -> ThreadShape(0.11f, 0.06f, 0.5f, 2.5f, 0.9f)
        ProfileId.PERFORMANCE -> ThreadShape(0.17f, 0.12f, 0.5f, 3f, 0.95f)
        ProfileId.GAME -> ThreadShape(0.21f, 0.18f, 0.5f, 3f, 1f)
        ProfileId.AUTO -> ThreadShape(0.13f, 0.1f, 0.5f, 2.5f, 0.9f)
        null -> ThreadShape(0.09f, 0.05f, 0.5f, 2.5f, 0.8f)
    }
    SetupStep.DONE -> ThreadShape(amplitude = 0.06f, curl = 0f, center = 0.5f, width = 3f, alpha = 1f)
}

/** The system animator scale is 0 ("Remove animations"): show still frames. */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Soft, luminous backdrop: the surface colour with two large, faint glows of
 * the primary and tertiary roles whose positions glide between steps.
 */
@Composable
fun SetupBackdrop(step: SetupStep, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val spec = spring<Float>(stiffness = Spring.StiffnessVeryLow)
    val gx by animateFloatAsState(if (step.ordinal % 2 == 0) 0.85f else 0.2f, spec, label = "glowX")
    val gy by animateFloatAsState(0.12f + step.ordinal * 0.04f, spec, label = "glowY")
    val warm = if (dark) cs.primary.copy(alpha = 0.16f) else cs.primaryContainer.copy(alpha = 0.75f)
    val cool = if (dark) cs.tertiary.copy(alpha = 0.10f) else cs.tertiaryContainer.copy(alpha = 0.55f)
    Box(
        modifier.drawBehind {
            drawRect(cs.surface)
            val r = max(size.width, size.height)
            drawRect(
                Brush.radialGradient(listOf(warm, Color.Transparent), Offset(size.width * gx, size.height * gy), r * 0.62f),
            )
            drawRect(
                Brush.radialGradient(listOf(cool, Color.Transparent), Offset(size.width * (1f - gx), size.height * 0.92f), r * 0.55f),
            )
        },
    ) {
        // Text drawn straight on the backdrop (not inside a Surface) must still
        // take the theme's content colour, or it falls back to black in dark mode.
        CompositionLocalProvider(LocalContentColor provides cs.onSurface) { content() }
    }
}

/**
 * The flowing thread. [drawIn] (0..1) trims the visible length so the line
 * can draw itself in; it animates from 0 when [drawKey] changes.
 */
@Composable
fun SetupThread(shape: ThreadShape, modifier: Modifier = Modifier, drawKey: Any? = null) {
    val still = reducedMotion()
    val soft = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow)
    val amplitude by animateFloatAsState(shape.amplitude, soft, label = "amp")
    val curl by animateFloatAsState(shape.curl, soft, label = "curl")
    val center by animateFloatAsState(shape.center, soft, label = "center")
    val width by animateFloatAsState(shape.width, soft, label = "width")
    val alpha by animateFloatAsState(shape.alpha, soft, label = "alpha")
    val start by animateColorAsState(MaterialTheme.colorScheme.primary, tween(400), label = "c0")
    val end by animateColorAsState(MaterialTheme.colorScheme.tertiary, tween(400), label = "c1")

    val cycle = if (still) null else rememberInfiniteTransition(label = "thread").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "phase",
    )
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(drawKey) {
        if (drawKey != null && !still) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(1100))
        }
    }

    val path = remember { Path() }
    Canvas(modifier) {
        val t = cycle?.value ?: 0.15f
        val w = size.width
        val h = size.height
        val a = amplitude * h
        val r = curl * w
        val cy = center * h
        val steps = 180
        val visible = (steps * reveal.value).toInt().coerceAtLeast(1)
        // Two strands: the main line and a fainter echo half a cycle behind.
        for (strand in 0..1) {
            path.reset()
            val shift = strand * 0.5f
            for (i in 0..visible) {
                val s = i / steps.toFloat()
                val p = 2f * PI.toFloat() * (s * 2f + t + shift)
                val x = -0.08f * w + s * 1.16f * w + r * sin(p)
                val y = cy + a * sin(2f * PI.toFloat() * (s + t * 1f + shift)) + r * 0.85f * cos(p)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                brush = Brush.horizontalGradient(listOf(start, end, start)),
                alpha = alpha * if (strand == 0) 1f else 0.28f,
                style = Stroke(width.dp.toPx() * if (strand == 0) 1f else 0.7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** One-shot completion ring: a stroke sweeps closed while a faint halo expands. */
@Composable
fun CompletionRing(color: Color, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val sweep = remember { Animatable(if (still) 1f else 0f) }
    val halo = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        sweep.animateTo(1f, tween(800))
        halo.animateTo(1f, tween(700))
    }
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(color.copy(alpha = 0.15f), 0f, 360f, false, tl, androidx.compose.ui.geometry.Size(d, d), style = Stroke(stroke))
        drawArc(color, -90f, 360f * sweep.value, false, tl, androidx.compose.ui.geometry.Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        if (halo.value > 0f && halo.value < 1f) {
            drawCircle(color.copy(alpha = 0.25f * (1f - halo.value)), radius = d / 2 * (1f + 0.35f * halo.value), style = Stroke(stroke / 2))
        }
    }
}
