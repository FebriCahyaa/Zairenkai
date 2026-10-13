// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The batterystats "Estimated power use" parser and the uid decoder are pure,
 * so they are tested against real dumpsys output without a device.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPowerTest {
    private val dump = """
        Statistics since last charge:
          ...
          Estimated power use (mAh):
            Capacity: 5000, Computed drain: 812, actual drain: 790
            Screen: 210
            Uid u0a234: 120 ( cpu=80.0 wifi=40.0 )
            Uid u0a10: 45.5 ( cpu=45.5 )
            Uid 1000: 30 ( cpu=30 )
            Uid u0a99: 0 ( cpu=0 )

          Per-app mobile ms per packet: ...
    """.trimIndent()

    @Test fun parsesEstimatedPower() {
        val (computed, apps) = AppPowerReader.parse(dump)
        assertEquals(812.0, computed, 0.01)
        // the 0 mAh app is dropped; the section stops at the blank line
        assertEquals(3, apps.size)
        assertEquals(10234, apps[0].first)
        assertEquals(120.0, apps[0].second, 0.01)
        assertTrue(apps.any { it.first == 1000 && it.second == 30.0 })
    }

    @Test fun stopsOutsideSection() {
        assertTrue(AppPowerReader.parse("nothing here\nUid u0a1: 5").second.isEmpty())
    }

    @Test fun decodesUidTokens() {
        assertEquals(10234, AppPowerReader.uidOf("u0a234"))
        assertEquals(1_010_005, AppPowerReader.uidOf("u10a5"))
        assertEquals(1000, AppPowerReader.uidOf("1000"))
        assertNull(AppPowerReader.uidOf("wifi"))
    }
}
