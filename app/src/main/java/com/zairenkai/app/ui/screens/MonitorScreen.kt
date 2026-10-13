// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Monitor hub: one card per subsystem, each opening its own screen (FPS
 * recorder, CPU/GPU/thermal, battery). Battery and memory come from Android
 * and always show; CPU, GPU and thermal need the Zairenkai module and fall
 * back to a compact state without it.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import com.zairenkai.app.ui.icons.ZkIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.data.cpu.CpuClusters
import com.zairenkai.app.data.monitor.LiveState
import com.zairenkai.app.data.power.ChargeStatus
import com.zairenkai.app.ui.FpsViewModel
import com.zairenkai.app.ui.MonitorViewModel
import com.zairenkai.app.ui.PLAUSIBLE_CELSIUS
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.RingGauge
import com.zairenkai.app.ui.components.Sparkline
import com.zairenkai.app.ui.screens.monitor.*
import com.zairenkai.app.ui.theme.*

@Composable
fun MonitorScreen(
    factory: VmFactory,
    onOpenHistory: () -> Unit,
    onOpenCpu: (tab: Int) -> Unit,
    onOpenBattery: () -> Unit,
    onOpenFps: () -> Unit,
) {
    val vm: MonitorViewModel = viewModel(factory = factory)
    val fpsVm: FpsViewModel = viewModel(factory = factory)
    val s by vm.state.collectAsState()
    val live by fpsVm.live.collectAsState()
    val sessions by fpsVm.sessions.collectAsState()
    val watch by fpsVm.watch.collectAsState()
    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    Column(
        Modifier
            .fillMaxSize()
            // No top app bar here: this screen owns the status bar inset itself,
            // outside the scroll so content never slides under the status icons.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HubHeader(s, onOpenHistory)

        AnimatedVisibility(s.rootTelemetryMissing && !s.moduleBannerDismissed) {
            ModuleBanner(onDismiss = vm::dismissModuleBanner)
        }

        // FPS recorder
        val last = sessions?.firstOrNull()
        MonitorCard(
            shape = HeroShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = onOpenFps,
            padding = 20.dp,
        ) {
            val on = MaterialTheme.colorScheme.onPrimaryContainer
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.mon_fps_card_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = on.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(2.dp))
                    when {
                        live.recording != null -> BigValue(live.fps?.toString() ?: "—", "FPS", on)
                        last != null -> BigValue(fmt0(last.stats.avgFps), "FPS", on)
                        else -> Text(
                            stringResource(R.string.mon_fps_card_empty),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = on,
                        )
                    }
                }
                IconBadge(ZkIcons.Gamepad, on, size = 26.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    live.recording != null -> stringResource(R.string.mon_fps_card_recording, live.recordingLabel ?: live.recording!!)
                    live.running -> stringResource(R.string.mon_fps_card_waiting)
                    last != null -> stringResource(R.string.mon_fps_card_last, last.label, fmt0(last.stats.low1Fps))
                    else -> stringResource(R.string.mon_fps_card_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = on.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    pluralStringResource(R.plurals.mon_fps_watched, watch.size, watch.size),
                    on.copy(alpha = 0.1f), on,
                )
                val n = sessions?.size ?: 0
                Pill(pluralStringResource(R.plurals.mon_fps_sessions, n, n), on.copy(alpha = 0.1f), on)
                if (live.running) {
                    Pill(stringResource(R.string.mon_fps_auto_on), ZkGood.copy(alpha = 0.18f), on, dot = ZkGood)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CpuCard(s, Modifier.weight(1f)) { onOpenCpu(0) }
            GpuCard(s, Modifier.weight(1f)) { onOpenCpu(1) }
        }

        BatteryCard(s, onOpenBattery)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThermalCard(s, Modifier.weight(1f)) { onOpenCpu(2) }
            MemoryCard(s, Modifier.weight(1f))
        }

        s.monitor.latest?.zkfc?.let { zkfc ->
            MonitorCard(shape = TileShape, color = MaterialTheme.colorScheme.surfaceContainerLow, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        if (zkfc.thermalTripped) ZkIcons.Shield else ZkIcons.Verified,
                        contentDescription = null,
                        tint = if (zkfc.thermalTripped) ZkWarn else ZkGood,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.monitor_zkfc_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(
                                when {
                                    zkfc.thermalTripped -> R.string.monitor_zkfc_thermal_tripped
                                    zkfc.inputBoostActive -> R.string.monitor_zkfc_input_boost
                                    else -> R.string.monitor_zkfc_normal
                                },
                            ),
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
private fun HubHeader(s: LiveState, onOpenHistory: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.monitor_title),
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = (-0.7).sp),
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.monitor_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            val live = s.monitor.latest != null
            val dot = when {
                !s.running -> MaterialTheme.colorScheme.outline
                live -> ZkGood
                s.rootTelemetryMissing -> MaterialTheme.colorScheme.outline
                else -> ZkWarn
            }
            Pill(
                stringResource(
                    when {
                        !s.running -> R.string.monitor_status_paused
                        live -> R.string.monitor_status_live
                        s.rootTelemetryMissing -> R.string.mon_status_basic
                        else -> R.string.monitor_status_connecting
                    },
                ),
                dot.copy(alpha = 0.14f),
                MaterialTheme.colorScheme.onSurface,
                dot = dot,
            )
        }
        FilledTonalIconButton(onClick = onOpenHistory, modifier = Modifier.size(52.dp)) {
            Icon(ZkIcons.History, contentDescription = stringResource(R.string.monitor_open_history))
        }
    }
}

@Composable
private fun CpuCard(s: LiveState, modifier: Modifier, onClick: () -> Unit) {
    val m = s.monitor.latest
    val online = m?.cpu.orEmpty().filter { it.online && it.loadPct in 0..100 }
    val load = online.takeIf { it.isNotEmpty() }?.let { c -> c.sumOf { it.loadPct } / c.size }
    MonitorCard(modifier, onClick = onClick, padding = 16.dp) {
        CardHeaderSmall(ZkIcons.Memory, ZkCpu, "CPU")
        Spacer(Modifier.height(10.dp))
        if (load == null) {
            if (s.rootTelemetryMissing) ModuleNeeded() else WaitingText()
        } else {
            BigValue("$load", "%", ZkCpu, MaterialTheme.typography.headlineLarge)
            val clusters = CpuClusters.group(s.probe?.policies.orEmpty(), m!!.cpu)
            val top = clusters.mapNotNull { it.curKhz }.maxOrNull()
            Text(
                pluralStringResource(R.plurals.mon_cpu_card_detail, online.size, online.size, freqLabel(top)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Sparkline(s.monitor.cpuHistory.map { it.toFloat() }, ZkCpu, Modifier.fillMaxWidth().height(40.dp), capacity = 60)
        }
    }
}

@Composable
private fun GpuCard(s: LiveState, modifier: Modifier, onClick: () -> Unit) {
    val g = s.monitor.latest?.gpu
    val busy = g?.busyPct?.takeIf { it in 0..100 }
    val mhz = g?.curFreq?.takeIf { it > 0 }?.let { it / 1_000_000f }
    MonitorCard(modifier, onClick = onClick, padding = 16.dp) {
        CardHeaderSmall(ZkIcons.Chip, ZkGpu, "GPU")
        Spacer(Modifier.height(10.dp))
        if (busy == null && mhz == null) {
            if (s.rootTelemetryMissing) ModuleNeeded() else WaitingText(R.string.monitor_sensor_unavailable.takeIf { s.monitor.latest != null })
        } else {
            BigValue(busy?.toString() ?: "—", "%", ZkGpu, MaterialTheme.typography.headlineLarge)
            Text(
                mhz?.let { mhzLabel(it) } ?: stringResource(R.string.monitor_gpu_util_read),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Sparkline(s.monitor.gpuHistory.map { it.toFloat() }, ZkGpu, Modifier.fillMaxWidth().height(40.dp), capacity = 60)
        }
    }
}

@Composable
private fun BatteryCard(s: LiveState, onClick: () -> Unit) {
    val b = s.battery
    val charging = b?.status == ChargeStatus.CHARGING
    val accent = when {
        charging -> ZkGood
        (b?.levelPct ?: 100) <= 15 -> ZkBad
        else -> ZkAqua
    }
    MonitorCard(onClick = onClick, shape = CardShape) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RingGauge(b?.levelPct?.div(100f), accent, Modifier.size(96.dp), stroke = 10.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        b?.levelPct?.let { "$it%" } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        if (charging) ZkIcons.BatteryCharging else ZkIcons.Battery,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mon_battery_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    batteryStatusText(b?.status, b?.timeToFullMin),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MiniStat(stringResource(R.string.mon_battery_power), b?.powerW?.let { "%.1f W".format(kotlin.math.abs(it)) } ?: "—", accent)
                    MiniStat(stringResource(R.string.mon_battery_current), b?.currentMa?.let { "%.0f mA".format(it) } ?: "—")
                    MiniStat(stringResource(R.string.mon_battery_temp), b?.tempC?.let { "%.1f°".format(it) } ?: "—", tempColor(b?.tempC))
                }
            }
        }
        val extra = listOfNotNull(
            b?.healthPct?.let { stringResource(R.string.mon_battery_health_short, it.toInt()) },
            b?.cycleCount?.let { pluralStringResource(R.plurals.mon_battery_cycles_short, it, it) },
        )
        if (extra.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                extra.forEach { Pill(it, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

@Composable
private fun ThermalCard(s: LiveState, modifier: Modifier, onClick: () -> Unit) {
    val zones = s.monitor.latest?.thermal.orEmpty().filter { it.celsius in PLAUSIBLE_CELSIUS }
    val hottest = zones.maxOfOrNull { it.celsius }
    MonitorCard(modifier, onClick = onClick, padding = 16.dp) {
        CardHeaderSmall(ZkIcons.Thermometer, ZkTemp, stringResource(R.string.monitor_thermal_title))
        Spacer(Modifier.height(10.dp))
        if (hottest == null) {
            if (s.rootTelemetryMissing) ModuleNeeded() else WaitingText()
        } else {
            BigValue("%.1f".format(hottest), "°C", tempColor(hottest), MaterialTheme.typography.headlineLarge)
            Text(
                pluralStringResource(R.plurals.mon_thermal_zones, zones.size, zones.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Sparkline(s.monitor.tempHistory, ZkTemp, Modifier.fillMaxWidth().height(40.dp), minV = 20f, maxV = 90f, capacity = 60)
        }
    }
}

@Composable
private fun MemoryCard(s: LiveState, modifier: Modifier) {
    val mem = s.memory
    MonitorCard(modifier, padding = 16.dp) {
        CardHeaderSmall(ZkIcons.Storage, ZkIndigo, stringResource(R.string.mon_memory_title))
        Spacer(Modifier.height(10.dp))
        if (mem == null) {
            WaitingText()
        } else {
            BigValue("${mem.usedPct}", "%", loadColor(mem.usedPct), MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.mon_memory_used, gb(mem.usedMb), gb(mem.totalMb)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Sparkline(s.memHistory, ZkIndigo, Modifier.fillMaxWidth().height(40.dp), capacity = 60)
        }
    }
}

@Composable
private fun CardHeaderSmall(icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconBadge(icon, accent, size = 18.dp)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(ZkIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun WaitingText(res: Int? = null) {
    Text(
        stringResource(res ?: R.string.monitor_waiting_first_sample),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

@Composable
internal fun batteryStatusText(status: ChargeStatus?, ttfMin: Int?): String = when (status) {
    ChargeStatus.CHARGING -> ttfMin?.let { stringResource(R.string.mon_battery_charging_eta, durationText(it)) }
        ?: stringResource(R.string.mon_battery_charging)
    ChargeStatus.DISCHARGING -> stringResource(R.string.mon_battery_discharging)
    ChargeStatus.FULL -> stringResource(R.string.mon_battery_full)
    ChargeStatus.NOT_CHARGING -> stringResource(R.string.mon_battery_not_charging)
    else -> stringResource(R.string.mon_battery_unknown)
}

@Composable
internal fun durationText(min: Int): String =
    if (min >= 60) stringResource(R.string.mon_duration_hm, min / 60, min % 60) else stringResource(R.string.mon_duration_m, min)

private fun gb(mb: Long): String = "%.1f".format(mb / 1024f)
