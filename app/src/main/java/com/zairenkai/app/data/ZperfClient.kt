// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Root client for the resident zperfd performance engine.
 *
 * The app is a client of the engine: zperfd owns system policy, scene
 * adaptation and persistence. The client validates the small command surface
 * before crossing the root boundary and treats persistence failure as an apply
 * failure so UI state can never claim an uncommitted profile.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import com.zairenkai.app.domain.ProfileId
import kotlinx.serialization.json.Json

class ZperfClient(
    private val bin: String = "/data/adb/zperf/zperfd",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun available(): Boolean {
        val quoted = shellQuote(bin)
        return RootShell.run("[ -x $quoted ]", 4000).ok
    }

    suspend fun status(): ZperfStatus? {
        val r = RootShell.run("${shellQuote(bin)} status --json", 5000)
        val line = r.stdout.trim().lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: return null
        return runCatching { json.decodeFromString<ZperfStatus>(line) }.getOrNull()
    }

    suspend fun probe(): ZperfProbe? {
        val r = RootShell.run("${shellQuote(bin)} probe --json", 6000)
        if (!r.ok) return null
        val line = r.stdout.trim().lineSequence()
            .firstOrNull { it.trimStart().startsWith("{") }
            ?: return null
        return runCatching { json.decodeFromString<ZperfProbe>(line) }.getOrNull()
    }

    /** Ask zperfd to apply and durably commit a requested mode. */
    suspend fun apply(mode: String): Boolean {
        if (mode !in VALID_MODES) return false
        return RootShell.run("${shellQuote(bin)} set ${shellQuote(mode)}", 12_000).ok
    }

    suspend fun setAuto(enabled: Boolean): Boolean =
        apply(if (enabled) "auto" else "balance")

    /** Apply one validated tweak through zperfd's transactional engine. */
    suspend fun setTweak(id: String, value: String, lite: Boolean = false): Boolean {
        if (id.isBlank() || value.isBlank() || value.length > 4096) return false
        val lflag = if (lite) "--lite " else ""
        val r = RootShell.run(
            "${shellQuote(bin)} ${lflag}tweak set ${shellQuote(id)} ${shellQuote(value)}",
            12_000,
        )
        return r.ok
    }

    suspend fun reset(): Boolean =
        RootShell.run("${shellQuote(bin)} reset", 12_000).ok

    suspend fun core(): CoreManifest? {
        val r = RootShell.run("${shellQuote(bin)} core --json", 5000)
        if (!r.ok) return null
        val line = r.stdout.trim().lineSequence()
            .firstOrNull { it.trimStart().startsWith("{") } ?: return null
        return runCatching { json.decodeFromString<CoreManifest>(line) }.getOrNull()
    }

    suspend fun corePermissions(): CorePermissionsResult? {
        val r = RootShell.run("${shellQuote(bin)} core permissions --json", 5000)
        if (!r.ok) return null
        val line = r.stdout.trim().lineSequence()
            .firstOrNull { it.trimStart().startsWith("{") } ?: return null
        return runCatching { json.decodeFromString<CorePermissionsResult>(line) }.getOrNull()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private val VALID_MODES = setOf("powersave", "balance", "performance", "fast", "auto")

        /** Map a Zairenkai profile onto the zperfd power-mode contract. */
        fun modeFor(id: ProfileId): String = when (id) {
            ProfileId.GAME -> "fast"
            ProfileId.DAILY -> "balance"
            ProfileId.MEDIA -> "balance"
            ProfileId.BATTERY -> "powersave"
            ProfileId.BALANCED -> "balance"
            ProfileId.AUTO -> "auto"
        }
    }
}
