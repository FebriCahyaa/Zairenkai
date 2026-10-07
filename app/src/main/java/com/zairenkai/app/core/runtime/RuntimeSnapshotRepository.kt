// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.core.runtime

import com.zairenkai.app.core.capability.buildCapabilityMatrix
import com.zairenkai.app.data.AppRuntimeClients

class RuntimeSnapshotRepository(private val clients: AppRuntimeClients) {
    suspend fun observe(): RuntimeSnapshot {
        val root = clients.repository.rootAvailable()
        if (!root) {
            return RuntimeSnapshot(
                observedAtEpochMs = System.currentTimeMillis(),
                rootAvailable = false,
                info = null,
                license = null,
                security = null,
                probe = null,
                status = null,
                thermal = null,
                capabilities = buildCapabilityMatrix(false, null, null, null, null),
            )
        }
        val info = runCatching { clients.repository.info() }.getOrNull()
        val license = runCatching { clients.repository.license() }.getOrNull()
        val security = runCatching { clients.repository.security() }.getOrNull()
        val probe = runCatching { clients.zperf.probe() }.getOrNull()
        val status = runCatching { clients.zperf.status() }.getOrNull()
        val thermal = runCatching { clients.zperf.thermal() }.getOrNull()
        return RuntimeSnapshot(
            observedAtEpochMs = System.currentTimeMillis(),
            rootAvailable = true,
            info = info,
            license = license,
            security = security,
            probe = probe,
            status = status,
            thermal = thermal,
            capabilities = buildCapabilityMatrix(true, info, probe, status, thermal),
        )
    }
}
