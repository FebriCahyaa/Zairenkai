// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The collapsing title follows the scroll: expanded at the top, collapsed
 * (and stable) after scrolling down, expanded again back at the top. The
 * system-bar policy follows the app theme, not the system's.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.github.takahirom.roborazzi.captureRoboImage
import com.zairenkai.app.ui.theme.ZairenkaiTheme
import com.zairenkai.app.ui.theme.applySystemBars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.zairenkai.app.shotPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class CollapsingTitleUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun page(dark: Boolean = false) = rule.setContent {
        ZairenkaiTheme(themeMode = if (dark) "dark" else "light", dynamicColor = false) {
            CollapsingScaffold("Tentang aplikasi", onBack = {}) { inner ->
                LazyColumn(Modifier.fillMaxSize().testTag("list"), contentPadding = PaddingValues(top = inner.calculateTopPadding())) {
                    items(60) { i -> Text("Baris $i", Modifier.height(56.dp)) }
                }
            }
        }
    }

    private fun barHeight() = rule.onNodeWithTag("collapsingTopBar").getBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun titleCollapsesOnScrollAndExpandsBackAtTheTop() {
        page()
        val expanded = barHeight()
        rule.onRoot().captureRoboImage(shotPath("title_1_expanded.png"))

        // Real drag gestures: they go through nested scroll like a finger does.
        // Mid-drag (finger still down) the bar sits between both states, following the finger.
        rule.onNodeWithTag("list").performTouchInput { down(center); moveBy(Offset(0f, -60f)); moveBy(Offset(0f, -60f)) }
        rule.waitForIdle()
        val partial = barHeight()
        assertTrue("drag partly collapses ($expanded -> $partial)", partial < expanded)
        rule.onNodeWithTag("list").performTouchInput { up() }
        rule.waitForIdle()
        // Released half-way it settles to a whole state (no half-collapsed bar left behind).
        val settled = barHeight()
        assertTrue(settled.value == expanded.value || settled.value < partial.value)
        rule.onNodeWithTag("list").performTouchInput { swipeUp(startY = centerY, endY = centerY - 400f, durationMillis = 300) }
        rule.waitForIdle()
        val collapsed = barHeight()
        rule.onRoot().captureRoboImage(shotPath("title_2_collapsed.png"))
        assertTrue("bar must collapse ($expanded -> $collapsed)", collapsed < expanded)

        // Scrolling further keeps the collapsed toolbar stable.
        rule.onNodeWithTag("list").performTouchInput { swipeUp(durationMillis = 150) }
        rule.waitForIdle()
        assertEquals(collapsed.value, barHeight().value, 0.5f)

        // Back to the top (fling down repeatedly): the title expands again.
        repeat(6) { rule.onNodeWithTag("list").performTouchInput { swipeDown(durationMillis = 150) } }
        rule.waitForIdle()
        assertEquals(expanded.value, barHeight().value, 0.5f)
        // One title source: the text exists once in the expanded and once in the
        // collapsed slot; never more.
        assertTrue(rule.onAllNodesWithTag("pageTitle", useUnmergedTree = true).fetchSemanticsNodes().size <= 2)
    }

    @Test
    fun collapsedDark() {
        page(dark = true)
        rule.onNodeWithTag("list").performTouchInput { swipeUp(startY = centerY, endY = centerY - 400f, durationMillis = 300) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(shotPath("title_3_collapsed_dark.png"))
    }

    @Test
    fun systemBarIconsFollowTheAppThemeNotTheSystem() {
        rule.runOnUiThread {
            val a = rule.activity
            val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
            a.applySystemBars(dark = true)
            assertFalse("dark app theme -> light status icons", c.isAppearanceLightStatusBars)
            assertFalse(c.isAppearanceLightNavigationBars)
            a.applySystemBars(dark = false)
            assertTrue("light app theme -> dark status icons", c.isAppearanceLightStatusBars)
            assertTrue(c.isAppearanceLightNavigationBars)
            assertFalse(a.window.isNavigationBarContrastEnforced)
        }
    }
}
