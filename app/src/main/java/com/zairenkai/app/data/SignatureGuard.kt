// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Runtime signature self-check (anti-tamper). Reads the app's own signing
 * certificate and compares its SHA-256 against the value baked in at build
 * time. A repackaged / re-signed APK gets a different signer, so the app can
 * tell an official build from a modified one.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import android.content.Context
import android.content.pm.PackageManager
import com.zairenkai.app.BuildConfig
import java.security.MessageDigest

enum class SignatureState { OFFICIAL, MODIFIED, UNKNOWN }

object SignatureGuard {

    fun currentSha256(context: Context): String? = try {
        val pm = context.packageManager
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val s = info.signingInfo
            when {
                s == null -> emptyArray()
                else -> s.apkContentsSigners
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        sigs.firstOrNull()?.toByteArray()?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02X".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    fun check(context: Context): SignatureState {
        val expected = BuildConfig.EXPECTED_CERT_SHA256
        if (expected.isBlank()) return SignatureState.UNKNOWN
        val actual = currentSha256(context) ?: return SignatureState.UNKNOWN
        // Constant-time compare.
        return if (constantTimeEquals(expected, actual)) SignatureState.OFFICIAL
        else SignatureState.MODIFIED
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
