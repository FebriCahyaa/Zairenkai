// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * One live sampler shared by the Monitor hub and its detail screens, so
 * moving between them keeps the graphs instead of restarting them. Screens
 * acquire it while visible; it stops a few seconds after the last release.
 *
 * Three independent loops, so a missing root grant only empties what needs
 * root: `zkfctl monitor` (CPU, GPU, thermal), Android's battery + memory
 * (always), and zperfd's read-only probe/battery (cluster layout, cycle
 * count, design capacity, charger).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.monitor

import android.app.ActivityManager
import android.content.Context
import com.zairenkai.app.data.BatteryReport
import com.zairenkai.app.data.MonitorResult
import com.zairenkai.app.data.ZkfcRepository
import com.zairenkai.app.data.ZperfClient
import com.zairenkai.app.data.ZperfProbe
import com.zairenkai.app.data.cpu.CpuClusters
import com.zairenkai.app.data.power.AndroidBatteryReader
import com.zairenkai.app.data.power.BatteryMath
import com.zairenkai.app.data.power.BatteryReading
import com.zairenkai.app.ui.MonitorUi
import com.zairenkai.app.ui.monitorNextDelayMs
import com.zairenkai.app.ui.withResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** RAM as Android reports it to apps (no root). */
data class MemReading(val totalMb: Long, val availMb: Long, val thresholdMb: Long, val low: Boolean) {
    val usedMb: Long get() = (totalMb - availMb).coerceAtLeast(0)
    val usedPct: Int get() = if (totalMb > 0) (usedMb * 100 / totalMb).toInt() else 0
}

data class LiveState(
    val running: Boolean = false,
    /** `zkfctl monitor` state and its rolling CPU/GPU/temperature history. */
    val monitor: MonitorUi = MonitorUi(),
    val probe: ZperfProbe? = null,
    /** Load % per CPU, oldest first. */
    val coreHistory: Map<Int, List<Int>> = emptyMap(),
    /** Highest core frequency (MHz) per cluster policy, oldest first. */
    val clusterFreqHistory: Map<String, List<Float>> = emptyMap(),
    val gpuFreqHistory: List<Float> = emptyList(),
    val battery: BatteryReading? = null,
    val batteryHistory: List<BatteryReading> = emptyList(),
    val zperfBattery: Boolean = false,
    val memory: MemReading? = null,
    val memHistory: List<Float> = emptyList(),
    /** The user hid the "needs the Zairenkai module" banner for this run of the app. */
    val moduleBannerDismissed: Boolean = false,
) {
    /** True when root telemetry failed and nothing has come in since. */
    val rootTelemetryMissing: Boolean get() = monitor.latest == null && monitor.failures > 0
}

/**
 * Fold one `zkfctl monitor` result into the live state: the shared
 * [withResult] rules plus the per-core and per-cluster histories the
 * CPU/GPU screen draws. Unmeasured values are skipped, never stored as 0.
 */
internal fun LiveState.withMonitor(m: MonitorResult?, cap: Int = HISTORY_CAP): LiveState {
    val mon = monitor.withResult(m, cap)
    if (m == null || !m.ok) return copy(monitor = mon)
    val cores = coreHistory.toMutableMap()
    for (c in m.cpu) {
        if (c.online && c.loadPct in 0..100) cores[c.cpu] = ((cores[c.cpu] ?: emptyList()) + c.loadPct).takeLast(cap)
    }
    val freqs = clusterFreqHistory.toMutableMap()
    for (cl in CpuClusters.group(probe?.policies.orEmpty(), m.cpu)) {
        val mhz = cl.curKhz?.let { it / 1000f } ?: continue
        freqs[cl.policy] = ((freqs[cl.policy] ?: emptyList()) + mhz).takeLast(cap)
    }
    val gpuMhz = m.gpu.curFreq.takeIf { it > 0 }?.let { it / 1_000_000f }
    return copy(
        monitor = mon,
        coreHistory = cores,
        clusterFreqHistory = freqs,
        gpuFreqHistory = gpuMhz?.let { (gpuFreqHistory + it).takeLast(cap) } ?: gpuFreqHistory,
    )
}

internal const val HISTORY_CAP = 90
internal const val BATTERY_HISTORY_CAP = 1800

class LiveMonitor(
    context: Context,
    private val repository: ZkfcRepository,
    private val zperf: ZperfClient,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val batteryReader = AndroidBatteryReader(app)
    private val am = app.getSystemService(ActivityManager::class.java)

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var users = 0
    private var jobs: List<Job> = emptyList()
    private var stopJob: Job? = null

    /** Called by each visible screen; pair with [release]. */
    @Synchronized
    fun acquire() {
        users++
        stopJob?.cancel()
        stopJob = null
        if (jobs.any { it.isActive }) return
        _state.update {
            it.copy(running = true, monitor = it.monitor.copy(running = true, latest = null, error = null, failures = 0))
        }
        jobs = listOf(scope.launch { monitorLoop() }, scope.launch { androidLoop() }, scope.launch { zperfLoop() })
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(STOP_GRACE_MS)
            stopNow()
        }
    }

    @Synchronized
    private fun stopNow() {
        if (users > 0) return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _state.update { it.copy(running = false, monitor = it.monitor.copy(running = false)) }
    }

    fun dismissModuleBanner() = _state.update { it.copy(moduleBannerDismissed = true) }

    private suspend fun monitorLoop() {
        while (true) {
            val m = runCatching { repository.monitor(SAMPLE_WINDOW_MS) }.getOrNull()
            currentCoroutineContext().ensureActive()
            _state.update { it.withMonitor(m) }
            delay(monitorNextDelayMs(_state.value.monitor.failures, m?.kind))
        }
    }

    private var zperfReport: BatteryReport? = null

    private suspend fun androidLoop() {
        while (true) {
            val now = System.currentTimeMillis()
            val reading = runCatching { BatteryMath.merge(now, batteryReader.read(), zperfReport) }.getOrNull()
            val mem = readMemory()
            _state.update { s ->
                s.copy(
                    battery = reading ?: s.battery,
                    batteryHistory = reading?.let { (s.batteryHistory + it).takeLast(BATTERY_HISTORY_CAP) } ?: s.batteryHistory,
                    memory = mem ?: s.memory,
                    memHistory = mem?.let { (s.memHistory + it.usedPct.toFloat()).takeLast(HISTORY_CAP) } ?: s.memHistory,
                )
            }
            delay(ANDROID_PERIOD_MS)
        }
    }

    /**
     * zperfd is optional: after a few misses it is asked rarely, so a device
     * without the module does not spawn `su` every few seconds.
     */
    private suspend fun zperfLoop() {
        var misses = 0
        while (true) {
            if (_state.value.probe == null) {
                runCatching { zperf.probe() }.getOrNull()?.let { p -> _state.update { it.copy(probe = p) } }
            }
            val report = runCatching { zperf.battery() }.getOrNull()?.takeIf { it.ok }
            zperfReport = report
            misses = if (report == null) misses + 1 else 0
            _state.update { it.copy(zperfBattery = report != null) }
            delay(if (misses >= 3) ZPERF_IDLE_MS else ZPERF_PERIOD_MS)
        }
    }

    private fun readMemory(): MemReading? = runCatching {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        MemReading(
            totalMb = info.totalMem / MB,
            availMb = info.availMem / MB,
            thresholdMb = info.threshold / MB,
            low = info.lowMemory,
        )
    }.getOrNull()?.takeIf { it.totalMb > 0 }

    private companion object {
        const val SAMPLE_WINDOW_MS = 500
        const val ANDROID_PERIOD_MS = 1000L
        const val ZPERF_PERIOD_MS = 5000L
        const val ZPERF_IDLE_MS = 60_000L
        const val STOP_GRACE_MS = 4000L
        const val MB = 1024L * 1024L
    }
}
