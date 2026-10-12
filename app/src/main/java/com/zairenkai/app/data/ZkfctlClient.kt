// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Thin client over the zkfctl binary: run a subcommand as root and decode the
 * JSON result. The binary is installed by the Zairenkai module; we probe the
 * locations module/customize.sh uses and fall back to PATH.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import com.zairenkai.app.data.diag.InstallProbe
import com.zairenkai.app.data.diag.commandFailureKind
import kotlinx.serialization.json.Json

class ZkfctlClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var binPath: String? = null
    /** When the last lookup found no zkfctl; a miss is re-checked after [MISS_TTL_MS]. */
    @Volatile private var missAt: Long = 0

    private suspend fun resolveBin(): String? {
        binPath?.let { return it }
        if (missAt != 0L && System.currentTimeMillis() - missAt < MISS_TTL_MS) return null
        // One root shell for every candidate; prints the first executable one.
        val paths = InstallProbe.ZKFCTL_PATHS.joinToString(" ")
        val r = RootShell.run("for p in $paths; do [ -x \"\$p\" ] && { echo \"\$p\"; exit 0; }; done; command -v zkfctl", 3000)
        val found = r.stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") }
        // Only a found binary is cached; a miss is retried so installing the
        // module (or Retry, which calls invalidate()) is picked up.
        if (found != null) binPath = found else missAt = System.currentTimeMillis()
        return found
    }

    fun invalidate() {
        binPath = null
        missAt = 0
    }

    private suspend fun raw(args: String, lite: Boolean, timeoutMs: Long): String {
        val bin = resolveBin()
            ?: return """{"ok":false,"kind":"not_found","error":"zkfctl is not installed"}"""
        val lflag = if (lite) "--lite " else ""
        val res = RootShell.run("${shellQuote(bin)} $lflag$args", timeoutMs)
        // zkfctl prints JSON even on handled errors; pass that through. Anything
        // else is reported with a kind the UI can explain, never parsed as data.
        val body = res.stdout.trim()
        if (body.startsWith("{")) return body
        val kind = if (body.isEmpty()) commandFailureKind(res) else "malformed"
        val detail = res.stderr.ifBlank { body.ifBlank { "no output (exit ${res.exitCode})" } }.take(300)
        if (kind == "not_found" || kind == "not_executable") invalidate()
        return """{"ok":false,"kind":"$kind","error":${jsonStr(detail)},"errno":${res.exitCode}}"""
    }

    private fun jsonStr(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    suspend fun info(): InfoResult =
        json.decodeFromString(raw("info", false, 8000))

    suspend fun license(): LicenseResult =
        json.decodeFromString(raw("license", false, 8000))

    suspend fun installToken(path: String): LicenseResult {
        val stage = RootShell.run(
            "mkdir -p /data/adb/zkfc && chmod 0700 /data/adb/zkfc && " +
                "cp -f ${shellQuote(path)} /data/adb/zkfc/token.pending && " +
                "chmod 0600 /data/adb/zkfc/token.pending",
            4000,
        )
        if (!stage.ok) {
            return LicenseResult(ok = false, state = "storage_error", error = "could not stage the token under /data/adb/zkfc")
        }
        val result: LicenseResult = json.decodeFromString(
            raw("license install '/data/adb/zkfc/token.pending'", false, 8000),
        )
        // zkfctl persists an accepted token atomically. The staged file is
        // therefore only an input hand-off and must not be moved over the
        // canonical token a second time. Remove the staging copy instead.
        val cleanup = RootShell.run("rm -f /data/adb/zkfc/token.pending", 2000)
        if (result.state == "valid" && !cleanup.ok) {
            return result.copy(ok = false, state = "storage_error", error = "token accepted, but the staged copy could not be removed")
        }
        return result
    }

    suspend fun security(): SecurityResult =
        json.decodeFromString(raw("security", false, 8000))

    suspend fun tweaks(lite: Boolean): TweakList =
        json.decodeFromString(raw("tweak list", lite, 8000))

    // Tweak writes are not exposed here: zperfd is the only mutation path
    // (ZperfClient.setTweak), so writes share its gate, transaction and readback.

    suspend fun monitor(intervalMs: Int): MonitorResult =
        json.decodeFromString(raw("monitor $intervalMs", false, (intervalMs + 6000).toLong()))

    suspend fun boostStatus(): BoostStatus =
        json.decodeFromString(raw("boost status", false, 6000))

    suspend fun boostReset(): BoostStatus {
        raw("boost reset", false, 6000)
        return boostStatus()
    }

    suspend fun boostTask(pid: Int, min: Int, max: Int, inherit: Boolean): String =
        raw("boost task $pid $min $max ${if (inherit) "inherit" else ""}", false, 6000)

    suspend fun logSetLevel(level: String): String =
        raw("log level ${shellQuote(level)}", false, 5000)

    suspend fun log(fromSeq: Long): LogResult =
        json.decodeFromString(raw("log $fromSeq", false, 6000))

    suspend fun sulog(fromSeq: Long): SulogResult =
        json.decodeFromString(raw("sulog $fromSeq", false, 6000))

    suspend fun policy(): PolicyResult =
        json.decodeFromString(raw("policy", false, 6000))

    suspend fun safeStatus(): SafeStatus =
        json.decodeFromString(raw("safe status", false, 5000))

    suspend fun safeConfirm(): Boolean =
        raw("safe confirm", false, 5000).contains("\"ok\":true")

    suspend fun safeClear(): SafeStatus =
        json.decodeFromString(raw("safe clear", false, 5000))

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private companion object {
        const val MISS_TTL_MS = 15_000L
    }
}
