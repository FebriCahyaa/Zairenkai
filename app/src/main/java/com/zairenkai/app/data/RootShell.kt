// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Minimal root shell. Runs a single command through the device's su binary
 * (Magisk / KernelSU / APatch) and returns its output. All ZKFC privileged
 * work goes through zkfctl, so this is the only place that spawns su.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

object RootShell {
    @Volatile
    private var cachedAvailable: Boolean? = null

    /** Best-effort check that a su binary exists and grants root. */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        cachedAvailable?.let { return@withContext it }
        val res = run("id -u", timeoutMs = 4000)
        val available = res.ok && res.stdout.trim() == "0"
        cachedAvailable = available
        available
    }

    fun invalidate() { cachedAvailable = null }

    /** Run [command] as root. Returns exit 127 if su is missing/denied. */
    suspend fun run(command: String, timeoutMs: Long = 10_000): ShellResult =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                try {
                    val proc = ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(false)
                        .start()
                    val out = proc.inputStream.bufferedReader().use(BufferedReader::readText)
                    val err = proc.errorStream.bufferedReader().use(BufferedReader::readText)
                    val code = proc.waitFor()
                    ShellResult(code, out, err)
                } catch (e: Exception) {
                    ShellResult(127, "", e.message ?: "su not found")
                }
            } ?: ShellResult(124, "", "timeout")
        }
}
