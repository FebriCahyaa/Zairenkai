// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * App and device facts for the setup wizard's info sheet. Read from public
 * Android APIs only (no root): Build, ActivityManager, StatFs, Settings.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.zairenkai.app.BuildConfig

data class DeviceInfo(
    val appVersion: String,
    val appBuild: Long,
    val packageName: String,
    val buildType: String,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val securityPatch: String,
    val socManufacturer: String,
    val socModel: String,
    val hardware: String,
    val cores: Int,
    val abis: List<String>,
    val ramTotal: Long,
    val ramAvailable: Long,
    val storageTotal: Long,
    val storageFree: Long,
    val kernel: String,
) {
    /** "Qualcomm SM8550" when Android reports the SoC, else the board/hardware name. */
    val soc: String
        get() = listOf(socManufacturer, socModel)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .distinct()
            .joinToString(" ")
            .ifEmpty { hardware }

    /** "Pixel 8 Pro", or the user-set device name followed by the model when they differ. */
    val deviceLine: String
        get() {
            val model = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
            return if (deviceName.isBlank() || deviceName == this.model) model else "$deviceName · $model"
        }

    companion object {
        fun read(context: Context): DeviceInfo {
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            val name = runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull().orEmpty()
            return DeviceInfo(
                appVersion = BuildConfig.VERSION_NAME,
                appBuild = BuildConfig.VERSION_CODE.toLong(),
                packageName = BuildConfig.APPLICATION_ID,
                buildType = BuildConfig.BUILD_TYPE,
                deviceName = name,
                manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                model = Build.MODEL,
                androidRelease = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                securityPatch = Build.VERSION.SECURITY_PATCH,
                socManufacturer = Build.SOC_MANUFACTURER,
                socModel = Build.SOC_MODEL,
                hardware = Build.HARDWARE,
                cores = Runtime.getRuntime().availableProcessors(),
                abis = Build.SUPPORTED_ABIS.toList(),
                ramTotal = mem.totalMem,
                ramAvailable = mem.availMem,
                storageTotal = stat?.totalBytes ?: 0L,
                storageFree = stat?.availableBytes ?: 0L,
                kernel = System.getProperty("os.version").orEmpty(),
            )
        }
    }
}
