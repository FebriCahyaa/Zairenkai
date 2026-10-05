// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Entry activity: edge-to-edge, splash, theme, root nav.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.zairenkai.app.data.AppSettings
import com.zairenkai.app.ui.ZairenkaiRoot
import com.zairenkai.app.ui.theme.ZairenkaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ZairenkaiApp).container
        setContent {
            val settings by container.settings.settings.collectAsState(initial = AppSettings())
            ZairenkaiTheme(dynamicColor = settings.dynamicColor) {
                ZairenkaiRoot(container)
            }
        }
    }
}
