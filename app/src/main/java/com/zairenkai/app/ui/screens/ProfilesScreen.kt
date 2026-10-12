// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Profiles: the engine's modes, one tap each, plus Auto mode.
 *
 * The active profile, the effective mode and any baseline hold come from
 * `zperfd status`; each card describes what the device profile requests
 * (`zperfd modes`). Local settings are only shown, marked unverified, when
 * the engine cannot be reached.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.zairenkai.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.domain.Profile
import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.domain.Profiles
import com.zairenkai.app.ui.ProfilesViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.holdMessage
import com.zairenkai.app.ui.modeSummaryLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(factory: VmFactory) {
    val vm: ProfilesViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    val busy = ui.applying != null
    val status = ui.status

    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("Profil") }) }) { inner ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ui.engineAvailable) {
                val cached = ui.cachedProfile?.let { Profiles.byId(it).name }
                ProfileBanner(
                    "zperfd tidak aktif — status profil tidak dapat diverifikasi." +
                        (cached?.let { "\nTerakhir dipilih di aplikasi: $it (tidak terverifikasi)." } ?: ""),
                    MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                    action = "Muat ulang" to fun() { vm.refresh() },
                )
            }
            holdMessage(status?.hold, ui.selected?.name)?.let { message ->
                val confirm = status?.revalidationRequired == true && ui.selected != null
                ProfileBanner(
                    message,
                    MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                    action = if (confirm) "Konfirmasi & terapkan ${ui.selected?.name}" to vm::confirmSaved else null,
                    enabled = !busy,
                )
            }
            ui.error?.let {
                ProfileBanner(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                    action = "Tutup" to vm::dismissMessages)
            }
            ui.notice?.let {
                ProfileBanner(it, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                    action = "Tutup" to vm::dismissMessages)
            }
            if (status != null && status.hold == null) {
                EngineStateRow(selected = ui.selected, desired = status.desired, effective = status.effective)
            }

            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoMode, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Profiles.AUTO.name, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(Profiles.AUTO.tagline, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    if (ui.applying == ProfileId.AUTO) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(checked = ui.auto, onCheckedChange = { vm.setAuto(it) },
                            enabled = ui.engineAvailable && !busy)
                    }
                }
            }

            Profiles.all.filter { it.id != ProfileId.AUTO }.forEach { p ->
                val unsupported = ui.unsupported(p)
                ProfileCard(
                    profile = p,
                    summary = ui.modes[p.mode]?.let(::modeSummaryLine),
                    active = ui.selected?.id == p.id,
                    applying = ui.applying == p.id,
                    unsupported = unsupported,
                    enabled = ui.engineAvailable && !busy && !unsupported,
                    onApply = { vm.apply(p) },
                )
            }
            Text(
                "Nilai di atas adalah permintaan profil perangkat; frekuensi diselaraskan ke tabel OPP " +
                    "dan dapat dikurangi oleh envelope termal saat diterapkan.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EngineStateRow(selected: Profile?, desired: String, effective: String) {
    val selectedText = selected?.name ?: "Belum dipilih (default engine: $desired)"
    val effectiveProfile = Profiles.byMode(effective)?.name ?: effective
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Dipilih: $selectedText", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (effective != desired) {
                Text("Berjalan sebagai: $effectiveProfile (dipilih oleh Otomatis/aturan per-aplikasi)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileBanner(
    message: String,
    container: Color,
    content: Color,
    action: Pair<String, () -> Unit>? = null,
    enabled: Boolean = true,
) {
    Surface(color = container, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = content)
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                    Text(label, color = content)
                }
            } ?: Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    summary: String?,
    active: Boolean,
    applying: Boolean,
    unsupported: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    val container = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        modifier = Modifier.semantics {
            contentDescription = profile.name + (if (active) ", aktif" else "") +
                (if (unsupported) ", tidak tersedia di perangkat ini" else "")
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(profile.icon), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill("Aktif", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Text(profile.tagline, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    unsupported -> Text("Tidak tersedia di profil perangkat ini (mode ${profile.mode}).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    summary != null -> Text(summary, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            if (applying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else FilledTonalButton(onClick = onApply, enabled = enabled) { Text("Terapkan") }
        }
    }
}

private fun iconFor(key: String): ImageVector = when (key) {
    "sports_esports" -> Icons.Rounded.SportsEsports
    "speed" -> Icons.Rounded.Speed
    "battery_saver" -> Icons.Rounded.BatterySaver
    "balance" -> Icons.Rounded.Balance
    else -> Icons.Rounded.AutoMode
}
