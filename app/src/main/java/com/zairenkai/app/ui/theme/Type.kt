// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Typography — Google's Roboto Flex variable font (the Material 3 expressive
 * typeface), with per-role weight/optical-size/width variations.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zairenkai.app.R

private fun flex(weight: Int, opsz: Float, wdth: Float = 100f) = Font(
    R.font.roboto_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.opticalSizing(opsz.sp),
        FontVariation.width(wdth),
    ),
)

// Display/headlines get a slightly wider cut for the expressive look.
private val DisplayFlex = FontFamily(
    flex(400, 48f, 105f), flex(500, 40f, 105f), flex(600, 32f, 103f), flex(700, 28f, 103f),
)
private val BodyFlex = FontFamily(
    flex(400, 14f), flex(500, 14f), flex(600, 14f), flex(700, 14f),
)

private val base = Typography()

val ZkTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = DisplayFlex, fontWeight = FontWeight.SemiBold),
    displayMedium = base.displayMedium.copy(fontFamily = DisplayFlex, fontWeight = FontWeight.SemiBold),
    displaySmall = base.displaySmall.copy(fontFamily = DisplayFlex, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = DisplayFlex, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = DisplayFlex, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = DisplayFlex, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = DisplayFlex, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = BodyFlex, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontFamily = BodyFlex, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = BodyFlex),
    bodyMedium = base.bodyMedium.copy(fontFamily = BodyFlex),
    bodySmall = base.bodySmall.copy(fontFamily = BodyFlex),
    labelLarge = base.labelLarge.copy(fontFamily = BodyFlex, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = BodyFlex, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = BodyFlex, fontWeight = FontWeight.Medium),
)
