// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * CPU, GPU and thermal detail: clusters with their governor and frequency
 * range, per-core load and frequency, GPU busy/frequency, all thermal zones.
 * Everything is read-only telemetry from `zkfctl monitor` and `zperfd probe`.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens.monitor

import com.zairenkai.app.ui.icons.ZkIcons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.data.ThermalSample
import com.zairenkai.app.data.cpu.ClusterRole
import com.zairenkai.app.data.cpu.CpuCluster
import com.zairenkai.app.data.cpu.CpuClusters
import com.zairenkai.app.data.monitor.HISTORY_CAP
import com.zairenkai.app.data.monitor.LiveState
import com.zairenkai.app.ui.MonitorViewModel
import com.zairenkai.app.ui.PLAUSIBLE_CELSIUS
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.ChartSeries
import com.zairenkai.app.ui.components.MeterBar
import com.zairenkai.app.ui.components.RingGauge
import com.zairenkai.app.ui.components.Sparkline
import com.zairenkai.app.ui.components.TimeSeriesChart
import com.zairenkai.app.ui.theme.*

private val ClusterColors = listOf(ZkAqua, ZkCpu, ZkGpu, ZkAmber, ZkTemp)

@Composable
fun CpuGpuScreen(factory: VmFactory, initialTab: Int, onBack: () -> Unit) {
    val vm: MonitorViewModel = viewModel(factory = factory)
    val s by vm.state.collectAsState()
    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    MonitorDetailScaffold(stringResource(R.string.mon_cpu_screen_title), onBack) {
        item {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf("CPU", "GPU", stringResource(R.string.monitor_thermal_title)).forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontWeight = FontWeight.SemiBold) })
                }
            }
        }
        if (s.rootTelemetryMissing) {
            item { ModuleNeeded(detail = stringResource(R.string.mon_needs_module_detail)) }
        }
        when (tab) {
            0 -> cpuTab(s)
            1 -> gpuTab(s)
            else -> thermalTab(s)
        }
    }
}

private fun LazyListScope.cpuTab(s: LiveState) {
    val m = s.monitor.latest
    val cores = m?.cpu.orEmpty()
    val clusters = CpuClusters.group(s.probe?.policies.orEmpty(), cores)
    val online = cores.filter { it.online && it.loadPct in 0..100 }
    val load = online.takeIf { it.isNotEmpty() }?.let { c -> c.sumOf { it.loadPct } / c.size }

    item {
        MonitorCard(shape = HeroShape) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                RingGauge(load?.div(100f), loadColor(load), Modifier.size(116.dp), stroke = 12.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(load?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.mon_cpu_load), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoLine(stringResource(R.string.mon_cpu_cores), stringResource(R.string.monitor_cores_count, cores.count { it.online }, cores.size))
                    InfoLine(stringResource(R.string.mon_cpu_clusters), clusters.size.takeIf { it > 0 }?.toString() ?: "—")
                    InfoLine(stringResource(R.string.mon_cpu_peak), freqLabel(clusters.mapNotNull { it.curKhz }.maxOrNull()))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.mon_cpu_load_history), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (s.monitor.cpuHistory.size >= 2) {
                TimeSeriesChart(
                    listOf(ChartSeries(s.monitor.cpuHistory.map { it.toFloat() }, ZkCpu)),
                    Modifier.fillMaxWidth().height(140.dp),
                    formatY = { "%.0f%%".format(it) },
                    capacity = HISTORY_CAP,
                )
            } else {
                ChartPlaceholder(stringResource(R.string.monitor_waiting_first_sample), Modifier.fillMaxWidth().height(140.dp))
            }
        }
    }

    if (clusters.isNotEmpty()) {
        item {
            MonitorCard {
                CardHeader(ZkIcons.Speed, ZkCpu, stringResource(R.string.mon_cpu_clusters_title), stringResource(R.string.mon_cpu_clusters_sub))
                Spacer(Modifier.height(14.dp))
                clusters.forEachIndexed { i, c ->
                    ClusterBlock(c, ClusterColors[i % ClusterColors.size])
                    if (i != clusters.lastIndex) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                val series = clusters.mapIndexedNotNull { i, c ->
                    s.clusterFreqHistory[c.policy]?.let { ChartSeries(it, ClusterColors[i % ClusterColors.size], fill = false) }
                }
                if (series.any { it.values.size >= 2 }) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.mon_cpu_freq_history), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    val top = (clusters.maxOf { it.maxKhz } / 1000f).takeIf { it > 0f }
                        ?: (series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 1000f) * 1.1f
                    TimeSeriesChart(
                        series,
                        Modifier.fillMaxWidth().height(150.dp),
                        maxV = top,
                        formatY = { if (it >= 1000f) "%.1fG".format(it / 1000f) else "%.0fM".format(it) },
                        capacity = HISTORY_CAP,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        clusters.forEachIndexed { i, c -> LegendDot(ClusterColors[i % ClusterColors.size], roleLabel(c.role)) }
                    }
                }
            }
        }
    }

    if (cores.isNotEmpty()) {
        item {
            MonitorCard {
                CardHeader(ZkIcons.Memory, ZkCpu, stringResource(R.string.monitor_cores_title), stringResource(R.string.mon_cpu_cores_sub))
                Spacer(Modifier.height(14.dp))
                val colorOf = clusters.flatMapIndexed { i, c -> c.cores.map { it.cpu to ClusterColors[i % ClusterColors.size] } }.toMap()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cores.sortedBy { it.cpu }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { core ->
                                val accent = colorOf[core.cpu] ?: ZkCpu
                                val has = core.online && core.loadPct in 0..100
                                Surface(Modifier.weight(1f), shape = TileShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("CPU ${core.cpu}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                            Text(
                                                when {
                                                    !core.online -> stringResource(R.string.monitor_core_off)
                                                    has -> "${core.loadPct}%"
                                                    else -> "—"
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = if (has) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Sparkline(
                                            s.coreHistory[core.cpu].orEmpty().map { it.toFloat() },
                                            accent,
                                            Modifier.fillMaxWidth().height(30.dp),
                                            capacity = 40,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            if (core.curKhz > 0) freqLabel(core.curKhz) else stringResource(R.string.monitor_core_freq_na),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    s.probe?.let { p ->
        item {
            MonitorCard {
                CardHeader(ZkIcons.Chip, ZkIndigo, stringResource(R.string.mon_cpu_platform))
                Spacer(Modifier.height(8.dp))
                if (p.release.isNotBlank()) InfoLine(stringResource(R.string.mon_cpu_kernel), p.release)
                InfoLine(stringResource(R.string.mon_cpu_policies), p.policies.size.toString())
                val govs = p.policies.mapNotNull { it.governor }.distinct()
                if (govs.isNotEmpty()) InfoLine(stringResource(R.string.mon_cpu_governors), govs.joinToString(", "))
                InfoLine("cgroup v2", stringResource(if (p.cgroupV2) R.string.mon_yes else R.string.mon_no))
            }
        }
    }
}

@Composable
private fun ClusterBlock(c: CpuCluster, accent: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(roleLabel(c.role), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = accent)
                Text(
                    coreRange(c),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            c.governor?.let { Pill(it, accent.copy(alpha = 0.14f), MaterialTheme.colorScheme.onSurface) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(freqLabel(c.curKhz), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.mon_cpu_cluster_load, c.loadPct?.let { "$it%" } ?: "—"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        MeterBar((c.freqPct ?: 0) / 100f, accent, Modifier.fillMaxWidth())
        if (c.maxKhz > 0) {
            Spacer(Modifier.height(4.dp))
            Row {
                Text(freqLabel(c.minKhz), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(freqLabel(c.maxKhz), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun coreRange(c: CpuCluster): String {
    val ids = c.cores.map { it.cpu }
    val range = if (ids.size > 1 && ids.last() - ids.first() == ids.size - 1) "CPU ${ids.first()}–${ids.last()}" else ids.joinToString(", ") { "CPU $it" }
    return pluralStringResource(R.plurals.mon_cpu_cluster_cores, ids.size, ids.size, range)
}

@Composable
private fun roleLabel(r: ClusterRole): String = stringResource(
    when (r) {
        ClusterRole.SINGLE -> R.string.mon_cluster_all
        ClusterRole.EFFICIENCY -> R.string.mon_cluster_efficiency
        ClusterRole.BALANCED -> R.string.mon_cluster_balanced
        ClusterRole.PERFORMANCE -> R.string.mon_cluster_performance
        ClusterRole.PRIME -> R.string.mon_cluster_prime
    },
)

/** devfreq frequencies are Hz on most GPUs, kHz on a few; returns MHz. */
private fun gpuMhz(v: Long): Float? = when {
    v <= 0 -> null
    v >= 10_000_000 -> v / 1_000_000f
    v >= 10_000 -> v / 1000f
    else -> v.toFloat()
}

private fun LazyListScope.gpuTab(s: LiveState) {
    val g = s.monitor.latest?.gpu
    val busy = g?.busyPct?.takeIf { it in 0..100 }
    val mhz = g?.curFreq?.let(::gpuMhz)
    val pg = s.probe?.gpu
    val maxMhz = pg?.max?.let(::gpuMhz)
    val minMhz = pg?.min?.let(::gpuMhz)
    item {
        MonitorCard(shape = HeroShape) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                RingGauge(busy?.div(100f), ZkGpu, Modifier.size(116.dp), stroke = 12.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(busy?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.mon_gpu_busy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoLine(stringResource(R.string.mon_gpu_freq), mhzLabel(mhz))
                    InfoLine(stringResource(R.string.mon_gpu_range), if (maxMhz != null) "${mhzLabel(minMhz)} – ${mhzLabel(maxMhz)}" else "—")
                    InfoLine(
                        stringResource(R.string.mon_gpu_of_max),
                        if (mhz != null && maxMhz != null && maxMhz > 0f) "%.0f%%".format(mhz * 100f / maxMhz) else "—",
                    )
                }
            }
        }
    }
    item {
        MonitorCard {
            CardHeader(ZkIcons.Pulse, ZkGpu, stringResource(R.string.mon_gpu_busy_history))
            Spacer(Modifier.height(12.dp))
            if (s.monitor.gpuHistory.size >= 2) {
                TimeSeriesChart(
                    listOf(ChartSeries(s.monitor.gpuHistory.map { it.toFloat() }, ZkGpu)),
                    Modifier.fillMaxWidth().height(140.dp),
                    formatY = { "%.0f%%".format(it) },
                    capacity = HISTORY_CAP,
                )
            } else {
                ChartPlaceholder(stringResource(R.string.monitor_sensor_unavailable), Modifier.fillMaxWidth().height(100.dp))
            }
        }
    }
    item {
        MonitorCard {
            CardHeader(ZkIcons.Speed, ZkGpu, stringResource(R.string.mon_gpu_freq_history))
            Spacer(Modifier.height(12.dp))
            if (s.gpuFreqHistory.size >= 2) {
                val top = maxMhz ?: ((s.gpuFreqHistory.maxOrNull() ?: 100f) * 1.1f)
                TimeSeriesChart(
                    listOf(ChartSeries(s.gpuFreqHistory, ZkGpu.copy(alpha = 0.9f))),
                    Modifier.fillMaxWidth().height(140.dp),
                    maxV = top,
                    formatY = { "%.0f".format(it) },
                    capacity = HISTORY_CAP,
                )
                Text("MHz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ChartPlaceholder(stringResource(R.string.monitor_sensor_unavailable), Modifier.fillMaxWidth().height(100.dp))
            }
        }
    }
    if (pg != null) {
        item {
            MonitorCard {
                CardHeader(ZkIcons.Chip, ZkGpu, stringResource(R.string.mon_gpu_info))
                Spacer(Modifier.height(8.dp))
                pg.provider.ifBlank { pg.kind }.takeIf { it.isNotBlank() }?.let { InfoLine(stringResource(R.string.mon_gpu_driver), it) }
                InfoLine(stringResource(R.string.mon_gpu_opps), pg.opps.toString())
                InfoLine(stringResource(R.string.mon_gpu_min), mhzLabel(minMhz))
                InfoLine(stringResource(R.string.mon_gpu_max), mhzLabel(maxMhz))
            }
        }
    }
}

private fun LazyListScope.thermalTab(s: LiveState) {
    val zones = s.monitor.latest?.thermal.orEmpty().filter { it.celsius in PLAUSIBLE_CELSIUS }.sortedByDescending { it.celsius }
    val hottest = zones.firstOrNull()
    item {
        MonitorCard(shape = HeroShape) {
            Text(stringResource(R.string.mon_thermal_hottest), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BigValue(hottest?.let { "%.1f".format(it.celsius) } ?: "—", "°C", tempColor(hottest?.celsius))
            hottest?.let { Text(zoneName(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(14.dp))
            if (s.monitor.tempHistory.size >= 2) {
                TimeSeriesChart(
                    listOf(ChartSeries(s.monitor.tempHistory, ZkTemp)),
                    Modifier.fillMaxWidth().height(140.dp),
                    minV = 20f,
                    maxV = 90f,
                    formatY = { "%.0f°".format(it) },
                    capacity = HISTORY_CAP,
                )
            } else {
                ChartPlaceholder(stringResource(R.string.monitor_thermal_empty_title), Modifier.fillMaxWidth().height(100.dp))
            }
        }
    }
    if (zones.isEmpty()) {
        item {
            MonitorCard {
                Text(stringResource(R.string.monitor_thermal_empty_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.monitor_thermal_empty_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        item {
            Text(
                pluralStringResource(R.plurals.mon_thermal_zones, zones.size, zones.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
        items(zones, key = { it.zone + it.type }) { z ->
            MonitorCard(shape = TileShape, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(zoneName(z), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("%.1f °C".format(z.celsius), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tempColor(z.celsius))
                }
                Spacer(Modifier.height(8.dp))
                MeterBar(z.celsius / 100f, tempColor(z.celsius), Modifier.fillMaxWidth())
            }
        }
        item {
            Text(
                stringResource(R.string.mon_thermal_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun zoneName(z: ThermalSample): String =
    z.type.ifBlank { z.zone.ifBlank { stringResource(R.string.monitor_thermal_zone_fallback) } }
