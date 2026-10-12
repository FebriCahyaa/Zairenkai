// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The setup flow as a user meets it (Robolectric + Compose UI test): each
 * step renders from real state, every action reaches the right callback,
 * the on-screen and system Back agree, an apply cannot be started twice, and
 * a missing module is explained without raw shell output. Screenshots of
 * every step (light and dark) are written to build/outputs/roborazzi.
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
import com.zairenkai.app.domain.ProfileId
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

    private fun show(initial: OnboardingUi, dark: Boolean = false): (OnboardingUi) -> Unit {
        var state by mutableStateOf(initial)
        rule.setContent {
            ZairenkaiTheme(themeMode = if (dark) "dark" else "light", dynamicColor = false) {
                SetupFlow(
                    ui = state,
                    onLanguage = { calls += "language" },
                    onInfo = { calls += "info" },
                    onSkip = { calls += "skip" },
                    onBack = { calls += "back" },
                    onNext = { calls += "next" },
                    onRecheck = { calls += "recheck" },
                    onChoose = { calls += "choose:$it" },
                    onApply = { calls += "apply" },
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
        rule.onNodeWithContentDescription("${str(R.string.app_name)}. ${str(R.string.setup_greeting_tagline)}").assertExists()
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
    fun missingModuleIsExplainedAndRecheckRerunsTheProbe() {
        show(OnboardingUi(step = SetupStep.CHECK, probe = missingModule))
        rule.onNodeWithText(str(R.string.setup_zkfc_module_missing)).assertIsDisplayed()
        // Language and info buttons are only on the welcome page.
        assertEquals(0, rule.onAllNodesWithContentDescription(str(R.string.setup_language)).fetchSemanticsNodes().size)
        // Raw shell output never reaches the screen.
        assertEquals(0, rule.onAllNodesWithText("inaccessible", substring = true).fetchSemanticsNodes().size)
        shot("2_check_missing_module_light")
        rule.onNodeWithText(str(R.string.setup_recheck)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.action_back)).performClick()
        rule.onNodeWithText(str(R.string.action_next)).assertIsEnabled().performClick()
        assertEquals(listOf("recheck", "back", "next"), calls)
    }

    @Test
    fun checkingBlocksNextUntilEveryProbeAnswers() {
        show(OnboardingUi(step = SetupStep.CHECK, probe = SetupProbe(root = true, rootState = RootState.GRANTED)), dark = true)
        rule.onNodeWithText(str(R.string.setup_checking)).assertIsNotEnabled()
        shot("2_check_running_dark")
    }

    @Test
    fun systemBackMatchesTheBackButtonOnMiddleSteps() {
        val set = show(OnboardingUi(step = SetupStep.PROFILE, probe = healthy))
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf("back"), calls)
        // While a profile is being applied, Back is ignored.
        set(OnboardingUi(step = SetupStep.PROFILE, probe = healthy, choice = ProfileId.GAME, applying = true))
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf("back"), calls)
    }

    @Test
    fun profileChoiceThenApplyAndNoSecondApplyWhileBusy() {
        val set = show(OnboardingUi(step = SetupStep.PROFILE, probe = healthy))
        // Nothing chosen: the action is "skip", and choosing calls back without applying.
        rule.onNodeWithText(str(R.string.setup_skip_for_now)).assertIsDisplayed()
        val game = str(R.string.profile_game_name)
        rule.onNodeWithText(game).performScrollTo().performClick()
        assertEquals(listOf("choose:GAME"), calls)

        set(OnboardingUi(step = SetupStep.PROFILE, probe = healthy, choice = ProfileId.GAME))
        rule.onNodeWithText(str(R.string.setup_apply_profile, game)).performClick()
        assertEquals("apply", calls.last())
        shot("3_profile_selected_light")

        set(OnboardingUi(step = SetupStep.PROFILE, probe = healthy, choice = ProfileId.GAME, applying = true))
        rule.onNodeWithText(str(R.string.setup_applying, game)).assertIsNotEnabled()
        shot("3_profile_applying_light")
    }

    @Test
    fun profilesAreBlockedWithoutTheStackAndSkipRemains() {
        show(OnboardingUi(step = SetupStep.PROFILE, probe = missingModule), dark = true)
        rule.onNodeWithText(str(R.string.setup_skip_for_now)).assertIsEnabled()
        shot("3_profile_blocked_dark")
    }

    @Test
    fun doneSummarisesHonestlyAndEntersTheApp() {
        show(OnboardingUi(step = SetupStep.DONE, probe = missingModule))
        rule.onNodeWithText(str(R.string.setup_done_partial_title)).assertIsDisplayed()
        shot("4_done_partial_light")
        rule.onNodeWithText(str(R.string.setup_enter_app)).performClick()
        assertEquals(listOf("finish"), calls)
    }

    @Test
    fun doneReadyDark() {
        show(OnboardingUi(step = SetupStep.DONE, probe = healthy), dark = true)
        rule.onNodeWithText(str(R.string.setup_done_ready_title)).assertIsDisplayed()
        shot("4_done_ready_dark")
    }
}
