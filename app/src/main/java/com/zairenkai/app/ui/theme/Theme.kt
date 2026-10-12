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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

@Composable
fun ZairenkaiTheme(
    themeMode: String = "system",
    accentId: String = "indigo",
    dynamicColor: Boolean = true,
    glass: GlassConfig = GlassConfig(),
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        // minSdk is 31 (S), so dynamic color is always supported.
        dynamicColor ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> schemeFor(accentById(accentId), dark)
    }
    SystemBarsEffect(dark)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZkTypography,
    ) {
        CompositionLocalProvider(LocalGlass provides glass, content = content)
    }
}
