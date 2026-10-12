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
    private var acc: UsageAccumulator? = null

    val samples: Int get() = acc?.samples ?: 0

    /** Blocks on [scope] until cancelled, sampling every [intervalMs]. */
    suspend fun run(scope: CoroutineScope, intervalMs: Long = 2000L, onUpdate: (() -> Unit)? = null) {
        val a = acc ?: UsageAccumulator(System.currentTimeMillis()).also { acc = it }
        while (scope.isActive) {
            val r = power.read()
            val pkg = fg.current() ?: "android.system"
            a.add(r, System.currentTimeMillis(), pkg, { fg.label(pkg) }, modeLabel)
            onUpdate?.invoke()
            delay(intervalMs)
        }
    }

    fun build(fpsSessions: List<FpsSession> = emptyList()): UsageSession =
        (acc ?: UsageAccumulator(System.currentTimeMillis())).build(System.currentTimeMillis(), fpsSessions)

    /** Non-empty once there is anything worth persisting. */
    fun hasData(): Boolean = acc?.hasData() == true
}

/**
 * The pure part of a recording: folds power samples into the session curve
 * and per-app scenarios. Values a device did not report (no battery
 * temperature, no voltage, unknown level) are left out of averages and
 * maxima instead of counting as zero.
 */
class UsageAccumulator(private val startMs: Long) {
    private class ScenarioAcc(var label: String, var mode: String) {
        var durationMs: Long = 0
        var energyWs: Double = 0.0
        var maxPowerW: Float = 0f
        var tempSum: Double = 0.0
        var tempCount: Int = 0
        var maxTempC: Float? = null
    }

    private val scenarios = LinkedHashMap<String, ScenarioAcc>()
    private val curve = ArrayList<PowerPoint>()
    private var lastMs = startMs
    private var startLevel = -1
    private var lastLevel = -1
    private var voltSum = 0.0; private var voltCount = 0
    private var powerSumW = 0.0; private var powerCount = 0
    private var tempSum = 0.0; private var tempCount = 0
    private var maxTemp: Float? = null
    private var lastTemp: Float? = null
    private var capacityWh = 0f
    private var activeMs = 0L
    private val curveCap = 480

    var samples: Int = 0
        private set

    fun add(r: PowerReading, nowMs: Long, pkg: String, label: () -> String, mode: () -> String) {
        val dt = (nowMs - lastMs).coerceIn(0, 10_000); lastMs = nowMs

        if (startLevel < 0 && r.levelPct >= 0) startLevel = r.levelPct
        if (r.levelPct >= 0) lastLevel = r.levelPct
        if (r.capacityWh > 0) capacityWh = r.capacityWh
        if (r.voltageV > 0) { voltSum += r.voltageV; voltCount++ }
        if (!r.charging) { powerSumW += r.powerW; powerCount++; activeMs += dt }
        val temp = r.tempC.takeIf { r.tempKnown }
        if (temp != null) {
            tempSum += temp; tempCount++
            maxTemp = maxOf(maxTemp ?: temp, temp)
            lastTemp = temp
        }

        // The curve needs a value per point: hold the last reported temperature.
        curve.add(PowerPoint(nowMs, r.levelPct, if (r.charging) -r.powerW else r.powerW, temp ?: lastTemp ?: 0f))
        if (curve.size > curveCap) decimate()

        val acc = scenarios.getOrPut(pkg) { ScenarioAcc(label(), mode()) }
        acc.durationMs += dt
        acc.energyWs += r.powerW * (dt / 1000.0)
        if (r.powerW > acc.maxPowerW) acc.maxPowerW = r.powerW
        if (temp != null) {
            acc.tempSum += temp; acc.tempCount++
            acc.maxTempC = maxOf(acc.maxTempC ?: temp, temp)
        }
        samples++
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

    fun build(endMs: Long, fpsSessions: List<FpsSession> = emptyList()): UsageSession {
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
                maxTempC = a.maxTempC ?: 0f,
            )
        }.sortedByDescending { it.durationMs }
        return UsageSession(
            id = UUID.randomUUID().toString(),
            startedAtMs = startMs,
            endedAtMs = endMs,
            startLevelPct = startLevel,
            endLevelPct = lastLevel,
            avgPowerW = avgPower,
            usedMs = activeMs,
            estEnduranceMin = estMin,
            capacityWh = capacityWh,
            avgTempC = avgTemp,
            maxTempC = maxTemp ?: 0f,
            avgVoltageV = avgVolt,
            curve = curve.toList(),
            scenarios = scenList,
            fpsSessions = fpsSessions,
        )
    }

    fun hasData(): Boolean = samples > 0 && scenarios.isNotEmpty()
}
