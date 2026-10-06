// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Usage recorder: the sampling engine behind the "riwayat penggunaan" feature.
 * Every tick it reads power + the foreground app, accumulates a battery/power
 * curve and per-app usage scenarios, then builds a UsageSession on stop.
 * Reused by both the in-app recorder and the background foreground-service.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID

class UsageRecorder(
    private val power: PowerSampler,
    private val fg: ForegroundApp,
    private val modeLabel: () -> String,
) {
    private class ScenarioAcc(var label: String, var mode: String) {
        var durationMs: Long = 0
        var energyWs: Double = 0.0
        var maxPowerW: Float = 0f
        var tempSum: Double = 0.0
        var tempCount: Int = 0
        var maxTempC: Float = 0f
    }

    private val scenarios = LinkedHashMap<String, ScenarioAcc>()
    private val curve = ArrayList<PowerPoint>()
    private var startMs = 0L
    private var startLevel = -1
    private var lastLevel = -1
    private var voltSum = 0.0; private var voltCount = 0
    private var powerSumW = 0.0; private var powerCount = 0
    private var tempSum = 0.0; private var tempCount = 0
    private var maxTemp = 0f
    private var capacityWh = 0f
    private var activeMs = 0L
    private val curveCap = 480

    @Volatile var samples: Int = 0; private set

    /** Blocks on [scope] until cancelled, sampling every [intervalMs]. */
    suspend fun run(scope: CoroutineScope, intervalMs: Long = 2000L, onUpdate: (() -> Unit)? = null) {
        if (startMs == 0L) startMs = System.currentTimeMillis()
        var lastMs = startMs
        while (scope.isActive) {
            val r = power.read()
            val now = System.currentTimeMillis()
            val dt = (now - lastMs).coerceIn(0, 10_000); lastMs = now

            if (startLevel < 0 && r.levelPct >= 0) startLevel = r.levelPct
            if (r.levelPct >= 0) lastLevel = r.levelPct
            if (r.capacityWh > 0) capacityWh = r.capacityWh
            if (r.voltageV > 0) { voltSum += r.voltageV; voltCount++ }
            if (!r.charging) { powerSumW += r.powerW; powerCount++; activeMs += dt }
            tempSum += r.tempC; tempCount++
            if (r.tempC > maxTemp) maxTemp = r.tempC

            curve.add(PowerPoint(now, r.levelPct, if (r.charging) -r.powerW else r.powerW, r.tempC))
            if (curve.size > curveCap) decimate()

            val pkg = fg.current() ?: "android.system"
            val acc = scenarios.getOrPut(pkg) { ScenarioAcc(fg.label(pkg), modeLabel()) }
            acc.durationMs += dt
            acc.energyWs += r.powerW * (dt / 1000.0)
            if (r.powerW > acc.maxPowerW) acc.maxPowerW = r.powerW
            acc.tempSum += r.tempC; acc.tempCount++
            if (r.tempC > acc.maxTempC) acc.maxTempC = r.tempC

            samples++
            onUpdate?.invoke()
            delay(intervalMs)
        }
    }

    /** Halve curve resolution while keeping the full time span. */
    private fun decimate() {
        var i = 1
        var w = 1
        while (i < curve.size) {
            curve[w] = curve[i]
            i += 2; w++
        }
        while (curve.size > w) curve.removeAt(curve.size - 1)
    }

    fun build(fpsSessions: List<FpsSession> = emptyList()): UsageSession {
        val end = System.currentTimeMillis()
        val avgPower = if (powerCount > 0) (powerSumW / powerCount).toFloat() else 0f
        val avgVolt = if (voltCount > 0) (voltSum / voltCount).toFloat() else 0f
        val avgTemp = if (tempCount > 0) (tempSum / tempCount).toFloat() else 0f
        val estMin = if (avgPower > 0.05f && capacityWh > 0f) {
            ((capacityWh / avgPower) * 60f).toInt()
        } else 0
        val scenList = scenarios.map { (pkg, a) ->
            AppScenario(
                pkg = pkg,
                label = a.label.ifEmpty { pkg },
                mode = a.mode,
                durationMs = a.durationMs,
                avgPowerW = if (a.durationMs > 0) (a.energyWs / (a.durationMs / 1000.0)).toFloat() else 0f,
                maxPowerW = a.maxPowerW,
                avgTempC = if (a.tempCount > 0) (a.tempSum / a.tempCount).toFloat() else 0f,
                maxTempC = a.maxTempC,
            )
        }.sortedByDescending { it.durationMs }
        return UsageSession(
            id = UUID.randomUUID().toString(),
            startedAtMs = startMs,
            endedAtMs = end,
            startLevelPct = startLevel,
            endLevelPct = lastLevel,
            avgPowerW = avgPower,
            usedMs = activeMs,
            estEnduranceMin = estMin,
            capacityWh = capacityWh,
            avgTempC = avgTemp,
            maxTempC = maxTemp,
            avgVoltageV = avgVolt,
            curve = curve.toList(),
            scenarios = scenList,
            fpsSessions = fpsSessions,
        )
    }

    /** Non-empty once there is anything worth persisting. */
    fun hasData(): Boolean = samples > 0 && scenarios.isNotEmpty()
}
