// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Profile wording: hold reasons, failure classes and mode summaries.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.data.CustomListResult
import com.zairenkai.app.data.CustomProfile
import com.zairenkai.app.data.CustomWriteResult
import com.zairenkai.app.data.ModeApplyResult
import com.zairenkai.app.data.ModeCpu
import com.zairenkai.app.data.ModeGpu
import com.zairenkai.app.data.ModeMem
import com.zairenkai.app.data.ModeSummary
import com.zairenkai.app.data.ZperfStatus
import com.zairenkai.app.domain.Profiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMessagesTest {
    @Test
    fun holdReasonsAreExplainedAndNamedTheSavedProfile() {
        assertNull(holdMessage(null, "Game"))
        assertTrue(holdMessage("revalidation_required", "Game")!!.contains("Game"))
        assertTrue(holdMessage("safe_mode", null)!!.contains("SAFE MODE"))
        assertTrue(holdMessage("stock", null)!!.contains("stok"))
    }

    @Test
    fun everyApplyFailureKindHasASpecificMessage() {
        val generic = profileFailureMessage(ModeApplyResult(ok = false, kind = "new-kind"))
        val kinds = listOf(
            "engine_unavailable", "framework_unavailable", "framework_incompatible", "license",
            "unauthorized", "sentinel", "thermal_guard", "blocked", "rejected", "not_observed",
            "failed", "rollback_failed", "state", "timeout",
        )
        val messages = kinds.map { profileFailureMessage(ModeApplyResult(ok = false, kind = it)) }
        messages.forEach { assertNotEquals(generic, it) }
        assertEquals(kinds.size, messages.toSet().size)
        assertTrue(
            profileFailureMessage(ModeApplyResult(false, "blocked", "apply blocked: ZKFC unavailable"))
                .endsWith("apply blocked: ZKFC unavailable"),
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
            "Profil Game diterapkan: 2 terverifikasi, 1 dibatasi kernel, 3 tidak tersedia di perangkat.",
            profileSuccessMessage("Game", ok),
        )
        val failed = profileFailureMessage(
            ModeApplyResult(ok = false, kind = "not_observed", failed = listOf("vm.swappiness=60: not observable")),
        )
        assertTrue(failed.contains("vm.swappiness=60"))
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
            "CPU min 30% · uclamp 20% · boost input 60% 2000 ms · GPU min 40% · swappiness 80",
            modeSummaryLine(performance),
        )
        val powersave = ModeSummary(
            id = "powersave",
            cpu = ModeCpu(minFreq = "min", maxPerfPct = 55, uclampMinPct = 0, inputBoostMs = 0),
            gpu = ModeGpu(maxPerfPct = 55),
        )
        assertEquals("CPU maks 55% · tanpa boost input · GPU maks 55%", modeSummaryLine(powersave))
        assertEquals("Nilai default kernel", modeSummaryLine(ModeSummary(id = "empty")))
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
        assertTrue(
            customWriteFailureMessage(CustomWriteResult(false, "x", "rejected", "memory value out of safe range"))
                .endsWith("memory value out of safe range"),
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
        assertEquals("Game dingin", ui.selectedName)
        assertEquals(Profiles.GAME.name, ui.baseName("fast"))

        // Stored but not listed (e.g. list failed): still named, by id.
        assertEquals("custom-game", ui.copy(customs = null).selectedName)
        // The engine default is not a selection.
        assertNull(ui.copy(status = ZperfStatus(ok = true, desired = "custom-game", saved = false)).selectedName)
    }
}
