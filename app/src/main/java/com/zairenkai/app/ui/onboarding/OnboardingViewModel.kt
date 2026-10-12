// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * State for the first-run wizard: the system check (root -> ZKFC -> zperfd ->
 * token, probed read-only) and an optional first profile, applied through
 * zperfd exactly like the Profiles screen. Nothing here writes a tunable
 * itself.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.zairenkai.app.AppContainer
import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.data.ModeApplyResult
import com.zairenkai.app.domain.Profile
import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.ui.profileFailureMessage
import com.zairenkai.app.ui.profileSuccessMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Minimum time between step changes; shorter than the page transition so normal use never notices. */
internal const val STEP_DEBOUNCE_MS = 280L

internal fun stepChangeAllowed(lastMs: Long, nowMs: Long): Boolean = lastMs == 0L || nowMs - lastMs >= STEP_DEBOUNCE_MS

enum class SetupStep(@StringRes val title: Int) {
    WELCOME(R.string.setup_step_welcome),
    CHECK(R.string.setup_step_check),
    PROFILE(R.string.setup_step_profile),
    DONE(R.string.setup_step_done),
}

data class OnboardingUi(
    val step: SetupStep = SetupStep.WELCOME,
    val probe: SetupProbe = SetupProbe(),
    val choice: ProfileId? = null,
    val applying: Boolean = false,
    /** Name of the profile zperfd committed during setup. */
    val applied: UiText? = null,
    val applyMessage: UiText? = null,
    val applyFailed: Boolean = false,
) {
    val checks: List<SetupCheck> get() = setupChecks(probe)
}

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(OnboardingUi())
    val ui: StateFlow<OnboardingUi> = _ui.asStateFlow()
    private var checking: Job? = null

    /** (Re)run every probe. Root is asked first; the rest run in parallel. */
    fun runChecks() {
        checking?.cancel()
        _ui.value = _ui.value.copy(probe = SetupProbe())
        checking = viewModelScope.launch { SetupProber(c).run(::update) }
    }

    private fun update(f: (SetupProbe) -> SetupProbe) {
        _ui.value = _ui.value.copy(probe = f(_ui.value.probe))
    }

    private var lastStepChangeMs = 0L

    fun goTo(step: SetupStep) {
        // A second tap during the page transition must not skip a step.
        val now = android.os.SystemClock.uptimeMillis()
        if (step != _ui.value.step && !stepChangeAllowed(lastStepChangeMs, now)) return
        if (_ui.value.applying) return
        lastStepChangeMs = now
        _ui.value = _ui.value.copy(step = step)
        if (step == SetupStep.CHECK && _ui.value.probe == SetupProbe()) runChecks()
    }

    fun next() = SetupStep.entries.getOrNull(_ui.value.step.ordinal + 1)?.let(::goTo)
    fun back() = SetupStep.entries.getOrNull(_ui.value.step.ordinal - 1)?.let(::goTo)

    fun choose(id: ProfileId?) {
        if (!_ui.value.applying) _ui.value = _ui.value.copy(choice = id, applyMessage = null, applyFailed = false)
    }

    /** Apply the chosen profile through zperfd, then move on only if it was committed. */
    fun applyChoice(profile: Profile) = viewModelScope.launch {
        if (_ui.value.applying) return@launch
        _ui.value = _ui.value.copy(applying = true, applyMessage = null, applyFailed = false)
        val result = runCatching { c.zperf.apply(profile.mode) }.getOrElse {
            ModeApplyResult(ok = false, kind = "protocol", error = it.message)
        }
        if (result.ok) {
            if (profile.id != ProfileId.AUTO) c.settings.setActiveProfile(profile.id)
            c.settings.setAutoProfile(profile.id == ProfileId.AUTO)
            _ui.value = _ui.value.copy(
                applying = false, applied = uiText(profile.nameRes),
                applyMessage = profileSuccessMessage(uiText(profile.nameRes), result), step = SetupStep.DONE,
            )
        } else {
            _ui.value = _ui.value.copy(applying = false, applyFailed = true, applyMessage = profileFailureMessage(result))
        }
    }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        c.settings.setOnboarded(true)
        onDone()
    }
}
