// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Home (Beranda), with the same character as setup: the brand backdrop, a
 * greeting that types itself, a gradient one-line summary and explicit
 * component states. System health stays near the top; performance controls
 * are grouped together, followed by device/framework facts. Wide layouts use
 * a balanced two-column performance row; narrow windows stay single-column.
 *
 * Stateless: [HomeScreen] renders an [OverviewState]; the ViewModel drives it
 * in the app and the tests drive it directly. Only fields that were reported
 * are shown; a result that did not come back `ok` never shows its defaults,
 * and a card whose source is known to be missing is left out.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.home

import com.zairenkai.app.ui.icons.ZkIcons
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.R
import com.zairenkai.app.core.text.asString
import com.zairenkai.app.data.BoostStatus
import com.zairenkai.app.data.ThermalStatus
import com.zairenkai.app.data.ZperfStatus
import com.zairenkai.app.domain.Profiles
import com.zairenkai.app.ui.OverviewState
import com.zairenkai.app.ui.holdMessage
import com.zairenkai.app.ui.components.SpecTile
import com.zairenkai.app.ui.components.ZkCard
import com.zairenkai.app.ui.onboarding.BrandBackdrop
import com.zairenkai.app.ui.onboarding.CheckId
import com.zairenkai.app.ui.onboarding.CheckRow
import com.zairenkai.app.ui.onboarding.CheckState
import com.zairenkai.app.ui.onboarding.SetupAnimation
import com.zairenkai.app.ui.onboarding.SetupCheck
import com.zairenkai.app.ui.onboarding.TypewriterLine
import com.zairenkai.app.ui.onboarding.brandGradient
import com.zairenkai.app.ui.onboarding.diagnosticsReport
import com.zairenkai.app.ui.onboarding.isDarkSurface
import com.zairenkai.app.ui.onboarding.reveal
import com.zairenkai.app.ui.onboarding.settled
import com.zairenkai.app.ui.theme.HomeGreetingStyle
import com.zairenkai.app.ui.theme.ZkBad
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkWarn
import com.zairenkai.app.ui.theme.statusGraphic
import com.zairenkai.app.ui.theme.statusInk
import java.util.Calendar

@Composable
fun HomeScreen(
    s: OverviewState,
    onRetry: () -> Unit,
    onResetBoosts: () -> Unit,
    onExitSafeMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    hour: Int = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
) {
    val checks = s.checks
    val summary = homeSummary(s.probe.root, checks)
    val info = s.info
    val systemSettled = s.rootAvailable && !s.loading
    val safeModeActive = systemSettled && s.safeMode
    val apiOutdated = systemSettled && (info?.api?.outdated == true || info?.licenseState == "api_outdated")
    val ready = !s.loading && summary == HomeSummary.Ready && !safeModeActive && !apiOutdated
    val notInstalled = checks.settled() && checks.firstOrNull { it.id == CheckId.ZKFC }?.state == CheckState.MISSING

    BrandBackdrop(arcY = { -0.34f }, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val dashboardMaxWidth = this.maxWidth
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    )
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TopRow(onOpenSettings)
                Hero(hour, summary, checks, safeMode = safeModeActive, apiOutdated = apiOutdated)

                // Surface urgent states before the dashboard's normal status and metrics.
                if (safeModeActive) {
                    Banner(
                        stringResource(R.string.dashboard_safe_mode_title),
                        stringResource(R.string.dashboard_safe_mode_body),
                        action = stringResource(R.string.dashboard_safe_mode_exit) to onExitSafeMode,
                        modifier = Modifier.reveal(1),
                    )
                }
                if (apiOutdated) {
                    Banner(
                        stringResource(R.string.dashboard_api_outdated_title),
                        stringResource(R.string.dashboard_api_outdated_body),
                        modifier = Modifier.reveal(2),
                    )
                }

                // Keep health at the top in both states. In the ready state it is compact;
                // warning conditions expand it, so a green summary never hides safe mode/API issues.
                StatusCard(
                    s,
                    compact = ready,
                    onRetry = onRetry,
                    modifier = Modifier.reveal(3),
                )

                // Not installed: the ZKFC framework is absent once the checks settle.
                if (notInstalled) InstallCard(Modifier.reveal(4))

                if (s.rootAvailable && !s.loading) {
                    val engineStatus = s.probe.engineStatus?.takeIf { it.ok }
                    val showThermal = s.probe.engineInstalled != false
                    val showBoost = s.probe.info?.ok == true
                    val showPerformance = engineStatus != null || showThermal || showBoost

                    if (showPerformance) {
                        SectionHeading(
                            title = stringResource(R.string.home_section_performance_title),
                            subtitle = stringResource(R.string.home_section_performance_subtitle),
                            modifier = Modifier.reveal(5),
                        )
                    }

                    // Use a balanced two-column layout on wide displays; preserve the single-column
                    // reading order on phones and narrow split-screen windows.
                    val orderStart = 6
                    if (dashboardMaxWidth >= 760.dp && engineStatus != null && showThermal) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            ModeCard(engineStatus, onOpenProfiles, Modifier.weight(1f).reveal(orderStart))
                            ThermalCard(s.thermal, Modifier.weight(1f).reveal(orderStart + 1))
                        }
                    } else {
                        engineStatus?.let { ModeCard(it, onOpenProfiles, Modifier.reveal(orderStart)) }
                        if (showThermal) ThermalCard(s.thermal, Modifier.reveal(orderStart + 1))
                    }
                    if (showBoost) BoostCard(s.boost, onResetBoosts, Modifier.reveal(orderStart + 2))

                    SectionHeading(
                        title = stringResource(R.string.home_section_device_title),
                        subtitle = stringResource(R.string.home_section_device_subtitle),
                        modifier = Modifier.reveal(orderStart + 3),
                    )
                    DeviceCard(s, Modifier.reveal(orderStart + 4))
                }
            }
        }
    }
}

/* ---------------- hero ---------------- */


@Composable
private fun SectionHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TopRow(onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SetupAnimation(R.raw.setup_welcome, loop = true, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSettings) {
            Icon(ZkIcons.Settings, stringResource(R.string.dashboard_settings), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Hero(
    hour: Int,
    summary: HomeSummary,
    checks: List<SetupCheck>,
    safeMode: Boolean,
    apiOutdated: Boolean,
) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TypewriterLine(stringResource(dayPart(hour).greeting), HomeGreetingStyle, caretWhenDone = false)
        val text = when {
            safeMode -> stringResource(R.string.home_summary_safe_mode)
            apiOutdated -> stringResource(R.string.home_summary_api_outdated)
            else -> when (summary) {
                HomeSummary.Checking -> stringResource(R.string.home_summary_checking)
                HomeSummary.NoRoot -> stringResource(R.string.home_summary_no_root)
                HomeSummary.Ready -> stringResource(R.string.home_summary_ready)
                is HomeSummary.Attention -> pluralStringResource(R.plurals.home_summary_attention, summary.count, summary.count)
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall.copy(brush = Brush.linearGradient(brandGradient(isDarkSurface()))),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.reveal(0).semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        ComponentStrip(checks, Modifier.reveal(0))
    }
}

/** Root, ZKFC, zperfd and token at a glance; the full reasons are in the status card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentStrip(checks: List<SetupCheck>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        checks.forEach { check ->
            val label = stringResource(
                when (check.id) {
                    CheckId.ROOT -> R.string.home_chip_root
                    CheckId.ZKFC -> R.string.home_chip_zkfc
                    CheckId.ENGINE -> R.string.home_chip_engine
                    CheckId.TOKEN -> R.string.home_chip_token
                },
            )
            val state = stringResource(
                when (check.state) {
                    CheckState.CHECKING -> R.string.setup_state_checking
                    CheckState.OK -> R.string.setup_state_ok
                    CheckState.WARN -> R.string.setup_state_warn
                    CheckState.MISSING -> R.string.setup_state_missing
                },
            )
            val a11y = stringResource(R.string.home_chip_a11y, stringResource(check.id.title), state)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tone = checkTone(check.state)
                    if (tone == null) {
                        CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                    } else {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(toneGraphic(tone)))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        state,
                        style = MaterialTheme.typography.labelSmall,
                        color = toneInk(tone ?: Tone.NEUTRAL),
                    )
                }
            }
        }
    }
}

/* ---------------- cards ---------------- */

@Composable
private fun Banner(title: String, body: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cs.errorContainer,
        contentColor = cs.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = if (action == null) 16.dp else 6.dp)) {
            Icon(ZkIcons.Warning, null, Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
                action?.let { (label, onClick) ->
                    TextButton(
                        onClick = onClick,
                        colors = ButtonDefaults.textButtonColors(contentColor = cs.onErrorContainer),
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(label) }
                }
            }
        }
    }
}

/** The profile the engine runs, with the brand tune animation. */
@Composable
private fun ModeCard(status: ZperfStatus, onOpenProfiles: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tint = Brush.linearGradient(brandGradient(isDarkSurface()).map { it.copy(alpha = 0.12f) })
    val desired = Profiles.byMode(status.desired)?.let { stringResource(it.nameRes) } ?: status.desired
    val effective = Profiles.byMode(status.effective)?.let { stringResource(it.nameRes) } ?: status.effective
    ZkCard(modifier, tint = tint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.home_mode_label), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Text(desired, style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
                if (status.auto) {
                    Surface(shape = CircleShape, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                        Text(
                            stringResource(Profiles.AUTO.nameRes),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                if (status.hold == null && status.effective != status.desired) {
                    Text(stringResource(R.string.profiles_running_as, effective), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else if (status.hold == null && !status.saved) {
                    Text(stringResource(R.string.home_mode_default), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            SetupAnimation(R.raw.setup_tune, loop = true, modifier = Modifier.size(88.dp))
        }
        holdMessage(status.hold, null)?.let { hold ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(ZkIcons.Warning, null, Modifier.size(18.dp), tint = statusInk(ZkWarn))
                Spacer(Modifier.width(8.dp))
                Text(hold.asString(), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            }
        }
        FilledTonalButton(onClick = onOpenProfiles) { Text(stringResource(R.string.home_mode_change)) }
    }
}

@Composable
private fun ThermalCard(t: ThermalStatus?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    ZkCard(modifier, title = stringResource(R.string.home_thermal_title)) {
        if (t == null) {
            Text(stringResource(R.string.dashboard_thermal_no_evidence), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            return@ZkCard
        }
        val tone = bandTone(t.band)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardMaxWidth = this.maxWidth
            val gaugeSize = if (cardMaxWidth < 320.dp) 104.dp else 128.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeadroomGauge(t.headroomFraction, toneGraphic(tone), Modifier.size(gaugeSize))
                Spacer(Modifier.width(if (cardMaxWidth < 320.dp) 12.dp else 18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonePill(bandLabel(t.band)?.let { stringResource(it) } ?: t.band, tone)
                    t.temperatureCelsius?.let { Fact(stringResource(R.string.home_thermal_hottest), "%.1f °C".format(it)) }
                    t.controlZone?.takeIf { it.isNotBlank() }?.let { Fact(stringResource(R.string.dashboard_control_zone), it) }
                    t.criticalTripMdeg?.let { Fact(stringResource(R.string.dashboard_critical_trip), "%.1f °C".format(it / 1000f)) }
                }
            }
        }
        t.reason.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** Thermal headroom as a 240° arc; the colour follows the engine's band. */
@Composable
private fun HeadroomGauge(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shown by animateFloatAsState(fraction ?: 0f, tween(900), label = "headroom")
    val value = fraction?.let { "${(it * 100).toInt()}%" } ?: "—"
    val a11y = stringResource(R.string.home_thermal_headroom_a11y, value)
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = a11y }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(cs.surfaceContainerHighest, 150f, 240f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction != null && shown > 0f) {
                drawArc(color, 150f, 240f * shown, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            Text(stringResource(R.string.home_thermal_headroom), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BoostCard(b: BoostStatus?, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    ZkCard(modifier, title = stringResource(R.string.dashboard_active_performance)) {
        if (b == null) {
            Text(stringResource(R.string.dashboard_boost_unavailable), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            return@ZkCard
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 320.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MiniStat(
                            stringResource(R.string.dashboard_input_boost),
                            stringResource(if (b.inputBoostActive) R.string.state_active else R.string.dashboard_idle),
                            if (b.inputBoostActive) statusInk(ZkGood) else cs.onSurface,
                            Modifier.weight(1f),
                        )
                        MiniStat(
                            stringResource(R.string.dashboard_boosted_tasks),
                            b.boostedTasks.toString(), cs.onSurface, Modifier.weight(1f),
                        )
                    }
                    MiniStat(
                        stringResource(R.string.dashboard_cpufreq_requests),
                        b.cpufreqRequests.toString(), cs.onSurface, Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniStat(
                        stringResource(R.string.dashboard_input_boost),
                        stringResource(if (b.inputBoostActive) R.string.state_active else R.string.dashboard_idle),
                        if (b.inputBoostActive) statusInk(ZkGood) else cs.onSurface,
                        Modifier.weight(1f),
                    )
                    MiniStat(
                        stringResource(R.string.dashboard_boosted_tasks),
                        b.boostedTasks.toString(), cs.onSurface, Modifier.weight(1f),
                    )
                    MiniStat(
                        stringResource(R.string.dashboard_cpufreq_requests),
                        b.cpufreqRequests.toString(), cs.onSurface, Modifier.weight(1f),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dashboard_thermal), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            TonePill(
                stringResource(if (b.thermalTripped) R.string.dashboard_thermal_mitigating else R.string.dashboard_thermal_normal),
                if (b.thermalTripped) Tone.WARN else Tone.GOOD,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) { Text(stringResource(R.string.dashboard_reset_boosts)) }
        }
    }
}

@Composable
private fun DeviceCard(s: OverviewState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val info = s.info
    val lic = s.license
    val facts = s.probe.facts
    ZkCard(modifier, title = stringResource(R.string.dashboard_device)) {
        val cells = buildList {
            add(Triple(stringResource(R.string.dashboard_zkfc_status),
                stringResource(if (info != null) R.string.state_active else R.string.dashboard_not_available),
                statusInk(if (info != null) ZkGood else ZkBad)))
            add(Triple(stringResource(R.string.dashboard_license),
                lic?.let { licenseLabel(it.state) } ?: stringResource(R.string.dashboard_needs_zkfc),
                lic?.let { statusInk(licenseColor(it.state)) } ?: cs.onSurfaceVariant))
            (info?.arch?.takeIf { it.isNotBlank() && it != "unknown" } ?: facts?.abi?.takeIf { it.isNotBlank() })
                ?.let { add(Triple(stringResource(R.string.dashboard_arch), it, cs.onSurface)) }
            info?.kernelType?.takeIf { it.isNotBlank() }?.let { add(Triple(stringResource(R.string.dashboard_kernel_type), it.uppercase(), cs.onSurface)) }
            if (info != null) {
                add(Triple(stringResource(R.string.dashboard_hook_mode), info.hookMode, cs.onSurface))
                add(Triple(stringResource(R.string.dashboard_zkfc_api), "${info.api.major}.${info.api.minor}.${info.api.patch}", cs.onSurface))
            }
        }
        cells.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, color) -> SpecTile(label, value, Modifier.weight(1f), valueColor = color) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        lic?.token?.licensee?.takeIf { it.isNotBlank() }
            ?.let { SpecTile(stringResource(R.string.home_licensee), it, Modifier.fillMaxWidth()) }
        (info?.kernelRelease?.takeIf { it.isNotBlank() } ?: facts?.kernel?.takeIf { it.isNotBlank() })
            ?.let { SpecTile(stringResource(R.string.dashboard_kernel), it, Modifier.fillMaxWidth()) }
        info?.buildId?.takeIf { it.isNotBlank() }?.let { SpecTile(stringResource(R.string.dashboard_build), it, Modifier.fillMaxWidth()) }
        if (info == null) {
            Text(stringResource(R.string.dashboard_zkfc_fields_unavailable), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * The system check. Full (every row with its reason and next step) while
 * something needs attention; once all is ready, one line with Retry. Raw
 * probe output stays in the technical details and the copyable report.
 */
@Composable
private fun StatusCard(s: OverviewState, compact: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val checks = s.checks
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val retryEnabled = checks.settled() || !s.loading
    val retry = { copied = false; onRetry() }
    ZkCard(modifier.animateContentSize(), title = if (compact) null else stringResource(R.string.dashboard_system_status)) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ZkIcons.CheckCircle, null, Modifier.size(22.dp), tint = statusInk(ZkGood))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.setup_check_all_ok), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                IconButton(onClick = retry, enabled = retryEnabled) {
                    Icon(ZkIcons.Refresh, stringResource(R.string.setup_recheck), tint = cs.onSurfaceVariant)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { checks.forEach { CheckRow(it) } }
            val zkfcMissing = checks.firstOrNull { it.id == CheckId.ZKFC }?.state == CheckState.MISSING
            if (checks.settled() && zkfcMissing) {
                Text(stringResource(R.string.dashboard_without_module), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) {
                FilledTonalButton(onClick = retry, enabled = retryEnabled) {
                    Icon(ZkIcons.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.setup_recheck))
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.dashboard_details_hide else R.string.dashboard_details_show))
            }
        }
        if (expanded) {
            val report = diagnosticsReport(s.probe, BuildConfig.VERSION_NAME)
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh, contentColor = cs.onSurface) {
                Text(
                    report,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Zairenkai diagnostics", report))
                    copied = true
                }) {
                    Icon(ZkIcons.Copy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dashboard_copy_diagnostics))
                }
                if (copied) {
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.info_copied), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                }
            }
        }
    }
}

/* ---------------- small pieces ---------------- */

@Composable
private fun toneInk(tone: Tone): Color = when (tone) {
    Tone.GOOD -> statusInk(ZkGood)
    Tone.WARN -> statusInk(ZkWarn)
    Tone.BAD -> statusInk(ZkBad)
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun toneGraphic(tone: Tone): Color = when (tone) {
    Tone.GOOD -> statusGraphic(ZkGood)
    Tone.WARN -> statusGraphic(ZkWarn)
    Tone.BAD -> statusGraphic(ZkBad)
    Tone.NEUTRAL -> MaterialTheme.colorScheme.outline
}

@Composable
private fun TonePill(text: String, tone: Tone) {
    val ink = toneInk(tone)
    Surface(shape = CircleShape, color = ink.copy(alpha = 0.12f), contentColor = ink) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MiniStat(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2)
        }
    }
}

@Composable
private fun licenseLabel(state: String): String = when (state) {
    "valid" -> stringResource(R.string.dashboard_license_valid)
    "missing" -> stringResource(R.string.dashboard_license_missing)
    "api_outdated" -> stringResource(R.string.dashboard_license_api_outdated)
    "expired" -> stringResource(R.string.dashboard_license_expired)
    "no_owner_key" -> stringResource(R.string.dashboard_license_no_owner_key)
    "revoked" -> stringResource(R.string.dashboard_license_revoked)
    "subkey_scope" -> stringResource(R.string.dashboard_license_subkey_scope)
    else -> state
}

private fun licenseColor(state: String) = when (state) {
    "valid" -> ZkGood
    "missing", "no_owner_key" -> ZkWarn
    else -> ZkBad
}
