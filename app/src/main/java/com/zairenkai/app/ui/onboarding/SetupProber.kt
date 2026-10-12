// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Runs the read-only system check used by the setup wizard and the
 * Dashboard: root first, then what is installed (one root shell), then
 * `zkfctl info` and `zperfd status` in parallel. Each step publishes as it
 * finishes so the UI can show progress.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import com.zairenkai.app.AppContainer
import com.zairenkai.app.data.diag.BinState
import com.zairenkai.app.data.diag.RootState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class SetupProber(private val c: AppContainer) {
    suspend fun run(update: ((SetupProbe) -> SetupProbe) -> Unit) {
        // Forget cached binary locations and root results so Retry re-probes.
        c.repository.invalidate()
        val root = runCatching { c.repository.rootState() }.getOrDefault(RootState.DENIED)
        update { it.copy(root = root == RootState.GRANTED, rootState = root) }
        if (root != RootState.GRANTED) return
        val facts = runCatching { c.repository.installFacts() }.getOrNull()
        update { it.copy(facts = facts) }
        coroutineScope {
            launch {
                val info = runCatching { c.repository.info() }.getOrNull()
                update { it.copy(info = info, infoDone = true) }
            }
            launch {
                val installed = facts?.let { it.engine == BinState.OK }
                    ?: runCatching { c.zperf.available() }.getOrDefault(false)
                val status = if (installed) runCatching { c.zperf.status() }.getOrNull() else null
                update { it.copy(engineInstalled = installed, engineStatus = status, engineDone = true) }
            }
        }
    }
}
