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

import kotlinx.serialization.json.Json

class ZperfClient(
    private val bin: String = "/data/adb/zperf/zperfd",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun available(): Boolean {
        val quoted = shellQuote(bin)
        return RootShell.run("[ -x $quoted ]", 4000).ok
    }

    suspend fun thermal(): ThermalStatus? {
        val r = RootShell.run("${shellQuote(bin)} thermal --json", 5000)
        if (!r.ok) return null
        val line = r.stdout.trim().lineSequence()
            .firstOrNull { it.trimStart().startsWith("{") }
            ?: return null
        return runCatching { json.decodeFromString<ThermalStatus>(line) }.getOrNull()
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
    suspend fun apply(mode: String): ModeApplyResult {
        if (mode !in VALID_MODES && !isCustomId(mode)) {
            return ModeApplyResult(ok = false, kind = "rejected", error = "mode tidak dikenal: $mode")
        }
        val r = RootShell.run("${shellQuote(bin)} --json set ${shellQuote(mode)}", 12_000)
        return parseModeApply(r.exitCode, r.stdout, r.stderr)
    }

    /** Modes of the device profile zperfd resolved, with what each one requests. */
    suspend fun modes(): ModesResult? {
        val r = RootShell.run("${shellQuote(bin)} --json modes", 6000)
        if (!r.ok) return null
        return parseModes(r.stdout)
    }

    /** Stored custom profiles, the built-in modes they can derive from, and their values. */
    suspend fun customs(): CustomListResult? {
        val r = RootShell.run("${shellQuote(bin)} --json custom list", 6000)
        if (!r.ok) return null
        return parseCustomList(r.stdout)
    }

    /**
     * Store (create or replace) a custom profile. The definition is staged in
     * zperfd's root-only state directory, read by `custom put`, and removed
     * whatever the outcome. Storing changes no tunable; apply it with [apply].
     */
    suspend fun putCustom(draft: CustomDraft): CustomWriteResult {
        draft.validate()?.let { return CustomWriteResult(ok = false, id = draft.id, kind = "rejected", error = it) }
        val r = RootShell.run(customPutCommand(bin, draft), 8000)
        return parseCustomWrite(draft.id, r.exitCode, r.stdout, r.stderr)
    }

    /** Remove a custom profile; zperfd refuses while it is the selected mode. */
    suspend fun deleteCustom(id: String): CustomWriteResult {
        if (!isCustomId(id)) return CustomWriteResult(ok = false, id = id, kind = "rejected", error = "ID profil tidak valid")
        val r = RootShell.run("${shellQuote(bin)} custom delete ${shellQuote(id)}", 6000)
        return parseCustomWrite(id, r.exitCode, r.stdout, r.stderr)
    }

    /** Runtime tweak descriptors from zperfd, the same engine that writes them. */
    suspend fun tweaks(lite: Boolean = false): TweakList? {
        val lflag = if (lite) "--lite " else ""
        val r = RootShell.run("${shellQuote(bin)} ${lflag}--json tweak list", 8000)
        if (!r.ok) return null
        return parseTweakList(r.stdout)
    }

    /**
     * Apply one tweak through zperfd's transactional engine. Success means the
     * value was written to every target, read back, and committed.
     */
    suspend fun setTweak(id: String, value: String, lite: Boolean = false): TweakWriteResult {
        if (id.isBlank() || value.isBlank() || value.length > 4096) {
            return TweakWriteResult(ok = false, id = id, kind = "rejected", error = "nilai kosong atau terlalu panjang")
        }
        val lflag = if (lite) "--lite " else ""
        val r = RootShell.run(
            "${shellQuote(bin)} ${lflag}tweak set ${shellQuote(id)} ${shellQuote(value)}",
            12_000,
        )
        return parseTweakWrite(id, r.exitCode, r.stdout, r.stderr)
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

    private fun shellQuote(value: String): String = quote(value)

    companion object {
        private val parser = Json { ignoreUnknownKeys = true }

        private fun firstJsonLine(stdout: String): String? =
            stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("{") }

        /** Decode `zperfd --json tweak list`; null when the output is not that contract. */
        fun parseTweakList(stdout: String): TweakList? {
            val line = firstJsonLine(stdout) ?: return null
            val list = runCatching { parser.decodeFromString<TweakList>(line) }.getOrNull() ?: return null
            return list.takeIf { it.ok && it.source == "zperfd" }
        }

        /**
         * Decode a `zperfd tweak set` outcome. The exit status is authoritative:
         * a zero exit without a matching success line, or a success line with a
         * non-zero exit, is a protocol error and never reported as applied.
         */
        fun parseTweakWrite(id: String, exitCode: Int, stdout: String, stderr: String): TweakWriteResult {
            val reported = firstJsonLine(stdout)?.let {
                runCatching { parser.decodeFromString<TweakWriteResult>(it) }.getOrNull()
            }
            if (exitCode == 0 && reported?.ok == true && reported.id == id) return reported
            if (exitCode != 0 && reported != null && !reported.ok) return reported
            val detail = stderr.trim().lineSequence().lastOrNull()?.takeIf { it.isNotBlank() }
            val kind = when (exitCode) {
                124 -> "timeout"
                125, 126, 127 -> "engine_unavailable"
                else -> "protocol"
            }
            return TweakWriteResult(ok = false, id = id, kind = kind, error = detail ?: "exit $exitCode")
        }

        /** Stage the definition next to the engine, store it, and always remove the staged copy. */
        internal fun customPutCommand(bin: String, draft: CustomDraft): String {
            val staged = "${bin.substringBeforeLast('/')}/${draft.id}.pending"
            return "umask 077; f=${quote(staged)}; printf '%s' ${quote(draft.toToml())} > \"\$f\" && " +
                "${quote(bin)} custom put ${quote(draft.id)} \"\$f\"; rc=\$?; rm -f \"\$f\"; exit \$rc"
        }

        private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun parseCustomList(stdout: String): CustomListResult? {
            val line = firstJsonLine(stdout) ?: return null
            return runCatching { parser.decodeFromString<CustomListResult>(line) }.getOrNull()?.takeIf { it.ok }
        }

        /** Decode `zperfd custom put|delete`; the exit status is authoritative, as for tweak writes. */
        fun parseCustomWrite(id: String, exitCode: Int, stdout: String, stderr: String): CustomWriteResult {
            val reported = firstJsonLine(stdout)?.let {
                runCatching { parser.decodeFromString<CustomWriteResult>(it) }.getOrNull()
            }
            if (exitCode == 0 && reported?.ok == true && reported.id == id) return reported
            if (exitCode != 0 && reported != null && !reported.ok) return reported
            val detail = stderr.trim().lineSequence().lastOrNull()?.takeIf { it.isNotBlank() }
            val kind = when (exitCode) {
                124 -> "timeout"
                125, 126, 127 -> "engine_unavailable"
                else -> "protocol"
            }
            return CustomWriteResult(ok = false, id = id, kind = kind, error = detail ?: "exit $exitCode")
        }

        fun parseModes(stdout: String): ModesResult? {
            val line = firstJsonLine(stdout) ?: return null
            return runCatching { parser.decodeFromString<ModesResult>(line) }.getOrNull()?.takeIf { it.ok }
        }

        /** Progress/diagnostic stderr lines zperfd also prints on success. */
        private val INFO_PREFIXES = listOf("thermal envelope:", "thermal guard:", "warning:")

        /**
         * Decode `zperfd [--json] set`. The JSON line is preferred; an engine
         * that predates it is read from its `mode=` summary and exit status.
         * The exit status stays authoritative: a success line with a non-zero
         * exit (or the reverse) is never reported as applied.
         */
        fun parseModeApply(exitCode: Int, stdout: String, stderr: String): ModeApplyResult {
            val reported = firstJsonLine(stdout)?.let {
                runCatching { parser.decodeFromString<ModeApplyResult>(it) }.getOrNull()
            }
            if (reported != null) {
                if (exitCode == 0 && reported.ok) return reported
                if (exitCode != 0 && !reported.ok) return reported
            }
            val summary = stdout.lineSequence().any { it.trimStart().startsWith("mode=") }
            if (exitCode == 0 && summary && reported == null) return ModeApplyResult(ok = true)
            val detail = stderr.lineSequence()
                .map { it.trim() }
                .filter { line -> line.isNotEmpty() && INFO_PREFIXES.none { line.startsWith(it) } }
                .take(4)
                .joinToString("\n")
                .ifBlank { null }
            val kind = when (exitCode) {
                0 -> "protocol"
                1 -> "unauthorized"
                2 -> "rejected"
                3 -> "failed"
                4 -> "blocked"
                124 -> "timeout"
                125, 126, 127 -> "engine_unavailable"
                else -> "failed"
            }
            return ModeApplyResult(ok = false, kind = kind, error = detail ?: "exit $exitCode")
        }

        private val VALID_MODES = setOf("powersave", "balance", "performance", "fast", "auto")

    }
}
