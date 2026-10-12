// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Home wording decisions: greeting by time of day, the summary line, and the
 * engine's thermal bands (labelled and toned as zperfd reports them).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.home

import com.zairenkai.app.R
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.ui.onboarding.CheckId
import com.zairenkai.app.ui.onboarding.CheckState
import com.zairenkai.app.ui.onboarding.SetupCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeModelTest {
    @Test
    fun greetingFollowsTheIndonesianDayParts() {
        val expected = mapOf(
            0 to DayPart.NIGHT, 3 to DayPart.NIGHT, 4 to DayPart.MORNING, 10 to DayPart.MORNING,
            11 to DayPart.MIDDAY, 14 to DayPart.MIDDAY, 15 to DayPart.AFTERNOON, 17 to DayPart.AFTERNOON,
            18 to DayPart.NIGHT, 23 to DayPart.NIGHT,
        )
        expected.forEach { (hour, part) -> assertEquals("hour $hour", part, dayPart(hour)) }
    }

    private fun check(id: CheckId, state: CheckState) = SetupCheck(id, state, uiText(R.string.setup_root_ok))
    private fun all(state: CheckState) = CheckId.entries.map { check(it, state) }

    @Test
    fun summaryIsHonestAboutEveryStage() {
        assertEquals(HomeSummary.Checking, homeSummary(null, all(CheckState.CHECKING)))
        assertEquals(HomeSummary.NoRoot, homeSummary(false, all(CheckState.MISSING)))
        assertEquals(HomeSummary.Checking, homeSummary(true, all(CheckState.OK).dropLast(1) + check(CheckId.TOKEN, CheckState.CHECKING)))
        assertEquals(HomeSummary.Ready, homeSummary(true, all(CheckState.OK)))
        val mixed = listOf(
            check(CheckId.ROOT, CheckState.OK), check(CheckId.ZKFC, CheckState.WARN),
            check(CheckId.ENGINE, CheckState.MISSING), check(CheckId.TOKEN, CheckState.MISSING),
        )
        assertEquals(HomeSummary.Attention(3), homeSummary(true, mixed))
    }

    @Test
    fun thermalBandsAreTheEnginesOwn() {
        assertEquals(Tone.GOOD, bandTone("Nominal"))
        assertEquals(Tone.WARN, bandTone("Warm"))
        assertEquals(Tone.WARN, bandTone("Hot"))
        assertEquals(Tone.BAD, bandTone("Critical"))
        assertEquals(Tone.NEUTRAL, bandTone("Unknown"))
        assertEquals(Tone.NEUTRAL, bandTone(null))
        assertEquals(R.string.home_band_unknown, bandLabel("Unknown"))
        assertEquals(R.string.home_band_critical, bandLabel("Critical"))
        // A band this app does not know is shown as reported, not guessed.
        assertNull(bandLabel("Scorching"))
        assertEquals(Tone.NEUTRAL, bandTone("Scorching"))
    }
}
