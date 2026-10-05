// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Profiles: one-tap optimization presets + Auto mode.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.domain.Profile
import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.domain.Profiles
import com.zairenkai.app.ui.ProfilesViewModel
import com.zairenkai.app.ui.VmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(factory: VmFactory) {
    val vm: ProfilesViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Profil") }) }) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoMode, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Profil Otomatis", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Menyesuaikan profil dari kondisi baterai, suhu & pengisian daya.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Switch(checked = ui.auto, onCheckedChange = { vm.setAuto(it) })
                }
            }

            Profiles.all.filter { it.id != ProfileId.AUTO }.forEach { p ->
                ProfileCard(
                    profile = p,
                    active = ui.active == p.id && !ui.auto,
                    applying = ui.applying == p.id,
                    enabled = !ui.auto,
                    onApply = { vm.apply(p) },
                )
            }

            ui.lastReport?.let {
                Text("Terakhir diterapkan: ${it.applied}/${it.requested} tweak.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: Profile, active: Boolean, applying: Boolean, enabled: Boolean, onApply: () -> Unit) {
    val container = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Surface(shape = MaterialTheme.shapes.extraLarge, color = container) {
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
            }
            Spacer(Modifier.width(10.dp))
            if (applying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else FilledTonalButton(onClick = onApply, enabled = enabled) { Text("Terapkan") }
        }
    }
}

private fun iconFor(key: String): ImageVector = when (key) {
    "sports_esports" -> Icons.Rounded.SportsEsports
    "smartphone" -> Icons.Rounded.Smartphone
    "movie" -> Icons.Rounded.Movie
    "battery_saver" -> Icons.Rounded.BatterySaver
    "balance" -> Icons.Rounded.Balance
    else -> Icons.Rounded.AutoMode
}
