// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Thin root client for the zperfd performance engine. Drives mode changes and
 * reads the device-capability probe. All calls go through the root shell; the
 * daemon (started by the module) converges on the mode file we write.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import com.zairenkai.app.domain.ProfileId
import kotlinx.serialization.json.Json

class ZperfClient(
    private val bin: String = "/data/adb/zperf/zperfd",
    private val stateDir: String = "/data/adb/zperf",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun available(): Boolean =
        RootShell.run("[ -x $bin ] && echo ok", 4000).stdout.contains("ok")

    suspend fun probe(): ZperfProbe? {
        val r = RootShell.run("$bin probe --json", 6000)
        if (!r.ok) return null
        val line = r.stdout.trim().lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: return null
        return runCatching { json.decodeFromString<ZperfProbe>(line) }.getOrNull()
    }

    /** Persist the mode (so the daemon keeps it) and apply it immediately. */
    suspend fun apply(mode: String): Boolean {
        RootShell.run("mkdir -p $stateDir && echo $mode > $stateDir/mode", 4000)
        return RootShell.run("$bin apply $mode", 8000).ok
    }

    companion object {
        /** Map a Zairenkai profile onto a zperfd power mode. */
        fun modeFor(id: ProfileId): String = when (id) {
            ProfileId.GAME -> "fast"
            ProfileId.DAILY -> "balance"
            ProfileId.MEDIA -> "balance"
            ProfileId.BATTERY -> "powersave"
            ProfileId.BALANCED -> "balance"
            // Auto: keep a balanced base; the daemon's per-app overrides do the
            // adapting (games -> performance/fast via the profile's [perapp]).
            ProfileId.AUTO -> "balance"
        }
    }
}
