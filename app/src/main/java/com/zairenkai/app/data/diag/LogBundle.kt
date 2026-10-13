// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Builds a diagnostics bundle (a .zip) for support: a summary of the device
 * and the Zairenkai stack, the install facts, the engine's own report, and
 * the full ZKFC kernel log. Everything is read live from the system and the
 * engine; nothing is invented, and no token contents or key material are
 * written (only state flags). The file lands in the app cache for the UI to
 * hand to the system file picker.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.diag

import android.content.Context
import android.os.Build
import com.zairenkai.app.data.LogRecord
import com.zairenkai.app.data.ZkfcRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogBundle {
    /** Format one log record as a line. */
    fun formatRecord(r: LogRecord): String =
        "[%012d] %-5s pid=%d uid=%d %s".format(r.tsNs, r.level.uppercase(), r.pid, r.uid, r.msg)

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** The human-readable summary that opens the bundle. No secrets. */
    private suspend fun summary(repo: ZkfcRepository, appVersion: String): String = buildString {
        appendLine("Zairenkai diagnostics bundle")
        appendLine("generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("app: $appVersion")
        appendLine()
        appendLine("[device]")
        appendLine("brand/model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("kernel: ${System.getProperty("os.version") ?: "?"}")
        appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()
        appendLine("[root]")
        appendLine("state: ${runCatching { repo.rootState() }.getOrNull() ?: "unknown"}")
        appendLine()
        appendLine("[license]")
        val lic = runCatching { repo.license() }.getOrNull()
        appendLine("state: ${lic?.state ?: "unavailable"}; token present: ${lic?.hasToken ?: "?"}; owner key: ${lic?.ownerKeyProvisioned ?: "?"}")
    }

    private suspend fun installFactsText(repo: ZkfcRepository): String =
        runCatching { repo.installFacts().toString() }.getOrElse { "install facts unavailable: ${it.message}" }

    private suspend fun engineInfoText(repo: ZkfcRepository): String = buildString {
        val info = runCatching { repo.info() }.getOrNull()
        appendLine("zkfctl info: ${info ?: "unavailable"}")
        val sec = runCatching { repo.security() }.getOrNull()
        appendLine("security: ${sec ?: "unavailable"}")
    }

    /** The full kernel log from the start, plus whatever the UI already buffered. */
    private suspend fun logText(repo: ZkfcRepository, buffered: List<LogRecord>): String {
        val full = runCatching { repo.log(0) }.getOrNull()
        val records = when {
            full != null && full.ok && full.records.isNotEmpty() -> full.records
            else -> buffered
        }
        if (records.isEmpty()) return "no ZKFC log records (engine may be absent or root not granted)"
        return records.joinToString("\n") { formatRecord(it) }
    }

    /**
     * Assemble the bundle and return the written file. Pure I/O; safe to call
     * off the main thread. Each section is gathered independently, so one
     * failing read never empties the rest.
     */
    suspend fun build(context: Context, repo: ZkfcRepository, appVersion: String, buffered: List<LogRecord>): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "zairenkai-diagnostics-${stamp()}.zip")
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                fun entry(name: String, body: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
                entry("summary.txt", summary(repo, appVersion))
                entry("install-facts.txt", installFactsText(repo))
                entry("engine.txt", engineInfoText(repo))
                entry("zkfc-log.txt", logText(repo, buffered))
            }
            file
        }
}
