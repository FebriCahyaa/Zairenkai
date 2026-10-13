// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The setup flow as a user meets it (Robolectric + Compose UI test): welcome,
 * legal consent, appearance, system check, permissions and done each render
 * from real state; every action reaches the right callback; Next is held
 * until consent is given and every probe has answered; the on-screen and
 * system Back agree; a missing module is explained without raw shell
 * output. Screenshots of every step (light and dark) are written to
 * build/outputs/roborazzi.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.takahirom.roborazzi.captureRoboImage
import com.zairenkai.app.R
import com.zairenkai.app.data.ApiVersion
import com.zairenkai.app.data.InfoResult
import com.zairenkai.app.data.ZperfStatus
import com.zairenkai.app.data.diag.InstallFacts
import com.zairenkai.app.data.diag.RootState
import com.zairenkai.app.ui.theme.ZairenkaiTheme
import org.junit.Assert.assertEquals
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
class SetupFlowUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private val missingModule = SetupProbe(
        root = true, rootState = RootState.GRANTED,
        facts = InstallFacts(abi = "arm64-v8a", kernel = "5.10.269-Zen-Itsu+"),
        info = InfoResult(ok = false, kind = "not_found", error = "/system/bin/sh: zkfctl: inaccessible or not found"),
        infoDone = true, engineInstalled = false, engineDone = true,
    )
    private val healthy = SetupProbe(
        root = true, rootState = RootState.GRANTED,
        info = InfoResult(ok = true, api = ApiVersion(1, 4, 0), arch = "arm64", kernelType = "gki", licenseState = "valid"),
        infoDone = true, engineInstalled = true, engineStatus = ZperfStatus(ok = true, desired = "balance", saved = true), engineDone = true,
    )

    private val calls = mutableListOf<String>()

    private val noPermissions = AppPermission.entries.associateWith { false }
    private val allPermissions = AppPermission.entries.associateWith { true }

    private fun show(initial: OnboardingUi, dark: Boolean = false, permissions: Map<AppPermission, Boolean> = noPermissions): (OnboardingUi) -> Unit {
        var state by mutableStateOf(initial)
        rule.setContent {
            ZairenkaiTheme(themeMode = if (dark) "dark" else "light", dynamicColor = false) {
                SetupFlow(
                    ui = state,
                    permissions = permissions,
                    onLanguage = { calls += "language" },
                    onInfo = { calls += "info" },
                    onBack = { calls += "back" },
                    onNext = { calls += "next" },
                    onLegalChecked = { calls += "legal:$it" },
                    onOpenLicenses = { calls += "licenses" },
                    onThemeMode = { calls += "mode:$it" },
                    onAccent = { calls += "accent:$it" },
                    onDynamic = { calls += "dynamic:$it" },
                    onRecheck = { calls += "recheck" },
                    onPermission = { calls += "perm:${it.name}" },
                    onFinish = { calls += "finish" },
                )
            }
        }
        return { state = it }
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage(shotPath("setup_$name.png"))

    @Test
    fun welcomeShowsTheBrandAndStartsTheFlow() {
        show(OnboardingUi())
        // The typed greeting exposes its final text to accessibility at once.
        rule.onNodeWithContentDescription("${str(R.string.setup_welcome_title)}. ${str(R.string.setup_greeting_tagline)}").assertExists()
        // Language and device info live at the bottom of the welcome page.
        rule.onNodeWithContentDescription(str(R.string.setup_language)).assertIsDisplayed()
        rule.onNodeWithContentDescription(str(R.string.setup_info)).assertIsDisplayed().performClick()
        assertEquals(listOf("info"), calls)
        calls.clear()
        rule.mainClock.advanceTimeBy(4_000)
        shot("1_welcome_light")
        rule.onNodeWithText(str(R.string.setup_start)).assertIsDisplayed().performClick()
        assertEquals(listOf("next"), calls)
    }

    @Test
    fun welcomeDark() {
        show(OnboardingUi(), dark = true)
        rule.mainClock.advanceTimeBy(4_000)
        shot("1_welcome_dark")
    }

    @Test
    fun legalNeedsConsentBeforeNext() {
        val set = show(OnboardingUi(step = SetupStep.LEGAL))
        rule.onNodeWithText(str(R.string.setup_agree_continue)).assertIsNotEnabled()
        // A section opens in place.
        rule.onNodeWithText(str(R.string.legal_privacy_title)).performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText(str(R.string.legal_privacy_body)).assertExists()
        shot("2_legal_light")
        rule.onNodeWithText(str(R.string.legal_consent)).performScrollTo().performClick()
        assertEquals("legal:true", calls.last())
        set(OnboardingUi(step = SetupStep.LEGAL, legalChecked = true))
        rule.onNodeWithText(str(R.string.setup_agree_continue)).assertIsEnabled().performClick()
        assertEquals("next", calls.last())
        // The open-source list is one tap away from the third-party section.
        rule.onNodeWithText(str(R.string.legal_third_party_title)).performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText(str(R.string.oss_title)).performScrollTo().performClick()
        assertEquals("licenses", calls.last())
    }

    @Test
    fun legalDark() {
        show(OnboardingUi(step = SetupStep.LEGAL, legalChecked = true), dark = true)
        rule.mainClock.advanceTimeBy(1_000)
        shot("2_legal_dark")
    }

    @Test
    fun appearanceChoicesReachTheSettings() {
        show(OnboardingUi(step = SetupStep.THEME, dynamicColor = false))
        rule.mainClock.advanceTimeBy(1_000)
        shot("3_theme_light")
        rule.onNodeWithText(str(R.string.theme_mode_dark)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.setup_theme_dynamic)).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Rose").performScrollTo().performClick()
        assertEquals(listOf("mode:dark", "dynamic:true", "accent:rose"), calls)
    }

    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    @Test
    fun appearanceDarkFull() {
        show(OnboardingUi(step = SetupStep.THEME, themeMode = "dark", dynamicColor = false, accent = "aqua"), dark = true)
        rule.mainClock.advanceTimeBy(2_500)
        shot("3_theme_full_dark")
    }

    @Test
    fun missingModuleIsExplainedAndRecheckRerunsTheProbe() {
        show(OnboardingUi(step = SetupStep.CHECK, probe = missingModule))
        rule.onNodeWithText(str(R.string.setup_zkfc_module_missing)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.setup_alert_missing_title)).performScrollTo().assertIsDisplayed()
        // Language and info buttons are only on the welcome page.
        assertEquals(0, rule.onAllNodesWithContentDescription(str(R.string.setup_language)).fetchSemanticsNodes().size)
        // Raw shell output never reaches the screen.
        assertEquals(0, rule.onAllNodesWithText("inaccessible", substring = true).fetchSemanticsNodes().size)
        rule.mainClock.advanceTimeBy(1_500)
        shot("4_check_missing_module_light")
        rule.onNodeWithText(str(R.string.setup_recheck)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.action_back)).performClick()
        rule.onNodeWithText(str(R.string.action_next)).assertIsEnabled().performClick()
        assertEquals(listOf("recheck", "back", "next"), calls)
    }

    @Test
    fun checkingBlocksNextUntilEveryProbeAnswers() {
        show(OnboardingUi(step = SetupStep.CHECK, probe = SetupProbe(root = true, rootState = RootState.GRANTED)), dark = true)
        rule.onNodeWithText(str(R.string.setup_checking)).assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(1_200)
        shot("4_check_running_dark")
    }

    @Test
    fun healthyCheckIsReady() {
        show(OnboardingUi(step = SetupStep.CHECK, probe = healthy))
        rule.onNodeWithText(str(R.string.setup_check_all_ok)).performScrollTo().assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1_500)
        shot("4_check_ready_light")
    }

    @Test
    fun systemBackMatchesTheBackButtonOnMiddleSteps() {
        show(OnboardingUi(step = SetupStep.PERMISSIONS, probe = healthy))
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf("back"), calls)
    }

    @Test
    fun permissionsShowRealStatusAndAskOnTap() {
        show(OnboardingUi(step = SetupStep.PERMISSIONS, probe = healthy), permissions = mapOf(
            AppPermission.NOTIFICATIONS to false, AppPermission.USAGE_ACCESS to true, AppPermission.BATTERY to false,
        ))
        rule.onNodeWithText(str(R.string.perm_allow)).performScrollTo().performClick()
        assertEquals(listOf("perm:NOTIFICATIONS"), calls)
        rule.onNodeWithContentDescription("${str(R.string.perm_usage_title)}: ${str(R.string.perm_granted)}").assertExists()
        rule.mainClock.advanceTimeBy(1_000)
        shot("5_permissions_light")
        // Every permission is optional: Next stays enabled.
        rule.onNodeWithText(str(R.string.action_next)).assertIsEnabled()
    }

    @Test
    fun permissionsDarkAllGranted() {
        show(OnboardingUi(step = SetupStep.PERMISSIONS, probe = healthy), dark = true, permissions = allPermissions)
        rule.mainClock.advanceTimeBy(1_000)
        shot("5_permissions_dark")
    }

    @Test
    fun doneSummarisesHonestlyAndStarts() {
        show(OnboardingUi(step = SetupStep.DONE, probe = missingModule))
        rule.onNodeWithContentDescription(str(R.string.setup_done_partial_title)).assertExists()
        rule.mainClock.advanceTimeBy(2_000)
        shot("6_done_partial_light")
        rule.onNodeWithText(str(R.string.setup_start_app)).performClick()
        assertEquals(listOf("finish"), calls)
    }

    @Test
    fun doneReadyCelebrates() {
        show(OnboardingUi(step = SetupStep.DONE, probe = healthy), permissions = allPermissions)
        rule.onNodeWithContentDescription(str(R.string.setup_done_ready_title)).assertExists()
        rule.mainClock.advanceTimeBy(1_800)
        shot("6_done_ready_light")
    }

    @Test
    fun doneReadyDark() {
        show(OnboardingUi(step = SetupStep.DONE, probe = healthy), dark = true, permissions = allPermissions)
        rule.mainClock.advanceTimeBy(3_800)
        shot("6_done_ready_dark")
    }
}
