// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The /proc/meminfo and /proc/swaps parsers are pure, so they are tested
 * directly against real sample output.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDetailTest {
    private val meminfo = """
        MemTotal:        8069000 kB
        MemFree:          500000 kB
        MemAvailable:    3000000 kB
        Buffers:           40000 kB
        Cached:          2500000 kB
        SwapTotal:       4000000 kB
        SwapFree:        3000000 kB
        SReclaimable:     120000 kB
    """.trimIndent()

    @Test fun parsesMeminfoFields() {
        val m = MemoryDetailReader.parseMeminfo(meminfo)!!
        assertEquals(8069000L * 1024, m.totalB)
        assertEquals(3000000L * 1024, m.availableB)
        // used = total - available
        assertEquals((8069000L - 3000000L) * 1024, m.usedB)
        assertEquals(62, m.usedPct)
        // swap used = total - free
        assertEquals(1000000L * 1024, m.swapUsedB)
        // cache = Cached + SReclaimable
        assertEquals((2500000L + 120000L) * 1024, m.cacheB)
    }

    @Test fun missingMemTotalIsNull() {
        assertNull(MemoryDetailReader.parseMeminfo("MemFree: 100 kB"))
        assertNull(MemoryDetailReader.parseMeminfo(""))
    }

    @Test fun fallsBackToFreePlusCacheWhenNoAvailable() {
        val m = MemoryDetailReader.parseMeminfo("MemTotal: 1000 kB\nMemFree: 200 kB\nCached: 300 kB\nBuffers: 100 kB")!!
        assertNull(m.availableB)
        // used = total - (free + buffers + cached) = 1000 - 600 = 400
        assertEquals(400L * 1024, m.usedB)
    }

    @Test fun parsesSwapsAndFlagsZram() {
        val swaps = MemoryDetailReader.parseSwaps(
            "Filename\tType\tSize\tUsed\tPriority\n/dev/block/zram0\tpartition\t4000000\t1000000\t-2\n",
        )
        assertEquals(1, swaps.size)
        assertTrue(swaps[0].isZram)
        assertEquals(4000000L * 1024, swaps[0].sizeB)
        assertEquals(1000000L * 1024, swaps[0].usedB)
    }

    @Test fun zramRatioAndSaved() {
        val z = ZramInfo(diskSizeB = 4_000_000, origDataB = 3_000_000, comprDataB = 1_000_000, memUsedTotalB = 1_100_000)
        assertEquals(3.0f, z.ratio, 0.01f)
        assertEquals(1_900_000L, z.savedB)
    }
}
