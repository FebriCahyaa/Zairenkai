// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Typography — Google Sans Flex, the variable typeface of Material 3
 * Expressive (OFL-1.1, see LICENSES/OFL-1.1.txt), bundled as a Latin /
 * Greek / Cyrillic subset; other scripts (Japanese, Chinese) fall back to the
 * system font. Each role sets its weight, optical size and roundness (ROND):
 * display and headline text is softly rounded, body text stays crisp.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.zairenkai.app.R

private fun sansFlex(weight: Int, opsz: Float, round: Float, wdth: Float = 100f) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.opticalSizing(opsz.sp),
        FontVariation.width(wdth),
        FontVariation.Setting("ROND", round),
    ),
)

/** One family per role so each gets its own optical size and roundness. */
private fun family(opsz: Float, round: Float, wdth: Float = 100f) = FontFamily(
    sansFlex(400, opsz, round, wdth),
    sansFlex(500, opsz, round, wdth),
    sansFlex(600, opsz, round, wdth),
    sansFlex(700, opsz, round, wdth),
)

private val Display = family(opsz = 48f, round = 100f, wdth = 102f)
private val Headline = family(opsz = 28f, round = 80f)
private val Title = family(opsz = 18f, round = 40f)
private val Body = family(opsz = 14f, round = 0f)

private fun TextStyle.with(f: FontFamily, w: FontWeight, tracking: TextUnit = letterSpacing) =
    copy(fontFamily = f, fontWeight = w, letterSpacing = tracking)

private val base = Typography()

val ZkTypography = Typography(
    displayLarge = base.displayLarge.with(Display, FontWeight.Medium, (-0.5).sp),
    displayMedium = base.displayMedium.with(Display, FontWeight.Medium, (-0.4).sp),
    displaySmall = base.displaySmall.with(Display, FontWeight.Medium, (-0.3).sp),
    headlineLarge = base.headlineLarge.with(Headline, FontWeight.Medium, (-0.2).sp),
    headlineMedium = base.headlineMedium.with(Headline, FontWeight.Medium, (-0.2).sp),
    headlineSmall = base.headlineSmall.with(Headline, FontWeight.Medium),
    titleLarge = base.titleLarge.with(Title, FontWeight.Medium),
    titleMedium = base.titleMedium.with(Title, FontWeight.Medium),
    titleSmall = base.titleSmall.with(Title, FontWeight.Medium),
    bodyLarge = base.bodyLarge.with(Body, FontWeight.Normal),
    bodyMedium = base.bodyMedium.with(Body, FontWeight.Normal),
    bodySmall = base.bodySmall.with(Body, FontWeight.Normal),
    labelLarge = base.labelLarge.with(Title, FontWeight.Medium),
    labelMedium = base.labelMedium.with(Title, FontWeight.Medium),
    labelSmall = base.labelSmall.with(Title, FontWeight.Medium),
)

/** The setup greeting: large, soft and wide like a handwritten hello. */
val GreetingStyle: TextStyle = base.displayMedium.with(family(opsz = 72f, round = 100f, wdth = 104f), FontWeight.Medium, (-0.8).sp)
    .copy(fontSize = 40.sp, lineHeight = 46.sp)
