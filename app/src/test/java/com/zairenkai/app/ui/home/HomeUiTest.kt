// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The home screen as a user meets it (Robolectric + Compose UI test): every
 * stage of the system check reads honestly, unreported fields stay hidden,
 * and each action reaches its callback. Screenshots (light and dark, viewport
 * and full page) are written to build/outputs/roborazzi.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.zairenkai.app.R
import com.zairenkai.app.data.ApiVersion
import com.zairenkai.app.data.BoostStatus
import com.zairenkai.app.data.InfoResult
import com.zairenkai.app.data.LicenseResult
import com.zairenkai.app.data.ThermalStatus
import com.zairenkai.app.data.TokenInfo
import com.zairenkai.app.data.ZperfStatus
import com.zairenkai.app.data.diag.InstallFacts
import com.zairenkai.app.data.diag.RootState
import com.zairenkai.app.shotPath
import com.zairenkai.app.ui.OverviewState
import com.zairenkai.app.ui.onboarding.SetupProbe
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
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class HomeUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)
    private val calls = mutableListOf<String>()

    private val info = InfoResult(
        ok = true, api = ApiVersion(1, 4, 0), arch = "arm64", hookMode = "kprobe", kernelType = "gki",
        kernelRelease = "5.10.269-Zen-Itsu+", buildId = "zkfc-1.4.0", licenseState = "valid",
    )
    private val ready = OverviewState(
        loading = false,
        probe = SetupProbe(
            root = true, rootState = RootState.GRANTED, info = info, infoDone = true,
            engineInstalled = true, engineStatus = ZperfStatus(ok = true, desired = "balance", effective = "balance", saved = true),
            engineDone = true,
        ),
        info = info,
        license = LicenseResult(ok = true, state = "valid", hasToken = true, token = TokenInfo(licensee = "FebriCahyaa")),
        boost = BoostStatus(ok = true, inputBoostEnabled = true, inputBoostActive = true, boostedTasks = 3, cpufreqRequests = 2),
        thermal = ThermalStatus(
            band = "Nominal", headroomPermille = 720, hottestMdeg = 41_200, controlZone = "cpu-1-0",
            criticalTripMdeg = 95_000, reason = "runtime thermal trip headroom is nominal",
        ),
    )
    private val missingModule = OverviewState(
        loading = false,
        probe = SetupProbe(
            root = true, rootState = RootState.GRANTED,
            facts = InstallFacts(abi = "arm64-v8a", kernel = "5.10.269-Zen-Itsu+"),
            info = InfoResult(ok = false, kind = "not_found", error = "/system/bin/sh: zkfctl: inaccessible or not found"),
            infoDone = true, engineInstalled = false, engineDone = true,
        ),
    )

    private fun show(s: OverviewState, dark: Boolean = false) {
        rule.setContent {
            ZairenkaiTheme(themeMode = if (dark) "dark" else "light", dynamicColor = false) {
                HomeScreen(
                    s,
                    onRetry = { calls += "retry" },
                    onResetBoosts = { calls += "reset" },
                    onExitSafeMode = { calls += "exitSafe" },
                    onOpenSettings = { calls += "settings" },
                    onOpenProfiles = { calls += "profiles" },
                    hour = 9,
                )
            }
        }
        rule.mainClock.advanceTimeBy(4_000)
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage(shotPath("home_$name.png"))

    @Test
    fun readyShowsGreetingProfileThermalAndActions() {
        show(ready)
        rule.onNodeWithContentDescription(str(R.string.home_greeting_morning)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.home_summary_ready)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.profile_balanced_name)).assertIsDisplayed()
        shot("1_ready_light")
        rule.onNodeWithContentDescription(str(R.string.home_thermal_headroom_a11y, "72%")).performScrollTo().assertIsDisplayed()
        // The band pill; the boost card's thermal pill also reads "Normal" in English.
        rule.onAllNodesWithText(str(R.string.home_band_nominal))[0].assertIsDisplayed()
        rule.onNodeWithText("41.2 °C").assertIsDisplayed()
        rule.onNodeWithText("FebriCahyaa").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.home_mode_change)).performScrollTo().performClick()
        rule.onNodeWithContentDescription(str(R.string.dashboard_settings)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.dashboard_reset_boosts)).performScrollTo().performClick()
        rule.onNodeWithContentDescription(str(R.string.setup_recheck)).performScrollTo().performClick()
        assertEquals(listOf("profiles", "settings", "reset", "retry"), calls)
    }

    @Test
    fun readyDark() {
        show(ready, dark = true)
        shot("2_ready_dark")
    }

    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    @Test
    fun readyFullPage() {
        show(ready)
        shot("3_ready_full_light")
    }

    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    @Test
    fun readyFullPageDark() {
        show(ready, dark = true)
        shot("4_ready_full_dark")
    }

    @Test
    fun missingModuleIsExplainedFirstWithoutDeviceDefaults() {
        show(missingModule)
        rule.onNodeWithText(rule.activity.resources.getQuantityString(R.plurals.home_summary_attention, 3, 3)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.dashboard_system_status)).assertIsDisplayed()
        // No profile card and no ZKFC defaults without an `ok` answer.
        assertEquals(0, rule.onAllNodesWithText(str(R.string.home_mode_label)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("0.0.0").fetchSemanticsNodes().size)
        // Thermal and boost need the engine and ZKFC; the status card explains instead.
        assertEquals(0, rule.onAllNodesWithText(str(R.string.home_thermal_title)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText(str(R.string.dashboard_active_performance)).fetchSemanticsNodes().size)
        rule.onNodeWithText(str(R.string.dashboard_zkfc_fields_unavailable)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.setup_recheck)).performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    @Test
    fun missingModuleFullPage() {
        show(missingModule)
        shot("5_missing_module_full_light")
    }

    @Test
    fun noRootDark() {
        show(OverviewState(loading = false, probe = SetupProbe(root = false, rootState = RootState.NO_SU)), dark = true)
        rule.onNodeWithText(str(R.string.home_summary_no_root)).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText(str(R.string.home_thermal_title)).fetchSemanticsNodes().size)
        shot("6_no_root_dark")
    }

    @Test
    fun checking() {
        show(OverviewState(loading = true, probe = SetupProbe(root = true, rootState = RootState.GRANTED)))
        rule.onNodeWithText(str(R.string.home_summary_checking)).assertIsDisplayed()
        shot("7_checking_light")
    }

    @Test
    fun safeModeAndHoldAreShownAndExitWorks() {
        show(
            ready.copy(
                safeMode = true,
                probe = ready.probe.copy(engineStatus = ZperfStatus(ok = true, desired = "balance", effective = "stock", hold = "safe_mode", safeMode = true)),
            ),
        )
        rule.onNodeWithText(str(R.string.dashboard_safe_mode_title)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.hold_safe_mode)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.dashboard_safe_mode_exit)).performScrollTo().performClick()
        assertEquals(listOf("exitSafe"), calls)
        shot("8_safe_mode_light")
    }
}
