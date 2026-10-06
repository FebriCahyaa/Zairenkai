// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Monitor: live FPS (+ drops), CPU/GPU graphs, per-core grid, thermal.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.ui.MonitorViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.*
import com.zairenkai.app.ui.theme.*

@Composable
fun MonitorScreen(factory: VmFactory, onOpenHistory: () -> Unit = {}) {
    val vm: MonitorViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    val view = LocalView.current
    val refreshHz = remember(view) {
        runCatching { view.display?.refreshRate ?: 60f }.getOrDefault(60f)
    }
    val frame = rememberFrameStats(refreshHz)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FilledTonalButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.History, null)
            Spacer(Modifier.width(8.dp))
            Text("Riwayat Penggunaan")
        }

        SectionCard("FPS  •  ${"%.0f".format(frame.current)} / ${"%.0f".format(refreshHz)} Hz") {
            // Whole-session summary grid (matches Scene's game FPS card).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FpsCell("MAX", "%.0f".format(frame.maxFps))
                FpsCell("MIN", "%.0f".format(frame.minFps))
                FpsCell("AVG", "%.0f".format(frame.avgFps))
                FpsCell("VARIANCE", "%.1f".format(frame.varianceFps))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FpsCell("≥${frame.smoothnessTarget}FPS", "%.1f%%".format(frame.smoothnessPct), ZkGood)
                FpsCell("5% Low", "%.0f".format(frame.low5Fps), ZkWarn)
                FpsCell("Frames", "${frame.totalFrames}", MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(12.dp))
            FpsGraph(
                fps = frame.fps,
                drops = frame.drops,
                target = refreshHz,
                lineColor = ZkFps,
                dropColor = ZkFpsDrop,
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Jank (drop): ${frame.jankCount}", style = MaterialTheme.typography.labelMedium,
                    color = ZkFpsDrop, fontWeight = FontWeight.SemiBold)
                Text("Refresh ${"%.0f".format(refreshHz)} Hz", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val cpuAvg = ui.cpuHistory.lastOrNull() ?: 0
        SectionCard("CPU  •  $cpuAvg%") {
            LineChart(ui.cpuHistory.map { it.toFloat() }, ZkCpu,
                Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(12.dp))
            CoreGrid(ui.latest?.cpu ?: emptyList())
        }

        val gpu = ui.gpuHistory.lastOrNull() ?: -1
        SectionCard("GPU  •  ${if (gpu >= 0) "$gpu%" else "n/a"}") {
            LineChart(ui.gpuHistory.map { it.coerceAtLeast(0).toFloat() }, ZkGpu,
                Modifier.fillMaxWidth().height(120.dp))
            ui.latest?.gpu?.curFreq?.takeIf { it > 0 }?.let {
                Spacer(Modifier.height(8.dp))
                LabeledRow("Frekuensi GPU", "${it / 1_000_000} MHz")
            }
        }

        SectionCard("Thermal") {
            val zones = ui.latest?.thermal.orEmpty().filter { it.celsius > 0 }
            if (zones.isEmpty()) {
                Text("Tidak ada zona thermal terbaca.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val hottest = zones.maxOf { it.celsius }
                LineChart(ui.tempHistory, ZkTemp, Modifier.fillMaxWidth().height(100.dp), minV = 20f, maxV = 90f)
                Spacer(Modifier.height(8.dp))
                LabeledRow("Terpanas", "%.1f °C".format(hottest),
                    if (hottest >= 46f) ZkBad else if (hottest >= 42f) ZkWarn else ZkGood)
                zones.sortedByDescending { it.celsius }.take(6).forEach {
                    LabeledRow(it.type.ifBlank { it.zone }, "%.1f °C".format(it.celsius))
                }
            }
        }
    }
}

@Composable
private fun RowScope.FpsCell(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color = ZkFps,
) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoreGrid(cores: List<com.zairenkai.app.data.CpuSample>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cores.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { core ->
                    Surface(
                        Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("CPU${core.cpu}", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    if (!core.online) "off" else "${core.loadPct.coerceAtLeast(0)}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!core.online) MaterialTheme.colorScheme.onSurfaceVariant else ZkCpu,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            MeterBar(
                                if (core.online) core.loadPct.coerceIn(0, 100) / 100f else 0f,
                                ZkCpu,
                                Modifier.fillMaxWidth(),
                            )
                            if (core.curKhz > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text("${core.curKhz / 1000} MHz", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
