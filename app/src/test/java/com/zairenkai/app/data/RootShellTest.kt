// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Process lifecycle of the root executor, exercised with a real `sh -c`
 * child so cancellation, timeout and output capture are tested end to end.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootShellTest {
    private val sh = listOf("sh", "-c")

    @Test
    fun capturesStdoutStderrAndExitCode() = runBlocking {
        val r = RootShell.runWith(sh, "echo out; echo err >&2; exit 3", 5_000)
        assertEquals(3, r.exitCode)
        assertEquals("out\n", r.stdout)
        assertEquals("err\n", r.stderr)
    }

    @Test
    fun timeoutKillsTheChildAndReportsExit124() = runBlocking {
        val marker = File.createTempFile("zk-rootshell", ".pid").apply { deleteOnExit() }
        val started = System.nanoTime()
        val r = RootShell.runWith(sh, "echo \$\$ > ${marker.path}; exec sleep 30", 400)
        assertEquals(124, r.exitCode)
        assertTrue("timeout must not wait for the child", (System.nanoTime() - started) / 1_000_000 < 5_000)
        assertFalse("child must be gone", processAlive(marker.readText().trim()))
    }

    @Test
    fun cancellationStopsWaitingAndKillsTheChild() = runBlocking {
        val marker = File.createTempFile("zk-rootshell", ".pid").apply { deleteOnExit() }
        val finished = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            try {
                RootShell.runWith(sh, "echo \$\$ > ${marker.path}; exec sleep 30", 60_000)
            } finally {
                finished.complete(Unit)
            }
        }
        while (marker.length() == 0L) delay(20)
        val started = System.nanoTime()
        job.cancelAndJoin()
        finished.await()
        assertTrue(
            "cancellation must not wait for the 60 s timeout",
            (System.nanoTime() - started) / 1_000_000 < 5_000,
        )
        assertFalse("cancelled child must be destroyed", processAlive(marker.readText().trim()))
    }

    private fun processAlive(pid: String): Boolean =
        ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)

    @Test
    fun streamEmitsLinesAndKillsTheChildWhenTheCollectorStops() = runBlocking {
        val marker = File.createTempFile("zk-rootstream", ".pid").apply { deleteOnExit() }
        val started = System.nanoTime()
        val lines = RootShell.streamWith(sh, "echo \$\$ > ${marker.path}; echo one; echo; echo two; exec sleep 30")
            .take(2).toList()
        assertEquals(listOf("one", "two"), lines)
        assertTrue("collector must not wait for the child", (System.nanoTime() - started) / 1_000_000 < 5_000)
        assertFalse("child must be gone", processAlive(marker.readText().trim()))
    }

    @Test
    fun streamEndsWhenTheChildExits() = runBlocking {
        assertEquals(listOf("a", "b"), RootShell.streamWith(sh, "printf 'a\\nb\\n'").toList())
    }
}
