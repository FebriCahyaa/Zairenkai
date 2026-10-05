// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Tweaks: grouped device optimizations with inline editors.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.data.Tweak
import com.zairenkai.app.ui.TweaksViewModel
import com.zairenkai.app.ui.VmFactory
import kotlin.math.roundToInt

private val governorPresets = listOf("performance", "schedutil", "powersave", "ondemand", "conservative")
private val ioPresets = listOf("none", "mq-deadline", "kyber", "bfq")
private val tcpPresets = listOf("bbr", "cubic", "westwood", "reno")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweaksScreen(factory: VmFactory) {
    val vm: TweaksViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    var editing by remember { mutableStateOf<Tweak?>(null) }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = { TopAppBar(title = { Text("Tweaks") }) }) { inner ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val available = ui.tweaks.filter { it.available }
        val grouped = available.groupBy { it.category }
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.lite) item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                    Text("Mode Lite aktif — sebagian tweak berat disembunyikan.",
                        Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (available.isEmpty()) item {
                Text("Tidak ada tunable yang terbaca pada perangkat ini.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            grouped.forEach { (cat, list) ->
                item {
                    Text(cat.uppercase(), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                }
                items(list, key = { it.id }) { t ->
                    TweakRow(t, busy = ui.busyId == t.id) { editing = t }
                }
            }
        }
    }

    editing?.let { t ->
        TweakEditor(
            tweak = t,
            onDismiss = { editing = null },
            onApply = { v -> vm.set(t.id, v); editing = null },
        )
    }
}

@Composable
private fun TweakRow(t: Tweak, busy: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(t.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(t.value ?: "—", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TweakEditor(tweak: Tweak, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val presets = when (tweak.id) {
        "cpu_governor", "gpu_governor" -> governorPresets
        "io_scheduler" -> ioPresets
        "tcp_congestion_control" -> tcpPresets
        else -> emptyList()
    }
    var text by remember { mutableStateOf(tweak.value ?: "") }
    val isRange = tweak.min != null && tweak.max != null && tweak.max > tweak.min
    var slider by remember { mutableFloatStateOf(text.toFloatOrNull() ?: (tweak.min?.toFloat() ?: 0f)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tweak.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isRange) {
                    Text("${slider.roundToInt()}", style = MaterialTheme.typography.headlineSmall)
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        valueRange = tweak.min!!.toFloat()..tweak.max!!.toFloat(),
                    )
                    Text("Rentang ${tweak.min}–${tweak.max}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text("Nilai") }, modifier = Modifier.fillMaxWidth())
                    if (presets.isNotEmpty()) {
                        FlowRowChips(presets) { text = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(if (isRange) slider.roundToInt().toString() else text.trim()) }) {
                Text("Terapkan")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(options: List<String>, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            AssistChip(onClick = { onPick(opt) }, label = { Text(opt) })
        }
    }
}
