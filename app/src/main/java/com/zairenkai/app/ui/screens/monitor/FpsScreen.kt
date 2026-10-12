// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * FPS recorder: the user picks games/apps, the background recorder saves a
 * session each time one of them is played, and every session opens a full
 * analysis.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens.monitor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.data.fps.FpsBlock
import com.zairenkai.app.data.fps.FpsMonitorService
import com.zairenkai.app.data.fps.FpsRecording
import com.zairenkai.app.ui.FpsViewModel
import com.zairenkai.app.ui.InstalledApp
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.AppIcon
import com.zairenkai.app.ui.components.Sparkline
import com.zairenkai.app.ui.components.formatClock
import com.zairenkai.app.ui.components.formatDuration
import com.zairenkai.app.ui.icons.*
import com.zairenkai.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsScreen(factory: VmFactory, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val vm: FpsViewModel = viewModel(factory = factory)
    val live by vm.live.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val watch by vm.watch.collectAsState()
    val apps by vm.apps.collectAsState()
    val context = LocalContext.current
    var picker by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadApps() }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        FpsMonitorService.start(context)
    }
    val labelOf: (String) -> String = { pkg -> apps?.firstOrNull { it.pkg == pkg }?.label ?: pkg }

    MonitorDetailScaffold(
        stringResource(R.string.fps_screen_title),
        onBack,
        actions = {
            if (!sessions.isNullOrEmpty()) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.fps_clear_all))
                }
            }
        },
    ) {
        item {
            MonitorCard(shape = HeroShape, color = MaterialTheme.colorScheme.primaryContainer, padding = 20.dp) {
                val on = MaterialTheme.colorScheme.onPrimaryContainer
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.fps_auto_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = on)
                        Text(
                            stringResource(
                                when {
                                    !live.running -> R.string.fps_auto_off
                                    watch.isEmpty() -> R.string.fps_auto_no_apps
                                    live.blocked == FpsBlock.NO_ROOT -> R.string.fps_auto_blocked_root
                                    live.blocked == FpsBlock.ENGINE_MISSING -> R.string.fps_auto_blocked_engine
                                    live.blocked == FpsBlock.STREAM_FAILED -> R.string.fps_auto_blocked_stream
                                    !live.streaming -> R.string.fps_auto_connecting
                                    live.recording != null -> R.string.fps_auto_recording
                                    else -> R.string.fps_auto_waiting
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = on.copy(alpha = 0.8f),
                        )
                    }
                    Switch(
                        checked = live.running,
                        onCheckedChange = { want ->
                            if (!want) {
                                FpsMonitorService.stop(context)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                FpsMonitorService.start(context)
                            }
                        },
                    )
                }
                if (live.recording != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(live.recording!!, size = 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                live.recordingLabel ?: live.recording!!,
                                style = MaterialTheme.typography.titleSmall,
                                color = on,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                pluralStringResource(R.plurals.fps_frames_recorded, live.frames, live.frames),
                                style = MaterialTheme.typography.labelSmall,
                                color = on.copy(alpha = 0.75f),
                            )
                        }
                        BigValue(live.fps?.toString() ?: "—", "FPS", on, MaterialTheme.typography.headlineLarge)
                    }
                }
            }
        }

        item {
            MonitorCard {
                CardHeader(
                    Icons.Rounded.SportsEsports,
                    ZkFps,
                    stringResource(R.string.fps_watch_title),
                    stringResource(R.string.fps_watch_sub),
                ) {
                    FilledTonalButton(onClick = { picker = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.fps_watch_add))
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (watch.isEmpty()) {
                    Text(
                        stringResource(R.string.fps_watch_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    watch.sortedBy { labelOf(it).lowercase() }.forEach { pkg ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppIcon(pkg, size = 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(labelOf(pkg), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val n = sessions?.count { it.pkg == pkg } ?: 0
                                Text(
                                    pluralStringResource(R.plurals.mon_fps_sessions, n, n),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.setWatched(pkg, false) }) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.fps_watch_remove))
                            }
                        }
                    }
                }
            }
        }

        val list = sessions.orEmpty()
        if (list.isNotEmpty()) {
            item { Overview(list) }
            item {
                Text(
                    stringResource(R.string.fps_sessions_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
            items(list, key = { it.id }) { rec -> SessionRow(rec) { onOpenSession(rec.id) } }
        } else if (sessions != null) {
            item {
                MonitorCard(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    CardHeader(Icons.Rounded.Insights, ZkFps, stringResource(R.string.fps_sessions_empty_title))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.fps_sessions_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (picker) {
        AppPicker(
            apps = apps,
            watch = watch,
            onToggle = vm::setWatched,
            onDismiss = { picker = false },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.fps_clear_all)) },
            text = { Text(stringResource(R.string.fps_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); confirmClear = false }) { Text(stringResource(R.string.fps_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Overview(list: List<FpsRecording>) {
    val active = list.sumOf { it.stats.activeMs }
    val avg = if (active > 0) list.sumOf { it.stats.avgFps.toDouble() * it.stats.activeMs } / active else null
    val stab = list.map { it.stats.stabilityPct }.average()
    MonitorCard {
        CardHeader(Icons.Rounded.BarChart, ZkFps, stringResource(R.string.fps_overview_title))
        Spacer(Modifier.height(12.dp))
        StatGrid(
            listOf(
                Triple(stringResource(R.string.fps_overview_sessions), list.size.toString(), null),
                Triple(stringResource(R.string.fps_overview_time), formatDuration(active), null),
                Triple(stringResource(R.string.fps_stat_avg), avg?.let { "%.0f".format(it) } ?: "—", ZkFps),
                Triple(stringResource(R.string.fps_stat_stability), "%.0f%%".format(stab), stabilityColor(stab.toFloat())),
            ),
        )
    }
}

@Composable
private fun SessionRow(rec: FpsRecording, onClick: () -> Unit) {
    MonitorCard(onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(rec.pkg, size = 42.dp)
            Column(Modifier.weight(1f)) {
                Text(rec.label.ifBlank { rec.pkg }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatClock(rec.startedAtMs)} · ${formatDuration(rec.stats.activeMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("%.0f".format(rec.stats.avgFps), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = ZkFps)
                Text(stringResource(R.string.fps_avg_short), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        val top = maxOf(rec.refreshHz, rec.stats.maxFps, 1f)
        Sparkline(rec.seconds.map { it.fps }, ZkFps, Modifier.fillMaxWidth().height(36.dp), maxV = top * 1.05f)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.fps_pill_low1, "%.0f".format(rec.stats.low1Fps)), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface)
            Pill(
                stringResource(R.string.fps_pill_stability, "%.0f".format(rec.stats.stabilityPct)),
                stabilityColor(rec.stats.stabilityPct).copy(alpha = 0.16f),
                MaterialTheme.colorScheme.onSurface,
            )
            if (rec.stats.stutterFrames > 0) {
                Pill(
                    pluralStringResource(R.plurals.fps_pill_stutters, rec.stats.stutterFrames, rec.stats.stutterFrames),
                    ZkFpsDrop.copy(alpha = 0.14f),
                    MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(
    apps: List<InstalledApp>?,
    watch: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(stringResource(R.string.fps_picker_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(stringResource(R.string.fps_picker_search)) },
                shape = MaterialTheme.shapes.extraLarge,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (apps == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                .sortedByDescending { it.pkg in watch }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.pkg }) { app ->
                    val checked = app.pkg in watch
                    ListItem(
                        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(app.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                        leadingContent = { AppIcon(app.pkg, size = 40.dp) },
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle(app.pkg, it) }) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

internal fun stabilityColor(pct: Float) = when {
    pct >= 85f -> ZkGood
    pct >= 65f -> ZkWarn
    else -> ZkBad
}
