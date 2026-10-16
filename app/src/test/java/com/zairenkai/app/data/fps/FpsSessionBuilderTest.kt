// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Session segmentation of the zperfd frame stream.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.fps

import com.zairenkai.app.data.FrameBatch
import com.zairenkai.app.data.ZperfClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FpsSessionBuilderTest {
    private val label = { p: String -> "L:$p" }

    private fun batch(t: Long, fg: String?, watched: Boolean, frames: List<Long> = emptyList()) =
        FrameBatch(tMs = t, fg = fg, watched = watched, source = "sf", refreshHz = 120f, frames = frames.map { listOf(it, 8000, 0) })

    /** One second of 60 FPS frames starting at second [s]. */
    private fun second(s: Int) = List(60) { i -> 1_000_000_000L * (s + 10) + i * 16_666_667L }

    @Test
    fun capturedLinesDecode() {
        val lines = javaClass.classLoader!!.getResource("contracts/zperfd-frames-follow.jsonl")!!.readText().lines()
            .mapNotNull(ZperfClient::parseFrameBatch)
        assertEquals(3, lines.size)
        assertEquals(false, lines[0].watched)
        assertEquals("com.game.demo", lines[1].fg)
        assertEquals(5, lines[1].frames.size)
        assertEquals(listOf(1058333333L, 41000L, 1L), lines[1].frames[3])
        assertEquals(120f, lines[1].refreshHz)
    }

    @Test
    fun aWatchedStretchBecomesOneSessionAndShortTripsAwayDoNotSplitIt() {
        val b = FpsSessionBuilder(minFrames = 60, minDurationMs = 5_000, leaveGraceMs = 3_000)
        for (s in 0 until 6) assertNull(b.onBatch(batch(1_000L * s, "com.game", true, second(s)), 30f + s, 4f, label))
        // Notification shade for 2 s: same session.
        assertNull(b.onBatch(batch(6_000, "com.android.systemui", false), null, null, label))
        assertNull(b.onBatch(batch(7_000, "com.android.systemui", false), null, null, label))
        for (s in 8 until 12) assertNull(b.onBatch(batch(1_000L * s, "com.game", true, second(s)), 36f, 4f, label))
        // Leave for good.
        assertNull(b.onBatch(batch(12_000, "com.launcher", false), null, null, label))
        val rec = b.onBatch(batch(15_000, "com.launcher", false), null, null, label)
        assertNotNull(rec)
        rec!!
        assertEquals("com.game", rec.pkg)
        assertEquals("L:com.game", rec.label)
        assertEquals(600, rec.stats.frames)
        assertEquals(0L, rec.startedAtMs)
        assertEquals(11_000L, rec.endedAtMs)
        assertEquals(30f, rec.seconds.first().tempC)
        assertNull(b.current)
    }

    @Test
    fun switchingWatchedAppsEndsTheFirstSessionAndTooShortOnesAreDropped() {
        val b = FpsSessionBuilder(minFrames = 60, minDurationMs = 5_000)
        for (s in 0 until 6) b.onBatch(batch(1_000L * s, "com.a", true, second(s)), null, null, label)
        val first = b.onBatch(batch(6_000, "com.b", true, second(6)), null, null, label)
        assertEquals("com.a", first?.pkg)
        assertEquals("com.b", b.current)
        assertNull("one second of com.b is too short to keep", b.finish(label))
    }

    @Test
    fun telemetryIsAttachedToTheNearestPerSecondSample() {
        val b = FpsSessionBuilder(minFrames = 60, minDurationMs = 5_000)
        for (s in 0 until 7) {
            b.onBatch(
                batch(1_000L * s, "com.game", true, second(s)),
                31f + s,
                3.5f,
                label,
                FpsTelemetry(
                    cpuLoadPct = 40f + s,
                    cpuFreqMhz = 1200f + s * 10f,
                    gpuLoadPct = 30f + s,
                    gpuFreqMhz = 400f + s * 5f,
                    ramUsedPct = 60f + s,
                    batteryPct = 80f - s,
                    thermalC = 55f + s,
                ),
            )
        }
        val rec = b.finish(label)
        assertNotNull(rec)
        rec!!
        assertEquals(40f, rec.seconds.first().cpuLoadPct)
        assertEquals(1200f, rec.seconds.first().cpuFreqMhz)
        assertEquals(30f, rec.seconds.first().gpuLoadPct)
        assertEquals(400f, rec.seconds.first().gpuFreqMhz)
        assertEquals(60f, rec.seconds.first().ramUsedPct)
        assertEquals(80f, rec.seconds.first().batteryPct)
        assertEquals(55f, rec.seconds.first().thermalC)
    }

    @Test
    fun overlappingFramesAreNotCountedTwice() {
        val b = FpsSessionBuilder(minFrames = 1, minDurationMs = 0)
        b.onBatch(batch(0, "com.a", true, listOf(10, 20, 30)), null, null, label)
        b.onBatch(batch(1, "com.a", true, listOf(20, 30, 40)), null, null, label)
        assertEquals(4, b.frames)
    }
}
