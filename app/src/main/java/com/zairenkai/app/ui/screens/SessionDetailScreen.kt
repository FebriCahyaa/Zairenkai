// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Session detail: power statistics for one recorded usage session — battery
 * curve, average draw / used time / estimated endurance, per-app usage
 * scenarios, and any recorded game FPS sub-sessions.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.data.AppScenario
import com.zairenkai.app.data.FpsSession
import com.zairenkai.app.data.UsageSession
import com.zairenkai.app.ui.UsageHistoryViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.*
import com.zairenkai.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(factory: VmFactory, sessionId: String, onBack: () -> Unit) {
    val vm: UsageHistoryViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    val session = remember(ui.sessions, sessionId) { ui.sessions.firstOrNull { it.id == sessionId } }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Statistik Daya") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ChevronLeft, "Kembali") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                ui.loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() }
                session == null -> Text(
                    "Sesi tidak ditemukan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> SessionBody(session)
            }
        }
    }
}

@Composable
private fun SessionBody(s: UsageSession) {
    SectionCard("Proses penggunaan  •  ${s.levelDropPct}%") {
        if (s.curve.size >= 2) {
            LineChart(
                s.curve.map { it.levelPct.coerceAtLeast(0).toFloat() },
                ZkGood, Modifier.fillMaxWidth().height(150.dp), minV = 0f, maxV = 100f,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(s.startedAtMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatClock(s.endedAtMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricChip("${"%.1f".format(s.capacityWh)}Wh")
            MetricChip("${"%.1f".format(s.avgTempC)}°C")
            MetricChip("${"%.3f".format(s.avgVoltageV)}v")
            MetricChip(if (s.avgPowerW > 0f) "Discharge" else "—")
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Rata-rata daya", "%.2f W".format(s.avgPowerW), ZkGpu, Modifier.weight(1f))
        StatTile("Dipakai", formatDuration(s.usedMs), ZkCpu, Modifier.weight(1f))
        StatTile("Estimasi", enduranceText(s.estEnduranceMin), ZkGood, Modifier.weight(1f))
    }

    SectionCard("Skenario penggunaan") {
        if (s.scenarios.isEmpty()) {
            Text("Tidak ada data skenario.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            s.scenarios.forEachIndexed { i, sc ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ScenarioRow(sc)
            }
        }
    }

    s.fpsSessions.forEach { FpsSessionCard(it) }
}

@Composable
private fun ScenarioRow(sc: AppScenario) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(sc.pkg, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (sc.mode.isNotBlank()) {
                Text(sc.mode, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Text(sc.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1)
            Text(
                "AVG ${"%.2f".format(sc.avgPowerW)}W   MAX ${"%.0f".format(sc.maxTempC)}°C",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(formatDuration(sc.durationMs), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FpsSessionCard(f: FpsSession) {
    SectionCard("FPS  •  ${f.label}") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell("MAX", "%.0f".format(f.maxFps), ZkFps)
            StatCell("MIN", "%.0f".format(f.minFps), ZkFps)
            StatCell("AVG", "%.0f".format(f.avgFps), ZkFps)
            StatCell("VARIANCE", "%.1f".format(f.varianceFps), ZkFps)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell("≥${f.smoothnessTarget}FPS", "%.1f%%".format(f.smoothnessPct), ZkGood)
            StatCell("5% Low", "%.0f".format(f.low5Fps), ZkWarn)
            StatCell("MAX °C", "%.0f".format(f.maxTempC), ZkTemp)
            StatCell("AVG W", "%.2f".format(f.avgPowerW), ZkGpu)
        }
        if (f.fps.size >= 2) {
            Spacer(Modifier.height(12.dp))
            FpsGraph(
                fps = f.fps,
                drops = f.fps.map { it < f.smoothnessTarget },
                target = if (f.maxFps > 0) f.maxFps else 60f,
                lineColor = ZkFps, dropColor = ZkFpsDrop,
                modifier = Modifier.fillMaxWidth().height(130.dp),
            )
        }
        if (f.frameTimeMs.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Text("Frame Time (ms)", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            LineChart(f.frameTimeMs, ZkFpsDrop, Modifier.fillMaxWidth().height(90.dp),
                minV = 0f, maxV = (f.frameTimeMs.maxOrNull() ?: 50f).coerceAtLeast(16f))
        }
    }
}

@Composable
private fun RowScope.StatCell(label: String, value: String, accent: androidx.compose.ui.graphics.Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricChip(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

private fun enduranceText(min: Int): String =
    if (min <= 0) "—" else "${min / 60}h${min % 60}m"
