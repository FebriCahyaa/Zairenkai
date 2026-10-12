// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The shared monitor state keeps per-core and per-cluster histories, never
 * stores an unmeasured value, and keeps history through a failed sample.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.monitor

import com.zairenkai.app.data.CpuSample
import com.zairenkai.app.data.GpuSample
import com.zairenkai.app.data.MonitorResult
import com.zairenkai.app.data.ZperfPolicy
import com.zairenkai.app.data.ZperfProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStateTest {
    private val probe = ZperfProbe(
        policies = listOf(
            ZperfPolicy(name = "policy0", cpus = listOf(0, 1), minHw = 300_000, maxHw = 1_800_000),
            ZperfPolicy(name = "policy2", cpus = listOf(2), minHw = 700_000, maxHw = 3_000_000),
        ),
    )

    private fun sample(load0: Int, khz2: Long, gpuHz: Long = -1) = MonitorResult(
        ok = true,
        cpu = listOf(
            CpuSample(cpu = 0, loadPct = load0, curKhz = 1_000_000),
            CpuSample(cpu = 1, loadPct = -1, curKhz = 1_200_000),
            CpuSample(cpu = 2, loadPct = 50, curKhz = khz2),
        ),
        gpu = GpuSample(busyPct = 30, curFreq = gpuHz),
    )

    @Test
    fun keepsPerCoreAndClusterHistoriesFromMeasuredValuesOnly() {
        val s = LiveState(probe = probe)
            .withMonitor(sample(10, 2_000_000, 585_000_000))
            .withMonitor(sample(20, -1))

        assertEquals(listOf(10, 20), s.coreHistory[0])
        assertNull("a core without a load is never stored as 0", s.coreHistory[1])
        assertEquals(listOf(50, 50), s.coreHistory[2])
        // Cluster frequency = highest core of the cluster, in MHz.
        assertEquals(listOf(1200f, 1200f), s.clusterFreqHistory["policy0"])
        assertEquals("no frequency read in the second sample", listOf(2000f), s.clusterFreqHistory["policy2"])
        assertEquals(listOf(585f), s.gpuFreqHistory)
        assertEquals(listOf(30, 30), s.monitor.gpuHistory)
    }

    @Test
    fun failedSampleKeepsHistoryAndMarksRootTelemetryMissing() {
        val good = LiveState(probe = probe).withMonitor(sample(10, 2_000_000))
        assertFalse(good.rootTelemetryMissing)
        val failed = good.withMonitor(MonitorResult(ok = false, error = "su: permission denied"))
        assertTrue(failed.rootTelemetryMissing)
        assertEquals(good.coreHistory, failed.coreHistory)
        assertEquals(good.clusterFreqHistory, failed.clusterFreqHistory)
    }

    @Test
    fun historiesAreCapped() {
        var s = LiveState(probe = probe)
        repeat(HISTORY_CAP + 15) { s = s.withMonitor(sample(it % 100, 2_000_000)) }
        assertEquals(HISTORY_CAP, s.coreHistory.getValue(0).size)
        assertEquals(HISTORY_CAP, s.clusterFreqHistory.getValue("policy2").size)
    }

    @Test
    fun memoryShareIsComputedFromTotalAndAvailable() {
        val m = MemReading(totalMb = 8000, availMb = 2000, thresholdMb = 500, low = false)
        assertEquals(6000, m.usedMb)
        assertEquals(75, m.usedPct)
    }
}
