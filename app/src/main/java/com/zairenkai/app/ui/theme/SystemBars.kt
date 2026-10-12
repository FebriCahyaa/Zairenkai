// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * One system-bar policy for the whole app. Both bars are transparent (edge to
 * edge); every screen draws its own background behind them (the setup
 * backdrop, the bottom navigation bar, a top app bar). Icon contrast follows
 * the app's resolved theme, not the system's: enableEdgeToEdge()'s defaults
 * read the system dark mode, so choosing Dark in the app while the system is
 * light used to leave dark status icons on a dark page.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.zairenkai.app.ui.locale.findActivity

/** Transparent bars whose icons are light on a dark app theme and dark on a light one. */
fun ComponentActivity.applySystemBars(dark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
    )
    // Three-button navigation: the app always paints the area behind the bar
    // (bottom navigation or the page background), so the system's own
    // translucent scrim would only add a mismatched band.
    window.isNavigationBarContrastEnforced = false
}

/** Keeps the bars in step with the theme while it is in composition. */
@Composable
fun SystemBarsEffect(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return
    DisposableEffect(activity, dark) {
        activity.applySystemBars(dark)
        onDispose { }
    }
}
