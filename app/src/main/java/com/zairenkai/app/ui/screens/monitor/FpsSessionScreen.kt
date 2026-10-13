// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * One recorded FPS session: headline numbers, the per-second timeline with
 * the drops shaded, frame-time and distribution statistics, and the
 * session-specific insights and drop explanations from FpsAnalysis.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens.monitor

import com.zairenkai.app.ui.icons.ZkIcons
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.core.text.asString
import com.zairenkai.app.data.fps.FpsAnalysis
import com.zairenkai.app.data.fps.FpsRecording
import com.zairenkai.app.ui.FpsViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.AppIcon
import com.zairenkai.app.ui.components.ChartSeries
import com.zairenkai.app.ui.components.Histogram
import com.zairenkai.app.ui.components.RingGauge
import com.zairenkai.app.ui.components.TimeSeriesChart
import com.zairenkai.app.ui.components.formatClock
import com.zairenkai.app.ui.components.formatDuration
import com.zairenkai.app.ui.theme.*
import kotlin.math.ceil
import kotlin.math.max

@Composable
fun FpsSessionScreen(factory: VmFactory, sessionId: String, onBack: () -> Unit) {
    val vm: FpsViewModel = viewModel(factory = factory)
    val sessions by vm.sessions.collectAsState()
    val rec = sessions?.firstOrNull { it.id == sessionId }
    var confirm by remember { mutableStateOf(false) }

    MonitorDetailScaffold(
        rec?.label?.ifBlank { rec.pkg } ?: stringResource(R.string.fps_session_title),
        onBack,
        actions = {
            if (rec != null) {
                IconButton(onClick = { confirm = true }) { Icon(ZkIcons.Delete, stringResource(R.string.fps_delete)) }
            }
        },
    ) {
        if (rec == null) {
            item {
                Text(
                    stringResource(if (sessions == null) R.string.monitor_waiting_first_sample else R.string.fps_session_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@MonitorDetailScaffold
        }
        val st = rec.stats
        val drops = FpsAnalysis.drops(rec)
        val insights = FpsAnalysis.insights(rec, drops)

        item {
            MonitorCard(shape = HeroShape, color = MaterialTheme.colorScheme.primaryContainer, padding = 20.dp) {
                val on = MaterialTheme.colorScheme.onPrimaryContainer
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(rec.pkg, size = 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${formatClock(rec.startedAtMs)} · ${formatDuration(st.activeMs)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = on,
                        )
                        Text(
                            stringResource(R.string.fps_session_meta, "%.0f".format(rec.refreshHz), sourceLabel(rec.source)),
                            style = MaterialTheme.typography.labelSmall,
                            color = on.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.fps_stat_avg), style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.8f))
                        BigValue("%.1f".format(st.avgFps), "FPS", on, MaterialTheme.typography.displayMedium)
                    }
                    RingGauge(st.stabilityPct / 100f, stabilityColor(st.stabilityPct), Modifier.size(96.dp), stroke = 10.dp, track = on.copy(alpha = 0.12f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%.0f%%".format(st.stabilityPct), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = on)
                            Text(stringResource(R.string.fps_stat_stability), style = MaterialTheme.typography.labelSmall, color = on.copy(alpha = 0.75f))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroStat(stringResource(R.string.fps_stat_median), "%.0f".format(st.medianFps), on, Modifier.weight(1f))
                    HeroStat(stringResource(R.string.fps_stat_low1), "%.0f".format(st.low1Fps), on, Modifier.weight(1f))
                    HeroStat(stringResource(R.string.fps_stat_low01), "%.0f".format(st.low01Fps), on, Modifier.weight(1f))
                }
            }
        }

        item {
            MonitorCard {
                CardHeader(ZkIcons.Pulse, ZkFps, stringResource(R.string.fps_timeline_title), stringResource(R.string.fps_timeline_sub))
                Spacer(Modifier.height(12.dp))
                val fps = rec.seconds.map { it.fps }
                val top = max(max(rec.refreshHz, st.maxFps), 30f) * 1.08f
                TimeSeriesChart(
                    listOf(ChartSeries(fps, ZkFps)),
                    Modifier.fillMaxWidth().height(180.dp),
                    maxV = top,
                    bands = drops.map { it.startSec until it.startSec + max(1, it.durationSec) },
                    bandColor = ZkFpsDrop.copy(alpha = 0.14f),
                    refLine = rec.refreshHz.takeIf { it > 0f },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot(ZkFps, "FPS")
                    LegendDot(ZkFpsDrop, stringResource(R.string.fps_legend_drop))
                    LegendDot(MaterialTheme.colorScheme.outline, stringResource(R.string.fps_legend_refresh))
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("0:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(FpsAnalysis.clock(rec.seconds.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val temps = rec.seconds.map { it.tempC }
                if (temps.count { it != null } >= 2) {
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.fps_temp_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    val known = temps.filterNotNull()
                    TimeSeriesChart(
                        listOf(ChartSeries(temps, ZkTemp)),
                        Modifier.fillMaxWidth().height(110.dp),
                        minV = (known.min() - 2f).coerceAtLeast(0f),
                        maxV = known.max() + 2f,
                        yTicks = 2,
                        formatY = { "%.0f°".format(it) },
                    )
                }
            }
        }

        item {
            MonitorCard {
                CardHeader(ZkIcons.Insights, ZkIndigo, stringResource(R.string.fps_insights_title), stringResource(R.string.fps_insights_sub))
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    insights.forEach { InsightRow(it) }
                }
            }
        }

        item {
            MonitorCard {
                CardHeader(ZkIcons.TrendingDown, ZkFpsDrop, stringResource(R.string.fps_drops_title), stringResource(R.string.fps_drops_sub, drops.size))
                Spacer(Modifier.height(12.dp))
                if (drops.isEmpty()) {
                    Text(stringResource(R.string.fps_drops_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        drops.forEach { d ->
                            Surface(shape = TileShape, color = causeColor(d.cause).copy(alpha = 0.1f)) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(shape = TileShape, color = causeColor(d.cause).copy(alpha = 0.2f)) {
                                        Text(
                                            causeLabel(d.cause),
                                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Text(
                                        FpsAnalysis.dropMessage(d).asString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            MonitorCard {
                CardHeader(ZkIcons.Speed, ZkCpu, stringResource(R.string.fps_frametime_title), stringResource(R.string.fps_frametime_sub))
                Spacer(Modifier.height(12.dp))
                StatGrid(
                    listOf(
                        Triple(stringResource(R.string.fps_stat_frame_avg), ms(st.avgFrameMs), null),
                        Triple(stringResource(R.string.fps_stat_frame_p95), ms(st.p95FrameMs), null),
                        Triple(stringResource(R.string.fps_stat_frame_p99), ms(st.p99FrameMs), null),
                        Triple(stringResource(R.string.fps_stat_frame_max), ms(st.maxFrameMs), if (st.maxFrameMs > 100f) ZkFpsDrop else null),
                        Triple(stringResource(R.string.fps_stat_pacing), "±" + ms(st.pacingStdDevMs), null),
                        Triple(stringResource(R.string.fps_stat_fps_sd), "±%.1f".format(st.fpsStdDev), null),
                    ),
                )
            }
        }

        item {
            MonitorCard {
                CardHeader(ZkIcons.Chart, ZkFps, stringResource(R.string.fps_dist_title), stringResource(R.string.fps_dist_sub))
                Spacer(Modifier.height(12.dp))
                val (counts, labels, edges) = distribution(rec)
                Histogram(
                    counts,
                    labels,
                    ZkFps,
                    Modifier.fillMaxWidth().height(140.dp),
                    highlight = { i -> edges[i + 1] >= st.medianFps * 0.9f },
                )
            }
        }

        item {
            MonitorCard {
                CardHeader(ZkIcons.Gamepad, ZkAqua, stringResource(R.string.fps_full_stats_title))
                Spacer(Modifier.height(12.dp))
                StatGrid(
                    listOf(
                        Triple(stringResource(R.string.fps_stat_min), "%.0f".format(st.minFps), null),
                        Triple(stringResource(R.string.fps_stat_max), "%.0f".format(st.maxFps), null),
                        Triple(stringResource(R.string.fps_stat_low5), "%.0f".format(st.low5Fps), null),
                        Triple(stringResource(R.string.fps_stat_frames), st.frames.toString(), null),
                        Triple(stringResource(R.string.fps_stat_jank), st.jankFrames.toString(), if (st.jankFrames > 0) ZkWarn else null),
                        Triple(stringResource(R.string.fps_stat_stutter), st.stutterFrames.toString(), if (st.stutterFrames > 0) ZkFpsDrop else null),
                        Triple(stringResource(R.string.fps_stat_dropped), st.droppedFrames.toString(), null),
                        Triple(stringResource(R.string.fps_stat_duration), formatDuration(st.activeMs), null),
                    ),
                )
                val powers = rec.seconds.mapNotNull { it.powerW }
                if (powers.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    InfoLine(stringResource(R.string.fps_stat_power), "%.2f W".format(powers.average()))
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.fps_stats_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirm && rec != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.fps_delete_title)) },
            text = { Text(stringResource(R.string.fps_delete_confirm, rec.label.ifBlank { rec.pkg })) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.delete(rec.id)
                    onBack()
                }) { Text(stringResource(R.string.fps_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun HeroStat(label: String, value: String, on: Color, modifier: Modifier) {
    Surface(modifier, shape = TileShape, color = on.copy(alpha = 0.08f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = on.copy(alpha = 0.75f), maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = on)
        }
    }
}

@Composable
private fun InsightRow(i: FpsAnalysis.Insight) {
    val (icon, color) = when (i.severity) {
        FpsAnalysis.Severity.GOOD -> ZkIcons.CheckCircle to ZkGood
        FpsAnalysis.Severity.INFO -> ZkIcons.Info to ZkCpu
        FpsAnalysis.Severity.WARN -> ZkIcons.Warning to ZkWarn
        FpsAnalysis.Severity.BAD -> ZkIcons.Warning to ZkBad
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(i.text.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun causeColor(c: FpsAnalysis.DropCause): Color = when (c) {
    FpsAnalysis.DropCause.HITCH -> ZkFpsDrop
    FpsAnalysis.DropCause.THERMAL -> ZkTemp
    FpsAnalysis.DropCause.SUSTAINED -> ZkWarn
    FpsAnalysis.DropCause.BRIEF -> ZkCpu
}

@Composable
private fun causeLabel(c: FpsAnalysis.DropCause): String = stringResource(
    when (c) {
        FpsAnalysis.DropCause.HITCH -> R.string.fps_cause_hitch
        FpsAnalysis.DropCause.THERMAL -> R.string.fps_cause_thermal
        FpsAnalysis.DropCause.SUSTAINED -> R.string.fps_cause_sustained
        FpsAnalysis.DropCause.BRIEF -> R.string.fps_cause_brief
    },
)

@Composable
private fun sourceLabel(s: String): String = when (s) {
    "surfaceflinger" -> "SurfaceFlinger"
    "gfxinfo" -> "gfxinfo"
    "" -> stringResource(R.string.mon_battery_unknown)
    else -> s
}

private fun ms(v: Float): String = "%.1f ms".format(v)

/** Per-second FPS counts in 8 equal buckets from 0 to the panel refresh (or the max FPS). */
private fun distribution(rec: FpsRecording): Triple<List<Float>, List<String>, List<Float>> {
    val top = max(rec.refreshHz, rec.stats.maxFps).coerceAtLeast(8f)
    val n = 8
    val width = ceil(top / n)
    val edges = (0..n).map { it * width }
    val counts = FloatArray(n)
    for (s in rec.seconds) {
        if (s.fps <= 0f) continue
        counts[((s.fps / width).toInt()).coerceIn(0, n - 1)]++
    }
    return Triple(counts.toList(), edges.dropLast(1).map { "%.0f".format(it) }, edges)
}
