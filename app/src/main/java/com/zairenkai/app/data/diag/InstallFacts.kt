// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * What is actually installed, read in one root shell: the zkfctl tool, the
 * ZKFC device node and kernel module, the Magisk/KernelSU module state, the
 * zperfd engine and the stored token file. The paths are the ones
 * module/customize.sh and module/service.sh use. Read-only: the script only
 * tests files and prints key=value lines. Parsing is pure and unit tested.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.diag

import com.zairenkai.app.data.ShellResult

enum class BinState { OK, NOT_EXECUTABLE, MISSING }

enum class ModuleState {
    /** No module directory: the Zairenkai zip was never flashed (or was removed). */
    MISSING,
    INSTALLED,
    /** Flashed in this boot; it activates after a reboot. */
    UPDATE_PENDING,
    DISABLED,
    /** Marked for removal on the next reboot. */
    REMOVING,
}

data class InstallFacts(
    val zkfctl: BinState = BinState.MISSING,
    val zkfctlPath: String? = null,
    /** /dev/zkfc exists: the ZKFC framework is loaded (built in or as zkfc.ko). */
    val devNode: Boolean = false,
    /** /sys/module/zkfc exists (loadable module); built-in ZKFC may not show here. */
    val kernelModule: Boolean = false,
    /** A zkfc.ko matching this kernel was bundled by the installer. */
    val bundledKo: Boolean = false,
    val module: ModuleState = ModuleState.MISSING,
    val engine: BinState = BinState.MISSING,
    /** zperfd.pid names a live process whose /proc executable/argv matches zperfd. */
    val engineRunning: Boolean = false,
    /** A PID file exists but does not point at the real zperfd process. */
    val enginePidStale: Boolean = false,
    /** Last few daemon log lines, sanitized for a setup/support diagnostic. */
    val engineLogTail: String = "",
    val tokenFile: Boolean = false,
    val abi: String = "",
    val kernel: String = "",
)

object InstallProbe {
    const val ZKFCTL = "/data/adb/zkfc/zkfctl"
    const val ZPERFD = "/data/adb/zperf/zperfd"
    private const val MODULE = "/data/adb/modules/zairenkai"
    private const val MODULE_UPDATE = "/data/adb/modules_update/zairenkai"

    /** zkfctl locations, in the order ZkfctlClient tries them. */
    val ZKFCTL_PATHS = listOf(ZKFCTL, "$MODULE/zkfctl", "/system/bin/zkfctl")

    /** Every value comes from a file test or a getprop/uname read; nothing is written. */
    val SCRIPT: String = buildString {
        append("echo zk=1; ")
        append("for p in ${ZKFCTL_PATHS.joinToString(" ")}; do ")
        append("if [ -e \"\$p\" ]; then if [ -x \"\$p\" ]; then echo zkfctl=ok:\$p; else echo zkfctl=noexec:\$p; fi; break; fi; done; ")
        append("[ -e /dev/zkfc ] && echo dev=1; ")
        append("[ -d /sys/module/zkfc ] && echo kmod=1; ")
        append("[ -f $MODULE/zkfc/zkfc.ko ] && echo ko=1; ")
        append("if [ -d $MODULE ]; then echo module=installed; ")
        append("[ -f $MODULE/disable ] && echo module_disable=1; ")
        append("[ -f $MODULE/remove ] && echo module_remove=1; fi; ")
        append("[ -d $MODULE_UPDATE ] && echo module_update=1; ")
        append("if [ -e $ZPERFD ]; then if [ -x $ZPERFD ]; then echo engine=ok; else echo engine=noexec; fi; fi; ")
        append("P=/data/adb/zperf/zperfd.pid; if [ -f \"\$P\" ]; then PID=\"\$(cat \"\$P\" 2>/dev/null)\"; case \"\$PID\" in ''|*[!0-9]*) PID='';; esac; if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then EXE=\"\$(readlink \"/proc/\$PID/exe\" 2>/dev/null)\"; CMD=\"\$(tr '\\000' ' ' < \"/proc/\$PID/cmdline\" 2>/dev/null)\"; case \"\$EXE \$CMD\" in *'/data/adb/zperf/zperfd'*) echo engine_running=1 ;; *) echo engine_pid_stale=1 ;; esac; else echo engine_pid_stale=1; fi; fi; ")
        append("if [ -f /data/adb/zperf/zperfd.log ]; then echo engine_log=\$(tail -n 8 /data/adb/zperf/zperfd.log 2>/dev/null | tr '\\n' ';' | tr '=' ':' | cut -c1-900); fi; ")
        append("[ -f /data/adb/zkfc/token.zkl ] && echo token=1; ")
        append("echo abi=\$(getprop ro.product.cpu.abi); ")
        append("echo kernel=\$(uname -r)")
    }

    /** Null when the script did not run (no root, or the shell failed before the marker). */
    fun parse(r: ShellResult): InstallFacts? {
        val kv = r.stdout.lineSequence()
            .mapNotNull { line -> line.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        if (kv["zk"] != "1") return null
        val zk = kv["zkfctl"]
        val module = when {
            kv["module_remove"] == "1" -> ModuleState.REMOVING
            kv["module_disable"] == "1" -> ModuleState.DISABLED
            kv["module"] == "installed" -> ModuleState.INSTALLED
            kv["module_update"] == "1" -> ModuleState.UPDATE_PENDING
            else -> ModuleState.MISSING
        }
        return InstallFacts(
            zkfctl = when {
                zk == null -> BinState.MISSING
                zk.startsWith("ok:") -> BinState.OK
                else -> BinState.NOT_EXECUTABLE
            },
            zkfctlPath = zk?.substringAfter(':')?.takeIf { it.isNotBlank() },
            devNode = kv["dev"] == "1",
            kernelModule = kv["kmod"] == "1",
            bundledKo = kv["ko"] == "1",
            module = module,
            engine = when (kv["engine"]) {
                "ok" -> BinState.OK
                "noexec" -> BinState.NOT_EXECUTABLE
                else -> BinState.MISSING
            },
            engineRunning = kv["engine_running"] == "1",
            enginePidStale = kv["engine_pid_stale"] == "1",
            engineLogTail = kv["engine_log"].orEmpty().take(900),
            tokenFile = kv["token"] == "1",
            abi = kv["abi"].orEmpty(),
            kernel = kv["kernel"].orEmpty(),
        )
    }
}

/** Why `su -c id -u` did not return uid 0. */
enum class RootState { GRANTED, DENIED, NO_SU, TIMEOUT }

fun classifyRoot(r: ShellResult): RootState = when {
    r.ok && r.stdout.trim() == "0" -> RootState.GRANTED
    // ProcessBuilder could not start `su`, or the shell reported it missing.
    r.exitCode == 127 || r.stderr.contains("not found", ignoreCase = true) ||
        r.stderr.contains("No such file", ignoreCase = true) -> RootState.NO_SU
    // The root manager prompt was not answered within the timeout.
    r.exitCode == 124 -> RootState.TIMEOUT
    else -> RootState.DENIED
}

/**
 * Why a zkfctl call produced no JSON. Shell exit codes: 127 = not found
 * (Android's toybox sh says "inaccessible or not found"), 126 = found but
 * not executable, 124 = our timeout.
 */
fun commandFailureKind(r: ShellResult): String = when {
    r.exitCode == 124 -> "timeout"
    r.exitCode == 127 || r.stderr.contains("not found", ignoreCase = true) -> "not_found"
    r.exitCode == 126 || r.stderr.contains("Permission denied", ignoreCase = true) -> "not_executable"
    else -> "no_output"
}
