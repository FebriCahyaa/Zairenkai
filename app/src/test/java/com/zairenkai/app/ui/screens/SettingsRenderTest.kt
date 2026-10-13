// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Settings, grouped: every row reaches its callback, and the page renders in
 * light and dark (build/outputs/roborazzi).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.zairenkai.app.R
import com.zairenkai.app.data.AppSettings
import com.zairenkai.app.shotPath
import com.zairenkai.app.ui.onboarding.AppPermission
import com.zairenkai.app.ui.theme.ZairenkaiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1700dp-xxhdpi", application = android.app.Application::class)
class SettingsRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()

    private fun show(dark: Boolean) {
        rule.setContent {
            ZairenkaiTheme(themeMode = if (dark) "dark" else "light", dynamicColor = false) {
                SettingsContent(
                    AppSettings(liteMode = true), mapOf(AppPermission.NOTIFICATIONS to true, AppPermission.USAGE_ACCESS to false, AppPermission.BATTERY to false),
                    onOpenTheme = { calls += "theme" }, onOpenAbout = { calls += "about" }, onOpenAboutPhone = { calls += "phone" },
                    onOpenLegal = { calls += "legal" }, onOpenLicenses = { calls += "licenses" }, onOpenLogs = { calls += "logs" },
                    onOpenUpdate = { calls += "update" },
                    onLanguage = { calls += "language" }, onPermissions = { calls += "permissions" },
                    onDynamic = { calls += "dynamic:$it" }, onLite = { calls += "lite:$it" }, onMitigation = { calls += "mitigation:$it" },
                )
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
    }

    private fun str(id: Int) = rule.activity.getString(id)

    @Test
    fun rowsReachTheirActions() {
        show(false)
        rule.onRoot().captureRoboImage(shotPath("settings_light.png"))
        for (id in listOf(R.string.theme_title, R.string.language_title, R.string.settings_permissions_title,
            R.string.settings_logs_title, R.string.legal_title, R.string.oss_title)) {
            rule.onNodeWithText(str(id)).performScrollTo().performClick()
        }
        // The lite row opens a detail sheet; its toggle is what reaches onLite.
        rule.onNodeWithText(str(R.string.settings_lite_title)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.settings_detail_what_happens)).assertExists()
        rule.onNodeWithText(str(R.string.settings_detail_on)).performClick()
        assertEquals(listOf("theme", "language", "permissions", "logs", "legal", "licenses", "lite:false"), calls)
    }

    @Test
    fun dark() {
        show(true)
        rule.onRoot().captureRoboImage(shotPath("settings_dark.png"))
    }
}
