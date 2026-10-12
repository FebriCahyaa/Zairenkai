// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Dashboard overview: the system check (root, installed components, ZKFC,
 * engine, token) plus the ZKFC, license, boost and thermal state. A result
 * that did not come back `ok` is dropped, so its defaults (API 0.0.0, hook
 * "none", zero boosts) are never shown as device data.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.data.*
import com.zairenkai.app.data.diag.BinState
import com.zairenkai.app.ui.onboarding.SetupCheck
import com.zairenkai.app.ui.onboarding.SetupProbe
import com.zairenkai.app.ui.onboarding.SetupProber
import com.zairenkai.app.ui.onboarding.setupChecks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OverviewState(
    val loading: Boolean = true,
    val probe: SetupProbe = SetupProbe(),
    /** Only an `ok` answer is kept; null means not available. */
    val info: InfoResult? = null,
    val license: LicenseResult? = null,
    val boost: BoostStatus? = null,
    val thermal: ThermalStatus? = null,
    val safeMode: Boolean = false,
    val message: UiText? = null,
) {
    val rootAvailable: Boolean get() = probe.root == true
    val checks: List<SetupCheck> get() = setupChecks(probe)
}

class OverviewViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(OverviewState())
    val state: StateFlow<OverviewState> = _state.asStateFlow()
    private var job: Job? = null
    private var confirmJob: Job? = null

    init { refresh() }

    fun refresh() {
        job?.cancel()
        confirmJob?.cancel()
        _state.value = OverviewState(loading = true)
        job = viewModelScope.launch {
            SetupProber(c).run { f -> _state.update { it.copy(probe = f(it.probe)) } }
            val p = _state.value.probe
            if (p.root != true) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val info = p.info?.takeIf { it.ok }
            val tool = p.facts?.zkfctl?.let { it == BinState.OK } ?: (info != null)
            // Safe-mode status needs zkfctl but not /dev/zkfc.
            val safe = if (tool) runCatching { c.repository.safeStatus() }.getOrNull()?.takeIf { it.ok } else null
            val lic = if (info != null) runCatching { c.repository.license() }.getOrNull()?.takeIf { it.ok } else null
            val boost = if (info != null) runCatching { c.repository.boostStatus() }.getOrNull()?.takeIf { it.ok } else null
            val thermal = if (p.engineInstalled == true) runCatching { c.zperf.thermal() }.getOrNull() else null
            _state.update {
                it.copy(
                    loading = false, info = info, license = lic, boost = boost, thermal = thermal,
                    safeMode = safe?.safeMode == true,
                )
            }
            if (info != null && safe?.safeMode != true) scheduleBootConfirm()
        }
    }

    /**
     * Fifteen seconds after a healthy load, confirm the boot so the next one
     * is not counted as a crash. Only when ZKFC answered, the engine and its
     * probe answer again now, and safe mode is off.
     */
    private fun scheduleBootConfirm() {
        confirmJob = viewModelScope.launch {
            delay(15_000)
            val engineHealthy = runCatching { c.zperf.status()?.ok == true }.getOrDefault(false)
            val probeHealthy = runCatching { c.zperf.probe() != null }.getOrDefault(false)
            if (engineHealthy && probeHealthy) runCatching { c.repository.safeConfirm() }
        }
    }

    fun resetBoosts() = viewModelScope.launch {
        val b = runCatching { c.repository.boostReset() }.getOrNull()?.takeIf { it.ok }
        _state.update { it.copy(boost = b) }
    }

    fun clearSafeMode() = viewModelScope.launch {
        runCatching { c.repository.safeClear() }
        refresh()
    }
}
