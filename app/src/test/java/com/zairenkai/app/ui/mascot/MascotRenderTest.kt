// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Renders the mascot in every state, as filmstrips of its Lottie (light and
 * dark, on the setup surfaces) for visual review (build/outputs/roborazzi).
 * Also checks that every pose loads and loops in a sensible time.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.mascot

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.compose.LottieAnimation
import com.github.takahirom.roborazzi.captureRoboImage
import com.zairenkai.app.shotPath
import com.zairenkai.app.ui.theme.ZairenkaiTheme
import com.zairenkai.app.ui.theme.ZkSetup
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w860dp-h900dp-mdpi", application = android.app.Application::class)
class MascotRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val stops = listOf(0f, 0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.9f)

    private fun sheet(dark: Boolean) {
        val compositions = MascotState.entries.map { st ->
            val r = LottieCompositionFactory.fromRawResSync(rule.activity, st.anim)
            assertTrue("${st.name} loads: ${r.exception}", r.value != null)
            assertTrue("${st.name} loops in 1.5-5 s", r.value!!.duration in 1500f..5000f)
            // Decode the embedded layers the way rememberLottieComposition does in the app.
            r.value!!.images.values.forEach { asset ->
                val bytes = Base64.decode(asset.fileName.substringAfter("base64,"), Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertTrue("${st.name}: ${asset.id} decodes", bitmap != null)
                asset.bitmap = bitmap
            }
            st to r.value!!
        }
        rule.setContent {
            ZairenkaiTheme(themeMode = if (dark) "dark" else "light", dynamicColor = false) {
                Column(Modifier.background(if (dark) ZkSetup.BackgroundDark else ZkSetup.BackgroundLight).padding(8.dp)) {
                    compositions.forEach { (st, c) ->
                        Text(st.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                        Row { stops.forEach { p -> LottieAnimation(c, progress = { p }, modifier = Modifier.size(120.dp)) } }
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage(shotPath("mascot_${if (dark) "dark" else "light"}.png"))
    }

    @Test fun light() = sheet(false)
    @Test fun dark() = sheet(true)
}
