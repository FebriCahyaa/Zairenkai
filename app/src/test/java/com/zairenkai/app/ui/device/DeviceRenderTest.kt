// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Brand-aware device identity: every brand mark, device heroes for several
 * brands, and About phone (light and dark), rendered for visual review.
 * About phone shows only reported facts: blank fields are left out.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.device

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.zairenkai.app.R
import com.zairenkai.app.shotPath
import com.zairenkai.app.ui.theme.ZairenkaiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun fakeDevice(
    brand: DeviceBrand = DeviceBrand.GOOGLE,
    productName: String = "Pixel 8 Pro",
    manufacturer: String = "Google",
    model: String = "Pixel 8 Pro",
    codename: String = "husky",
    soc: String = "Tensor G3",
    baseband: String = "g5300i-230927-231102-B-11025429",
) = DeviceInfo(
    appVersion = "1.0.0", appBuild = 1, packageName = "com.zairenkai.app", buildType = "debug",
    brand = brand, productName = productName, deviceName = "", manufacturer = manufacturer, model = model,
    codename = codename, androidRelease = "16", sdk = 36, securityPatch = "2026-09-05", buildNumber = "BP2A.250905.002",
    baseband = baseband, bootloader = "", socManufacturer = manufacturer, socModel = soc, hardware = codename,
    cores = 9, abis = listOf("arm64-v8a"), ramTotal = 12L shl 30, ramAvailable = 5L shl 30,
    storageTotal = 256L * 1_000_000_000, storageFree = 141L * 1_000_000_000,
    displayWidth = 1344, displayHeight = 2992, refreshRateMax = 120f, densityDpi = 480, kernel = "6.1.124-android14-11",
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class DeviceRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Config(qualifiers = "w720dp-h1400dp-mdpi")
    @Test
    fun brandSheet() {
        val heroes = listOf(
            fakeDevice(),
            fakeDevice(DeviceBrand.SAMSUNG, "Samsung SM-S928B", "Samsung", "SM-S928B", "e3q", "SM8650"),
            fakeDevice(DeviceBrand.POCO, "POCO F6 Pro", "Xiaomi", "23113RKC6G", "vermeer", "SM8550"),
            fakeDevice(DeviceBrand.NOTHING, "Nothing Phone (2a)", "Nothing", "A142", "Pacman", "MT6886"),
            fakeDevice(DeviceBrand.OTHER, "Asus ROG Phone 8", "Asus", "ASUS_AI2401", "AI2401", "SM8650"),
        )
        rule.setContent {
            ZairenkaiTheme(themeMode = "light", dynamicColor = false) {
                Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DeviceBrand.entries.forEach { BrandMark(it, size = 60.dp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.width(350.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { heroes.take(3).forEach { DeviceHero(it) } }
                        ZairenkaiTheme(themeMode = "dark", dynamicColor = false) {
                            Column(
                                Modifier.width(350.dp).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) { heroes.drop(2).forEach { DeviceHero(it) } }
                        }
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage(shotPath("device_brands.png"))
    }

    private fun about(dark: Boolean, info: DeviceInfo = fakeDevice()) {
        rule.setContent {
            ZairenkaiTheme(themeMode = if (dark) "dark" else "light", dynamicColor = false) {
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    AboutPhoneContent(info, aboutPhoneSections(rule.activity, info), copied = false)
                }
            }
        }
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test
    fun aboutPhoneLight() {
        about(false)
        // The product name in the hero; the model tile repeats it.
        rule.onAllNodesWithText("Pixel 8 Pro")[0].assertIsDisplayed()
        rule.onAllNodesWithText("Tensor G3")[0].assertIsDisplayed()
        rule.onNodeWithText("1344 × 2992 · 120 Hz · 480 dpi").assertIsDisplayed()
        // The bootloader was not reported: no row, no placeholder.
        assertEquals(0, rule.onAllNodesWithText(rule.activity.getString(R.string.about_phone_bootloader)).fetchSemanticsNodes().size)
        rule.onRoot().captureRoboImage(shotPath("about_phone_light.png"))
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test
    fun aboutPhoneDarkSamsung() {
        about(true, fakeDevice(DeviceBrand.SAMSUNG, "Samsung SM-S928B", "Samsung", "SM-S928B", "e3q", "SM8650"))
        rule.onNodeWithText("SAMSUNG").assertIsDisplayed()
        rule.onRoot().captureRoboImage(shotPath("about_phone_dark_samsung.png"))
    }

    @Test
    fun heroWaitsForTheRead() {
        rule.setContent { ZairenkaiTheme(themeMode = "light", dynamicColor = false) { DeviceHero(null) } }
        assertEquals(0, rule.onAllNodesWithText("Pixel 8 Pro").fetchSemanticsNodes().size)
    }
}
