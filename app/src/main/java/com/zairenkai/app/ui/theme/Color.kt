// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zairenkai brand palette (fallback when dynamic color is unavailable).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.theme

import androidx.compose.ui.graphics.Color

// Brand: electric indigo + aqua + warm amber.
val ZkIndigo = Color(0xFF5B5BF0)
val ZkIndigoDark = Color(0xFFBBC0FF)
val ZkAqua = Color(0xFF00C2B8)
val ZkAquaDark = Color(0xFF52E0D4)
val ZkAmber = Color(0xFFFFB64A)
val ZkAmberDark = Color(0xFFFFD08A)

// Semantic colors for charts and status (stable across themes).
val ZkGood = Color(0xFF2FBF71)
val ZkWarn = Color(0xFFF2A33C)
val ZkBad = Color(0xFFE5484D)
val ZkCpu = Color(0xFF5B8DEF)
val ZkGpu = Color(0xFFB45BF0)
val ZkFps = Color(0xFF2FBF71)
val ZkFpsDrop = Color(0xFFE5484D)
val ZkTemp = Color(0xFFFF8A4C)

// ---- selectable accents (seed colors for light/dark schemes) ----
data class Accent(val id: String, val label: String, val light: Color, val dark: Color)

val ZkAccents = listOf(
    Accent("indigo", "Indigo", Color(0xFF5B5BF0), Color(0xFFBBC0FF)),
    Accent("violet", "Violet", Color(0xFF8B46E6), Color(0xFFD5B5FF)),
    Accent("aqua", "Aqua", Color(0xFF009E96), Color(0xFF52E0D4)),
    Accent("amber", "Amber", Color(0xFFB9791B), Color(0xFFFFD08A)),
    Accent("rose", "Rose", Color(0xFFD23B6B), Color(0xFFFFB1C6)),
    Accent("mono", "Mono", Color(0xFF5B6070), Color(0xFFC7CCDA)),
)

fun accentById(id: String): Accent = ZkAccents.firstOrNull { it.id == id } ?: ZkAccents[0]

/**
 * Setup and mascot tokens. The backgrounds are the setup surfaces; the
 * accents are for illustration (Zai, rings), not for text: on the light
 * background they are below 4.5:1.
 */
object ZkSetup {
    val BackgroundLight = Color(0xFFF7F9FC)
    val BackgroundDark = Color(0xFF0B1018)
    val Accent = Color(0xFF64B5FF)
    val AccentAlt = Color(0xFF8B9CFF)
}
