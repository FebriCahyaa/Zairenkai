// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The home screen's wording decisions, as pure functions so they are unit
 * tested: the greeting for the time of day, the one-line summary of the
 * system check, and how the engine's thermal band is labelled and toned.
 * The band and its meaning come from zperfd; the app sets no temperature
 * thresholds of its own.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.home

import androidx.annotation.StringRes
import com.zairenkai.app.R
import com.zairenkai.app.ui.onboarding.CheckState
import com.zairenkai.app.ui.onboarding.SetupCheck
import com.zairenkai.app.ui.onboarding.settled

enum class DayPart(@StringRes val greeting: Int) {
    MORNING(R.string.home_greeting_morning),
    MIDDAY(R.string.home_greeting_midday),
    AFTERNOON(R.string.home_greeting_afternoon),
    NIGHT(R.string.home_greeting_night),
}

/** Indonesian day parts: pagi 04–10, siang 11–14, sore 15–17, malam 18–03. */
fun dayPart(hour: Int): DayPart = when (hour) {
    in 4..10 -> DayPart.MORNING
    in 11..14 -> DayPart.MIDDAY
    in 15..17 -> DayPart.AFTERNOON
    else -> DayPart.NIGHT
}

/** The headline under the greeting. */
sealed interface HomeSummary {
    data object Checking : HomeSummary
    data object NoRoot : HomeSummary
    data object Ready : HomeSummary
    data class Attention(val count: Int) : HomeSummary
}

fun homeSummary(root: Boolean?, checks: List<SetupCheck>): HomeSummary = when {
    root == false -> HomeSummary.NoRoot
    root == null || !checks.settled() -> HomeSummary.Checking
    checks.all { it.state == CheckState.OK } -> HomeSummary.Ready
    else -> HomeSummary.Attention(checks.count { it.state != CheckState.OK })
}

enum class Tone { GOOD, WARN, BAD, NEUTRAL }

/** zperfd's thermal bands (thermal.rs `Band`), toned by what the engine says they mean. */
fun bandTone(band: String?): Tone = when (band) {
    "Nominal" -> Tone.GOOD
    "Warm", "Hot" -> Tone.WARN
    "Critical" -> Tone.BAD
    else -> Tone.NEUTRAL
}

/** A localized label for a known band; null for a band this app does not know (shown as reported). */
@StringRes
fun bandLabel(band: String?): Int? = when (band) {
    "Nominal" -> R.string.home_band_nominal
    "Warm" -> R.string.home_band_warm
    "Hot" -> R.string.home_band_hot
    "Critical" -> R.string.home_band_critical
    null, "", "Unknown" -> R.string.home_band_unknown
    else -> null
}

fun checkTone(state: CheckState): Tone? = when (state) {
    CheckState.CHECKING -> null
    CheckState.OK -> Tone.GOOD
    CheckState.WARN -> Tone.WARN
    CheckState.MISSING -> Tone.BAD
}
