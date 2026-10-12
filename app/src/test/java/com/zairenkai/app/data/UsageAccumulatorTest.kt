// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Usage recording: values a device does not report stay out of averages.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageAccumulatorTest {
    private fun reading(tempC: Float?, powerW: Float = 2f) = PowerReading(
        levelPct = 80, voltageV = 4f, powerW = powerW,
        tempC = tempC ?: 0f, tempKnown = tempC != null,
    )

    @Test
    fun unreportedBatteryTemperatureIsNotAveragedAsZero() {
        val acc = UsageAccumulator(startMs = 0)
        acc.add(reading(30f), 2_000, "com.game", { "Game" }, { "Game" })
        acc.add(reading(null), 4_000, "com.game", { "Game" }, { "Game" })
        acc.add(reading(34f), 6_000, "com.game", { "Game" }, { "Game" })
        val s = acc.build(endMs = 6_000)
        assertEquals(32f, s.avgTempC, 0.001f)
        assertEquals(34f, s.maxTempC, 0.001f)
        assertEquals(32f, s.scenarios.single().avgTempC, 0.001f)
        // The curve holds the last reported value instead of dropping to 0 °C.
        assertEquals(listOf(30f, 30f, 34f), s.curve.map { it.tempC })
    }

    @Test
    fun aDeviceWithoutTemperatureReportsZeroNotAFakeAverage() {
        val acc = UsageAccumulator(startMs = 0)
        repeat(3) { acc.add(reading(null), (it + 1) * 2_000L, "a.b", { "A" }, { "Seimbang" }) }
        val s = acc.build(endMs = 6_000)
        assertEquals(0f, s.avgTempC, 0f)
        assertEquals(0f, s.maxTempC, 0f)
        assertEquals(6_000L, s.scenarios.single().durationMs)
        assertEquals(2f, s.avgPowerW, 0.001f)
    }

    @Test
    fun subZeroTemperaturesAreKeptAsMeasurements() {
        val acc = UsageAccumulator(startMs = 0)
        acc.add(reading(-5f), 2_000, "a.b", { "A" }, { "Seimbang" })
        acc.add(reading(-3f), 4_000, "a.b", { "A" }, { "Seimbang" })
        val s = acc.build(endMs = 4_000)
        assertEquals(-4f, s.avgTempC, 0.001f)
        assertEquals(-3f, s.maxTempC, 0.001f)
    }
}
