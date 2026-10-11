// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * JSON contract tests against output captured from the real zperfd/zkfctl
 * binaries (see src/test/resources/contracts/README.md). They fail when the
 * engine and the app models drift apart.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("contracts/$name")) { "missing fixture $name" }.readText()

    @Test
    fun zperfdTweakListDecodesWithRuntimeConstraints() {
        val list = requireNotNull(ZperfClient.parseTweakList(fixture("zperfd-tweak-list.json")))
        assertEquals("zperfd", list.source)
        val ids = list.tweaks.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)

        val governor = list.tweaks.single { it.id == "cpu_governor" }
        assertEquals("choice", governor.kind)
        assertEquals(listOf("schedutil", "performance"), governor.choices)
        assertEquals("schedutil", governor.value)

        val io = list.tweaks.single { it.id == "io_scheduler" }
        assertEquals("mq-deadline", io.value)
        assertTrue(io.choices.containsAll(listOf("mq-deadline", "kyber", "none")))

        val swappiness = list.tweaks.single { it.id == "swappiness" }
        assertEquals("range", swappiness.kind)
        assertEquals(0L, swappiness.min)
        assertEquals(200L, swappiness.max)

        val zram = list.tweaks.single { it.id == "zram_disksize" }
        assertNotNull("initialized zram must be locked", zram.lockedReason)

        val gpu = list.tweaks.single { it.id == "gpu_governor" }
        assertFalse(gpu.available)
        assertNull(gpu.value)
    }

    @Test
    fun liteListHidesHeavyTweaks() {
        val full = requireNotNull(ZperfClient.parseTweakList(fixture("zperfd-tweak-list.json")))
        val lite = requireNotNull(ZperfClient.parseTweakList(fixture("zperfd-tweak-list-lite.json")))
        assertTrue(lite.liteMode)
        assertTrue(lite.tweaks.none { it.liteHidden })
        assertEquals(full.tweaks.count { !it.liteHidden }, lite.tweaks.size)
    }

    @Test
    fun legacyZkfctlListStillDecodesAsReadOnlySource() {
        val list = json.decodeFromString<TweakList>(fixture("zkfctl-tweak-list.json"))
        assertTrue(list.ok)
        assertEquals("zkfctl", list.source)
        assertTrue(list.tweaks.isNotEmpty())
        assertTrue(list.tweaks.all { it.kind == null && it.choices.isEmpty() })
    }

    @Test
    fun nonContractOutputIsNotMistakenForATweakList() {
        assertNull(ZperfClient.parseTweakList(""))
        assertNull(ZperfClient.parseTweakList("cpu_governor\tcpu\tCPU governor\tschedutil"))
        assertNull(ZperfClient.parseTweakList(fixture("zkfctl-tweak-list.json")))
    }

    @Test
    fun otherZperfdOutputsMatchAppModels() {
        val core = json.decodeFromString<CoreManifest>(fixture("zperfd-core.json"))
        assertEquals("zairenkai.core", core.id)
        assertTrue(core.architectures.isNotEmpty())
        val thermal = json.decodeFromString<ThermalStatus>(fixture("zperfd-thermal.json"))
        assertTrue(thermal.telemetryComplete)
        assertNotNull(thermal.controlZone)
        assertNotNull(thermal.headroomPermille)
        val status = json.decodeFromString<ZperfStatus>(fixture("zperfd-status.json"))
        assertTrue(status.ok)
    }

    @Test
    fun engineReportedFailureKeepsItsClass() {
        val r = ZperfClient.parseTweakWrite(
            "swappiness", 4, fixture("zperfd-tweak-set-framework-unavailable.json"), "tweak blocked",
        )
        assertFalse(r.ok)
        assertEquals("framework_unavailable", r.kind)
        assertTrue(r.error!!.contains("ZKFC unavailable"))
    }

    @Test
    fun successRequiresZeroExitAndMatchingSuccessLine() {
        val line = """{"ok":true,"id":"io_scheduler","value":"kyber","targets":2}"""
        val ok = ZperfClient.parseTweakWrite("io_scheduler", 0, line, "")
        assertTrue(ok.ok)
        assertEquals("kyber", ok.value)
        assertEquals(2, ok.targets)

        // Success text with a failing exit status is never treated as applied.
        assertFalse(ZperfClient.parseTweakWrite("io_scheduler", 3, line, "").ok)
        // A success line for a different tweak is a protocol error.
        val other = ZperfClient.parseTweakWrite("swappiness", 0, line, "")
        assertFalse(other.ok)
        assertEquals("protocol", other.kind)
        // Zero exit with no contract line (e.g. an older engine) is not success.
        assertFalse(ZperfClient.parseTweakWrite("swappiness", 0, "", "").ok)
    }

    @Test
    fun processFailuresWithoutEngineOutputAreClassified() {
        assertEquals("timeout", ZperfClient.parseTweakWrite("x", 124, "", "timeout").kind)
        assertEquals("engine_unavailable", ZperfClient.parseTweakWrite("x", 127, "", "not found").kind)
        val legacy = ZperfClient.parseTweakWrite("x", 3, "", "tweak failed: value 900 outside safe range 0..200\n")
        assertEquals("protocol", legacy.kind)
        assertEquals("tweak failed: value 900 outside safe range 0..200", legacy.error)
    }
}
