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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
// Metric sources, units and unavailable states: docs/monitor-metrics.md.
data class MonitorUi(
    val running: Boolean = false,
    /** The last successful sample; null while waiting or after a failed one. */
    val latest: MonitorResult? = null,
    val cpuHistory: List<Int> = emptyList(),      // avg load % of online cores
    val gpuHistory: List<Int> = emptyList(),      // busy %
    val tempHistory: List<Float> = emptyList(),   // hottest valid zone, °C
    /** Why the last sample failed; cleared by the next good one. */
    val error: String? = null,
    val failures: Int = 0,
)

/** Thermal zone readings outside this range (°C) are treated as not reported. */
internal val PLAUSIBLE_CELSIUS = 1f..150f

/**
 * Fold one `zkfctl monitor` result into the monitor state. Only measured
 * values are appended: encoding an unavailable sensor as 0 draws convincing
 * but false flatlines. A failed sample clears the headline (so an old sample
 * is never shown as live) and keeps the rolling history.
 */
internal fun MonitorUi.withResult(m: MonitorResult?, cap: Int = 60): MonitorUi {
    if (m == null || !m.ok) {
        return copy(
            latest = null,
            error = m?.error?.takeIf { it.isNotBlank() } ?: "zkfctl tidak merespons",
            failures = failures + 1,
        )
    }
    val onCpus = m.cpu.filter { it.online && it.loadPct in 0..100 }
    val hottest = m.thermal.map { it.celsius }.filter { it in PLAUSIBLE_CELSIUS }.maxOrNull()
    return copy(
        latest = m,
        error = null,
        failures = 0,
        cpuHistory = if (onCpus.isNotEmpty()) {
            (cpuHistory + onCpus.sumOf { it.loadPct } / onCpus.size).takeLast(cap)
        } else cpuHistory,
        gpuHistory = if (m.gpu.busyPct in 0..100) (gpuHistory + m.gpu.busyPct).takeLast(cap) else gpuHistory,
        tempHistory = hottest?.let { (tempHistory + it).takeLast(cap) } ?: tempHistory,
    )
}

/**
 * Pause before the next sample. A good sample is followed immediately (the
 * sample itself spans the 500 ms window); failures back off 1, 2, 4, 8 s so a
 * missing zkfctl or root grant does not spawn `su` in a tight loop.
 */
internal fun monitorRetryDelayMs(failures: Int): Long =
    if (failures <= 0) 0L else 1000L shl (failures - 1).coerceAtMost(3)

class MonitorViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(MonitorUi())
    val ui: StateFlow<MonitorUi> = _ui.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        // Keep the rolling graphs, but force the headline status to wait for a
        // fresh result instead of presenting a stale sample as live telemetry.
        _ui.value = _ui.value.copy(running = true, latest = null, error = null, failures = 0)
        job = viewModelScope.launch {
            while (isActive) {
                val m = runCatching { c.repository.monitor(SAMPLE_WINDOW_MS) }.getOrNull()
                ensureActive()
                _ui.value = _ui.value.withResult(m)
                delay(monitorRetryDelayMs(_ui.value.failures))
            }
        }
    }

    // Cancelling (not just flagging) the loop means a quick stop/start cannot
    // leave two loops sampling side by side.
    fun stop() {
        job?.cancel()
        job = null
        _ui.value = _ui.value.copy(running = false)
    }

    override fun onCleared() { stop() }

    private companion object { const val SAMPLE_WINDOW_MS = 500 }
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
    /** Stored custom profiles and the built-in modes they derive from; null when unknown. */
    val customs: CustomListResult? = null,
    /** Last explicit profile from local settings; shown only as unverified when the engine is down. */
    val cachedProfile: ProfileId? = null,
    val applying: ProfileId? = null,
    val applyingCustom: String? = null,
    /** The custom profile being edited; null when the editor is closed. */
    val editor: CustomDraft? = null,
    val editorError: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    /** The user's persisted choice according to the engine; null means the engine default applies. */
    val selected: Profile? get() = status?.takeIf { it.saved }?.let { Profiles.byMode(it.desired) }
    /** The persisted choice when it is a custom profile. */
    val selectedCustom: CustomProfile?
        get() = status?.takeIf { it.saved && isCustomId(it.desired) }
            ?.let { s -> customs?.profiles?.firstOrNull { it.id == s.desired } }
    val selectedName: String?
        get() = selected?.name ?: selectedCustom?.label
            ?: status?.takeIf { it.saved && isCustomId(it.desired) }?.desired
    val auto: Boolean get() = selected?.id == ProfileId.AUTO
    val busy: Boolean get() = applying != null || applyingCustom != null || saving
    /** True when the device profile does not define the mode a profile needs. */
    fun unsupported(profile: Profile): Boolean =
        profile.id != ProfileId.AUTO && modes.isNotEmpty() && profile.mode !in modes
    /** Display name of a mode a custom profile derives from. */
    fun baseName(base: String): String = Profiles.byMode(base)?.name ?: base
    fun baseValues(base: String): ModeValues = customs?.bases?.firstOrNull { it.id == base }?.values.orEmpty()
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
                modes = emptyMap(), customs = null, cachedProfile = cached)
            return@launch
        }
        val status = runCatching { c.zperf.status() }.getOrNull()
        val modes = runCatching { c.zperf.modes() }.getOrNull()?.modes.orEmpty().associateBy { it.id }
        val customs = runCatching { c.zperf.customs() }.getOrNull()
        _ui.value = _ui.value.copy(
            loading = false,
            engineAvailable = status != null,
            status = status,
            modes = modes,
            customs = customs,
            cachedProfile = cached,
        )
    }

    /** Apply an engine mode; [onApplied] runs only after zperfd committed it. */
    private suspend fun applyMode(mode: String, name: String, onApplied: suspend () -> Unit = {}) {
        if (!runCatching { c.zperf.available() }.getOrDefault(false)) {
            _ui.value = _ui.value.copy(
                applying = null, applyingCustom = null,
                error = "zperfd belum aktif. Profil tidak diterapkan agar tidak melewati transaction engine.",
            )
            return
        }
        val result = runCatching { c.zperf.apply(mode) }.getOrElse {
            ModeApplyResult(ok = false, kind = "protocol", error = it.message)
        }
        if (result.ok) {
            onApplied()
            _ui.value = _ui.value.copy(applying = null, applyingCustom = null,
                notice = profileSuccessMessage(name, result))
        } else {
            _ui.value = _ui.value.copy(applying = null, applyingCustom = null, error = profileFailureMessage(result))
        }
        refresh()
    }

    fun apply(profile: Profile) = viewModelScope.launch {
        _ui.value = _ui.value.copy(applying = profile.id, error = null, notice = null)
        applyMode(profile.mode, profile.name) {
            // Local settings only cache the last explicit choice for offline
            // display; the engine status read below is the source of truth.
            if (profile.id != ProfileId.AUTO) c.settings.setActiveProfile(profile.id)
            c.settings.setAutoProfile(profile.id == ProfileId.AUTO)
        }
    }

    fun applyCustom(custom: CustomProfile) = viewModelScope.launch {
        _ui.value = _ui.value.copy(applyingCustom = custom.id, error = null, notice = null)
        applyMode(custom.id, custom.label) { c.settings.setAutoProfile(false) }
    }

    /** Re-apply the saved choice after a kernel/device change (engine hold: revalidation_required). */
    fun confirmSaved() {
        val ui = _ui.value
        ui.selectedCustom?.let { applyCustom(it); return }
        apply(ui.selected ?: return)
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

    fun newCustom() {
        val ui = _ui.value
        val bases = ui.customs?.bases.orEmpty().map { it.id }
        val preferred = (ui.selectedCustom?.base ?: ui.selected?.mode)?.takeIf { it in bases }
        val base = preferred ?: "balance".takeIf { it in bases } ?: bases.firstOrNull() ?: return
        _ui.value = ui.copy(editor = CustomDraft(id = "custom-baru", label = "", base = base), editorError = null)
    }

    fun editCustom(custom: CustomProfile) {
        _ui.value = _ui.value.copy(editor = CustomDraft.fromProfile(custom), editorError = null)
    }

    fun updateDraft(draft: CustomDraft) {
        _ui.value = _ui.value.copy(editor = draft, editorError = null)
    }

    fun closeEditor() {
        if (!_ui.value.saving) _ui.value = _ui.value.copy(editor = null, editorError = null)
    }

    fun saveDraft() = viewModelScope.launch {
        val ui = _ui.value
        val editing = ui.editor ?: return@launch
        val existing = ui.customs?.profiles.orEmpty().map { it.id }.toSet()
        val draft = if (editing.isNew) editing.copy(id = newCustomId(editing.label, existing)) else editing
        draft.validate()?.let {
            _ui.value = ui.copy(editorError = it)
            return@launch
        }
        _ui.value = ui.copy(saving = true, editorError = null, error = null, notice = null)
        val result = runCatching { c.zperf.putCustom(draft) }.getOrElse {
            CustomWriteResult(ok = false, id = draft.id, kind = "protocol", error = it.message)
        }
        if (!result.ok) {
            _ui.value = _ui.value.copy(saving = false, editorError = customWriteFailureMessage(result))
            return@launch
        }
        val active = ui.selectedCustom?.id == draft.id
        _ui.value = _ui.value.copy(saving = false, editor = null,
            notice = "Profil kustom ${draft.label.trim()} disimpan.")
        refresh().join()
        // The active profile's stored values changed: re-apply so the device
        // runs them now and the outcome is verified like any apply.
        if (active) _ui.value.customs?.profiles?.firstOrNull { it.id == draft.id }?.let { applyCustom(it) }
    }

    fun deleteCustom(custom: CustomProfile) = viewModelScope.launch {
        _ui.value = _ui.value.copy(saving = true, error = null, notice = null)
        val result = runCatching { c.zperf.deleteCustom(custom.id) }.getOrElse {
            CustomWriteResult(ok = false, id = custom.id, kind = "protocol", error = it.message)
        }
        _ui.value = if (result.ok) {
            _ui.value.copy(saving = false, notice = "Profil kustom ${custom.label} dihapus.")
        } else {
            _ui.value.copy(saving = false, error = customWriteFailureMessage(result))
        }
        refresh()
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
