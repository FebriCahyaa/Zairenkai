// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Live monitor state: the `zkfctl monitor` fold rules and the screen-facing
 * view of the shared sampler.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import com.zairenkai.app.AppContainer
import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.data.*
import com.zairenkai.app.data.monitor.LiveState
import kotlinx.coroutines.flow.StateFlow

// Metric sources, units and unavailable states: docs/monitor-metrics.md.
data class MonitorUi(
    val running: Boolean = false,
    /** The last successful sample; null while waiting or after a failed one. */
    val latest: MonitorResult? = null,
    val cpuHistory: List<Int> = emptyList(),      // avg load % of online cores
    val gpuHistory: List<Int> = emptyList(),      // busy %
    val tempHistory: List<Float> = emptyList(),   // hottest valid zone, °C
    /** Why the last sample failed; cleared by the next good one. */
    val error: UiText? = null,
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
            error = m?.error?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) }
                ?: uiText(R.string.monitor_error_no_response),
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

/**
 * Like [monitorRetryDelayMs], but when zkfctl is known to be absent or not
 * executable a retry cannot succeed until the module is installed, so ask
 * once a minute instead of every 8 s.
 */
internal fun monitorNextDelayMs(failures: Int, failureKind: String?): Long =
    if (failures > 0 && (failureKind == "not_found" || failureKind == "not_executable")) MISSING_DEPENDENCY_RETRY_MS
    else monitorRetryDelayMs(failures)

internal const val MISSING_DEPENDENCY_RETRY_MS = 60_000L

/**
 * Thin view of the shared [com.zairenkai.app.data.monitor.LiveMonitor]: the
 * hub and every detail screen read the same state, so the graphs carry over
 * when the user opens a card.
 */
class MonitorViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<LiveState> = c.live.state
    private var held = false

    fun start() {
        if (held) return
        held = true
        c.live.acquire()
    }

    fun stop() {
        if (!held) return
        held = false
        c.live.release()
    }

    fun dismissModuleBanner() = c.live.dismissModuleBanner()

    override fun onCleared() { stop() }
}
