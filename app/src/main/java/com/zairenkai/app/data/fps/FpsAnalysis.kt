// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * FPS statistics, frame-drop detection and explanatory insights for a
 * recorded session. Pure functions: everything is derived from the frame
 * present times and the per-second temperature/power samples, so each
 * session gets messages that describe what actually happened in it.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.fps

import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An interval this long means the app presented nothing (static screen), not a slow frame. */
const val IDLE_GAP_MS = 1000f

/** Frames slower than this are visible hitches. */
const val STUTTER_MS = 100f

object FpsAnalysis {

    /**
     * Statistics and the per-second series of one session. [presentNs] are
     * frame present times in increasing order; [refreshHz] is the panel
     * rate (0 when unknown).
     */
    fun compute(presentNs: LongArray, refreshHz: Float): Pair<FpsStats, List<FpsSecond>> {
        if (presentNs.size < 2) return FpsStats(frames = presentNs.size) to emptyList()
        val budgetMs = if (refreshHz > 0f) 1000f / refreshHz else 1000f / 60f
        val intervals = ArrayList<Float>(presentNs.size)
        for (i in 1 until presentNs.size) {
            val d = (presentNs[i] - presentNs[i - 1]) / 1_000_000f
            if (d > 0f && d <= IDLE_GAP_MS) intervals += d
        }
        if (intervals.isEmpty()) return FpsStats(frames = presentNs.size) to emptyList()

        val sorted = intervals.sorted()
        val medianInterval = sorted[sorted.size / 2]
        val activeMs = intervals.sum()
        val avgFrameMs = activeMs / intervals.size

        var jank = 0
        var stutter = 0
        var dropped = 0
        val window = ArrayDeque<Float>()
        for (d in intervals) {
            if (window.size == 3) {
                val cadence = window.average().toFloat()
                if (d > 2f * cadence && d > 2f * budgetMs) jank++
            }
            if (d > STUTTER_MS) stutter++
            dropped += max(0, (d / medianInterval).roundToInt() - 1)
            window.addLast(d)
            if (window.size > 3) window.removeFirst()
        }

        val seconds = perSecond(presentNs, budgetMs)
        // Partial first/last seconds understate FPS; leave them out of the per-second stats.
        val core = if (seconds.size >= 3) seconds.subList(1, seconds.size - 1) else seconds
        val fpsValues = core.map { it.fps }.sorted()
        val medianFps = fpsValues.getOrElse(fpsValues.size / 2) { 1000f / avgFrameMs }
        val stable = core.count { abs(it.fps - medianFps) <= medianFps * 0.10f }
        val meanFps = fpsValues.average().toFloat()
        val fpsStd = sqrt(fpsValues.map { (it - meanFps) * (it - meanFps) }.average()).toFloat()
        val pacingStd = sqrt(intervals.map { (it - avgFrameMs) * (it - avgFrameMs) }.average()).toFloat()

        fun low(fraction: Float): Float {
            val k = max(1, ceil(intervals.size * fraction).toInt())
            val worst = sorted.subList(sorted.size - k, sorted.size)
            return 1000f / worst.average().toFloat()
        }
        fun pct(p: Float): Float = sorted[((sorted.size - 1) * p).roundToInt()]

        val stats = FpsStats(
            frames = presentNs.size,
            activeMs = activeMs.toLong(),
            avgFps = 1000f / avgFrameMs,
            medianFps = medianFps,
            minFps = fpsValues.firstOrNull() ?: 0f,
            maxFps = fpsValues.lastOrNull() ?: 0f,
            low1Fps = low(0.01f),
            low01Fps = low(0.001f),
            low5Fps = low(0.05f),
            avgFrameMs = avgFrameMs,
            p95FrameMs = pct(0.95f),
            p99FrameMs = pct(0.99f),
            maxFrameMs = sorted.last(),
            stabilityPct = if (core.isEmpty()) 0f else stable * 100f / core.size,
            fpsStdDev = fpsStd,
            pacingStdDevMs = pacingStd,
            jankFrames = jank,
            stutterFrames = stutter,
            droppedFrames = dropped,
        )
        return stats to seconds
    }

    /** Frames per wall second of the session; seconds inside idle pauses are left out. */
    private fun perSecond(presentNs: LongArray, budgetMs: Float): List<FpsSecond> {
        val start = presentNs.first()
        val buckets = LinkedHashMap<Long, MutableList<Float>>()
        val counts = LinkedHashMap<Long, Int>()
        for (i in presentNs.indices) {
            val b = (presentNs[i] - start) / 1_000_000_000L
            counts[b] = (counts[b] ?: 0) + 1
            if (i > 0) {
                val d = (presentNs[i] - presentNs[i - 1]) / 1_000_000f
                if (d <= IDLE_GAP_MS) buckets.getOrPut(b) { mutableListOf() } += d
            }
        }
        return counts.keys.sorted().map { b ->
            val ds = buckets[b].orEmpty()
            FpsSecond(
                fps = (counts[b] ?: 0).toFloat(),
                worstMs = ds.maxOrNull() ?: 0f,
                jank = ds.count { it > 2f * budgetMs && it > 2f * (ds.average().toFloat()) },
            )
        }
    }

    enum class DropCause { HITCH, THERMAL, SUSTAINED, BRIEF }

    data class DropEvent(
        val startSec: Int,
        val durationSec: Int,
        val baselineFps: Float,
        val minFps: Float,
        val worstMs: Float,
        val tempRiseC: Float?,
        val cause: DropCause,
    )

    /**
     * Stretches where FPS fell below 80 % of the session median, or a
     * hitch over [STUTTER_MS] occurred, merged when less than two seconds
     * apart.
     */
    fun drops(rec: FpsRecording): List<DropEvent> {
        val s = rec.seconds
        if (s.size < 3) return emptyList()
        val median = rec.stats.medianFps.takeIf { it > 0f } ?: return emptyList()
        val firstTemp = s.firstNotNullOfOrNull { it.tempC }
        val flagged = s.indices.filter { i ->
            val partial = i == 0 || i == s.lastIndex
            (!partial && s[i].fps < median * 0.8f) || s[i].worstMs > STUTTER_MS
        }
        val groups = mutableListOf<MutableList<Int>>()
        for (i in flagged) {
            val last = groups.lastOrNull()
            if (last != null && i - last.last() <= 2) last += i else groups += mutableListOf(i)
        }
        return groups.map { g ->
            val secs = g.first()..g.last()
            val minFps = secs.minOf { s[it].fps }
            val worst = secs.maxOf { s[it].worstMs }
            val temp = secs.mapNotNull { s[it].tempC }.maxOrNull()
            val rise = if (temp != null && firstTemp != null) temp - firstTemp else null
            val duration = g.last() - g.first() + 1
            val cause = when {
                duration <= 2 && worst > STUTTER_MS -> DropCause.HITCH
                rise != null && rise >= 4f -> DropCause.THERMAL
                duration >= 5 -> DropCause.SUSTAINED
                else -> DropCause.BRIEF
            }
            DropEvent(g.first(), duration, median, minFps, worst, rise, cause)
        }
    }

    fun dropMessage(e: DropEvent): UiText {
        val at = clock(e.startSec)
        val from = e.baselineFps.roundToInt()
        val to = e.minFps.roundToInt()
        val worst = e.worstMs.roundToInt()
        return when (e.cause) {
            DropCause.HITCH -> uiText(R.string.fps_drop_hitch, at, worst)
            DropCause.THERMAL -> uiText(R.string.fps_drop_thermal, at, from, to, e.durationSec, "%.1f".format(e.tempRiseC ?: 0f))
            DropCause.SUSTAINED -> uiText(R.string.fps_drop_sustained, at, from, to, e.durationSec, worst)
            DropCause.BRIEF -> uiText(R.string.fps_drop_brief, at, from, to, e.durationSec, worst)
        }
    }

    enum class Severity { GOOD, INFO, WARN, BAD }

    data class Insight(val severity: Severity, val text: UiText)

    private val COMMON_CAPS = listOf(24, 30, 40, 45, 60, 72, 90, 120, 144, 165)

    /** Session-level conclusions, most important first. */
    fun insights(rec: FpsRecording, drops: List<DropEvent> = drops(rec)): List<Insight> {
        val st = rec.stats
        if (st.frames < 2 || st.avgFps <= 0f) return listOf(Insight(Severity.INFO, uiText(R.string.fps_insight_no_data)))
        val out = mutableListOf<Insight>()
        val median = st.medianFps.roundToInt()
        val stability = st.stabilityPct.roundToInt()

        out += when {
            st.stabilityPct >= 90f -> Insight(Severity.GOOD, uiText(R.string.fps_insight_stable, stability, median))
            st.stabilityPct >= 70f -> Insight(Severity.INFO, uiText(R.string.fps_insight_mostly_stable, stability, median))
            else -> Insight(Severity.BAD, uiText(R.string.fps_insight_unstable, stability, median, st.fpsStdDev.roundToInt()))
        }

        val cap = COMMON_CAPS.firstOrNull { abs(st.medianFps - it) <= it * 0.03f }
        if (cap != null && rec.refreshHz > cap * 1.15f && st.stabilityPct >= 70f) {
            out += Insight(Severity.INFO, uiText(R.string.fps_insight_capped, cap, rec.refreshHz.roundToInt()))
        }

        if (st.low1Fps < st.avgFps * 0.6f) {
            out += Insight(
                Severity.WARN,
                uiText(R.string.fps_insight_low_lows, st.low1Fps.roundToInt(), st.avgFps.roundToInt()),
            )
        }

        if (st.avgFrameMs > 0f && st.pacingStdDevMs > st.avgFrameMs * 0.25f) {
            out += Insight(
                Severity.WARN,
                uiText(R.string.fps_insight_pacing, "%.1f".format(st.pacingStdDevMs), "%.1f".format(st.avgFrameMs)),
            )
        }

        val s = rec.seconds
        if (s.size >= 30) {
            val third = s.size / 3
            val early = s.subList(0, third).map { it.fps }.average()
            val late = s.subList(s.size - third, s.size).map { it.fps }.average()
            val t0 = s.subList(0, third).mapNotNull { it.tempC }.average().takeIf { !it.isNaN() }
            val t1 = s.subList(s.size - third, s.size).mapNotNull { it.tempC }.average().takeIf { !it.isNaN() }
            if (late < early * 0.9) {
                val dropPct = ((1 - late / early) * 100).roundToInt()
                val rise = if (t0 != null && t1 != null) t1 - t0 else null
                out += if (rise != null && rise >= 3.0) {
                    Insight(Severity.BAD, uiText(R.string.fps_insight_thermal_decline, dropPct, "%.1f".format(rise)))
                } else {
                    Insight(Severity.WARN, uiText(R.string.fps_insight_decline, dropPct))
                }
            }
        }

        if (st.stutterFrames > 0) {
            out += Insight(
                if (st.stutterFrames >= 5) Severity.BAD else Severity.WARN,
                UiText.Plural(R.plurals.fps_insight_stutters, st.stutterFrames, listOf(st.stutterFrames, st.maxFrameMs.roundToInt())),
            )
        }

        val minutes = st.activeMs / 60_000f
        if (minutes >= 1f && st.jankFrames / minutes > 10f) {
            out += Insight(Severity.WARN, uiText(R.string.fps_insight_jank_rate, (st.jankFrames / minutes).roundToInt()))
        }

        val power = s.mapNotNull { it.powerW }.average().takeIf { !it.isNaN() && it > 0.0 }
        val temps = s.mapNotNull { it.tempC }
        if (power != null || temps.isNotEmpty()) {
            out += Insight(
                Severity.INFO,
                uiText(
                    R.string.fps_insight_power,
                    power?.let { "%.1f".format(it) } ?: "—",
                    temps.maxOrNull()?.let { "%.1f".format(it) } ?: "—",
                ),
            )
        }

        if (out.none { it.severity == Severity.WARN || it.severity == Severity.BAD } && drops.isEmpty()) {
            out += Insight(Severity.GOOD, uiText(R.string.fps_insight_all_good))
        }
        return out.sortedBy { when (it.severity) { Severity.BAD -> 0; Severity.WARN -> 1; Severity.GOOD -> 2; Severity.INFO -> 3 } }
    }

    fun clock(second: Int): String = "%d:%02d".format(second / 60, second % 60)
}
