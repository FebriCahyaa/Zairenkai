// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The setup's character. A soft pastel backdrop with a luminous arc that
 * breathes; a greeting that types itself (Halo -> Zairenkai -> a gradient
 * tagline) with a blinking caret; content that rises in, in order; and the
 * brand gradient used by the line animations (res/raw/setup_*.json).
 *
 * Animated values are read in the draw phase or in graphicsLayer, so motion
 * does not recompose the screen. With "Remove animations" (animator scale 0)
 * everything shows in its final state at once.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max

/** Material 3 "emphasized decelerate": content arriving. */
internal val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** The system animator scale is 0 ("Remove animations"): show final states. */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Brand gradient (blue -> violet -> pink -> amber), tuned for contrast on light or dark surfaces. */
fun brandGradient(dark: Boolean): List<Color> = if (dark) {
    listOf(Color(0xFF91A7FF), Color(0xFFB197FC), Color(0xFFF783AC), Color(0xFFFFC078))
} else {
    listOf(Color(0xFF3B5BDB), Color(0xFF7048E8), Color(0xFFD6336C), Color(0xFFE8590C))
}

@Composable
fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * Setup backdrop: the brand backdrop (a luminous arc across the top that
 * breathes slowly, and a soft glow at the bottom); the arc moves a little per step. The
 * arc's colours come from the scheme, so dynamic colour still applies.
 * Text drawn on it gets the scheme's onSurface colour.
 */
@Composable
fun SetupBackdrop(step: SetupStep, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val arcY by animateFloatAsState(
        if (step == SetupStep.WELCOME || step == SetupStep.DONE) -0.36f else -0.44f,
        spring(stiffness = Spring.StiffnessVeryLow), label = "arcY",
    )
    BrandBackdrop(arcY = { arcY }, modifier = modifier, content = content)
}

/**
 * The backdrop itself, shared by setup and the home screen. [arcY] is the
 * arc centre as a fraction of the height (negative: above the screen); it is
 * read in the draw phase, so moving it does not recompose.
 */
@Composable
fun BrandBackdrop(arcY: () -> Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    val still = reducedMotion()
    val breathe = if (still) null else rememberInfiniteTransition(label = "arc").animateFloat(
        0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse), label = "breathe",
    )
    // On dark surfaces the containers are deep and turn muddy when blended, so
    // the arc glows in the light pastels themselves, kept faint.
    val band = if (dark) {
        listOf(
            lerp(cs.tertiary, Color(0xFFFFC078), 0.5f).copy(alpha = 0.20f),
            lerp(cs.primary, Color(0xFFF783AC), 0.5f).copy(alpha = 0.24f),
            lerp(cs.secondary, Color(0xFFB197FC), 0.5f).copy(alpha = 0.30f),
        )
    } else {
        listOf(
            lerp(cs.tertiaryContainer, Color(0xFFFFD8A8), 0.35f).copy(alpha = 0.95f),
            lerp(cs.primaryContainer, Color(0xFFFFC9DE), 0.35f).copy(alpha = 0.95f),
            lerp(cs.secondaryContainer, Color(0xFFD0BFFF), 0.35f).copy(alpha = 0.95f),
        )
    }
    val low = lerp(cs.primaryContainer, Color(0xFFE5DBFF), 0.4f).copy(alpha = if (dark) 0.22f else 0.8f)
    Box(
        modifier.drawBehind {
            drawRect(cs.surface)
            val b = breathe?.value ?: 0.5f
            val r = max(size.width, size.height) * (0.62f + 0.02f * b)
            // The arc: a ring band of a huge circle whose centre sits above the screen.
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.74f to Color.Transparent,
                    0.83f to band[0],
                    0.9f to band[1],
                    0.96f to band[2],
                    1f to Color.Transparent,
                    center = Offset(size.width * (0.5f + 0.04f * (b - 0.5f)), size.height * arcY()),
                    radius = r,
                ),
            )
            drawRect(Brush.radialGradient(listOf(low, Color.Transparent), Offset(size.width * 0.5f, size.height * 1.12f), size.height * 0.5f))
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides cs.onSurface) { content() }
    }
}

/**
 * Rises and fades in on first composition, [order] steps after the others,
 * so a page reads top to bottom. Runs once per appearance.
 */
@Composable
fun Modifier.reveal(order: Int, start: Boolean = true): Modifier {
    val still = reducedMotion()
    val progress = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(start) {
        if (start && !still && progress.value < 1f) {
            delay(order * 70L)
            progress.animateTo(1f, tween(480, easing = EmphasizedDecelerate))
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 28.dp.toPx()
    }
}

/** The greeting script; pure so it is unit tested. Each frame is (line1, line2). */
internal fun greetingFrames(hello: String, brand: String, tagline: String): List<Pair<String, String>> = buildList {
    for (i in 1..hello.length) add(hello.take(i) to "")
    repeat(6) { add(hello to "") } // hold
    for (i in hello.length - 1 downTo 0) add(hello.take(i) to "")
    for (i in 1..brand.length) add(brand.take(i) to "")
    repeat(3) { add(brand to "") }
    for (i in 1..tagline.length) add(brand to tagline.take(i))
}

/**
 * ColorOS-style greeting: "Halo" types and erases, the brand types, then the
 * tagline types in the brand gradient, followed by a blinking caret. Space for
 * the final text is reserved, so nothing below moves while it types. Screen
 * readers get the final text at once.
 */
@Composable
fun TypewriterGreeting(
    hello: String,
    brand: String,
    tagline: String,
    style: TextStyle,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val still = reducedMotion()
    val frames = remember(hello, brand, tagline) { greetingFrames(hello, brand, tagline) }
    var index by rememberSaveable { mutableIntStateOf(if (still) frames.lastIndex else 0) }
    LaunchedEffect(frames) {
        while (index < frames.lastIndex) {
            delay(if (frames[index].second.isEmpty()) 85L else 45L)
            index++
        }
        onFinished()
    }
    val caret = if (still) null else rememberInfiniteTransition(label = "caret").animateFloat(
        1f, 0f, infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
    )
    val (line1, line2) = frames[index]
    val gradient = Brush.linearGradient(brandGradient(isDarkSurface()))
    val ink = MaterialTheme.colorScheme.onSurface
    val caretColor = MaterialTheme.colorScheme.primary
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = "$brand. $tagline"; heading() }) {
        // Reserved space: the final text, invisible.
        Text(buildAnnotatedString { append(brand); append('\n'); append(tagline) }, style = style, color = Color.Transparent)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = ink)) { append(line1) }
                if (line2.isEmpty()) appendCaret(caretColor, caret?.value)
                append('\n')
                if (line2.isNotEmpty()) {
                    withStyle(SpanStyle(brush = gradient)) { append(line2) }
                    appendCaret(caretColor, caret?.value)
                }
            },
            style = style,
        )
    }
}

/** A single line that types itself with a caret (the completion title, the home greeting), space reserved. */
@Composable
fun TypewriterLine(text: String, style: TextStyle, modifier: Modifier = Modifier, caretWhenDone: Boolean = true) {
    val still = reducedMotion()
    var shown by rememberSaveable(text) { mutableIntStateOf(if (still) text.length else 0) }
    LaunchedEffect(text) {
        while (shown < text.length) {
            delay(55L)
            shown++
        }
    }
    val caret = if (still) null else rememberInfiniteTransition(label = "caret").animateFloat(
        1f, 0f, infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
    )
    val ink = MaterialTheme.colorScheme.onSurface
    val caretColor = MaterialTheme.colorScheme.primary
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = text; heading() }) {
        Text(text, style = style, color = Color.Transparent)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = ink)) { append(text.take(shown)) }
                if (caretWhenDone || shown < text.length) appendCaret(caretColor, caret?.value)
            },
            style = style,
        )
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendCaret(color: Color, alpha: Float?) {
    if (alpha == null) return
    withStyle(SpanStyle(color = color.copy(alpha = alpha))) { append("|") }
}
