// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * CPU cluster grouping from zperfd's policies and zkfctl's per-core samples.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.cpu

import com.zairenkai.app.data.CpuSample
import com.zairenkai.app.data.ZperfProbe
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuClustersTest {
    private val probe = Json { ignoreUnknownKeys = true }.decodeFromString<ZperfProbe>(
        javaClass.classLoader!!.getResource("contracts/zperfd-probe-clusters.json")!!.readText(),
    )

    private fun core(i: Int, load: Int, khz: Long, online: Boolean = true) = CpuSample(i, online, load, khz)

    @Test
    fun threePoliciesBecomeEfficiencyPerformanceAndPrime() {
        val cores = listOf(core(0, 10, 300_000), core(1, 30, 1_200_000), core(2, 20, 600_000), core(3, -1, -1, online = false),
            core(4, 60, 1_555_200), core(5, 40, 1_555_200), core(6, 50, 710_400), core(7, 90, 3_187_200))
        val c = CpuClusters.group(probe.policies, cores)
        assertEquals(listOf(ClusterRole.EFFICIENCY, ClusterRole.PERFORMANCE, ClusterRole.PRIME), c.map { it.role })
        assertEquals(listOf(0, 1, 2, 3), c[0].cores.map { it.cpu })
        assertEquals(20, c[0].loadPct)            // offline core 3 left out
        assertEquals(1_200_000L, c[0].curKhz)
        assertEquals(59, c[0].freqPct)            // (1.2-0.3)/(1.8048-0.3) = 59.8 %, truncated
        assertEquals("schedutil", c[1].governor)
        assertEquals(50, c[1].loadPct)
        assertEquals(100, c[2].freqPct)
        assertEquals("performance", c[2].governor)
    }

    @Test
    fun withoutPoliciesAllCoresAreOneCluster() {
        val c = CpuClusters.group(emptyList(), listOf(core(1, 10, -1), core(0, 30, -1)))
        assertEquals(1, c.size)
        assertEquals(ClusterRole.SINGLE, c[0].role)
        assertEquals(listOf(0, 1), c[0].cores.map { it.cpu })
        assertNull(c[0].curKhz)
        assertNull(c[0].freqPct)
        assertEquals(emptyList<CpuCluster>(), CpuClusters.group(emptyList(), emptyList()))
    }
}
