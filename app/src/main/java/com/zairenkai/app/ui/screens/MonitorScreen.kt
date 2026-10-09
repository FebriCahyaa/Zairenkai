// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Expressive live performance monitor: frame cadence, CPU/GPU telemetry,
 * per-core activity, and thermal readings.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.data.CpuSample
import com.zairenkai.app.data.ThermalSample
import com.zairenkai.app.ui.MonitorViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.FpsGraph
import com.zairenkai.app.ui.components.LineChart
import com.zairenkai.app.ui.components.MeterBar
import com.zairenkai.app.ui.components.rememberFrameStats
import com.zairenkai.app.ui.theme.ZkBad
import com.zairenkai.app.ui.theme.ZkCpu
import com.zairenkai.app.ui.theme.ZkFps
import com.zairenkai.app.ui.theme.ZkFpsDrop
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkGpu
import com.zairenkai.app.ui.theme.ZkTemp
import com.zairenkai.app.ui.theme.ZkWarn

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
        runCatching { view.display?.refreshRate ?: 60f }.getOrDefault(60f).coerceAtLeast(1f)
    }
    val frame = rememberFrameStats(refreshHz)
    var showCores by rememberSaveable { mutableStateOf(false) }

    val coreSamples = ui.latest?.cpu.orEmpty()
    val validCores = coreSamples.filter { it.online && it.loadPct >= 0 }
    val cpuHasData = validCores.isNotEmpty()
    val cpuNow = if (cpuHasData) validCores.sumOf { it.loadPct } / validCores.size else null
    val gpuNow = ui.latest?.gpu?.busyPct?.takeIf { it in 0..100 }
    val gpuFreqMhz = ui.latest?.gpu?.curFreq?.takeIf { it > 0 }?.div(1_000_000L)
    val thermalZones = ui.latest?.thermal.orEmpty().filter { it.celsius in 1f..150f }
    val hottest = thermalZones.maxOfOrNull { it.celsius }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MonitorHeader(
            isRunning = ui.running,
            hasSample = ui.latest != null,
            refreshHz = refreshHz,
            onOpenHistory = onOpenHistory,
        )

        FpsHeroCard(
            frame = frame,
            refreshHz = refreshHz,
            hasSamples = frame.fps.size >= 2,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "CPU",
                value = cpuNow?.let { "$it%" } ?: "—",
                detail = when {
                    cpuNow != null -> "${validCores.size} core terbaca"
                    ui.latest == null -> "Menunggu data zperfd"
                    else -> "Sensor belum terbaca"
                },
                icon = Icons.Rounded.Memory,
                accent = ZkCpu,
                samples = ui.cpuHistory.map { it.toFloat() },
                hasCurrentValue = cpuNow != null,
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "GPU",
                value = gpuNow?.let { "$it%" } ?: "n/a",
                detail = when {
                    gpuFreqMhz != null -> "$gpuFreqMhz MHz"
                    gpuNow != null -> "Utilisasi terbaca"
                    ui.latest == null -> "Menunggu data zperfd"
                    else -> "Sensor tidak tersedia"
                },
                icon = Icons.Rounded.DeveloperBoard,
                accent = ZkGpu,
                samples = ui.gpuHistory.map { it.toFloat() },
                hasCurrentValue = gpuNow != null,
            )
        }

        if (coreSamples.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Aktivitas per-core", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${coreSamples.count { it.online }} online · ${coreSamples.size} terdeteksi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showCores = !showCores }) {
                            Text(if (showCores) "Ringkas" else "Lihat detail")
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                if (showCores) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = showCores,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Column(Modifier.padding(top = 14.dp)) {
                            CoreGrid(coreSamples)
                        }
                    }
                }
            }
        }

        ThermalCard(
            zones = thermalZones,
            hottest = hottest,
            history = ui.tempHistory,
        )

        val zkfc = ui.latest?.zkfc
        if (zkfc != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (zkfc.thermalTripped) Icons.Rounded.Shield else Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = if (zkfc.thermalTripped) ZkWarn else ZkGood,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("ZKFC runtime", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                zkfc.thermalTripped -> "Thermal guard sedang membatasi performa"
                                zkfc.inputBoostActive -> "Input boost aktif"
                                else -> "Kontrol kernel berjalan normal"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${zkfc.boostedTasks}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitorHeader(
    isRunning: Boolean,
    hasSample: Boolean,
    refreshHz: Float,
    onOpenHistory: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Monitor",
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = (-0.7).sp),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Performa perangkat, secara langsung",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    label = when {
                        !isRunning -> "PAUSED"
                        hasSample -> "LIVE"
                        else -> "MENGHUBUNGKAN"
                    },
                    dotColor = when {
                        !isRunning -> MaterialTheme.colorScheme.outline
                        hasSample -> ZkGood
                        else -> ZkWarn
                    },
                    containerColor = when {
                        !isRunning -> MaterialTheme.colorScheme.surfaceContainerHigh
                        hasSample -> ZkGood.copy(alpha = 0.13f)
                        else -> ZkWarn.copy(alpha = 0.13f)
                    },
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        "${refreshHzLabel(refreshHz)} DISPLAY",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        FilledTonalIconButton(
            onClick = onOpenHistory,
            modifier = Modifier.size(52.dp),
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(Icons.Rounded.History, contentDescription = "Riwayat penggunaan")
        }
    }
}

private fun refreshHzLabel(value: Float): String = "%.0f Hz".format(value.coerceAtLeast(1f))

@Composable
private fun StatusPill(
    label: String,
    dotColor: Color,
    containerColor: Color,
) {
    Surface(shape = CircleShape, color = containerColor) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FpsHeroCard(
    frame: com.zairenkai.app.ui.components.FrameStats,
    refreshHz: Float,
    hasSamples: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp, 32.dp, 32.dp, 22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        "FPS UI MONITOR",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (frame.current > 0f) "%.0f".format(frame.current) else "—",
                            style = MaterialTheme.typography.displayLarge.copy(letterSpacing = (-1.5).sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "FPS",
                            modifier = Modifier.padding(bottom = 9.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
                ) {
                    Icon(
                        Icons.Rounded.Timeline,
                        contentDescription = null,
                        modifier = Modifier.padding(14.dp).size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Refresh panel ${refreshHzLabel(refreshHz)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (hasSamples) "Sampel stabil" else "Mengumpulkan sampel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }

            if (hasSamples) {
                FpsGraph(
                    fps = frame.fps,
                    drops = frame.drops,
                    target = refreshHz,
                    lineColor = ZkFps,
                    dropColor = ZkFpsDrop,
                    modifier = Modifier.fillMaxWidth().height(112.dp),
                )
            } else {
                ChartEmptyState(
                    modifier = Modifier.fillMaxWidth().height(86.dp),
                    title = "Menunggu sampel frame",
                    detail = "Grafik muncul setelah data tersedia",
                    compact = true,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 16.dp, bottom = 14.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FpsStat(
                    label = "RATA-RATA",
                    value = frame.avgFps.takeIf { it > 0f }?.let { "%.0f".format(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                FpsStat(
                    label = "5% LOW",
                    value = frame.low5Fps.takeIf { it > 0f }?.let { "%.0f".format(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                FpsStat(
                    label = "JANK",
                    value = if (frame.totalFrames > 0) "${frame.jankCount}" else "—",
                    modifier = Modifier.weight(1f),
                    valueColor = if (frame.jankCount > 0) ZkFpsDrop else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun FpsStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    detail: String,
    icon: ImageVector,
    accent: Color,
    samples: List<Float>,
    hasCurrentValue: Boolean,
) {
    Surface(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(shape = RoundedCornerShape(13.dp), color = accent.copy(alpha = 0.14f)) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                        tint = accent,
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.6).sp),
                fontWeight = FontWeight.Bold,
                color = if (hasCurrentValue) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Text(
                detail,
                modifier = Modifier.heightIn(min = 32.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            if (hasCurrentValue && samples.size >= 2) {
                LineChart(
                    data = samples,
                    color = accent,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                )
            } else {
                ChartEmptyState(
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    title = if (hasCurrentValue) "Sampel awal" else "Belum tersedia",
                    detail = null,
                    compact = true,
                    foreground = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThermalCard(
    zones: List<ThermalSample>,
    hottest: Float?,
    history: List<Float>,
) {
    val sortedZones = zones.sortedByDescending { it.celsius }.take(5)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = ZkTemp.copy(alpha = 0.15f)) {
                    Icon(
                        Icons.Rounded.DeviceThermostat,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                        tint = ZkTemp,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Thermal", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Sensor suhu perangkat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hottest != null) {
                    Text(
                        "%.1f°".format(hottest),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = thermalColor(hottest),
                    )
                }
            }

            if (zones.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.SensorsOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Belum ada pembacaan", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Kernel atau vendor belum mengekspos zona thermal yang valid.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                if (history.size >= 2) {
                    Spacer(Modifier.height(12.dp))
                    LineChart(
                        data = history,
                        color = ZkTemp,
                        modifier = Modifier.fillMaxWidth().height(76.dp),
                        minV = 20f,
                        maxV = 90f,
                    )
                }
                Spacer(Modifier.height(12.dp))
                sortedZones.forEachIndexed { index, zone ->
                    ThermalZoneRow(zone = zone, isLast = index == sortedZones.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun ThermalZoneRow(zone: ThermalSample, isLast: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(thermalColor(zone.celsius)))
        Spacer(Modifier.width(10.dp))
        Text(
            zone.type.ifBlank { zone.zone.ifBlank { "Thermal zone" } },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "%.1f °C".format(zone.celsius),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = thermalColor(zone.celsius),
        )
    }
    if (!isLast) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

private fun thermalColor(celsius: Float): Color = when {
    celsius >= 48f -> ZkBad
    celsius >= 42f -> ZkWarn
    else -> ZkGood
}

@Composable
private fun ChartEmptyState(
    modifier: Modifier,
    title: String,
    detail: String?,
    compact: Boolean,
    foreground: Color,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 16.dp else 20.dp))
            .background(foreground.copy(alpha = 0.045f))
            .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Timeline,
            contentDescription = null,
            modifier = Modifier.size(if (compact) 17.dp else 22.dp),
            tint = foreground.copy(alpha = 0.72f),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                title,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                color = foreground.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CoreGrid(cores: List<CpuSample>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cores.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { core ->
                    val hasLoad = core.online && core.loadPct >= 0
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("CPU ${core.cpu}", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    when {
                                        !core.online -> "OFF"
                                        hasLoad -> "${core.loadPct}%"
                                        else -> "—"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = when {
                                        !core.online -> MaterialTheme.colorScheme.onSurfaceVariant
                                        hasLoad -> ZkCpu
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(9.dp))
                            MeterBar(
                                fraction = if (hasLoad) core.loadPct / 100f else 0f,
                                color = ZkCpu,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(
                                if (core.curKhz > 0) "${core.curKhz / 1000} MHz" else "Frekuensi n/a",
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
