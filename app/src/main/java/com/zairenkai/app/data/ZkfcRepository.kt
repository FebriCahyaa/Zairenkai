// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Repository: single entry point the UI/ViewModels use. Wraps the zkfctl
 * client and applies profiles.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import com.zairenkai.app.data.diag.InstallProbe

class ZkfcRepository(
    val client: ZkfctlClient = ZkfctlClient(),
    private val zperf: ZperfClient = ZperfClient(),
) {

    suspend fun rootAvailable() = RootShell.isRootAvailable()
    suspend fun rootState() = RootShell.rootState()

    /** Installed components, read in one root shell; null when the shell did not run. */
    suspend fun installFacts() = InstallProbe.parse(RootShell.run(InstallProbe.SCRIPT, 6000))
    suspend fun safeClear() = client.safeClear()
    suspend fun core() = zperf.core()
    suspend fun corePermissions() = zperf.corePermissions()

    suspend fun info() = client.info()
    suspend fun license() = client.license()
    suspend fun installToken(path: String) = client.installToken(path)
    suspend fun security() = client.security()

    /**
     * zperfd publishes the authoritative, writable descriptor. When the engine
     * is absent the legacy zkfctl list is shown read-only (source = "zkfctl").
     */
    suspend fun tweaks(lite: Boolean): TweakList {
        if (zperf.available()) zperf.tweaks(lite)?.let { return it }
        return client.tweaks(lite).copy(source = "zkfctl")
    }
    suspend fun monitor(intervalMs: Int) = client.monitor(intervalMs)
    suspend fun boostStatus() = client.boostStatus()
    suspend fun boostReset() = client.boostReset()
    suspend fun log(fromSeq: Long) = client.log(fromSeq)
    suspend fun sulog(fromSeq: Long) = client.sulog(fromSeq)
    suspend fun policy() = client.policy()
    suspend fun setLogLevel(level: String) = client.logSetLevel(level)
    suspend fun safeStatus() = client.safeStatus()
    suspend fun safeConfirm() = client.safeConfirm()

    /** The only tweak mutation path: zperfd (license gate, Sentinel, transaction, readback). */
    suspend fun setTweak(id: String, value: String, lite: Boolean): TweakWriteResult {
        if (!zperf.available()) {
            return TweakWriteResult(ok = false, id = id, kind = "engine_unavailable", error = "zperfd not installed")
        }
        return zperf.setTweak(id, value, lite)
    }


    fun invalidate() {
        RootShell.invalidate()
        client.invalidate()
    }
}
