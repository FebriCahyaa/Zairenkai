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
            assertTrue("${it.id} -> ${it.mode} missing from engine profile", it.mode in engineModes)
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

    @Test
    fun jsonOutcomeCarriesTheFailureClassAndPerWriteResults() {
        val blocked = ZperfClient.parseModeApply(4, fixture("zperfd-set-framework-unavailable.json"), "apply blocked")
        assertFalse(blocked.ok)
        assertEquals("framework_unavailable", blocked.kind)
        assertTrue(blocked.error!!.contains("ZKFC unavailable"))

        val ok = ZperfClient.parseModeApply(
            0,
            """{"ok":true,"mode":"fast","effective":"fast","applied":["policy0 gov=performance","uclamp.min=40%"],""" +
                """"skipped":["/sys/kernel/gpu"],"constrained":["policy0=max 2000000 (kernel reports 1800000)"],""" +
                """"unverified":["input_boost_freq"],"failed":[]}""",
            "",
        )
        assertTrue(ok.ok)
        assertEquals(2, ok.applied.size)
        assertEquals(1, ok.constrained.size)
        assertEquals(1, ok.skipped.size)
        assertEquals(1, ok.unverified.size)

        val notObserved = ZperfClient.parseModeApply(
            3,
            """{"ok":false,"mode":"fast","kind":"not_observed","error":"apply failed: 1 failed, 3 applied, 0 skipped",""" +
                """"applied":[],"skipped":[],"constrained":[],"unverified":[],""" +
                """"failed":["vm.swappiness=60: not observable after write (kernel reports '100')"]}""",
            "",
        )
        assertEquals("not_observed", notObserved.kind)
        assertEquals(1, notObserved.failed.size)

        // A JSON success line with a failing exit status is not applied.
        assertFalse(ZperfClient.parseModeApply(3, """{"ok":true,"mode":"fast"}""", "").ok)
    }
}
