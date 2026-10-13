// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * RAM and ZRAM detail. A stacked breakdown of physical memory (used, cache,
 * buffers, free), the ZRAM compression figures, and any swap areas — all read
 * from the kernel's own files by MemoryDetailReader. Reads refresh on a slow
 * interval while the screen is visible.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens.monitor

import android.text.format.Formatter
import com.zairenkai.app.ui.icons.ZkIcons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import com.zairenkai.app.R
import com.zairenkai.app.data.monitor.MemInfo
import com.zairenkai.app.data.monitor.MemoryDetail
import com.zairenkai.app.data.monitor.MemoryDetailReader
import com.zairenkai.app.data.monitor.ZramInfo
import com.zairenkai.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val detail by produceState<MemoryDetail?>(initialValue = null) {
        while (true) {
            value = MemoryDetailReader.read()
            delay(2500)
        }
    }
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)

    MonitorDetailScaffold(stringResource(R.string.mon_mem_title), onBack) {
        val mem = detail?.mem
        item {
            MonitorCard(shape = HeroShape, color = ZkIndigo.copy(alpha = 0.12f).let { it.compositeOverCard() }) {
                if (mem == null) {
                    ChartPlaceholder(stringResource(R.string.monitor_waiting_first_sample), Modifier.fillMaxWidth().height(120.dp))
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        BigValue("${mem.usedPct}", "%", loadColor(mem.usedPct), MaterialTheme.typography.displayLarge)
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(size(mem.usedB), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.mon_mem_ram_used, size(mem.usedB), size(mem.totalB)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    MemoryBar(mem)
                    Spacer(Modifier.height(14.dp))
                    FlowLegend(
                        listOf(
                            Triple(stringResource(R.string.mon_mem_used), size(mem.usedB), ZkCpu),
                            Triple(stringResource(R.string.mon_mem_cached), size(mem.cacheB), ZkAqua),
                            Triple(stringResource(R.string.mon_mem_buffers), size(mem.buffersB), ZkGpu),
                            Triple(stringResource(R.string.mon_mem_free), size(mem.freeB), MaterialTheme.colorScheme.outlineVariant),
                        ),
                    )
                }
            }
        }

        if (mem != null) {
            item {
                MonitorCard {
                    CardHeader(ZkIcons.Storage, ZkIndigo, stringResource(R.string.mon_mem_ram_title))
                    Spacer(Modifier.height(12.dp))
                    StatGrid(
                        listOf(
                            Triple(stringResource(R.string.mon_mem_total), size(mem.totalB), null),
                            Triple(stringResource(R.string.mon_mem_available), mem.availableB?.let { size(it) } ?: "—", ZkGood),
                            Triple(stringResource(R.string.mon_mem_cached), size(mem.cacheB), ZkAqua),
                            Triple(stringResource(R.string.mon_mem_buffers), size(mem.buffersB), ZkGpu),
                        ),
                    )
                }
            }

            item { ZramCard(detail?.zram, ::size) }

            detail?.swaps?.filter { !it.isZram }?.takeIf { it.isNotEmpty() }?.let { swaps ->
                item {
                    MonitorCard {
                        CardHeader(ZkIcons.Storage, ZkGpu, stringResource(R.string.mon_swap_title))
                        Spacer(Modifier.height(10.dp))
                        swaps.forEach { InfoLine(it.name.substringAfterLast('/'), stringResource(R.string.mon_swap_row, size(it.usedB), size(it.sizeB))) }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.mon_mem_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ZramCard(zram: ZramInfo?, size: (Long) -> String) {
    MonitorCard {
        CardHeader(ZkIcons.Memory, ZkAqua, stringResource(R.string.mon_zram_title), stringResource(R.string.mon_zram_sub))
        Spacer(Modifier.height(12.dp))
        if (zram == null) {
            Text(stringResource(R.string.mon_zram_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@MonitorCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BigValue(stringResource(R.string.mon_zram_ratio_value, zram.ratio), null, ZkGood, MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.mon_zram_ratio), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        StatGrid(
            listOf(
                Triple(stringResource(R.string.mon_zram_stored), size(zram.origDataB), null),
                Triple(stringResource(R.string.mon_zram_compressed), size(zram.comprDataB), ZkAqua),
                Triple(stringResource(R.string.mon_zram_total), size(zram.memUsedTotalB), null),
                Triple(stringResource(R.string.mon_zram_saved), size(zram.savedB), ZkGood),
            ),
        )
        Spacer(Modifier.height(10.dp))
        InfoLine(stringResource(R.string.mon_zram_disksize), size(zram.diskSizeB))
    }
}

/** A single stacked bar: used | cache | buffers | free, in proportion to total. */
@Composable
private fun MemoryBar(mem: MemInfo) {
    val total = mem.totalB.toFloat().coerceAtLeast(1f)
    val segments = listOf(
        mem.usedB / total to ZkCpu,
        mem.cacheB / total to ZkAqua,
        mem.buffersB / total to ZkGpu,
    )
    Row(
        Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        segments.forEach { (frac, color) ->
            if (frac > 0f) Box(Modifier.fillMaxHeight().weight(frac.coerceIn(0.0001f, 1f)).background(color))
        }
        // remainder (free) left as the track colour
        val filled = segments.sumOf { it.first.toDouble() }.toFloat().coerceIn(0f, 1f)
        if (filled < 1f) Spacer(Modifier.weight(1f - filled))
    }
}

@Composable
private fun FlowLegend(items: List<Triple<String, String, Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, value, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Color.compositeOverCard(): Color =
    compositeOver(MaterialTheme.colorScheme.surfaceContainer)

private fun loadColor(pct: Int): Color = when {
    pct >= 90 -> ZkBad
    pct >= 75 -> ZkWarn
    else -> ZkIndigo
}
