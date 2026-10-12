// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Battery units, signs and wear, and the merge with zperfd's snapshot.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.power

import com.zairenkai.app.data.ZperfClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryMathTest {
    @Test
    fun currentIsMilliampsAndPositiveWhileCharging() {
        assertEquals(1850f, BatteryMath.currentMa(-1_850_000, ChargeStatus.CHARGING))
        assertEquals(1850f, BatteryMath.currentMa(1_850_000, ChargeStatus.CHARGING))
        assertEquals(-420f, BatteryMath.currentMa(420_000, ChargeStatus.DISCHARGING))
        // Devices that report mA.
        assertEquals(-420f, BatteryMath.currentMa(-420, ChargeStatus.DISCHARGING))
        assertEquals(0f, BatteryMath.currentMa(0, ChargeStatus.FULL))
        assertNull(BatteryMath.currentMa(null, ChargeStatus.CHARGING))
        assertNull(BatteryMath.currentMa(Long.MIN_VALUE, ChargeStatus.CHARGING))
    }

    @Test
    fun wearIsFullOverDesignWhenPlausible() {
        assertEquals(82f, BatteryMath.healthPct(4_100_000, 5_000_000)!!, 0.01f)
        assertNull(BatteryMath.healthPct(4_100_000, null))
        assertNull(BatteryMath.healthPct(100, 5_000_000))
        assertEquals(8.4f, BatteryMath.powerW(4.2f, 2000f)!!, 0.001f)
    }

    @Test
    fun mergeFillsWhatOnlySysfsKnows() {
        val z = ZperfClient.parseBattery(javaClass.classLoader!!.getResource("contracts/zperfd-battery.json")!!.readText())
        val a = AndroidBattery(levelPct = 78, status = ChargeStatus.CHARGING, plug = PlugType.USB, tempC = 33.5f,
            voltageV = 4.21f, currentRaw = -1_850_000, chargeTimeRemainingMs = 1_680_000)
        val r = BatteryMath.merge(1L, a, z)
        assertEquals(1850f, r.currentMa)
        assertEquals(7.79f, r.powerW!!, 0.01f)
        assertEquals(312, r.cycleCount)
        assertEquals(CycleSource.KERNEL, r.cycleSource)
        assertNull(r.kernelCycleCount)
        assertEquals(82f, r.healthPct!!, 0.01f)
        assertEquals(4100, r.fullMah)
        assertEquals(5000, r.designMah)
        assertEquals(3198, r.remainingMah)
        assertEquals(28, r.timeToFullMin)
        assertEquals("USB_PD", r.chargerType)
        assertEquals(18f, r.chargerPowerW!!, 0.01f)
        assertEquals("Fast", r.chargeType)

        // Without root everything comes from Android, and sysfs-only values stay unknown.
        val plain = BatteryMath.merge(1L, a, null)
        assertNull(plain.cycleCount)
        assertNull(plain.healthPct)
        assertNull(plain.chargerType)
        assertEquals(1850f, plain.currentMa)
    }

    @Test
    fun cycleCountKeepsItsSourceAndADisagreeingKernelValue() {
        val z = ZperfClient.parseBattery(javaClass.classLoader!!.getResource("contracts/zperfd-battery.json")!!.readText())
        // Android 14+ reports its own count (the recording showed 1444); it is
        // shown as Android's, and the kernel's different number is kept too.
        val android = AndroidBattery(levelPct = 50, status = ChargeStatus.DISCHARGING, cycleCount = 1444)
        val r = BatteryMath.merge(1L, android, z)
        assertEquals(1444, r.cycleCount)
        assertEquals(CycleSource.ANDROID, r.cycleSource)
        assertEquals(312, r.kernelCycleCount)
        // Agreeing sources need no second number.
        assertNull(BatteryMath.merge(1L, android.copy(cycleCount = 312), z).kernelCycleCount)
        // No source: no number and no source label.
        val none = BatteryMath.merge(1L, android.copy(cycleCount = null), null)
        assertNull(none.cycleCount)
        assertNull(none.cycleSource)
    }

    @Test
    fun healthNeedsBothCapacitiesAndAPlausibleRatio() {
        assertNull(BatteryMath.healthPct(null, null))
        assertNull(BatteryMath.healthPct(0, 5_000_000))
        // A learned capacity above 120 % of design is a broken gauge, not a healthy battery.
        assertNull(BatteryMath.healthPct(7_000_000, 5_000_000))
        assertEquals(100f, BatteryMath.healthPct(5_000_000, 5_000_000)!!, 0.01f)
    }
}
