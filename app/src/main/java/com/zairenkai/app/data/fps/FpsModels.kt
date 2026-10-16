// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Recorded FPS sessions of watched apps. A session is one continuous stretch
 * of a watched app in the foreground, measured from the frames Android
 * presented (zperfd frames, read-only). Persisted as JSON.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.fps

import kotlinx.serialization.Serializable

/** One second of a session. [tempC]/[powerW] are null when not reported. */
@Serializable
data class FpsSecond(
    val fps: Float = 0f,
    /** Longest frame interval in this second. */
    val worstMs: Float = 0f,
    val jank: Int = 0,
    val tempC: Float? = null,
    val powerW: Float? = null,
    /** Sampled hardware values; null means the device did not report that sensor. */
    val cpuLoadPct: Float? = null,
    val cpuFreqMhz: Float? = null,
    val gpuLoadPct: Float? = null,
    val gpuFreqMhz: Float? = null,
    val ramUsedPct: Float? = null,
    val batteryPct: Float? = null,
    /** Hottest plausible thermal-zone reading, distinct from battery temperature. */
    val thermalC: Float? = null,
)

/** Optional read-only hardware sample captured during an FPS session. */
data class FpsTelemetry(
    val cpuLoadPct: Float? = null,
    val cpuFreqMhz: Float? = null,
    val gpuLoadPct: Float? = null,
    val gpuFreqMhz: Float? = null,
    val ramUsedPct: Float? = null,
    val batteryPct: Float? = null,
    val thermalC: Float? = null,
)

@Serializable
data class FpsStats(
    val frames: Int = 0,
    /** Time covered by frames, without idle pauses (no new frame for over a second). */
    val activeMs: Long = 0,
    val avgFps: Float = 0f,
    val medianFps: Float = 0f,
    val minFps: Float = 0f,
    val maxFps: Float = 0f,
    /** Average FPS of the slowest 1 %, 0.1 % and 5 % of frames. */
    val low1Fps: Float = 0f,
    val low01Fps: Float = 0f,
    val low5Fps: Float = 0f,
    val avgFrameMs: Float = 0f,
    val p95FrameMs: Float = 0f,
    val p99FrameMs: Float = 0f,
    val maxFrameMs: Float = 0f,
    /** Share of active seconds within ±10 % of the median FPS. */
    val stabilityPct: Float = 0f,
    /** Standard deviation of per-second FPS. */
    val fpsStdDev: Float = 0f,
    /** Standard deviation of frame intervals (frame pacing), ms. */
    val pacingStdDevMs: Float = 0f,
    /** Frames that took over twice their recent cadence (and two display refreshes). */
    val jankFrames: Int = 0,
    /** Frames over 100 ms: visible hitches. */
    val stutterFrames: Int = 0,
    /** Frames the app's own cadence missed, estimated from interval length. */
    val droppedFrames: Int = 0,
)

@Serializable
data class FpsRecording(
    val id: String = "",
    val pkg: String = "",
    val label: String = "",
    val startedAtMs: Long = 0,
    val endedAtMs: Long = 0,
    val refreshHz: Float = 0f,
    val source: String = "",
    val stats: FpsStats = FpsStats(),
    val seconds: List<FpsSecond> = emptyList(),
)
