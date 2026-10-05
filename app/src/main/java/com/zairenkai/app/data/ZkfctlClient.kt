// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Thin client over the zkfctl binary: run a subcommand as root and decode the
 * JSON result. The binary is installed by the Zairenkai module; we probe the
 * common locations and fall back to PATH.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.serialization.json.Json

class ZkfctlClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var binPath: String? = null

    private val candidates = listOf(
        "/data/adb/zkfc/zkfctl",
        "/data/adb/modules/zairenkai/zkfctl",
        "/system/bin/zkfctl",
        "zkfctl",
    )

    private suspend fun resolveBin(): String {
        binPath?.let { return it }
        for (c in candidates) {
            val probe = if (c.startsWith("/")) "test -x $c && echo yes" else "command -v $c >/dev/null && echo yes"
            if (RootShell.run(probe, 3000).stdout.trim() == "yes") {
                binPath = c
                return c
            }
        }
        return candidates.last().also { binPath = it }
    }

    fun invalidate() { binPath = null }

    private suspend fun raw(args: String, lite: Boolean, timeoutMs: Long): String {
        val bin = resolveBin()
        val lflag = if (lite) "--lite " else ""
        val res = RootShell.run("$bin $lflag$args", timeoutMs)
        // zkfctl prints JSON even on handled errors; pass stdout through.
        return res.stdout.ifBlank {
            """{"ok":false,"error":${jsonStr(res.stderr.ifBlank { "no output (exit ${res.exitCode})" })},"errno":${res.exitCode}}"""
        }
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

    suspend fun installToken(path: String): LicenseResult =
        json.decodeFromString(raw("license install ${shellQuote(path)}", false, 8000))

    suspend fun security(): SecurityResult =
        json.decodeFromString(raw("security", false, 8000))

    suspend fun tweaks(lite: Boolean): TweakList =
        json.decodeFromString(raw("tweak list", lite, 8000))

    suspend fun setTweak(id: String, value: String, lite: Boolean): TweakList {
        val r = raw("tweak set ${shellQuote(id)} ${shellQuote(value)}", lite, 8000)
        // Re-list so the UI reflects the applied value.
        return if (r.contains("\"ok\":true")) tweaks(lite) else json.decodeFromString(
            """{"ok":false,"error":"apply failed","tweaks":[]}""",
        )
    }

    /** Apply one tweak without re-listing. Returns true on success. */
    suspend fun applyTweak(id: String, value: String, lite: Boolean): Boolean =
        raw("tweak set ${shellQuote(id)} ${shellQuote(value)}", lite, 6000).contains("\"ok\":true")

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
        raw("log level $level", false, 5000)

    suspend fun log(fromSeq: Long): LogResult =
        json.decodeFromString(raw("log $fromSeq", false, 6000))

    suspend fun sulog(fromSeq: Long): SulogResult =
        json.decodeFromString(raw("sulog $fromSeq", false, 6000))

    suspend fun policy(): PolicyResult =
        json.decodeFromString(raw("policy", false, 6000))

    suspend fun safeStatus(): SafeStatus =
        json.decodeFromString(raw("safe status", false, 5000))

    suspend fun safeConfirm() {
        raw("safe confirm", false, 5000)
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
