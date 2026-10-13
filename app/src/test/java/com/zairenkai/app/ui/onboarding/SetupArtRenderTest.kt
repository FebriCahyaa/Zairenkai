// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Renders each setup animation as a filmstrip (light and dark), plus the
 * custom icons, for visual review (build/outputs/roborazzi). Also checks
 * that every animation loads and lasts a sensible time.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.github.takahirom.roborazzi.captureRoboImage
import com.zairenkai.app.R
import com.zairenkai.app.shotPath
import com.zairenkai.app.ui.icons.ZkIcons
import com.zairenkai.app.ui.theme.ZairenkaiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-mdpi", application = android.app.Application::class)
class SetupArtRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // The check states are the mascot's (MascotRenderTest); these remain line art.
    private val anims = listOf("welcome" to R.raw.setup_welcome, "tune" to R.raw.setup_tune, "success" to R.raw.setup_success)
    private val stops = listOf(0.05f, 0.2f, 0.35f, 0.5f, 0.65f, 0.8f, 1f)

    private fun strip(dark: Boolean) {
        val compositions = anims.map { (name, res) ->
            val c = LottieCompositionFactory.fromRawResSync(rule.activity, res).value
            assertTrue("$name loads: " + LottieCompositionFactory.fromRawResSync(rule.activity, res).exception?.let { it.toString() + " / " + it.cause }, c != null)
            assertTrue("$name lasts 1-7 s", c!!.duration in 1000f..7000f)
            name to c
        }
        rule.setContent {
            ZairenkaiTheme(themeMode = if (dark) "dark" else "light", dynamicColor = false) {
                val grad = brandGradient(dark).map { it.toArgb() }.toTypedArray()
                val dyn = rememberLottieDynamicProperties(
                    rememberLottieDynamicProperty(LottieProperty.GRADIENT_COLOR, grad, "**", "brandGradient"),
                    rememberLottieDynamicProperty(LottieProperty.COLOR, MaterialTheme.colorScheme.primary.toArgb(), "**", "primary"),
                )
                Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                    compositions.forEach { (name, c) ->
                        Text(name, color = MaterialTheme.colorScheme.onSurface)
                        Row {
                            stops.forEach { p -> LottieAnimation(c, progress = { p }, dynamicProperties = dyn, modifier = Modifier.size(108.dp)) }
                        }
                    }

                }
            }
        }
        rule.onRoot().captureRoboImage(shotPath("art_${if (dark) "dark" else "light"}.png"))
    }

    /** Every icon of the app's own set, labelled. */
    @Test
    fun icons() {
        rule.setContent {
            ZairenkaiTheme(themeMode = "light", dynamicColor = false) {
                Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                    ZkIcons.all.chunked(10).forEach { row ->
                        Row {
                            row.forEach { (name, icon) ->
                                Column(Modifier.size(78.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(icon, null, Modifier.padding(8.dp).size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage(shotPath("icons.png"))
    }

    @Test fun light() = strip(false)
    @Test fun dark() = strip(true)

    /** The launcher icon in common masks, the themed (monochrome) icon, and the splash mark. */
    @Test
    fun appIcon() {
        rule.setContent {
            val shapes = listOf(CircleShape, RoundedCornerShape(30), RoundedCornerShape(18))
            Row(Modifier.background(Color(0xFFECE6F0)).padding(16.dp)) {
                shapes.forEach { shape ->
                    Box(Modifier.padding(8.dp).size(108.dp).clip(shape)) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                    }
                }
                Box(Modifier.padding(8.dp).size(108.dp).clip(CircleShape).background(Color(0xFFDDE3FF))) {
                    Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.fillMaxSize(),
                        colorFilter = ColorFilter.tint(Color(0xFF283593)))
                }
                Box(Modifier.padding(8.dp).size(108.dp).background(Color(0xFF0E0E16))) {
                    Image(painterResource(R.drawable.ic_zk_logo), null, Modifier.fillMaxSize())
                }
            }
        }
        rule.onRoot().captureRoboImage(shotPath("app_icon.png"))
    }
}
