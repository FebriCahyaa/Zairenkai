// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Models for recorded usage sessions ("riwayat penggunaan"): a discharge run
 * with a battery/power curve, per-app usage scenarios (AVG/MAX power & temp,
 * duration) and optional per-game FPS sub-sessions. Serialized to JSON.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.serialization.Serializable

/** One sampled point on the session curve. */
@Serializable
data class PowerPoint(
    val tMs: Long = 0,
    val levelPct: Int = -1,
    val powerW: Float = 0f,   // discharge >0, charging <0
    val tempC: Float = 0f,
)

/** Aggregated usage of one foreground app during a session (Scene "使用场景"). */
@Serializable
data class AppScenario(
    val pkg: String = "",
    val label: String = "",
    val mode: String = "",          // scheduler/profile label active during use
    val durationMs: Long = 0,
    val avgPowerW: Float = 0f,
    val maxPowerW: Float = 0f,
    val avgTempC: Float = 0f,
    val maxTempC: Float = 0f,
)

/** A recorded frame-rate run for one app (Scene game FPS card). */
@Serializable
data class FpsSession(
    val pkg: String = "",
    val label: String = "",
    val startedAtMs: Long = 0,
    val durationMs: Long = 0,
    val maxFps: Float = 0f,
    val minFps: Float = 0f,
    val avgFps: Float = 0f,
    val varianceFps: Float = 0f,
    val low5Fps: Float = 0f,            // 5% low (frame-time 95th percentile)
    val smoothnessPct: Float = 0f,      // % of frames >= smoothnessTarget
    val smoothnessTarget: Int = 45,
    val maxTempC: Float = 0f,
    val avgPowerW: Float = 0f,
    val fps: List<Float> = emptyList(),         // downsampled series
    val temp: List<Float> = emptyList(),
    val frameTimeMs: List<Float> = emptyList(),
)

/** A whole recorded session (one discharge/usage run). */
@Serializable
data class UsageSession(
    val id: String = "",
    val startedAtMs: Long = 0,
    val endedAtMs: Long = 0,
    val startLevelPct: Int = -1,
    val endLevelPct: Int = -1,
    val avgPowerW: Float = 0f,
    val usedMs: Long = 0,               // active (screen-on / discharge) time
    val estEnduranceMin: Int = 0,       // theoretical endurance at avg draw
    val capacityWh: Float = 0f,
    val avgTempC: Float = 0f,
    val maxTempC: Float = 0f,
    val avgVoltageV: Float = 0f,
    val curve: List<PowerPoint> = emptyList(),
    val scenarios: List<AppScenario> = emptyList(),
    val fpsSessions: List<FpsSession> = emptyList(),
) {
    val levelDropPct: Int get() = (startLevelPct - endLevelPct).coerceAtLeast(0)
}
