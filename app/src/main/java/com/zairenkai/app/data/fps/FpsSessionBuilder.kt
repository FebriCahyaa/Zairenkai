// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Turns the `zperfd frames --follow` stream into sessions: one per
 * continuous stretch of a watched app in the foreground. A short trip away
 * (notification shade, a dialog from another app) does not split a session;
 * leaving for longer than [leaveGraceMs] ends it. Pure and unit tested.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.fps

import com.zairenkai.app.data.FrameBatch
import java.util.UUID

class FpsSessionBuilder(
    private val minFrames: Int = 120,
    private val minDurationMs: Long = 10_000,
    private val leaveGraceMs: Long = 3_000,
) {
    private var pkg: String? = null
    private var presents = LongArray(4096)
    private var count = 0
    private var startWallMs = 0L
    private var lastWallMs = 0L
    private var refreshHz = 0f
    private var source = ""
    private data class TimedSample(
        val timeMs: Long,
        val tempC: Float?,
        val powerW: Float?,
        val telemetry: FpsTelemetry?,
    )
    private val samples = ArrayList<TimedSample>()
    private var awaySince: Long? = null

    /** The watched app being recorded, if any. */
    val current: String? get() = pkg

    /** Frames recorded in the current session. */
    val frames: Int get() = count

    /**
     * Feed one stream line with the battery temperature/power measured when
     * it arrived. Returns a session that just ended, if any.
     */
    fun onBatch(
        b: FrameBatch,
        tempC: Float?,
        powerW: Float?,
        label: (String) -> String,
        telemetry: FpsTelemetry? = null,
    ): FpsRecording? {
        val fg = b.fg
        if (b.watched && fg != null) {
            val ended = if (pkg != null && pkg != fg) finish(label) else null
            if (pkg == null) {
                pkg = fg
                startWallMs = b.tMs
            }
            awaySince = null
            lastWallMs = b.tMs
            b.refreshHz?.takeIf { it > 0f }?.let { refreshHz = it }
            b.source?.let { source = it }
            for (row in b.frames) {
                val t = row.firstOrNull() ?: continue
                if (count > 0 && t <= presents[count - 1]) continue
                if (count == presents.size) presents = presents.copyOf(presents.size * 2)
                presents[count++] = t
            }
            samples += TimedSample(b.tMs, tempC, powerW, telemetry)
            return ended
        }
        if (pkg == null) return null
        val since = awaySince ?: b.tMs.also { awaySince = it }
        return if (b.tMs - since >= leaveGraceMs) finish(label) else null
    }

    /** End the current session (stream stopped); null when it was too short to keep. */
    fun finish(label: (String) -> String): FpsRecording? {
        val p = pkg ?: return null
        val frames = presents.copyOf(count)
        val start = startWallMs
        val end = lastWallMs
        val hz = refreshHz
        val src = source
        val measurements = samples.toList()
        reset()
        if (frames.size < minFrames) return null
        val spanMs = (frames.last() - frames.first()) / 1_000_000
        if (spanMs < minDurationMs) return null
        val (stats, seconds) = FpsAnalysis.compute(frames, hz)
        // Second i of the frame timeline ~ wall time start + i s; attach the nearest sample.
        val withEnv = seconds.mapIndexed { i, s ->
            val wall = start + i * 1000L
            val near = measurements.minByOrNull { kotlin.math.abs(it.timeMs - wall) }
            s.copy(
                tempC = near?.tempC,
                powerW = near?.powerW,
                cpuLoadPct = near?.telemetry?.cpuLoadPct,
                cpuFreqMhz = near?.telemetry?.cpuFreqMhz,
                gpuLoadPct = near?.telemetry?.gpuLoadPct,
                gpuFreqMhz = near?.telemetry?.gpuFreqMhz,
                ramUsedPct = near?.telemetry?.ramUsedPct,
                batteryPct = near?.telemetry?.batteryPct,
                thermalC = near?.telemetry?.thermalC,
            )
        }
        return FpsRecording(
            id = UUID.randomUUID().toString(),
            pkg = p,
            label = label(p),
            startedAtMs = start,
            endedAtMs = end,
            refreshHz = hz,
            source = src,
            stats = stats,
            seconds = withEnv,
        )
    }

    private fun reset() {
        pkg = null
        presents = LongArray(4096)
        count = 0
        samples.clear()
        awaySince = null
        refreshHz = 0f
        source = ""
    }
}
