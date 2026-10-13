// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Which brand made this phone, and what it is called. Pure functions over
 * Build fields and system properties so they are unit tested.
 *
 * Brand: the `debug.zairenkai.brand` override (previews), then
 * `ro.product.brand`, Build.BRAND and Build.MANUFACTURER; the first one that
 * names a known brand wins, so a POCO (brand POCO, manufacturer Xiaomi) is
 * POCO and a ROM that sets an odd brand still falls back to the maker.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.device

enum class DeviceBrand(val label: String, private val keys: Set<String>) {
    GOOGLE("Google", setOf("google", "pixel")),
    SAMSUNG("Samsung", setOf("samsung")),
    XIAOMI("Xiaomi", setOf("xiaomi")),
    REDMI("Redmi", setOf("redmi")),
    POCO("POCO", setOf("poco")),
    ONEPLUS("OnePlus", setOf("oneplus")),
    REALME("realme", setOf("realme")),
    NOTHING("Nothing", setOf("nothing")),
    MOTOROLA("motorola", setOf("motorola", "moto")),
    HUAWEI("HUAWEI", setOf("huawei")),
    INFINIX("Infinix", setOf("infinix")),
    IQOO("iQOO", setOf("iqoo")),
    TECNO("TECNO", setOf("tecno")),

    /** Any other maker: shown with the Android mark and the maker's own name. */
    OTHER("Android", emptySet());

    companion object {
        fun fromKey(value: String?): DeviceBrand? {
            val key = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { key in it.keys }
        }
    }
}

/** The first of [candidates] that names a known brand, else [DeviceBrand.OTHER]. */
fun resolveBrand(vararg candidates: String?): DeviceBrand =
    candidates.firstNotNullOfOrNull { DeviceBrand.fromKey(it) } ?: DeviceBrand.OTHER

/** Properties some makers set to the retail name ("Xiaomi 14 Ultra", "OnePlus 12"). */
val MARKETING_NAME_PROPS = listOf(
    "ro.product.marketname",
    "ro.product.vendor.marketname",
    "ro.product.odm.marketname",
    "ro.vendor.oplus.market.name",
    "ro.vendor.oplus.market.enname",
    "ro.oppo.market.name",
    "ro.config.marketing_name",
    "ro.product.model.name",
)

/**
 * The retail name: a marketing-name property when the maker sets one,
 * otherwise the model with the maker in front ("Samsung SM-S911B"). The
 * user's own device name is shown separately, never as the product name.
 */
fun marketingName(props: Map<String, String>, manufacturer: String, model: String): String {
    MARKETING_NAME_PROPS.firstNotNullOfOrNull { props[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }?.let { return it }
    val maker = manufacturer.trim().replaceFirstChar { it.uppercase() }
    return when {
        model.isBlank() -> maker
        maker.isBlank() || model.startsWith(maker, ignoreCase = true) -> model
        // Pixels report "Pixel 8 Pro" with manufacturer Google: the model is the name.
        model.startsWith("Pixel", ignoreCase = true) -> model
        else -> "$maker $model"
    }
}

/** Parses `getprop` output: lines of `[key]: [value]`. Malformed lines are skipped. */
fun parseGetprop(text: String): Map<String, String> = buildMap {
    val line = Regex("""^\[([^\]]+)\]: \[(.*)\]$""")
    text.lineSequence().forEach { l -> line.find(l.trim())?.let { put(it.groupValues[1], it.groupValues[2]) } }
}
