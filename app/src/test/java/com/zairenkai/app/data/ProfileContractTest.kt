// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Profile <-> zperfd mode contract, against captured engine output.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.domain.Profiles
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("contracts/$name")) { "missing fixture $name" }.readText()

    @Test
    fun everyProfileSelectsADistinctEngineModeTheGenericProfileDefines() {
        val modes = requireNotNull(ZperfClient.parseModes(fixture("zperfd-modes.json")))
        val engineModes = modes.modes.map { it.id }.toSet()
        val profileModes = Profiles.all.map { it.mode }
        assertEquals("profiles must map 1:1 to modes", profileModes.size, profileModes.toSet().size)
        Profiles.all.filter { it.id != ProfileId.AUTO }.forEach {
            assertTrue("${it.name} -> ${it.mode} missing from engine profile", it.mode in engineModes)
        }
        // Every engine mode is reachable from a profile (performance used to be unreachable).
        assertTrue(engineModes.all { mode -> Profiles.byMode(mode) != null })
    }

    @Test
    fun modeSummariesCarryTheProfileRequests() {
        val modes = requireNotNull(ZperfClient.parseModes(fixture("zperfd-modes.json"))).modes.associateBy { it.id }
        val performance = modes.getValue("performance")
        assertEquals("30%", performance.cpu.minFreq)
        assertEquals(20, performance.cpu.uclampMinPct)
        assertEquals(256, performance.io.readAheadKb)
        assertEquals(55, modes.getValue("powersave").cpu.maxPerfPct)
    }

    @Test
    fun retiredProfileIdsMigrateToBalancedAndUnknownIdsAreDropped() {
        assertEquals(ProfileId.BALANCED, ProfileId.fromStored("DAILY"))
        assertEquals(ProfileId.BALANCED, ProfileId.fromStored("MEDIA"))
        assertEquals(ProfileId.GAME, ProfileId.fromStored("GAME"))
        assertNull(ProfileId.fromStored("TURBO"))
        assertNull(ProfileId.fromStored(null))
    }

    @Test
    fun statusDistinguishesEngineDefaultSavedChoiceAndRevalidationHold() {
        val fresh = json.decodeFromString<ZperfStatus>(fixture("zperfd-status.json"))
        assertFalse(fresh.saved)
        assertNull(fresh.hold)

        val held = json.decodeFromString<ZperfStatus>(fixture("zperfd-status-revalidation.json"))
        assertTrue(held.saved)
        assertTrue(held.revalidationRequired)
        assertEquals("revalidation_required", held.hold)
        assertEquals("performance", held.desired)
        assertEquals("stock", held.effective)
    }

    @Test
    fun modeApplyNeedsZeroExitAndSummaryLine() {
        assertTrue(ZperfClient.parseModeApply(0, "mode=fast effective=fast applied=12 skipped=3\n", "").ok)
        assertFalse(ZperfClient.parseModeApply(0, "", "").ok)

        val blocked = ZperfClient.parseModeApply(
            4, "",
            "thermal envelope: band=Nominal boost=1000\napply blocked by Sentinel: [ThermalLimit]\n",
        )
        assertFalse(blocked.ok)
        assertEquals("blocked", blocked.kind)
        assertEquals("apply blocked by Sentinel: [ThermalLimit]", blocked.error)

        assertEquals("rejected", ZperfClient.parseModeApply(2, "", "unknown mode 'turbo'").kind)
        assertEquals("engine_unavailable", ZperfClient.parseModeApply(127, "", "").kind)
    }
}
