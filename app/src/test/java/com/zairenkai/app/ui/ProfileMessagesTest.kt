// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Profile wording: hold reasons, failure classes and mode summaries.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.data.ModeApplyResult
import com.zairenkai.app.data.ModeCpu
import com.zairenkai.app.data.ModeGpu
import com.zairenkai.app.data.ModeMem
import com.zairenkai.app.data.ModeSummary
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
        val kinds = listOf("engine_unavailable", "unauthorized", "blocked", "rejected", "failed", "timeout")
        val messages = kinds.map { profileFailureMessage(ModeApplyResult(ok = false, kind = it)) }
        messages.forEach { assertNotEquals(generic, it) }
        assertEquals(kinds.size, messages.toSet().size)
        assertTrue(
            profileFailureMessage(ModeApplyResult(false, "blocked", "apply blocked: ZKFC unavailable"))
                .endsWith("apply blocked: ZKFC unavailable"),
        )
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
}
