// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Entry activity: edge-to-edge, splash, theme, first-run gate, root nav.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import com.zairenkai.app.ui.locale.AppLocale
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.zairenkai.app.ui.ZairenkaiRoot
import com.zairenkai.app.ui.onboarding.Onboarding
import com.zairenkai.app.ui.theme.GlassBackdrop
import com.zairenkai.app.ui.theme.GlassConfig
import com.zairenkai.app.ui.theme.ZairenkaiTheme
import com.zairenkai.app.ui.theme.applySystemBars

class MainActivity : ComponentActivity() {
    // Android 12 per-app language (Android 13+ is handled by the framework).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Until settings load (splash), follow the system; ZairenkaiTheme then
        // re-applies the same policy with the app's own light/dark choice.
        applySystemBars(dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        val container = (application as ZairenkaiApp).container

        // Hold the splash until the first settings emission (avoids an
        // onboarding flash for returning users).
        var ready = false
        splash.setKeepOnScreenCondition { !ready }

        setContent {
            val settings by container.settings.settings.collectAsState(initial = null)
            ready = settings != null
            val s = settings
            ZairenkaiTheme(
                themeMode = s?.themeMode ?: "system",
                accentId = s?.accent ?: "indigo",
                dynamicColor = s?.dynamicColor ?: true,
                glass = GlassConfig(
                    enabled = s?.glass ?: false,
                    opacity = s?.glassOpacity ?: 0.6f,
                    blur = s?.glassBlur ?: 18f,
                    tint = s?.glassTint ?: 0.12f,
                ),
            ) {
                val cs = MaterialTheme.colorScheme
                GlassBackdrop(
                    background = cs.background,
                    primary = cs.primary,
                    secondary = cs.secondary,
                    tertiary = cs.tertiary,
                    glass = GlassConfig(enabled = s?.glass ?: false),
                ) {
                    if (s != null) {
                        AnimatedContent(
                            targetState = s.onboarded,
                            transitionSpec = { fadeIn(tween(350)).togetherWith(fadeOut(tween(200))) },
                            label = "root",
                        ) { onboarded ->
                            if (onboarded) ZairenkaiRoot(container)
                            else Onboarding(container, onDone = {})
                        }
                    }
                }
            }
        }
    }
}
