// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Glass UI: an optional expressive glassmorphism surface style. Translucent
 * cards float over a soft, blurred color backdrop so the accent glows through.
 * Compose's Modifier.blur (API 31+) softens the backdrop layer only, never the
 * card content, so text stays crisp and scrolling stays smooth.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

data class GlassConfig(
    val enabled: Boolean = false,
    val opacity: Float = 0.6f,
    val blur: Float = 18f,
    val tint: Float = 0.12f,
)

val LocalGlass = compositionLocalOf { GlassConfig() }

/** Surface fill for a glass card: accent-tinted, translucent. */
@Composable
fun glassContainerColor(base: Color, accent: Color, glass: GlassConfig): Color =
    if (!glass.enabled) base
    else lerp(base, accent, glass.tint).copy(alpha = glass.opacity.coerceIn(0.3f, 0.97f))

/**
 * Backdrop behind the whole app. With glass on, three blurred accent blobs
 * give depth; otherwise it is a flat themed ground.
 */
@Composable
fun GlassBackdrop(
    background: Color,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    glass: GlassConfig,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(background)) {
        if (glass.enabled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(primary.copy(alpha = 0.55f), Color.Transparent),
                            center = Offset(220f, 260f),
                            radius = 720f,
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .blur(72.dp)
                    .graphicsLayer { translationX = 400f; translationY = 1200f }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(tertiary.copy(alpha = 0.5f), Color.Transparent),
                            radius = 760f,
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .blur(80.dp)
                    .graphicsLayer { translationY = 2200f }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(secondary.copy(alpha = 0.42f), Color.Transparent),
                            center = Offset(900f, 0f),
                            radius = 820f,
                        ),
                    ),
            )
        }
        content()
    }
}

/** Border stroke for glass cards (a faint light rim), transparent when off. */
@Composable
fun Modifier.glassEdge(glass: GlassConfig, shape: RoundedCornerShape): Modifier =
    if (!glass.enabled) this
    else this
        .clip(shape)
        .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
