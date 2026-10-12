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
import com.zairenkai.app.core.runtime.RuntimeSnapshot
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
    val thermal: ThermalStatus? = null,
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
        // Keep the rolling graphs, but force the headline status to wait for a
        // fresh result instead of presenting a stale sample as live telemetry.
        _ui.value = _ui.value.copy(running = true, latest = null)
        viewModelScope.launch {
            while (isActive && _ui.value.running) {
                val m = runCatching { c.repository.monitor(500) }.getOrNull()
                if (m != null && m.ok) {
                    // Only append measured values. Encoding an unavailable sensor as 0
                    // produces convincing but false flatlines in the monitor charts.
                    val onCpus = m.cpu.filter { it.online && it.loadPct in 0..100 }
                    val validThermal = m.thermal.map { it.celsius }.filter { it in 1f..150f }
                    val current = _ui.value
                    _ui.value = current.copy(
                        latest = m,
                        cpuHistory = if (onCpus.isNotEmpty()) {
                            (current.cpuHistory + onCpus.sumOf { it.loadPct } / onCpus.size).takeLast(cap)
                        } else current.cpuHistory,
                        gpuHistory = if (m.gpu.busyPct in 0..100) {
                            (current.gpuHistory + m.gpu.busyPct).takeLast(cap)
                        } else current.gpuHistory,
                        tempHistory = validThermal.maxOrNull()?.let {
                            (current.tempHistory + it).takeLast(cap)
                        } ?: current.tempHistory,
                    )
                }
            }
        }
    }

    fun stop() { _ui.value = _ui.value.copy(running = false) }
    override fun onCleared() { stop() }
}

/* ---------------- usage history ---------------- */
data class UsageHistoryUi(
    val loading: Boolean = true,
    val sessions: List<UsageSession> = emptyList(),
    val hasUsageAccess: Boolean = true,
)

class UsageHistoryViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(UsageHistoryUi())
    val ui: StateFlow<UsageHistoryUi> = _ui.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val list = runCatching { c.usageStore.load() }.getOrDefault(emptyList())
        val access = runCatching { ForegroundApp(c.appContext).hasUsageAccess() }.getOrDefault(false)
        _ui.value = UsageHistoryUi(false, list, access)
    }

    fun delete(id: String) = viewModelScope.launch {
        val list = runCatching { c.usageStore.delete(id) }.getOrDefault(_ui.value.sessions)
        _ui.value = _ui.value.copy(sessions = list)
    }

    fun clearAll() = viewModelScope.launch {
        runCatching { c.usageStore.clear() }
        _ui.value = _ui.value.copy(sessions = emptyList())
    }

    fun find(id: String): UsageSession? = _ui.value.sessions.firstOrNull { it.id == id }
}

/* ---------------- tweaks ---------------- */
data class TweaksUi(
    val loading: Boolean = true,
    val lite: Boolean = false,
    val tweaks: List<Tweak> = emptyList(),
    /** False when the list came from the zkfctl fallback: nothing is writable. */
    val writable: Boolean = false,
    /** Set when the list itself could not be read (not the same as "no tunables"). */
    val loadError: String? = null,
    val busyId: String? = null,
    val error: String? = null,
    val notice: String? = null,
)

class TweaksViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(TweaksUi())
    val ui: StateFlow<TweaksUi> = _ui.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val lite = c.settings.settings.first().liteMode
        _ui.value = _ui.value.copy(loading = true, lite = lite)
        if (!c.repository.rootAvailable()) {
            _ui.value = TweaksUi(loading = false, lite = lite, loadError = "Akses root tidak tersedia.")
            return@launch
        }
        val list = runCatching { c.repository.tweaks(lite) }
        _ui.value = list.fold(
            onSuccess = {
                TweaksUi(
                    loading = false,
                    lite = lite,
                    tweaks = it.tweaks,
                    writable = it.source == "zperfd",
                    loadError = if (!it.ok) (it.error ?: "Daftar tweak tidak dapat dibaca.") else null,
                )
            },
            onFailure = {
                TweaksUi(loading = false, lite = lite, loadError = "Daftar tweak tidak dapat dibaca: ${it.message ?: "galat"}")
            },
        )
    }

    fun set(id: String, value: String) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busyId = id, error = null, notice = null)
        val lite = _ui.value.lite
        val result = runCatching { c.repository.setTweak(id, value, lite) }.getOrElse {
            TweakWriteResult(ok = false, id = id, kind = "protocol", error = it.message)
        }
        if (!result.ok) {
            _ui.value = _ui.value.copy(busyId = null, error = tweakFailureMessage(result))
            return@launch
        }
        // Re-read from the engine so the row shows the observed value, not the request.
        val list = runCatching { c.repository.tweaks(lite) }.getOrNull()
        _ui.value = _ui.value.copy(
            busyId = null,
            tweaks = list?.tweaks ?: _ui.value.tweaks,
            notice = tweakSuccessMessage(result),
        )
    }

    fun dismissMessages() {
        _ui.value = _ui.value.copy(error = null, notice = null)
    }
}

/* ---------------- profiles ---------------- */
data class ProfilesUi(
    val loading: Boolean = true,
    val engineAvailable: Boolean = false,
    /** Engine truth: desired/effective mode, hold reason. Null when zperfd is unreachable. */
    val status: ZperfStatus? = null,
    /** Modes the device profile defines, keyed by mode id. Empty when unknown. */
    val modes: Map<String, ModeSummary> = emptyMap(),
    /** Last explicit profile from local settings; shown only as unverified when the engine is down. */
    val cachedProfile: ProfileId? = null,
    val applying: ProfileId? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    /** The user's persisted choice according to the engine; null means the engine default applies. */
    val selected: Profile? get() = status?.takeIf { it.saved }?.let { Profiles.byMode(it.desired) }
    val auto: Boolean get() = selected?.id == ProfileId.AUTO
    /** True when the device profile does not define the mode a profile needs. */
    fun unsupported(profile: Profile): Boolean =
        profile.id != ProfileId.AUTO && modes.isNotEmpty() && profile.mode !in modes
}

class ProfilesViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ProfilesUi())
    val ui: StateFlow<ProfilesUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val cached = c.settings.settings.first().activeProfile
        val available = runCatching { c.zperf.available() }.getOrDefault(false)
        if (!available) {
            _ui.value = _ui.value.copy(loading = false, engineAvailable = false, status = null,
                modes = emptyMap(), cachedProfile = cached)
            return@launch
        }
        val status = runCatching { c.zperf.status() }.getOrNull()
        val modes = runCatching { c.zperf.modes() }.getOrNull()?.modes.orEmpty().associateBy { it.id }
        _ui.value = _ui.value.copy(
            loading = false,
            engineAvailable = status != null,
            status = status,
            modes = modes,
            cachedProfile = cached,
        )
    }

    fun apply(profile: Profile) = viewModelScope.launch {
        _ui.value = _ui.value.copy(applying = profile.id, error = null, notice = null)
        if (!runCatching { c.zperf.available() }.getOrDefault(false)) {
            _ui.value = _ui.value.copy(
                applying = null,
                error = "zperfd belum aktif. Profil tidak diterapkan agar tidak melewati transaction engine.",
            )
            return@launch
        }
        val result = runCatching { c.zperf.apply(profile.mode) }.getOrElse {
            ModeApplyResult(ok = false, kind = "protocol", error = it.message)
        }
        if (result.ok) {
            // Local settings only cache the last explicit choice for offline
            // display; the engine status read below is the source of truth.
            if (profile.id != ProfileId.AUTO) c.settings.setActiveProfile(profile.id)
            c.settings.setAutoProfile(profile.id == ProfileId.AUTO)
            _ui.value = _ui.value.copy(applying = null, notice = profileSuccessMessage(profile.name, result))
        } else {
            _ui.value = _ui.value.copy(applying = null, error = profileFailureMessage(result))
        }
        refresh()
    }

    /** Re-apply the saved choice after a kernel/device change (engine hold: revalidation_required). */
    fun confirmSaved() {
        val saved = _ui.value.status?.takeIf { it.saved }?.let { Profiles.byMode(it.desired) } ?: return
        apply(saved)
    }

    fun setAuto(enabled: Boolean) {
        if (enabled) {
            apply(Profiles.AUTO)
            return
        }
        val fallback = _ui.value.cachedProfile?.takeIf { it != ProfileId.AUTO }?.let(Profiles::byId)
            ?: Profiles.BALANCED
        apply(fallback)
    }

    fun dismissMessages() {
        _ui.value = _ui.value.copy(error = null, notice = null)
    }
}

/* ---------------- system / security ---------------- */
data class SystemUi(
    val loading: Boolean = true,
    val security: SecurityResult? = null,
    val license: LicenseResult? = null,
    val policy: PolicyResult? = null,
    val info: InfoResult? = null,
    val zperf: ZperfProbe? = null,
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
            zperf = runCatching { c.zperf.probe() }.getOrNull(),
        )
    }

    /** Install an API token staged at [path]; malformed engine output is a rejection, not a crash. */
    suspend fun installToken(path: String): LicenseResult =
        runCatching { c.repository.installToken(path) }.getOrElse {
            LicenseResult(ok = false, state = "engine_error", error = it.message)
        }
}

/* ---------------- runtime platform ---------------- */
data class RuntimeUi(
    val loading: Boolean = true,
    val snapshot: RuntimeSnapshot? = null,
    val error: String? = null,
)

class RuntimeViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(RuntimeUi())
    val ui: StateFlow<RuntimeUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _ui.value = _ui.value.copy(loading = true, error = null)
        runCatching { c.runtime.observe() }
            .onSuccess { _ui.value = RuntimeUi(false, it, null) }
            .onFailure { _ui.value = RuntimeUi(false, null, it.message ?: "runtime observation failed") }
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
    fun setThemeMode(v: String) = viewModelScope.launch { c.settings.setThemeMode(v) }
    fun setAccent(v: String) = viewModelScope.launch { c.settings.setAccent(v) }
    fun setGlass(v: Boolean) = viewModelScope.launch { c.settings.setGlass(v) }
    fun setGlassOpacity(v: Float) = viewModelScope.launch { c.settings.setGlassOpacity(v) }
    fun setGlassBlur(v: Float) = viewModelScope.launch { c.settings.setGlassBlur(v) }
    fun setGlassTint(v: Float) = viewModelScope.launch { c.settings.setGlassTint(v) }
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
        modelClass.isAssignableFrom(UsageHistoryViewModel::class.java) -> UsageHistoryViewModel(c)
        modelClass.isAssignableFrom(TweaksViewModel::class.java) -> TweaksViewModel(c)
        modelClass.isAssignableFrom(ProfilesViewModel::class.java) -> ProfilesViewModel(c)
        modelClass.isAssignableFrom(SystemViewModel::class.java) -> SystemViewModel(c)
        modelClass.isAssignableFrom(RuntimeViewModel::class.java) -> RuntimeViewModel(c)
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(c)
        else -> throw IllegalArgumentException("unknown VM $modelClass")
    } as T
}
