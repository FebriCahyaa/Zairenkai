// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * App and device facts for About phone and the setup wizard's info sheet.
 * Read from public Android APIs and `getprop` only (no root): Build,
 * ActivityManager, StatFs, Display, Settings. Reading spawns `getprop`, so
 * [DeviceInfo.read] must run off the main thread ([rememberDeviceInfo]).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.zairenkai.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class DeviceInfo(
    val appVersion: String,
    val appBuild: Long,
    val packageName: String,
    val buildType: String,
    val brand: DeviceBrand,
    /** Retail name ("Pixel 8 Pro", "Xiaomi 14 Ultra"), see [marketingName]. */
    val productName: String,
    /** The name the user gave the phone (Settings → About); may be blank. */
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val codename: String,
    val androidRelease: String,
    val sdk: Int,
    val securityPatch: String,
    val buildNumber: String,
    val baseband: String,
    val bootloader: String,
    val socManufacturer: String,
    val socModel: String,
    val hardware: String,
    val cores: Int,
    val abis: List<String>,
    val ramTotal: Long,
    val ramAvailable: Long,
    val storageTotal: Long,
    val storageFree: Long,
    /** Panel resolution of the largest display mode, e.g. 1440 × 3120; null when not reported. */
    val displayWidth: Int?,
    val displayHeight: Int?,
    val refreshRateMax: Float?,
    val densityDpi: Int,
    val kernel: String,
) {
    /** "Qualcomm SM8550" when Android reports the SoC, else the board/hardware name. */
    val soc: String
        get() = listOf(socManufacturer, socModel)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .distinct()
            .joinToString(" ")
            .ifEmpty { hardware }

    /** The product name, plus the user's own name for the phone when they set a different one. */
    val deviceLine: String
        get() = if (deviceName.isBlank() || deviceName == productName || deviceName == model) productName else "$deviceName · $productName"

    companion object {
        private fun getprop(): Map<String, String> = runCatching {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(2, TimeUnit.SECONDS)
            parseGetprop(text)
        }.getOrDefault(emptyMap())

        /** Blocking: spawns `getprop`. Call from a background dispatcher. */
        fun read(context: Context): DeviceInfo {
            val props = getprop()
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            val name = runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull().orEmpty()
            @Suppress("DEPRECATION")
            val display = runCatching { context.getSystemService(WindowManager::class.java).defaultDisplay }.getOrNull()
            val modes = display?.supportedModes.orEmpty()
            val largest = modes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            return DeviceInfo(
                appVersion = BuildConfig.VERSION_NAME,
                appBuild = BuildConfig.VERSION_CODE.toLong(),
                packageName = BuildConfig.APPLICATION_ID,
                buildType = BuildConfig.BUILD_TYPE,
                brand = resolveBrand(props["debug.zairenkai.brand"], props["ro.product.brand"], Build.BRAND, Build.MANUFACTURER),
                productName = marketingName(props, manufacturer, Build.MODEL),
                deviceName = name,
                manufacturer = manufacturer,
                model = Build.MODEL,
                codename = Build.DEVICE,
                androidRelease = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                securityPatch = Build.VERSION.SECURITY_PATCH,
                buildNumber = Build.DISPLAY,
                baseband = Build.getRadioVersion().orEmpty(),
                bootloader = Build.BOOTLOADER.takeUnless { it.equals("unknown", ignoreCase = true) }.orEmpty(),
                socManufacturer = Build.SOC_MANUFACTURER,
                socModel = Build.SOC_MODEL,
                hardware = Build.HARDWARE,
                cores = Runtime.getRuntime().availableProcessors(),
                abis = Build.SUPPORTED_ABIS.toList(),
                ramTotal = mem.totalMem,
                ramAvailable = mem.availMem,
                storageTotal = stat?.totalBytes ?: 0L,
                storageFree = stat?.availableBytes ?: 0L,
                displayWidth = largest?.physicalWidth?.takeIf { it > 0 },
                displayHeight = largest?.physicalHeight?.takeIf { it > 0 },
                refreshRateMax = modes.maxOfOrNull { it.refreshRate }?.takeIf { it > 0f },
                densityDpi = context.resources.displayMetrics.densityDpi,
                kernel = System.getProperty("os.version").orEmpty(),
            )
        }
    }
}

/** The device facts, read once on a background thread; null until ready. */
@Composable
fun rememberDeviceInfo(): DeviceInfo? {
    val context = LocalContext.current
    val info by produceState<DeviceInfo?>(null, context) {
        value = withContext(Dispatchers.IO) { DeviceInfo.read(context) }
    }
    return info
}
