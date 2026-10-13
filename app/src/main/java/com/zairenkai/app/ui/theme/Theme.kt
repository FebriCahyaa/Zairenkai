// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zairenkai theme (Material 3, expressive typeface + optional Glass
 * UI). Theme mode, accent and dynamic color are user-selectable in Settings →
 * Tema.
 *
 * MaterialExpressiveTheme / MotionScheme are internal in the stable
 * material3 1.4.x pulled in by the Compose BOM, so they cannot be used
 * without a pre-release dependency. The theme uses the stable MaterialTheme;
 * switch back once material3 1.5 is stable.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

internal fun schemeFor(accent: Accent, dark: Boolean) = if (dark) {
    val p = accent.dark
    darkColorScheme(
        primary = p,
        secondary = lerp(p, Color(0xFF9FE8DF), 0.35f),
        tertiary = lerp(p, Color(0xFFFFD08A), 0.4f),
    )
} else {
    // Light accents can be too bright for primary-coloured text and for white
    // text on a primary button. Darken along the accent's own hue until both
    // reach 4.5:1 against the brightest container (checked by ContrastTest).
    val base = lightColorScheme()
    val p = readableOn(accent.light, base.surfaceContainerHigh, Color.White)
    lightColorScheme(
        primary = p,
        secondary = readableOn(lerp(p, Color(0xFF009E96), 0.35f), base.surfaceContainerHigh, Color.White),
        tertiary = readableOn(lerp(p, Color(0xFFB9791B), 0.4f), base.surfaceContainerHigh, Color.White),
    )
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}

/** [c] darkened just enough to read (4.5:1) on [surface] and under [onColor]. */
internal fun readableOn(c: Color, surface: Color, onColor: Color, min: Float = 4.5f): Color {
    var out = c
    var t = 0f
    while ((contrast(out, surface) < min || contrast(onColor, out) < min) && t < 1f) {
        t += 0.04f
        out = lerp(c, Color.Black, t)
    }
    return out
}

/**
 * [c] moved just far enough towards black (on a light [surface]) or white (on
 * a dark one) to read as text there (4.5:1). For status colours such as
 * [ZkGood], which are too light for text on light surfaces.
 */
internal fun readableInk(c: Color, surface: Color, min: Float = 4.5f): Color {
    val target = if (surface.luminance() < 0.5f) Color.White else Color.Black
    var out = c
    var t = 0f
    while (contrast(out, surface) < min && t < 1f) {
        t += 0.04f
        out = lerp(c, target, t)
    }
    return out
}

/**
 * A status colour ([ZkGood], [ZkWarn], [ZkBad] …) that reads on every surface
 * container of [cs], and on its own 12 % tint (status pills), hence the margin
 * above 4.5:1. Checked by ContrastTest.
 */
internal fun statusInkOn(c: Color, cs: ColorScheme): Color = readableInk(c, cs.surfaceContainerHighest, min = 5.2f)

@Composable
@ReadOnlyComposable
fun statusInk(c: Color): Color = statusInkOn(c, MaterialTheme.colorScheme)

/** A status colour for graphics (gauges, dots): 3:1 against every container, so it stays bright. */
@Composable
@ReadOnlyComposable
fun statusGraphic(c: Color): Color = readableInk(c, MaterialTheme.colorScheme.surfaceContainerHighest, min = 3f)

@Composable
fun ZairenkaiTheme(
    themeMode: String = "system",
    accentId: String = "indigo",
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
    glass: GlassConfig = GlassConfig(),
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val base = when {
        // minSdk is 31 (S), so dynamic color is always supported.
        dynamicColor ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> schemeFor(accentById(accentId), dark)
    }
    // AMOLED: pure-black backgrounds in dark mode. Containers stay a hair above
    // black so cards remain legible; text/accent colours are untouched.
    val colorScheme = if (dark && amoled) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0B),
            surfaceContainer = Color(0xFF111113),
            surfaceContainerHigh = Color(0xFF17171A),
            surfaceContainerHighest = Color(0xFF1E1E22),
        )
    } else base
    SystemBarsEffect(dark)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZkTypography,
    ) {
        CompositionLocalProvider(LocalGlass provides glass, content = content)
    }
}
