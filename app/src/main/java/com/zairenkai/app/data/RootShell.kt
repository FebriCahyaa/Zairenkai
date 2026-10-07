// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Minimal root process executor. Every privileged command is executed through
 * the device's su implementation (Magisk / KernelSU / APatch).
 *
 * The executor drains stdout/stderr concurrently, caps captured output, and
 * forcibly terminates timed-out children so a failed command cannot continue
 * mutating the device after the UI has returned an error.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val DEFAULT_TIMEOUT_MS = 10_000L
private const val MAX_CAPTURE_BYTES = 2 * 1024 * 1024

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}

object RootShell {
    @Volatile
    private var cachedAvailable: Boolean? = null

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (cachedAvailable == true) return@withContext true
        val result = run("id -u", 4000)
        val available = result.ok && result.stdout.trim() == "0"
        // Cache only a positive root result. A denied/unavailable su can become
        // available later after the user grants root or a root manager starts.
        if (available) cachedAvailable = true
        available
    }

    fun invalidate() {
        cachedAvailable = null
    }

    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(false)
                    .start()
            } catch (e: Exception) {
                return@withContext ShellResult(127, "", e.message ?: "su not found")
            }

            try {
                coroutineScope {
                    val out = async(Dispatchers.IO) { readCapped(process.inputStream) }
                    val err = async(Dispatchers.IO) { readCapped(process.errorStream) }
                    val timeout = timeoutMs.coerceIn(250, 120_000)
                    val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
                    if (!finished) {
                        // Destroy the child before leaving this coroutine scope.
                        // Otherwise the stream readers remain children of the
                        // scope and can wait forever for EOF from the still-live
                        // process, turning the timeout path into a deadlock.
                        destroyProcess(process)
                        out.cancel()
                        err.cancel()
                        ShellResult(124, "", "timeout")
                    } else {
                        ShellResult(process.exitValue(), out.await(), err.await())
                    }
                }
            } catch (e: CancellationException) {
                destroyProcess(process)
                throw e
            } catch (e: Exception) {
                destroyProcess(process)
                ShellResult(125, "", e.message ?: "root process failed")
            }
        }

    private fun destroyProcess(process: Process) {
        runCatching { process.destroy() }
        runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
        if (process.isAlive) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(1000, TimeUnit.MILLISECONDS) }
        }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    private fun readCapped(input: java.io.InputStream): String {
        input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                val allowed = MAX_CAPTURE_BYTES - out.size()
                if (allowed > 0) out.write(buf, 0, minOf(n, allowed))
                // Continue draining and discard bytes beyond the cap so the
                // child can always make progress without blocking on its pipe.
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
