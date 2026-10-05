// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zairenkai theme. Uses Android 12+ dynamic color when available, otherwise
 * the brand palette. Material 3 (expressive baseline).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = ZkIndigoDark,
    secondary = ZkAquaDark,
    tertiary = ZkAmberDark,
)

private val LightColors = lightColorScheme(
    primary = ZkIndigo,
    secondary = ZkAqua,
    tertiary = ZkAmber,
)

@Composable
fun ZairenkaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZkTypography,
        content = content,
    )
}
