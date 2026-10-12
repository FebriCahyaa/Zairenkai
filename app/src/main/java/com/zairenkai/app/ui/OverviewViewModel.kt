// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Dashboard overview: root, ZKFC info, license, boost and thermal state.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OverviewState(
    val loading: Boolean = true,
    val rootAvailable: Boolean = false,
    val info: InfoResult? = null,
    val license: LicenseResult? = null,
    val boost: BoostStatus? = null,
    val thermal: ThermalStatus? = null,
    val safeMode: Boolean = false,
    val message: UiText? = null,
)

class OverviewViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(OverviewState())
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val root = c.repository.rootAvailable()
            if (!root) {
                _state.value = OverviewState(loading = false, rootAvailable = false)
                return@launch
            }
            val safe = runCatching { c.repository.safeStatus() }.getOrNull()
            val info = runCatching { c.repository.info() }.getOrNull()
            val lic = runCatching { c.repository.license() }.getOrNull()
            val boost = runCatching { c.repository.boostStatus() }.getOrNull()
            val thermal = runCatching { c.zperf.thermal() }.getOrNull()
            viewModelScope.launch {
                kotlinx.coroutines.delay(15_000)
                val engineHealthy = runCatching { c.zperf.status()?.ok == true }.getOrDefault(false)
                val probeHealthy = runCatching { c.zperf.probe() != null }.getOrDefault(false)
                // A missing license is a normal state, not a boot-health failure.
                // Boot confirmation only proves the engine/probe path is stable.
                if (info != null && engineHealthy && probeHealthy && (safe?.safeMode != true)) {
                    runCatching { c.repository.safeConfirm() }
                }
            }
            _state.value = OverviewState(
                loading = false, rootAvailable = true, info = info, license = lic,
                boost = boost, thermal = thermal, safeMode = safe?.safeMode == true,
            )
        }
    }

    fun resetBoosts() = viewModelScope.launch {
        val b = runCatching { c.repository.boostReset() }.getOrNull()
        _state.value = _state.value.copy(boost = b)
    }

    fun clearSafeMode() = viewModelScope.launch {
        runCatching { ZkfctlClient().safeClear() }
        refresh()
    }
}
