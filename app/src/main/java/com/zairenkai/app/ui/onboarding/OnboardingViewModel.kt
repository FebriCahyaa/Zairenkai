// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * State for the first-run setup: welcome, legal information and consent,
 * appearance (theme mode, accent, dynamic colour), the system check (root ->
 * ZKFC -> zperfd -> token, probed read-only), app permissions, and done.
 * Nothing here writes a tunable; the theme is saved to the settings store
 * as it is chosen, so the whole app (and the preview) follows it live.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Minimum time between step changes; shorter than the page transition so normal use never notices. */
internal const val STEP_DEBOUNCE_MS = 280L

internal fun stepChangeAllowed(lastMs: Long, nowMs: Long): Boolean = lastMs == 0L || nowMs - lastMs >= STEP_DEBOUNCE_MS

/** The version of the legal terms shown in setup; raise it when they change materially. */
const val LEGAL_VERSION = 1

enum class SetupStep(@StringRes val title: Int) {
    WELCOME(R.string.setup_step_welcome),
    LEGAL(R.string.setup_step_legal),
    THEME(R.string.setup_step_theme),
    CHECK(R.string.setup_step_check),
    PERMISSIONS(R.string.setup_step_permissions),
    DONE(R.string.setup_step_done),
}

data class OnboardingUi(
    val step: SetupStep = SetupStep.WELCOME,
    val probe: SetupProbe = SetupProbe(),
    /** The consent box on the legal step; required to continue. */
    val legalChecked: Boolean = false,
    val themeMode: String = "system",
    val accent: String = "indigo",
    val dynamicColor: Boolean = true,
) {
    val checks: List<SetupCheck> get() = setupChecks(probe)

    /** Next is allowed from this step (legal needs consent; the check needs every probe answered). */
    val canContinue: Boolean
        get() = when (step) {
            SetupStep.LEGAL -> legalChecked
            SetupStep.CHECK -> checks.settled()
            else -> true
        }
}

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(OnboardingUi())
    val ui: StateFlow<OnboardingUi> = _ui.asStateFlow()
    private var checking: Job? = null

    init {
        viewModelScope.launch {
            c.settings.settings.collect { s ->
                _ui.update {
                    it.copy(
                        themeMode = s.themeMode, accent = s.accent, dynamicColor = s.dynamicColor,
                        // A returning user who accepted these terms keeps the box ticked.
                        legalChecked = it.legalChecked || s.legalAccepted >= LEGAL_VERSION,
                    )
                }
            }
        }
    }

    /** (Re)run every probe. Root is asked first; the rest run in parallel. */
    fun runChecks() {
        checking?.cancel()
        _ui.update { it.copy(probe = SetupProbe()) }
        checking = viewModelScope.launch { SetupProber(c).run(::update) }
    }

    private fun update(f: (SetupProbe) -> SetupProbe) {
        _ui.update { it.copy(probe = f(it.probe)) }
    }

    private var lastStepChangeMs = 0L

    fun goTo(step: SetupStep) {
        // A second tap during the page transition must not skip a step.
        val now = android.os.SystemClock.uptimeMillis()
        val cur = _ui.value
        if (step == cur.step || !stepChangeAllowed(lastStepChangeMs, now)) return
        // Forward needs this step done; back is always allowed.
        if (step.ordinal > cur.step.ordinal && !cur.canContinue) return
        if (cur.step == SetupStep.LEGAL && step.ordinal > cur.step.ordinal) {
            viewModelScope.launch { c.settings.acceptLegal(LEGAL_VERSION, System.currentTimeMillis()) }
        }
        lastStepChangeMs = now
        _ui.update { it.copy(step = step) }
        if (step == SetupStep.CHECK && _ui.value.probe == SetupProbe()) runChecks()
    }

    fun next() = SetupStep.entries.getOrNull(_ui.value.step.ordinal + 1)?.let(::goTo)
    fun back() = SetupStep.entries.getOrNull(_ui.value.step.ordinal - 1)?.let(::goTo)

    fun setLegalChecked(v: Boolean) = _ui.update { it.copy(legalChecked = v) }

    fun setThemeMode(v: String) = viewModelScope.launch { c.settings.setThemeMode(v) }
    fun setAccent(v: String) = viewModelScope.launch { c.settings.setAccent(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { c.settings.setDynamicColor(v) }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        c.settings.setOnboarded(true)
        onDone()
    }
}
