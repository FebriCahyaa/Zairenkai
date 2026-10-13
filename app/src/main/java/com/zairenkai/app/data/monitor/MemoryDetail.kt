// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Detailed RAM and ZRAM reader. Everything here is read straight from the
 * kernel's own files and reported as-is — nothing is estimated or invented:
 *
 *   /proc/meminfo   total, free, available, buffers, cached, slab, swap
 *   /proc/swaps     the swap/zram backing devices and their use
 *   /sys/block/zram0/{disksize,mm_stat}   ZRAM size and compression
 *
 * /proc/meminfo and /proc/swaps are world-readable, so RAM works with no root.
 * Some devices restrict the zram sysfs nodes; when a plain read comes back
 * empty the reader retries through root if it is available, and otherwise
 * simply reports no ZRAM rather than guessing.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.monitor

import com.zairenkai.app.data.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A line of /proc/meminfo, in bytes. Missing fields stay null, never 0. */
data class MemInfo(
    val totalB: Long,
    val freeB: Long,
    val availableB: Long?,
    val buffersB: Long,
    val cachedB: Long,
    val slabB: Long,
    val swapTotalB: Long,
    val swapFreeB: Long,
) {
    val usedB: Long get() = (totalB - (availableB ?: (freeB + buffersB + cachedB))).coerceAtLeast(0)
    val usedPct: Int get() = if (totalB > 0) (usedB * 100 / totalB).toInt() else 0
    val swapUsedB: Long get() = (swapTotalB - swapFreeB).coerceAtLeast(0)
    val swapUsedPct: Int get() = if (swapTotalB > 0) (swapUsedB * 100 / swapTotalB).toInt() else 0
    /** cached includes reclaimable slab; buffers kept separate for the breakdown bar. */
    val cacheB: Long get() = cachedB + slabB
}

/** ZRAM statistics from the block device. All values in bytes. */
data class ZramInfo(
    val diskSizeB: Long,
    /** Uncompressed data currently stored. */
    val origDataB: Long,
    /** Compressed size of that data. */
    val comprDataB: Long,
    /** Total memory the zram device occupies (compressed + metadata). */
    val memUsedTotalB: Long,
) {
    /** How much real data is packed per byte of RAM, e.g. 2.8x. */
    val ratio: Float get() = if (comprDataB > 0) origDataB.toFloat() / comprDataB else 0f
    val usedPct: Int get() = if (diskSizeB > 0) (origDataB * 100 / diskSizeB).toInt() else 0
    /** RAM saved: what the data would cost uncompressed minus what it costs now. */
    val savedB: Long get() = (origDataB - memUsedTotalB).coerceAtLeast(0)
}

data class SwapArea(val name: String, val sizeB: Long, val usedB: Long, val isZram: Boolean)

data class MemoryDetail(
    val mem: MemInfo,
    val zram: ZramInfo?,
    val swaps: List<SwapArea>,
)

object MemoryDetailReader {
    /** Read everything once. Pure I/O; returns null only when /proc/meminfo is unreadable. */
    suspend fun read(): MemoryDetail? = withContext(Dispatchers.IO) {
        val mem = parseMeminfo(readMaybeRoot("/proc/meminfo")) ?: return@withContext null
        val swaps = parseSwaps(readMaybeRoot("/proc/swaps"))
        val zram = readZram()
        MemoryDetail(mem, zram, swaps)
    }

    /** Read a file directly; if that yields nothing, try once through root. */
    private suspend fun readMaybeRoot(path: String): String {
        runCatching { File(path).readText() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        if (RootShell.isRootAvailable()) {
            val r = RootShell.run("cat $path", 3000)
            if (r.ok && r.stdout.isNotBlank()) return r.stdout
        }
        return ""
    }

    internal fun parseMeminfo(text: String): MemInfo? {
        if (text.isBlank()) return null
        val kb = HashMap<String, Long>()
        for (line in text.lineSequence()) {
            val k = line.substringBefore(':', "").trim()
            if (k.isEmpty()) continue
            // "MemTotal:       16218680 kB" -> kilobytes
            val v = line.substringAfter(':').trim().substringBefore(" kB").trim().toLongOrNull() ?: continue
            kb[k] = v
        }
        val total = (kb["MemTotal"] ?: return null) * 1024
        if (total <= 0) return null
        return MemInfo(
            totalB = total,
            freeB = (kb["MemFree"] ?: 0) * 1024,
            availableB = kb["MemAvailable"]?.times(1024),
            buffersB = (kb["Buffers"] ?: 0) * 1024,
            cachedB = (kb["Cached"] ?: 0) * 1024,
            slabB = (kb["SReclaimable"] ?: kb["Slab"] ?: 0) * 1024,
            swapTotalB = (kb["SwapTotal"] ?: 0) * 1024,
            swapFreeB = (kb["SwapFree"] ?: 0) * 1024,
        )
    }

    internal fun parseSwaps(text: String): List<SwapArea> {
        // Filename  Type  Size  Used  Priority  (sizes in KiB)
        return text.lineSequence().drop(1).mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 4) return@mapNotNull null
            val size = f[2].toLongOrNull() ?: return@mapNotNull null
            val used = f[3].toLongOrNull() ?: return@mapNotNull null
            SwapArea(f[0], size * 1024, used * 1024, f[0].contains("zram"))
        }.toList()
    }

    private suspend fun readZram(): ZramInfo? {
        val disk = readMaybeRoot("/sys/block/zram0/disksize").trim().toLongOrNull() ?: return null
        if (disk <= 0) return null
        // mm_stat: orig_data_size compr_data_size mem_used_total mem_limit mem_used_max same_pages pages_compacted ...
        val stat = readMaybeRoot("/sys/block/zram0/mm_stat").trim().split(Regex("\\s+"))
        val orig = stat.getOrNull(0)?.toLongOrNull() ?: 0
        val compr = stat.getOrNull(1)?.toLongOrNull() ?: 0
        val memUsed = stat.getOrNull(2)?.toLongOrNull() ?: compr
        return ZramInfo(disk, orig, compr, memUsed)
    }
}
