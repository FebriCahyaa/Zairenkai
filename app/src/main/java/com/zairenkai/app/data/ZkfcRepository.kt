// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Repository: single entry point the UI/ViewModels use. Wraps the zkfctl
 * client and applies profiles.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import com.zairenkai.app.domain.Profile

class ZkfcRepository(val client: ZkfctlClient = ZkfctlClient()) {

    suspend fun rootAvailable() = RootShell.isRootAvailable()

    suspend fun info() = client.info()
    suspend fun license() = client.license()
    suspend fun installToken(path: String) = client.installToken(path)
    suspend fun security() = client.security()
    suspend fun tweaks(lite: Boolean) = client.tweaks(lite)
    suspend fun setTweak(id: String, value: String, lite: Boolean) = client.setTweak(id, value, lite)
    suspend fun monitor(intervalMs: Int) = client.monitor(intervalMs)
    suspend fun boostStatus() = client.boostStatus()
    suspend fun boostReset() = client.boostReset()
    suspend fun log(fromSeq: Long) = client.log(fromSeq)
    suspend fun sulog(fromSeq: Long) = client.sulog(fromSeq)
    suspend fun policy() = client.policy()
    suspend fun setLogLevel(level: String) = client.logSetLevel(level)
    suspend fun safeStatus() = client.safeStatus()
    suspend fun safeConfirm() = client.safeConfirm()

    data class ApplyReport(val requested: Int, val applied: Int)

    /** Apply every tweak of [profile]; skips ones the device lacks. */
    suspend fun applyProfile(profile: Profile, lite: Boolean): ApplyReport {
        var applied = 0
        for ((id, value) in profile.tweaks) {
            if (client.applyTweak(id, value, lite || profile.lite)) applied++
        }
        return ApplyReport(profile.tweaks.size, applied)
    }

    fun invalidate() {
        RootShell.invalidate()
        client.invalidate()
    }
}
