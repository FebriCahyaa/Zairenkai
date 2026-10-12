// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Profile wording: hold reasons, failure classes and mode summaries.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.data.CustomListResult
import com.zairenkai.app.data.CustomProfile
import com.zairenkai.app.data.CustomWriteResult
import com.zairenkai.app.data.ModeApplyResult
import com.zairenkai.app.data.ModeCpu
import com.zairenkai.app.data.ModeGpu
import com.zairenkai.app.data.ModeMem
import com.zairenkai.app.data.ModeSummary
import com.zairenkai.app.data.ZperfStatus
import com.zairenkai.app.debug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMessagesTest {
    private val game = uiText(R.string.profile_game_name)

    @Test
    fun holdReasonsAreExplainedAndNameTheSavedProfile() {
        assertNull(holdMessage(null, game))
        assertEquals(uiText(R.string.hold_revalidation, game), holdMessage("revalidation_required", game))
        assertEquals(
            uiText(R.string.hold_revalidation, uiText(R.string.hold_revalidation_saved_fallback)),
            holdMessage("revalidation_required", null),
        )
        assertEquals(uiText(R.string.hold_safe_mode), holdMessage("safe_mode", null))
        assertEquals(uiText(R.string.hold_stock), holdMessage("stock", null))
        assertEquals(uiText(R.string.hold_other, "x"), holdMessage("x", null))
    }

    @Test
    fun everyApplyFailureKindHasASpecificMessage() {
        val generic = profileFailureMessage(ModeApplyResult(ok = false, kind = "new-kind"))
        assertEquals(uiText(R.string.apply_fail_unknown), generic)
        val kinds = listOf(
            "engine_unavailable", "framework_unavailable", "framework_incompatible", "license",
            "unauthorized", "sentinel", "thermal_guard", "blocked", "rejected", "not_observed",
            "failed", "rollback_failed", "state", "timeout",
        )
        val messages = kinds.map { profileFailureMessage(ModeApplyResult(ok = false, kind = it)) }
        messages.forEach { assertNotEquals(generic, it) }
        assertEquals(kinds.size, messages.toSet().size)
        // Engine detail follows the localized reason, untranslated.
        val blocked = profileFailureMessage(ModeApplyResult(false, "blocked", "apply blocked: ZKFC unavailable"))
        assertEquals(
            UiText.Join(listOf(uiText(R.string.apply_fail_blocked), UiText.Raw("apply blocked: ZKFC unavailable"))),
            blocked,
        )
    }

    @Test
    fun successNamesEveryOutcomeClassAndFailureListsRejectedWrites() {
        val ok = ModeApplyResult(
            ok = true,
            applied = listOf("a", "b"),
            constrained = listOf("c"),
            skipped = listOf("d", "e", "f"),
        )
        assertEquals(
            uiText(
                R.string.apply_success, game,
                UiText.Join(
                    listOf(
                        uiText(R.string.apply_outcome_verified, 2),
                        uiText(R.string.apply_outcome_constrained, 1),
                        uiText(R.string.apply_outcome_skipped, 3),
                    ),
                    ", ",
                ),
            ),
            profileSuccessMessage(game, ok),
        )
        val failed = profileFailureMessage(
            ModeApplyResult(ok = false, kind = "not_observed", failed = listOf("vm.swappiness=60: not observable")),
        )
        assertTrue(failed.debug().contains("vm.swappiness=60"))
    }

    @Test
    fun summaryShowsOnlyRequestsThatChangeTheDefaults() {
        val performance = ModeSummary(
            id = "performance",
            cpu = ModeCpu(minFreq = "30%", maxFreq = "max", uclampMinPct = 20, inputBoostPct = 60, inputBoostMs = 2000),
            gpu = ModeGpu(minFreq = "40%", maxFreq = "max"),
            mem = ModeMem(swappiness = 80),
        )
        assertEquals(
            UiText.Join(
                listOf(
                    uiText(R.string.mode_cpu_min, "30%"),
                    uiText(R.string.mode_uclamp, 20),
                    uiText(R.string.mode_input_boost, 60, 2000),
                    uiText(R.string.mode_gpu_min, "40%"),
                    uiText(R.string.mode_swappiness, 80),
                ),
                " · ",
            ),
            modeSummaryLine(performance),
        )
        val powersave = ModeSummary(
            id = "powersave",
            cpu = ModeCpu(minFreq = "min", maxPerfPct = 55, uclampMinPct = 0, inputBoostMs = 0),
            gpu = ModeGpu(maxPerfPct = 55),
        )
        assertEquals(
            UiText.Join(
                listOf(
                    uiText(R.string.mode_cpu_max_pct, 55),
                    uiText(R.string.mode_no_input_boost),
                    uiText(R.string.mode_gpu_max_pct, 55),
                ),
                " · ",
            ),
            modeSummaryLine(powersave),
        )
        assertEquals(uiText(R.string.mode_kernel_defaults), modeSummaryLine(ModeSummary(id = "empty")))
    }

    @Test
    fun everyCustomWriteRefusalHasASpecificMessage() {
        val generic = customWriteFailureMessage(CustomWriteResult(ok = false, kind = "new-kind"))
        val kinds = listOf(
            "rejected", "limit", "in_use", "not_found", "state", "unauthorized", "profile",
            "engine_unavailable", "timeout",
        )
        val messages = kinds.map { customWriteFailureMessage(CustomWriteResult(ok = false, kind = it)) }
        messages.forEach { assertNotEquals(generic, it) }
        assertEquals(kinds.size, messages.toSet().size)
        assertEquals(
            UiText.Join(listOf(uiText(R.string.custom_fail_rejected), UiText.Raw("memory value out of safe range"))),
            customWriteFailureMessage(CustomWriteResult(false, "x", "rejected", "memory value out of safe range")),
        )
    }

    @Test
    fun aSavedCustomSelectionIsNamedByItsLabelAndIsNotABuiltInProfile() {
        val customs = CustomListResult(
            ok = true, max = 8,
            profiles = listOf(CustomProfile(id = "custom-game", label = "Game dingin", base = "fast", valid = true)),
        )
        val ui = ProfilesUi(status = ZperfStatus(ok = true, desired = "custom-game", saved = true), customs = customs)
        assertNull(ui.selected)
        assertEquals("custom-game", ui.selectedCustom?.id)
        assertEquals(UiText.Raw("Game dingin"), ui.selectedName)
        assertEquals(game, ui.baseName("fast"))

        // Stored but not listed (e.g. list failed): still named, by id.
        assertEquals(UiText.Raw("custom-game"), ui.copy(customs = null).selectedName)
        // The engine default is not a selection.
        assertNull(ui.copy(status = ZperfStatus(ok = true, desired = "custom-game", saved = false)).selectedName)
    }
}
