// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Live FPS sampler using Choreographer frame callbacks. Measures the real
 * frame cadence of the running UI, including dropped frames (jank), exposes
 * rolling series for the FPS graph, and keeps whole-session summary statistics
 * (max/min/avg/variance, 5% low, smoothness) for the stats card.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.components

import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FrameStats(
    val fps: List<Float> = emptyList(),
    val drops: List<Boolean> = emptyList(),
    val current: Float = 0f,
    val jankCount: Int = 0,
    val refreshHz: Float = 60f,
    // whole-session summary
    val maxFps: Float = 0f,
    val minFps: Float = 0f,
    val avgFps: Float = 0f,
    val varianceFps: Float = 0f,
    val low5Fps: Float = 0f,
    val smoothnessPct: Float = 0f,
    val smoothnessTarget: Int = 45,
    val totalFrames: Int = 0,
)

@Composable
fun rememberFrameStats(refreshHz: Float, cap: Int = 60): FrameStats {
    var stats by remember { mutableStateOf(FrameStats(refreshHz = refreshHz)) }

    DisposableEffect(refreshHz) {
        val ch = Choreographer.getInstance()
        var lastNs = 0L
        val fpsList = ArrayList<Float>()
        val dropList = ArrayList<Boolean>()
        var jank = 0
        val frameBudgetNs = (1_000_000_000.0 / refreshHz)
        val target = min(45, refreshHz.roundToInt())

        // whole-session accumulators
        var count = 0L
        var sum = 0.0
        var sumSq = 0.0
        var minFps = Float.MAX_VALUE
        var maxFps = 0f
        var smoothCount = 0L
        val histSize = (ceil(refreshHz * 1.25).toInt() + 2).coerceIn(16, 512)
        val hist = IntArray(histSize)

        fun summarize(): FrameStats {
            val avg = if (count > 0) (sum / count).toFloat() else 0f
            val variance = if (count > 0) ((sumSq / count) - (avg * avg)).toFloat().coerceAtLeast(0f) else 0f
            // 5% low = fps at the 5th percentile (worst 5% of frames fall at/below it)
            var low5 = 0f
            if (count > 0) {
                val threshold = max(1L, (count * 5) / 100)
                var cum = 0L
                for (b in hist.indices) {
                    cum += hist[b]
                    if (cum >= threshold) { low5 = b.toFloat(); break }
                }
            }
            return FrameStats(
                fps = ArrayList(fpsList),
                drops = ArrayList(dropList),
                current = fpsList.lastOrNull() ?: 0f,
                jankCount = jank,
                refreshHz = refreshHz,
                maxFps = maxFps,
                minFps = if (minFps == Float.MAX_VALUE) 0f else minFps,
                avgFps = avg,
                varianceFps = variance,
                low5Fps = low5,
                smoothnessPct = if (count > 0) smoothCount * 100f / count else 0f,
                smoothnessTarget = target,
                totalFrames = count.toInt(),
            )
        }

        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastNs != 0L) {
                    val dtNs = (frameTimeNanos - lastNs).coerceAtLeast(1)
                    val fps = (1_000_000_000.0 / dtNs).toFloat().coerceAtMost(refreshHz * 1.2f)
                    val dropped = dtNs > frameBudgetNs * 1.5
                    if (dropped) jank++
                    fpsList.add(fps); dropList.add(dropped)
                    while (fpsList.size > cap) { fpsList.removeAt(0); dropList.removeAt(0) }

                    count++; sum += fps; sumSq += fps.toDouble() * fps
                    if (fps < minFps) minFps = fps
                    if (fps > maxFps) maxFps = fps
                    if (fps >= target) smoothCount++
                    val bucket = fps.roundToInt().coerceIn(0, hist.size - 1)
                    hist[bucket]++

                    stats = summarize()
                }
                lastNs = frameTimeNanos
                ch.postFrameCallback(this)
            }
        }
        ch.postFrameCallback(cb)
        onDispose { ch.removeFrameCallback(cb) }
    }
    return stats
}
