// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zairenkai theme (Material 3, expressive typeface + motion + optional Glass
 * UI). Theme mode, accent and dynamic color are user-selectable in Settings →
 * Tema. (MaterialExpressiveTheme is still internal in this Compose release, so
 * the expressive feel comes from Roboto Flex, spring motion and Glass.)
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.theme

import android.os.Build
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
import androidx.compose.ui.platform.LocalContext

private fun schemeFor(accent: Accent, dark: Boolean) = if (dark) {
    val p = accent.dark
    darkColorScheme(
        primary = p,
        secondary = lerp(p, Color(0xFF9FE8DF), 0.35f),
        tertiary = lerp(p, Color(0xFFFFD08A), 0.4f),
    )
} else {
    val p = accent.light
    lightColorScheme(
        primary = p,
        secondary = lerp(p, Color(0xFF009E96), 0.35f),
        tertiary = lerp(p, Color(0xFFB9791B), 0.4f),
    )
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
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> schemeFor(accentById(accentId), dark)
    }
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ZkTypography,
            content = content,
        )
    }
}
