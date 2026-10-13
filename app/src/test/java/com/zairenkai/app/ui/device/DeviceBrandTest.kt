// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Brand detection and product names from Build fields and getprop.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceBrandTest {
    @Test
    fun everySupportedBrandIsDetected() {
        val cases = mapOf(
            "google" to DeviceBrand.GOOGLE, "Pixel" to DeviceBrand.GOOGLE,
            "samsung" to DeviceBrand.SAMSUNG, "Xiaomi" to DeviceBrand.XIAOMI, "Redmi" to DeviceBrand.REDMI,
            "POCO" to DeviceBrand.POCO, "OnePlus" to DeviceBrand.ONEPLUS, "realme" to DeviceBrand.REALME,
            "Nothing" to DeviceBrand.NOTHING, "motorola" to DeviceBrand.MOTOROLA, "moto" to DeviceBrand.MOTOROLA,
            "HUAWEI" to DeviceBrand.HUAWEI, "Infinix" to DeviceBrand.INFINIX, "iQOO" to DeviceBrand.IQOO, "TECNO" to DeviceBrand.TECNO,
        )
        cases.forEach { (brand, expected) -> assertEquals(brand, expected, resolveBrand(null, brand, "", "")) }
    }

    @Test
    fun theFirstKnownSourceWins() {
        // POCO phones are made by Xiaomi: the brand, not the maker, decides.
        assertEquals(DeviceBrand.POCO, resolveBrand(null, "POCO", "POCO", "Xiaomi"))
        // A ROM with an odd brand still falls back to the maker.
        assertEquals(DeviceBrand.XIAOMI, resolveBrand(null, "qti", "qti", "Xiaomi"))
        // The debug override previews another brand.
        assertEquals(DeviceBrand.NOTHING, resolveBrand("nothing", "google", "google", "Google"))
        // Blank override is ignored.
        assertEquals(DeviceBrand.GOOGLE, resolveBrand("", "google", "google", "Google"))
        assertEquals(DeviceBrand.OTHER, resolveBrand(null, "asus", "asus", "asus"))
        assertEquals(DeviceBrand.OTHER, resolveBrand(null, null, "", ""))
    }

    @Test
    fun productNamePrefersTheMarketingProperty() {
        assertEquals("Xiaomi 14 Ultra", marketingName(mapOf("ro.product.marketname" to "Xiaomi 14 Ultra"), "Xiaomi", "24030PN60G"))
        assertEquals("OnePlus 12", marketingName(mapOf("ro.vendor.oplus.market.name" to "OnePlus 12"), "OnePlus", "CPH2581"))
        assertEquals("Pixel 8 Pro", marketingName(emptyMap(), "Google", "Pixel 8 Pro"))
        assertEquals("Samsung SM-S911B", marketingName(emptyMap(), "samsung", "SM-S911B"))
        assertEquals("motorola edge 50", marketingName(emptyMap(), "motorola", "motorola edge 50"))
        // Blank properties are skipped.
        assertEquals("Nothing A065", marketingName(mapOf("ro.product.marketname" to " "), "Nothing", "A065"))
    }

    @Test
    fun getpropOutputIsParsed() {
        val text = """
            [ro.product.brand]: [POCO]
            [ro.product.marketname]: [POCO F6 Pro]
            [ro.empty]: []
            not a property line
        """.trimIndent()
        assertEquals(mapOf("ro.product.brand" to "POCO", "ro.product.marketname" to "POCO F6 Pro", "ro.empty" to ""), parseGetprop(text))
    }
}
