// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Custom profiles against captured `zperfd custom` / `modes` output, and the
 * draft -> TOML -> root command path end to end with a stand-in engine.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomProfileContractTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("contracts/$name")) { "missing fixture $name" }.readText()

    @Test
    fun listCarriesBasesOverridesMergedValuesAndInvalidEntries() {
        val list = requireNotNull(ZperfClient.parseCustomList(fixture("zperfd-custom-list.json")))
        assertEquals(8, list.max)
        assertEquals(setOf("balance", "fast", "performance", "powersave"), list.bases.map { it.id }.toSet())

        val game = list.profiles.single { it.id == "custom-game-cool" }
        assertTrue(game.valid)
        assertEquals("Game dingin", game.label)
        assertEquals("fast", game.base)
        assertEquals("85", game.overrides.text("cpu.max_perf_pct"))
        assertEquals("schedutil", game.overrides.text("cpu.governor"))
        assertNull(game.overrides.text("cpu.min_freq"))
        // Merged: overridden fields win, the rest follows the base.
        val fast = list.bases.single { it.id == "fast" }.values
        assertEquals("60", game.values!!.text("mem.swappiness"))
        assertEquals(fast.text("cpu.min_freq"), game.values!!.text("cpu.min_freq"))

        val orphan = list.profiles.single { it.id == "custom-old" }
        assertFalse(orphan.valid)
        assertNull(orphan.values)
        assertTrue(orphan.error!!.contains("turbo"))
    }

    @Test
    fun modesMarkCustomEntriesWithLabelAndBase() {
        val modes = requireNotNull(ZperfClient.parseModes(fixture("zperfd-modes-custom.json"))).modes
        val custom = modes.single { it.custom }
        assertEquals("custom-game-cool", custom.id)
        assertEquals("Game dingin", custom.label)
        assertEquals("fast", custom.base)
        assertTrue(modes.filter { !it.custom }.all { it.label == null })
    }

    @Test
    fun writeOutcomesKeepTheEngineKind() {
        assertTrue(ZperfClient.parseCustomWrite("custom-game-cool", 0, fixture("zperfd-custom-put.json"), "").ok)
        val rejected = ZperfClient.parseCustomWrite("custom-bad", 2, fixture("zperfd-custom-put-rejected.json"), "")
        assertEquals("rejected", rejected.kind)
        assertEquals("memory value out of safe range", rejected.error)
        val inUse = ZperfClient.parseCustomWrite("custom-game-cool", 2, fixture("zperfd-custom-delete-in-use.json"), "")
        assertEquals("in_use", inUse.kind)
        assertFalse(ZperfClient.parseCustomWrite("custom-x", 0, """{"ok":true,"id":"custom-y"}""", "").ok)
        assertEquals("engine_unavailable", ZperfClient.parseCustomWrite("custom-x", 127, "", "").kind)
    }

    @Test
    fun draftValidationMirrorsTheEngineLimits() {
        val ok = CustomDraft("custom-a", "A", "fast")
        assertNull(ok.validate())
        assertNotNull(ok.with("mem.swappiness", "300").validate())
        assertNotNull(ok.with("cpu.max_perf_pct", "abc").validate())
        assertNotNull(ok.with("cpu.max_freq", "fast").validate())
        assertNotNull(ok.with("cpu.governor", "a/b").validate())
        assertNotNull(ok.with("cpu.uclamp_min_pct", "60").with("cpu.uclamp_max_pct", "20").validate())
        assertNotNull(ok.copy(label = "line\nbreak").validate())
        assertNotNull(ok.copy(id = "fast").validate())
        for (freq in listOf("min", "MAX", "80%", "1.8GHz", "1800MHz", "-300MHz", "1800000")) {
            assertNull(freq, ok.with("cpu.max_freq", freq).validate())
        }
        assertNull(ok.with("cpu.max_perf_pct", "  ").validate())
        assertEquals(0, ok.with("cpu.max_perf_pct", "  ").overrideCount)
    }

    @Test
    fun idsAreDerivedFromTheLabelAndUnique() {
        assertEquals("custom-game-dingin", newCustomId("Game Dingin!", emptySet()))
        assertEquals("custom-game-dingin-2", newCustomId("game dingin", setOf("custom-game-dingin")))
        assertEquals("custom-profil", newCustomId("☆☆", emptySet()))
        assertTrue(isCustomId(newCustomId("x".repeat(80), emptySet())))
    }

    @Test
    fun tomlRoundTripsTheCapturedOverridesAndEscapesTheLabel() {
        val game = requireNotNull(ZperfClient.parseCustomList(fixture("zperfd-custom-list.json")))
            .profiles.single { it.id == "custom-game-cool" }
        val draft = CustomDraft.fromProfile(game)
        assertFalse(draft.isNew)
        assertEquals(
            "label = \"Game dingin\"\nbase = \"fast\"\n\n[cpu]\nmax_perf_pct = 85\ngovernor = [\"schedutil\"]\n\n" +
                "[mem]\nswappiness = 60\n",
            draft.toToml(),
        )
        val tricky = CustomDraft("custom-q", "Ab \"c\" \\ d's", "balance")
            .with("cpu.max_freq", "1.8GHz").with("io.scheduler", "kyber, mq-deadline")
        assertEquals(
            "label = \"Ab \\\"c\\\" \\\\ d's\"\nbase = \"balance\"\n\n[cpu]\nmax_freq = \"1.8GHz\"\n\n" +
                "[io]\nscheduler = [\"kyber\", \"mq-deadline\"]\n",
            tricky.toToml(),
        )
    }

    @Test
    fun putCommandHandsTheExactDefinitionToTheEngineAndRemovesTheStagedFile() = runBlocking {
        val dir = Files.createTempDirectory("zk-custom").toFile()
        val received = File(dir, "received.toml")
        val engine = File(dir, "zperfd").apply {
            writeText(
                "#!/bin/sh\n" +
                    "[ \"\$1 \$2\" = \"custom put\" ] || exit 9\n" +
                    "cp \"\$4\" '${received.path}'\n" +
                    "printf '{\"ok\":true,\"id\":\"%s\"}\\n' \"\$3\"\n",
            )
            setExecutable(true)
        }
        val draft = CustomDraft("custom-q", "It's \"x\" \$HOME `id`", "fast").with("cpu.max_perf_pct", "70")
        val r = RootShell.runWith(listOf("sh", "-c"), ZperfClient.customPutCommand(engine.path, draft), 5000)
        assertEquals(r.stderr, 0, r.exitCode)
        assertTrue(ZperfClient.parseCustomWrite("custom-q", r.exitCode, r.stdout, r.stderr).ok)
        assertEquals(draft.toToml(), received.readText())
        assertFalse(File(dir, "custom-q.pending").exists())
        dir.deleteRecursively()
        Unit
    }
}
