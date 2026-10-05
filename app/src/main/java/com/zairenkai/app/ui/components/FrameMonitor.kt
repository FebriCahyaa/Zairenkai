// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Live FPS sampler using Choreographer frame callbacks. Measures the real
 * frame cadence of the running UI, including dropped frames (jank), and
 * exposes rolling series for the FPS graph.
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

data class FrameStats(
    val fps: List<Float> = emptyList(),
    val drops: List<Boolean> = emptyList(),
    val current: Float = 0f,
    val jankCount: Int = 0,
    val refreshHz: Float = 60f,
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

        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastNs != 0L) {
                    val dtNs = (frameTimeNanos - lastNs).coerceAtLeast(1)
                    val fps = (1_000_000_000.0 / dtNs).toFloat().coerceAtMost(refreshHz * 1.2f)
                    // A drop = this frame took noticeably longer than one budget.
                    val dropped = dtNs > frameBudgetNs * 1.5
                    if (dropped) jank++
                    fpsList.add(fps); dropList.add(dropped)
                    while (fpsList.size > cap) { fpsList.removeAt(0); dropList.removeAt(0) }
                    stats = FrameStats(
                        fps = ArrayList(fpsList),
                        drops = ArrayList(dropList),
                        current = fps,
                        jankCount = jank,
                        refreshHz = refreshHz,
                    )
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
