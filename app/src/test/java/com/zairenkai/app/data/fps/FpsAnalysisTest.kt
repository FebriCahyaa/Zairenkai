// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * FPS statistics, drop detection and insights from synthetic frame timelines.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.fps

import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsAnalysisTest {
    /** Present times for [seconds] at [fps], with optional extra delays (ms) after given frame indices. */
    private fun timeline(fps: Int, seconds: Int, delays: Map<Int, Long> = emptyMap()): LongArray {
        val step = 1_000_000_000L / fps
        var t = 5_000_000_000L
        return LongArray(fps * seconds) { i ->
            t += step + (delays[i] ?: 0L) * 1_000_000L
            t
        }
    }

    private fun ids(list: List<FpsAnalysis.Insight>) = list.map {
        when (val t = it.text) {
            is UiText.Res -> t.id
            is UiText.Plural -> t.id
            else -> error("unexpected $t")
        }
    }

    @Test
    fun steadyCappedSessionIsStableAndExplainsTheCap() {
        val (stats, seconds) = FpsAnalysis.compute(timeline(60, 20), refreshHz = 120f)
        assertEquals(60f, stats.avgFps, 0.5f)
        assertEquals(60f, stats.medianFps, 0.5f)
        assertEquals(100f, stats.stabilityPct, 0.1f)
        assertEquals(0, stats.jankFrames)
        assertEquals(0, stats.stutterFrames)
        assertEquals(0, stats.droppedFrames)
        assertEquals(20, seconds.size)
        val rec = FpsRecording(refreshHz = 120f, stats = stats, seconds = seconds)
        assertTrue(FpsAnalysis.drops(rec).isEmpty())
        val insights = ids(FpsAnalysis.insights(rec))
        assertTrue(R.string.fps_insight_stable in insights)
        assertTrue(R.string.fps_insight_capped in insights)
        assertTrue(R.string.fps_insight_all_good in insights)
    }

    @Test
    fun aLongFrameIsAHitchWithItsOwnExplanation() {
        val (stats, seconds) = FpsAnalysis.compute(timeline(120, 15, mapOf(900 to 250L)), refreshHz = 120f)
        assertEquals(1, stats.stutterFrames)
        assertEquals(1, stats.jankFrames)
        assertTrue(stats.maxFrameMs > 250f)
        assertTrue("dropped frames estimated from the gap", stats.droppedFrames >= 29)
        assertTrue(stats.low01Fps < 10f)
        val rec = FpsRecording(refreshHz = 120f, stats = stats, seconds = seconds)
        val drops = FpsAnalysis.drops(rec)
        assertEquals(1, drops.size)
        assertEquals(FpsAnalysis.DropCause.HITCH, drops[0].cause)
        assertEquals(7, drops[0].startSec)
        val msg = FpsAnalysis.dropMessage(drops[0]) as UiText.Res
        assertEquals(R.string.fps_drop_hitch, msg.id)
        assertEquals("0:07", msg.args[0])
        assertTrue(R.plurals.fps_insight_stutters in ids(FpsAnalysis.insights(rec, drops)))
    }

    @Test
    fun idlePausesAreNotCountedAsSlowFrames() {
        // 5 s at 60 FPS, nothing for 3 s (static screen), 5 s more.
        val (stats, _) = FpsAnalysis.compute(timeline(60, 10, mapOf(299 to 3000L)), refreshHz = 60f)
        assertEquals(60f, stats.avgFps, 0.5f)
        assertEquals(0, stats.stutterFrames)
        assertTrue(stats.activeMs in 9_000..10_500)
    }

    @Test
    fun fallingFpsWithRisingTemperatureIsThermalThrottling() {
        val seconds = List(60) { i ->
            val late = i >= 40
            FpsSecond(fps = if (late) 80f else 118f, worstMs = if (late) 16f else 9f, tempC = 34f + i * 0.15f)
        }
        val stats = FpsStats(frames = 6000, activeMs = 60_000, avgFps = 105f, medianFps = 118f, low1Fps = 75f,
            avgFrameMs = 9.5f, pacingStdDevMs = 1f, stabilityPct = 66f, maxFrameMs = 18f)
        val rec = FpsRecording(refreshHz = 120f, stats = stats, seconds = seconds)
        val drops = FpsAnalysis.drops(rec)
        assertEquals(1, drops.size)
        assertEquals(FpsAnalysis.DropCause.THERMAL, drops[0].cause)
        assertEquals(40, drops[0].startSec)
        val insights = FpsAnalysis.insights(rec, drops)
        assertEquals(FpsAnalysis.Severity.BAD, insights.first().severity)
        val all = ids(insights)
        assertTrue(R.string.fps_insight_unstable in all)
        assertTrue(R.string.fps_insight_thermal_decline in all)
        assertTrue(R.string.fps_insight_all_good !in all)
    }

    @Test
    fun tooFewFramesSaySo() {
        val rec = FpsRecording(stats = FpsAnalysis.compute(LongArray(1), 60f).first)
        assertEquals(listOf(R.string.fps_insight_no_data), ids(FpsAnalysis.insights(rec)))
    }
}
