// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Usage history ("Riwayat Penggunaan"): list of recorded discharge/usage
 * sessions plus a background-recording toggle. Tapping a session opens the
 * power-statistics detail.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.data.UsageSession
import com.zairenkai.app.data.ZkMonitorService
import com.zairenkai.app.ui.UsageHistoryViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.*
import com.zairenkai.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageHistoryScreen(factory: VmFactory, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val vm: UsageHistoryViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    val recording by ZkMonitorService.running.collectAsState()
    val context = LocalContext.current

    // Reload when returning to this screen.
    LaunchedEffect(recording) { vm.load() }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ZkMonitorService.start(context) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Riwayat Penggunaan") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ChevronLeft, "Kembali") }
                },
                actions = {
                    if (ui.sessions.isNotEmpty()) {
                        IconButton(onClick = { vm.clearAll() }) {
                            Icon(Icons.Rounded.DeleteSweep, "Hapus semua")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item {
                SectionCard(if (recording) "Merekam…" else "Perekaman latar belakang") {
                    Text(
                        if (recording) {
                            "Zairenkai sedang merekam daya & aplikasi yang aktif. Hentikan untuk menyimpan sesi."
                        } else {
                            "Rekam riwayat daya dan penggunaan aplikasi di latar belakang, lalu tinjau per sesi."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (recording) {
                        Button(
                            onClick = { ZkMonitorService.stop(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = ZkBad),
                        ) {
                            Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Hentikan & simpan")
                        }
                    } else {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                ZkMonitorService.start(context)
                            }
                        }) {
                            Icon(Icons.Rounded.FiberManualRecord, null, tint = ZkBad)
                            Spacer(Modifier.width(8.dp)); Text("Mulai merekam")
                        }
                    }
                    if (!ui.hasUsageAccess) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Beri izin \"Akses penggunaan\" agar aplikasi aktif terdeteksi akurat " +
                                "(tanpa itu, deteksi memakai root bila tersedia).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("Buka pengaturan akses") }
                    }
                }
            }

            if (ui.sessions.isEmpty() && !ui.loading) {
                item {
                    Text(
                        "Belum ada sesi. Mulai merekam untuk melihat riwayat di sini.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            items(ui.sessions, key = { it.id }) { s ->
                SessionCard(s) { onOpenSession(s.id) }
            }
        }
    }
}

@Composable
private fun SessionCard(s: UsageSession, onClick: () -> Unit) {
    SectionCardClickable(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${formatClock(s.startedAtMs)} → ${formatClock(s.endedAtMs)}",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${s.startLevelPct}% → ${s.endLevelPct}%  •  turun ${s.levelDropPct}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("%.2f W".format(s.avgPowerW), style = MaterialTheme.typography.titleMedium,
                    color = ZkGpu, fontWeight = FontWeight.Bold)
                Text(formatDuration(s.usedMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (s.curve.size >= 2) {
            Spacer(Modifier.height(10.dp))
            LineChart(
                s.curve.map { it.levelPct.coerceAtLeast(0).toFloat() },
                ZkGood, Modifier.fillMaxWidth().height(44.dp), minV = 0f, maxV = 100f,
            )
        }
    }
}
