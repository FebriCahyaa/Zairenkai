// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Install facts and failure classification: what the one-shell probe prints
 * maps to component states, and root / zkfctl failures are told apart.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.diag

import com.zairenkai.app.data.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallFactsTest {
    private fun out(vararg lines: String) = ShellResult(0, lines.joinToString("\n"), "")

    @Test
    fun nothingInstalledIsReportedAsMissingNotAsAnError() {
        // The screen recording: root granted, no module flashed.
        val f = InstallProbe.parse(out("zk=1", "abi=arm64-v8a", "kernel=5.10.269-Zen-Itsu+"))!!
        assertEquals(BinState.MISSING, f.zkfctl)
        assertEquals(ModuleState.MISSING, f.module)
        assertEquals(BinState.MISSING, f.engine)
        assertFalse(f.devNode)
        assertFalse(f.tokenFile)
        assertEquals("arm64-v8a", f.abi)
        assertEquals("5.10.269-Zen-Itsu+", f.kernel)
    }

    @Test
    fun fullyInstalledStack() {
        val f = InstallProbe.parse(
            out(
                "zk=1", "zkfctl=ok:/data/adb/zkfc/zkfctl", "dev=1", "kmod=1", "ko=1", "module=installed",
                "engine=ok", "engine_running=1", "token=1", "abi=arm64-v8a", "kernel=6.1.75-android14-11",
            ),
        )!!
        assertEquals(BinState.OK, f.zkfctl)
        assertEquals("/data/adb/zkfc/zkfctl", f.zkfctlPath)
        assertTrue(f.devNode && f.kernelModule && f.bundledKo && f.engineRunning && f.tokenFile)
        assertEquals(ModuleState.INSTALLED, f.module)
        assertEquals(BinState.OK, f.engine)
    }

    @Test
    fun daemonStatusDoesNotTrustAStalePidAndCapturesRecentLogs() {
        val facts = InstallProbe.parse(
            out(
                "zk=1", "engine=ok", "engine_pid_stale=1",
                "engine_log=zperfd: profile error: missing profile;",
            ),
        )!!
        assertFalse(facts.engineRunning)
        assertTrue(facts.enginePidStale)
        assertTrue(facts.engineLogTail.contains("profile error"))
    }

    @Test
    fun moduleFlagsAndPermissionsAreDistinguished() {
        assertEquals(ModuleState.UPDATE_PENDING, InstallProbe.parse(out("zk=1", "module_update=1"))!!.module)
        assertEquals(ModuleState.DISABLED, InstallProbe.parse(out("zk=1", "module=installed", "module_disable=1"))!!.module)
        assertEquals(ModuleState.REMOVING, InstallProbe.parse(out("zk=1", "module=installed", "module_remove=1"))!!.module)
        val noexec = InstallProbe.parse(out("zk=1", "zkfctl=noexec:/data/adb/zkfc/zkfctl", "engine=noexec"))!!
        assertEquals(BinState.NOT_EXECUTABLE, noexec.zkfctl)
        assertEquals(BinState.NOT_EXECUTABLE, noexec.engine)
    }

    @Test
    fun aShellThatDidNotRunTheScriptGivesNoFacts() {
        assertNull(InstallProbe.parse(ShellResult(1, "", "Permission denied")))
        assertNull(InstallProbe.parse(out("garbage")))
    }

    @Test
    fun theProbeScriptOnlyReads() {
        val script = InstallProbe.SCRIPT
        listOf("rm ", "chmod", "cp ", "mv ", "insmod", "setprop", ">/data", "> /data", "echo 1 >").forEach {
            assertFalse("probe must not contain '$it'", script.contains(it))
        }
        InstallProbe.ZKFCTL_PATHS.forEach { assertTrue(script.contains(it)) }
    }

    @Test
    fun rootFailuresAreClassified() {
        assertEquals(RootState.GRANTED, classifyRoot(ShellResult(0, "0\n", "")))
        assertEquals(RootState.NO_SU, classifyRoot(ShellResult(127, "", "Cannot run program \"su\": error=2, No such file or directory")))
        assertEquals(RootState.TIMEOUT, classifyRoot(ShellResult(124, "", "timeout")))
        assertEquals(RootState.DENIED, classifyRoot(ShellResult(1, "", "Permission denied")))
        // uid other than 0 is not root even when su exits 0.
        assertEquals(RootState.DENIED, classifyRoot(ShellResult(0, "2000", "")))
    }

    @Test
    fun zkfctlFailuresAreClassified() {
        assertEquals("not_found", commandFailureKind(ShellResult(127, "", "/system/bin/sh: zkfctl: inaccessible or not found")))
        assertEquals("not_executable", commandFailureKind(ShellResult(126, "", "Permission denied")))
        assertEquals("timeout", commandFailureKind(ShellResult(124, "", "timeout")))
        assertEquals("no_output", commandFailureKind(ShellResult(1, "", "")))
    }
}
