// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Monitor sampling: only measured values reach the charts, failures are shown
 * and back off instead of spinning.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.data.CpuSample
import com.zairenkai.app.data.GpuSample
import com.zairenkai.app.data.MonitorResult
import com.zairenkai.app.data.ThermalSample
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorStateTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("contracts/$name")) { "missing fixture $name" }.readText()

    @Test
    fun hostSampleWithoutFrequencyGpuOrThermalAddsOnlyTheCpuPoint() {
        val m = json.decodeFromString<MonitorResult>(fixture("zkfctl-monitor-host.json"))
        assertTrue(m.ok)
        assertTrue(m.cpu.all { it.curKhz == -1L })
        assertEquals(-1, m.gpu.busyPct)
        assertEquals(-1L, m.gpu.curFreq)

        val ui = MonitorUi().withResult(m)
        assertEquals(1, ui.cpuHistory.size)
        assertTrue(ui.gpuHistory.isEmpty())
        assertTrue(ui.tempHistory.isEmpty())
        assertNull(ui.error)
    }

    @Test
    fun offlineCoresAndImplausibleZonesAreNotMeasurements() {
        val m = MonitorResult(
            ok = true,
            cpu = listOf(
                CpuSample(cpu = 0, online = true, loadPct = 40),
                CpuSample(cpu = 1, online = true, loadPct = 20),
                CpuSample(cpu = 2, online = false, loadPct = 0),
                CpuSample(cpu = 3, online = true, loadPct = -1),
            ),
            gpu = GpuSample(busyPct = 0, curFreq = 305_000_000),
            thermal = listOf(
                ThermalSample("thermal_zone0", "cpu-0-0", 41_500),
                ThermalSample("thermal_zone1", "battery", 45),          // °C, not m°C: not plausible
                ThermalSample("thermal_zone2", "disabled", -273_000),
            ),
        )
        val ui = MonitorUi().withResult(m)
        assertEquals(listOf(30), ui.cpuHistory)
        assertEquals(listOf(0), ui.gpuHistory)                         // a measured 0 % is kept
        assertEquals(listOf(41.5f), ui.tempHistory)
    }

    @Test
    fun aFailedSampleClearsTheHeadlineKeepsHistoryAndBacksOff() {
        val good = MonitorUi().withResult(
            MonitorResult(ok = true, cpu = listOf(CpuSample(cpu = 0, loadPct = 50))),
        )
        val failed = good.withResult(MonitorResult(ok = false, error = "su: permission denied"))
        assertNull(failed.latest)
        assertEquals(UiText.Raw("su: permission denied"), failed.error)
        assertEquals(listOf(50), failed.cpuHistory)

        val noOutput = failed.withResult(null)
        assertEquals(UiText.Res(R.string.monitor_error_no_response), noOutput.error)
        assertEquals(2, noOutput.failures)

        assertEquals(listOf(0L, 1000L, 2000L, 4000L, 8000L, 8000L), (0..5).map(::monitorRetryDelayMs))

        val recovered = noOutput.withResult(MonitorResult(ok = true))
        assertNull(recovered.error)
        assertEquals(0, recovered.failures)
    }

    @Test
    fun historyIsBoundedToTheCap() {
        var ui = MonitorUi()
        repeat(70) { i -> ui = ui.withResult(MonitorResult(ok = true, cpu = listOf(CpuSample(loadPct = i % 101)))) }
        assertEquals(60, ui.cpuHistory.size)
        assertEquals(69, ui.cpuHistory.last())
    }

    @Test
    fun aMissingZkfctlIsRetriedRarely() {
        assertEquals(MISSING_DEPENDENCY_RETRY_MS, monitorNextDelayMs(1, "not_found"))
        assertEquals(MISSING_DEPENDENCY_RETRY_MS, monitorNextDelayMs(3, "not_executable"))
        // Other failures keep the short backoff; a good sample continues at once.
        assertEquals(2000L, monitorNextDelayMs(2, "timeout"))
        assertEquals(0L, monitorNextDelayMs(0, null))
    }
}
