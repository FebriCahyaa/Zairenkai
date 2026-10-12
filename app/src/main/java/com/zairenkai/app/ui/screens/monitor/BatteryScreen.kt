// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Battery detail: level and charge state, a live charging diagram (power,
 * current, level, temperature, voltage), the charger, and battery wear
 * (full vs design capacity, cycle count). Android's BatteryManager works
 * without root; zperfd adds what only sysfs knows.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens.monitor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.data.power.BatteryHealth
import com.zairenkai.app.data.power.BatteryReading
import com.zairenkai.app.data.power.ChargeStatus
import com.zairenkai.app.data.power.CycleSource
import com.zairenkai.app.data.power.PlugType
import com.zairenkai.app.ui.MonitorViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.ChartSeries
import com.zairenkai.app.ui.components.MeterBar
import com.zairenkai.app.ui.components.RingGauge
import com.zairenkai.app.ui.components.TimeSeriesChart
import com.zairenkai.app.ui.icons.*
import com.zairenkai.app.ui.screens.batteryStatusText
import com.zairenkai.app.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class BatteryMetric(@StringRes val label: Int, val unit: String, val color: Color, val pick: (BatteryReading) -> Float?) {
    POWER(R.string.mon_battery_power, "W", ZkGood, { it.powerW }),
    CURRENT(R.string.mon_battery_current, "mA", ZkAqua, { it.currentMa }),
    LEVEL(R.string.mon_battery_level, "%", ZkCpu, { it.levelPct?.toFloat() }),
    TEMP(R.string.mon_battery_temp, "°C", ZkTemp, { it.tempC }),
    VOLTAGE(R.string.mon_battery_voltage, "V", ZkGpu, { it.voltageV }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryScreen(factory: VmFactory, onBack: () -> Unit) {
    val vm: MonitorViewModel = viewModel(factory = factory)
    val s by vm.state.collectAsState()
    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }
    val b = s.battery
    val charging = b?.status == ChargeStatus.CHARGING
    val accent = when {
        charging -> ZkGood
        (b?.levelPct ?: 100) <= 15 -> ZkBad
        else -> ZkAqua
    }
    var metric by rememberSaveable { mutableStateOf(BatteryMetric.POWER) }

    MonitorDetailScaffold(stringResource(R.string.mon_battery_title), onBack) {
        item {
            MonitorCard(shape = HeroShape, color = accent.copy(alpha = 0.12f).compositeOverSurface()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RingGauge(b?.levelPct?.div(100f), accent, Modifier.size(140.dp), stroke = 14.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                b?.levelPct?.let { "$it%" } ?: "—",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            batteryStatusText(b?.status, b?.timeToFullMin),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        b?.plug?.takeIf { it != PlugType.NONE }?.let {
                            Text(plugLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        BigValue(
                            b?.powerW?.let { (if (it > 0f) "+" else if (it < 0f) "−" else "") + "%.2f".format(abs(it)) } ?: "—",
                            "W",
                            accent,
                            MaterialTheme.typography.headlineLarge,
                        )
                        Text(
                            b?.currentMa?.let { stringResource(R.string.mon_battery_current_value, "%+.0f".format(it)) } ?: "—",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            MonitorCard {
                CardHeader(Icons.Rounded.Timeline, metric.color, stringResource(R.string.mon_battery_chart_title), chartSpan(s.batteryHistory))
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BatteryMetric.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = metric == m,
                            onClick = { metric = m },
                            shape = SegmentedButtonDefaults.itemShape(i, BatteryMetric.entries.size),
                            icon = {},
                            label = { Text(m.unit, maxLines = 1) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(metric.label), style = MaterialTheme.typography.labelLarge, color = metric.color)
                Spacer(Modifier.height(8.dp))
                val values = s.batteryHistory.map(metric.pick)
                val known = values.filterNotNull()
                if (known.size >= 2) {
                    val lo = known.min()
                    val hi = known.max()
                    val (minV, maxV) = when (metric) {
                        BatteryMetric.LEVEL -> 0f to 100f
                        else -> {
                            val pad = max((hi - lo) * 0.15f, if (metric == BatteryMetric.VOLTAGE) 0.05f else 1f)
                            val l = if (lo >= 0f && metric != BatteryMetric.VOLTAGE && metric != BatteryMetric.TEMP) 0f else lo - pad
                            l to (hi + pad)
                        }
                    }
                    TimeSeriesChart(
                        listOf(ChartSeries(values, metric.color)),
                        Modifier.fillMaxWidth().height(170.dp),
                        minV = minV,
                        maxV = maxV,
                        formatY = { if (metric == BatteryMetric.VOLTAGE || (metric == BatteryMetric.POWER && maxV - minV < 10f)) "%.1f".format(it) else "%.0f".format(it) },
                        refLine = if (minV < 0f && maxV > 0f) 0f else null,
                    )
                    Spacer(Modifier.height(12.dp))
                    val fmt: (Float) -> String = { v ->
                        (if (metric == BatteryMetric.VOLTAGE || metric == BatteryMetric.POWER || metric == BatteryMetric.TEMP) "%.2f" else "%.0f").format(v) + " " + metric.unit
                    }
                    StatGrid(
                        listOf(
                            Triple(stringResource(R.string.mon_stat_min), fmt(lo), null),
                            Triple(stringResource(R.string.mon_stat_avg), fmt(known.average().toFloat()), metric.color),
                            Triple(stringResource(R.string.mon_stat_max), fmt(hi), null),
                        ),
                        columns = 3,
                    )
                } else {
                    ChartPlaceholder(stringResource(R.string.monitor_waiting_first_sample), Modifier.fillMaxWidth().height(170.dp))
                }
            }
        }

        if (b != null && (b.plug != PlugType.NONE || b.chargerType != null)) {
            item {
                MonitorCard {
                    CardHeader(Icons.Rounded.ElectricalServices, ZkGood, stringResource(R.string.mon_charger_title), b.chargerType ?: plugLabel(b.plug))
                    Spacer(Modifier.height(12.dp))
                    StatGrid(
                        listOf(
                            Triple(stringResource(R.string.mon_charger_input_w), b.chargerPowerW?.let { "%.1f W".format(it) } ?: "—", ZkGood),
                            Triple(stringResource(R.string.mon_charger_to_battery), b.powerW?.takeIf { it > 0f }?.let { "%.1f W".format(it) } ?: "—", null),
                            Triple(stringResource(R.string.mon_charger_input_v), b.chargerVoltageV?.let { "%.2f V".format(it) } ?: "—", null),
                            Triple(stringResource(R.string.mon_charger_input_a), b.chargerCurrentA?.let { "%.2f A".format(it) } ?: "—", null),
                        ),
                    )
                    b.chargeType?.let {
                        Spacer(Modifier.height(8.dp))
                        InfoLine(stringResource(R.string.mon_charger_phase), it)
                    }
                    if (b.chargerPowerW == null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(if (s.zperfBattery) R.string.mon_charger_not_reported else R.string.mon_charger_needs_module),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            MonitorCard {
                CardHeader(Icons.Rounded.HealthAndSafety, healthColor(b?.healthPct), stringResource(R.string.mon_health_title), stringResource(R.string.mon_health_sub))
                Spacer(Modifier.height(14.dp))
                val hp = b?.healthPct
                Row(verticalAlignment = Alignment.Bottom) {
                    BigValue(hp?.let { "%.0f".format(min(it, 100f)) } ?: "—", "%", healthColor(hp), MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.weight(1f))
                    b?.cycleCount?.let {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(it.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                pluralStringResource(R.plurals.mon_health_cycles_label, it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (hp != null) {
                    Spacer(Modifier.height(8.dp))
                    MeterBar(min(hp, 100f) / 100f, healthColor(hp), Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.mon_health_capacity, b.fullMah ?: 0, b.designMah ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(if (s.zperfBattery) R.string.mon_health_not_reported else R.string.mon_health_needs_module),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                InfoLine(stringResource(R.string.mon_health_status), healthLabel(b?.health), if (b?.health == BatteryHealth.GOOD) ZkGood else null)
                InfoLine(stringResource(R.string.mon_health_cycles), b?.cycleCount?.toString() ?: "—")
                b?.cycleSource?.let {
                    Text(
                        stringResource(if (it == CycleSource.ANDROID) R.string.mon_cycles_source_android else R.string.mon_cycles_source_kernel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                b?.kernelCycleCount?.let {
                    Text(
                        stringResource(R.string.mon_cycles_kernel_differs, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                b?.technology?.let { InfoLine(stringResource(R.string.mon_health_tech), it) }
            }
        }

        item {
            MonitorCard {
                CardHeader(Icons.Rounded.Bolt, ZkAqua, stringResource(R.string.mon_electrical_title))
                Spacer(Modifier.height(12.dp))
                StatGrid(
                    listOf(
                        Triple(stringResource(R.string.mon_battery_voltage), b?.voltageV?.let { "%.3f V".format(it) } ?: "—", null),
                        Triple(stringResource(R.string.mon_battery_current), b?.currentMa?.let { "%+.0f mA".format(it) } ?: "—", null),
                        Triple(stringResource(R.string.mon_battery_temp), b?.tempC?.let { "%.1f °C".format(it) } ?: "—", tempColor(b?.tempC)),
                        Triple(stringResource(R.string.mon_battery_remaining), b?.remainingMah?.let { "$it mAh" } ?: "—", null),
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.mon_battery_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Color.compositeOverSurface(): Color = compositeOver(MaterialTheme.colorScheme.surfaceContainer)

@Composable
private fun chartSpan(h: List<BatteryReading>): String {
    if (h.size < 2) return stringResource(R.string.mon_battery_chart_sub_empty)
    val min = ((h.last().tMs - h.first().tMs) / 60_000).toInt()
    return if (min < 1) stringResource(R.string.mon_battery_chart_sub_seconds) else pluralStringResource(R.plurals.mon_battery_chart_sub, min, min)
}

private fun healthColor(pct: Float?): Color = when {
    pct == null -> ZkGood
    pct >= 80f -> ZkGood
    pct >= 65f -> ZkWarn
    else -> ZkBad
}

@Composable
private fun plugLabel(p: PlugType): String = stringResource(
    when (p) {
        PlugType.AC -> R.string.mon_plug_ac
        PlugType.USB -> R.string.mon_plug_usb
        PlugType.WIRELESS -> R.string.mon_plug_wireless
        PlugType.DOCK -> R.string.mon_plug_dock
        PlugType.NONE -> R.string.mon_plug_none
    },
)

@Composable
private fun healthLabel(h: BatteryHealth?): String = stringResource(
    when (h) {
        BatteryHealth.GOOD -> R.string.mon_health_good
        BatteryHealth.OVERHEAT -> R.string.mon_health_overheat
        BatteryHealth.DEAD -> R.string.mon_health_dead
        BatteryHealth.OVER_VOLTAGE -> R.string.mon_health_over_voltage
        BatteryHealth.COLD -> R.string.mon_health_cold
        BatteryHealth.FAILURE -> R.string.mon_health_failure
        else -> R.string.mon_battery_unknown
    },
)
