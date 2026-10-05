// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * ViewModels and a factory that injects the AppContainer. Kept in one file to
 * avoid boilerplate; each screen observes the one it needs.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.data.*
import com.zairenkai.app.domain.Profile
import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.domain.Profiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/* ---------------- shared root/overview ---------------- */
data class OverviewState(
    val loading: Boolean = true,
    val rootAvailable: Boolean = false,
    val info: InfoResult? = null,
    val license: LicenseResult? = null,
    val boost: BoostStatus? = null,
    val safeMode: Boolean = false,
    val message: String? = null,
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
            // Read safe-mode BEFORE confirming, then confirm this boot healthy.
            val safe = runCatching { c.repository.safeStatus() }.getOrNull()
            runCatching { c.repository.safeConfirm() }
            val info = runCatching { c.repository.info() }.getOrNull()
            val lic = runCatching { c.repository.license() }.getOrNull()
            val boost = runCatching { c.repository.boostStatus() }.getOrNull()
            _state.value = OverviewState(
                loading = false, rootAvailable = true, info = info, license = lic,
                boost = boost, safeMode = safe?.safeMode == true,
            )
        }
    }

    fun resetBoosts() = viewModelScope.launch {
        val b = runCatching { c.repository.boostReset() }.getOrNull()
        _state.value = _state.value.copy(boost = b)
    }
}

/* ---------------- live monitor ---------------- */
data class MonitorUi(
    val running: Boolean = false,
    val latest: MonitorResult? = null,
    val cpuHistory: List<Int> = emptyList(),      // avg load %
    val gpuHistory: List<Int> = emptyList(),      // busy %
    val tempHistory: List<Float> = emptyList(),   // hottest °C
)

class MonitorViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(MonitorUi())
    val ui: StateFlow<MonitorUi> = _ui.asStateFlow()
    private val cap = 60

    fun start() {
        if (_ui.value.running) return
        _ui.value = _ui.value.copy(running = true)
        viewModelScope.launch {
            while (isActive && _ui.value.running) {
                val m = runCatching { c.repository.monitor(500) }.getOrNull()
                if (m != null && m.ok) {
                    val onCpus = m.cpu.filter { it.online && it.loadPct >= 0 }
                    val avg = if (onCpus.isEmpty()) 0 else onCpus.sumOf { it.loadPct } / onCpus.size
                    val hottest = m.thermal.maxOfOrNull { it.celsius } ?: 0f
                    _ui.value = _ui.value.copy(
                        latest = m,
                        cpuHistory = (_ui.value.cpuHistory + avg).takeLast(cap),
                        gpuHistory = (_ui.value.gpuHistory + m.gpu.busyPct.coerceAtLeast(0)).takeLast(cap),
                        tempHistory = (_ui.value.tempHistory + hottest).takeLast(cap),
                    )
                }
            }
        }
    }

    fun stop() { _ui.value = _ui.value.copy(running = false) }
    override fun onCleared() { stop() }
}

/* ---------------- tweaks ---------------- */
data class TweaksUi(
    val loading: Boolean = true,
    val lite: Boolean = false,
    val tweaks: List<Tweak> = emptyList(),
    val busyId: String? = null,
)

class TweaksViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(TweaksUi())
    val ui: StateFlow<TweaksUi> = _ui.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val lite = c.settings.settings.first().liteMode
        val list = runCatching { c.repository.tweaks(lite) }.getOrNull()
        _ui.value = TweaksUi(false, lite, list?.tweaks ?: emptyList())
    }

    fun set(id: String, value: String) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busyId = id)
        val list = runCatching { c.repository.setTweak(id, value, _ui.value.lite) }.getOrNull()
        _ui.value = _ui.value.copy(busyId = null, tweaks = list?.tweaks ?: _ui.value.tweaks)
    }
}

/* ---------------- profiles ---------------- */
data class ProfilesUi(
    val active: ProfileId? = null,
    val auto: Boolean = false,
    val applying: ProfileId? = null,
    val lastReport: ZkfcRepository.ApplyReport? = null,
)

class ProfilesViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ProfilesUi())
    val ui: StateFlow<ProfilesUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val s = c.settings.settings.first()
            _ui.value = _ui.value.copy(active = s.activeProfile, auto = s.autoProfile)
        }
    }

    fun apply(profile: Profile) = viewModelScope.launch {
        _ui.value = _ui.value.copy(applying = profile.id)
        val report = runCatching { c.repository.applyProfile(profile, false) }.getOrNull()
        c.settings.setActiveProfile(profile.id)
        _ui.value = _ui.value.copy(applying = null, active = profile.id, lastReport = report)
    }

    fun setAuto(enabled: Boolean) = viewModelScope.launch {
        c.settings.setAutoProfile(enabled)
        _ui.value = _ui.value.copy(auto = enabled)
    }
}

/* ---------------- system / security ---------------- */
data class SystemUi(
    val loading: Boolean = true,
    val security: SecurityResult? = null,
    val license: LicenseResult? = null,
    val policy: PolicyResult? = null,
    val info: InfoResult? = null,
)

class SystemViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SystemUi())
    val ui: StateFlow<SystemUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _ui.value = _ui.value.copy(loading = true)
        _ui.value = SystemUi(
            loading = false,
            security = runCatching { c.repository.security() }.getOrNull(),
            license = runCatching { c.repository.license() }.getOrNull(),
            policy = runCatching { c.repository.policy() }.getOrNull(),
            info = runCatching { c.repository.info() }.getOrNull(),
        )
    }
}

/* ---------------- settings + live log ---------------- */
class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings
    private val _log = MutableStateFlow<List<LogRecord>>(emptyList())
    val log: StateFlow<List<LogRecord>> = _log.asStateFlow()
    private var seq = 0L
    private var streaming = false

    fun setLite(v: Boolean) = viewModelScope.launch { c.settings.setLite(v) }
    fun setMitigation(v: Boolean) = viewModelScope.launch { c.settings.setMitigation(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { c.settings.setDynamicColor(v) }
    fun setLogLevel(level: String) = viewModelScope.launch {
        c.settings.setLogLevel(level)
        runCatching { c.repository.setLogLevel(level) }
    }

    fun startLog() {
        if (streaming) return
        streaming = true
        viewModelScope.launch {
            while (isActive && streaming) {
                val r = runCatching { c.repository.log(seq) }.getOrNull()
                if (r != null && r.ok) {
                    if (r.records.isNotEmpty()) {
                        _log.value = (_log.value + r.records).takeLast(300)
                        seq = r.nextSeq
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun stopLog() { streaming = false }
}

/* ---------------- factory ---------------- */
@Suppress("UNCHECKED_CAST")
class VmFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(OverviewViewModel::class.java) -> OverviewViewModel(c)
        modelClass.isAssignableFrom(MonitorViewModel::class.java) -> MonitorViewModel(c)
        modelClass.isAssignableFrom(TweaksViewModel::class.java) -> TweaksViewModel(c)
        modelClass.isAssignableFrom(ProfilesViewModel::class.java) -> ProfilesViewModel(c)
        modelClass.isAssignableFrom(SystemViewModel::class.java) -> SystemViewModel(c)
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(c)
        else -> throw IllegalArgumentException("unknown VM $modelClass")
    } as T
}
