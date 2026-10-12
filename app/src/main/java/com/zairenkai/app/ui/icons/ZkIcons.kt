// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zairenkai's own icons, drawn for the app (not taken from an icon set):
 * rounded 2 dp strokes on a 24 dp grid, matching the setup's line language.
 *  - ZkGlobe: a globe (meridian, equator) with a small orbit arc (language).
 *  - ZkInfo:  a soft squircle with a rounded "i" (app and device info).
 *  - ZkMark:  the Zairenkai mark, four comma petals around a dot.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

object ZkIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(width: Float = 2f, alpha: Float = 1f, d: PathBuilder.() -> Unit) =
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeAlpha = alpha,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = d,
        )

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    val Globe: ImageVector by lazy {
        icon("ZkGlobe") {
            line { circle(11f, 13f, 7.5f) }
            // Meridian.
            line { moveTo(11f, 5.5f); curveTo(8.4f, 8.2f, 8.4f, 17.8f, 11f, 20.5f); curveTo(13.6f, 17.8f, 13.6f, 8.2f, 11f, 5.5f) }
            // Equator.
            line(width = 1.8f) { moveTo(3.5f, 13f); lineTo(18.5f, 13f) }
            // Brand accent: a short orbit arc and its satellite, top right.
            line(width = 1.8f) { moveTo(16.2f, 3.6f); curveTo(19.2f, 4.2f, 21f, 6.2f, 21.2f, 9f) }
            line(width = 2.8f) { moveTo(21.2f, 9f); lineTo(21.2f, 9.05f) }
        }
    }

    val Info: ImageVector by lazy {
        icon("ZkInfo") {
            // Squircle: straight-ish sides with generous corners.
            line {
                moveTo(12f, 3.5f)
                curveTo(18.6f, 3.5f, 20.5f, 5.4f, 20.5f, 12f)
                curveTo(20.5f, 18.6f, 18.6f, 20.5f, 12f, 20.5f)
                curveTo(5.4f, 20.5f, 3.5f, 18.6f, 3.5f, 12f)
                curveTo(3.5f, 5.4f, 5.4f, 3.5f, 12f, 3.5f)
                close()
            }
            line(width = 2.4f) { moveTo(12f, 11.2f); lineTo(12f, 16.2f) }
            line(width = 2.8f) { moveTo(12f, 7.9f); lineTo(12f, 7.95f) }
        }
    }

    /** The mark: four comma petals turning around a centre dot. */
    val Mark: ImageVector by lazy {
        icon("ZkMark") {
            for (k in 0 until 4) {
                val a = Math.toRadians(k * 90.0 - 45.0)
                line(width = 2.2f) {
                    moveTo(12f, 12f)
                    val steps = 8
                    for (i in 1..steps) {
                        val t = i / steps.toFloat()
                        val r = 8.6f * t
                        val sweep = a + 0.55 * sin(Math.PI * t) * (1 - 0.3 * t)
                        lineTo(12f + r * cos(sweep).toFloat(), 12f + r * sin(sweep).toFloat())
                    }
                }
            }
            line(width = 3f) { moveTo(12f, 12f); lineTo(12f, 12.05f) }
        }
    }
}
