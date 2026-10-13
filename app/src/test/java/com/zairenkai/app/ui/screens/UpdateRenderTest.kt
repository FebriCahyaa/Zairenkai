// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Update app: renders the installed version, the two download channels and the
 * changelog in light and dark (build/outputs/roborazzi).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.zairenkai.app.R
import com.zairenkai.app.data.update.ReleaseManifest
import com.zairenkai.app.data.update.UpdateChannel
import com.zairenkai.app.shotPath
import com.zairenkai.app.ui.UpdateUiState
import com.zairenkai.app.ui.theme.ZairenkaiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2200dp-xxhdpi", application = android.app.Application::class)
class UpdateRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val available = UpdateUiState.Available(
        ReleaseManifest(versionCode = 3, versionName = "1.2.0", apkUrl = "https://example/x.apk", sha256 = ""),
    )

    private fun show(dark: Boolean, state: UpdateUiState) {
        rule.setContent {
            ZairenkaiTheme(themeMode = if (dark) "dark" else "light", dynamicColor = false) {
                UpdateContent(
                    state = state,
                    autoCheck = true,
                    channel = UpdateChannel.DEBUG,
                    onBack = {},
                    onCheck = {},
                    onDownload = {},
                    onInstall = {},
                    onAutoCheckChange = {},
                    onOpenUrl = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
    }

    private fun str(id: Int) = rule.activity.getString(id)

    @Test
    fun rendersAvailableAndChangelog() {
        show(false, available)
        rule.onNodeWithText(str(R.string.update_download_install)).assertExists()
        rule.onNodeWithText(str(R.string.update_auto_title)).assertExists()
        rule.onNodeWithText(str(R.string.update_changelog_title)).assertExists()
        rule.onRoot().captureRoboImage(shotPath("update_light.png"))
    }

    @Test
    fun dark() {
        show(true, UpdateUiState.UpToDate("1.1.0"))
        rule.onRoot().captureRoboImage(shotPath("update_dark.png"))
    }
}
