// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Minimal root process executor. Every privileged command is executed through
 * the device's su implementation (Magisk / KernelSU / APatch).
 *
 * The executor drains stdout/stderr concurrently, caps captured output, and
 * forcibly terminates timed-out or cancelled children so a failed command
 * cannot continue mutating the device after the UI has returned an error.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import com.zairenkai.app.data.diag.RootState
import com.zairenkai.app.data.diag.classifyRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val DEFAULT_TIMEOUT_MS = 10_000L
private const val MAX_CAPTURE_BYTES = 2 * 1024 * 1024
private const val ROOT_PROMPT_TIMEOUT_MS = 15_000L

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

    suspend fun isRootAvailable(): Boolean = rootState() == RootState.GRANTED

    /**
     * Ask for root and say why it is not available. The timeout leaves the
     * root manager's grant prompt time to be answered before reporting
     * [RootState.TIMEOUT].
     */
    suspend fun rootState(): RootState = withContext(Dispatchers.IO) {
        if (cachedAvailable == true) return@withContext RootState.GRANTED
        val state = classifyRoot(run("id -u", ROOT_PROMPT_TIMEOUT_MS))
        // Cache only a positive root result. A denied/unavailable su can become
        // available later after the user grants root or a root manager starts.
        if (state == RootState.GRANTED) cachedAvailable = true
        state
    }

    fun invalidate() {
        cachedAvailable = null
    }

    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        runWith(listOf("su", "-c"), command, timeoutMs)

    /** [run] with an injectable launcher; tests use `sh -c` instead of `su -c`. */
    internal suspend fun runWith(launcher: List<String>, command: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder(launcher + command)
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
                    // waitFor() blocks the thread; runInterruptible turns
                    // coroutine cancellation into an interrupt so a cancelled
                    // caller stops waiting immediately. The child must then be
                    // destroyed inside this scope: the readers are scope
                    // children blocked on its pipes and only return at EOF.
                    val finished = try {
                        runInterruptible { process.waitFor(timeout, TimeUnit.MILLISECONDS) }
                    } catch (e: CancellationException) {
                        destroyProcess(process)
                        throw e
                    }
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

    /**
     * Run a long-lived root command and emit its stdout line by line. The
     * process is destroyed when the collector stops (cancellation or
     * completion); stderr is drained and dropped so the child never blocks.
     */
    fun stream(command: String): Flow<String> = streamWith(listOf("su", "-c"), command)

    /** [stream] with an injectable launcher; tests use `sh -c` instead of `su -c`. */
    internal fun streamWith(launcher: List<String>, command: String): Flow<String> = flow {
        val process = ProcessBuilder(launcher + command).redirectErrorStream(false).start()
        try {
            coroutineScope {
                // A pipe read does not react to thread interrupts, so a
                // cancelled collector would wait for the next line. This
                // child destroys the process on cancellation, which ends
                // the read immediately.
                val killer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { destroyProcess(process) }
                    }
                }
                launch(Dispatchers.IO) { runCatching { process.errorStream.use { e -> val buf = ByteArray(4096); while (e.read(buf) >= 0) Unit } } }
                val reader = process.inputStream.bufferedReader()
                try {
                    while (true) {
                        val line = runInterruptible(Dispatchers.IO) { runCatching { reader.readLine() }.getOrNull() } ?: break
                        if (line.isNotBlank()) emit(line)
                    }
                } finally {
                    killer.cancel()
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { destroyProcess(process) }
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
