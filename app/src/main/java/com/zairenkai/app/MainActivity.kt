// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Entry activity: edge-to-edge, splash, theme, first-run gate, root nav.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.zairenkai.app.data.AppSettings
import com.zairenkai.app.ui.ZairenkaiRoot
import com.zairenkai.app.ui.onboarding.Onboarding
import com.zairenkai.app.ui.theme.ZairenkaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ZairenkaiApp).container

        // Hold the splash until the first settings emission (avoids an
        // onboarding flash for returning users).
        var ready = false
        splash.setKeepOnScreenCondition { !ready }

        setContent {
            val settings by container.settings.settings.collectAsState(initial = null)
            ready = settings != null
            ZairenkaiTheme(dynamicColor = settings?.dynamicColor ?: true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val s = settings ?: return@Surface
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
