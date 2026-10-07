// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Repository: single entry point the UI/ViewModels use. Wraps the zkfctl
 * client and applies profiles.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data


class ZkfcRepository(val client: ZkfctlClient = ZkfctlClient()) {

    suspend fun rootAvailable() = RootShell.isRootAvailable()
    suspend fun core() = zperf.core()
    suspend fun corePermissions() = zperf.corePermissions()

    suspend fun info() = client.info()
    suspend fun license() = client.license()
    suspend fun installToken(path: String) = client.installToken(path)
    suspend fun security() = client.security()
    suspend fun tweaks(lite: Boolean) = client.tweaks(lite)
    suspend fun monitor(intervalMs: Int) = client.monitor(intervalMs)
    suspend fun boostStatus() = client.boostStatus()
    suspend fun boostReset() = client.boostReset()
    suspend fun log(fromSeq: Long) = client.log(fromSeq)
    suspend fun sulog(fromSeq: Long) = client.sulog(fromSeq)
    suspend fun policy() = client.policy()
    suspend fun setLogLevel(level: String) = client.logSetLevel(level)
    suspend fun safeStatus() = client.safeStatus()
    suspend fun safeConfirm() = client.safeConfirm()

    suspend fun setTweak(id: String, value: String, @Suppress("UNUSED_PARAMETER") lite: Boolean): TweakList {
        if (!zperf.available() || !zperf.setTweak(id, value)) {
            return TweakList(ok = false, error = "zperfd tidak tersedia atau menolak perubahan", tweaks = emptyList())
        }
        return runCatching { client.tweaks(lite) }.getOrElse {
            TweakList(ok = false, error = it.message ?: "gagal membaca status", tweaks = emptyList())
        }
    }

    private val zperf = ZperfClient()

    fun invalidate() {
        RootShell.invalidate()
        client.invalidate()
    }
}
