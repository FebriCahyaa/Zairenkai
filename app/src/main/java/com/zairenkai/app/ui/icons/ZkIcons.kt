// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zairenkai's own icon set, drawn for the app (not taken from an icon
 * library): rounded 2 dp strokes on a 24 dp grid, the same line language as
 * the setup art. Every icon in the app comes from here; tint them with the
 * usual Icon(tint) / LocalContentColor.
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
import kotlin.math.PI
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

    private fun ImageVector.Builder.solid(d: PathBuilder.() -> Unit) = path(fill = SolidColor(Color.Black), pathBuilder = d)

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.rrect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        arcTo(rad, rad, 0f, false, true, r, t + rad)
        lineTo(r, b - rad)
        arcTo(rad, rad, 0f, false, true, r - rad, b)
        lineTo(l + rad, b)
        arcTo(rad, rad, 0f, false, true, l, b - rad)
        lineTo(l, t + rad)
        arcTo(rad, rad, 0f, false, true, l + rad, t)
        close()
    }

    private fun PathBuilder.poly(vararg p: Float, closed: Boolean = false) {
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) {
            lineTo(p[i], p[i + 1]); i += 2
        }
        if (closed) close()
    }

    private fun PathBuilder.dot(cx: Float, cy: Float, r: Float = 1.2f) = circle(cx, cy, r)

    /** A four-point sparkle centred on cx, cy. */
    private fun PathBuilder.sparkle(cx: Float, cy: Float, r: Float) {
        val k = r * 0.22f
        moveTo(cx, cy - r)
        quadTo(cx + k, cy - k, cx + r, cy)
        quadTo(cx + k, cy + k, cx, cy + r)
        quadTo(cx - k, cy + k, cx - r, cy)
        quadTo(cx - k, cy - k, cx, cy - r)
        close()
    }

    private fun shield(b: ImageVector.Builder) = b.line {
        moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11.5f)
        curveTo(19f, 16f, 16f, 19.3f, 12f, 21f)
        curveTo(8f, 19.3f, 5f, 16f, 5f, 11.5f); lineTo(5f, 6f); close()
    }

    private fun battery(b: ImageVector.Builder) {
        b.line { rrect(7f, 4.5f, 17f, 21f, 2.2f) }
        b.line { moveTo(10.5f, 2.5f); lineTo(13.5f, 2.5f) }
    }

    /* ---------------- brand ---------------- */

    val Globe: ImageVector by lazy {
        icon("ZkGlobe") {
            line { circle(11f, 13f, 7.5f) }
            line { moveTo(11f, 5.5f); curveTo(8.4f, 8.2f, 8.4f, 17.8f, 11f, 20.5f); curveTo(13.6f, 17.8f, 13.6f, 8.2f, 11f, 5.5f) }
            line(width = 1.8f) { moveTo(3.5f, 13f); lineTo(18.5f, 13f) }
            line(width = 1.8f) { moveTo(16.2f, 3.6f); curveTo(19.2f, 4.2f, 21f, 6.2f, 21.2f, 9f) }
            line(width = 2.8f) { moveTo(21.2f, 9f); lineTo(21.2f, 9.05f) }
        }
    }

    val Info: ImageVector by lazy {
        icon("ZkInfo") {
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
                    for (i in 1..8) {
                        val t = i / 8f
                        val r = 8.6f * t
                        val sweep = a + 0.55 * sin(Math.PI * t) * (1 - 0.3 * t)
                        lineTo(12f + r * cos(sweep).toFloat(), 12f + r * sin(sweep).toFloat())
                    }
                }
            }
            line(width = 3f) { moveTo(12f, 12f); lineTo(12f, 12.05f) }
        }
    }

    /* ---------------- actions ---------------- */

    val Check by lazy { icon("ZkCheck") { line(2.2f) { poly(5f, 12.5f, 10f, 17.5f, 19f, 7f) } } }
    val CheckCircle by lazy { icon("ZkCheckCircle") { line { circle(12f, 12f, 9f) }; line { poly(8f, 12.3f, 11f, 15.3f, 16.2f, 9.5f) } } }
    val Close by lazy { icon("ZkClose") { line(2.2f) { poly(6f, 6f, 18f, 18f) }; line(2.2f) { poly(18f, 6f, 6f, 18f) } } }
    val Add by lazy { icon("ZkAdd") { line(2.2f) { poly(12f, 5f, 12f, 19f) }; line(2.2f) { poly(5f, 12f, 19f, 12f) } } }
    val ArrowForward by lazy { icon("ZkArrowForward") { line { poly(5f, 12f, 19f, 12f) }; line { poly(13f, 6f, 19f, 12f, 13f, 18f) } } }
    val ArrowBack by lazy { icon("ZkArrowBack") { line { poly(19f, 12f, 5f, 12f) }; line { poly(11f, 6f, 5f, 12f, 11f, 18f) } } }
    val ChevronRight by lazy { icon("ZkChevronRight") { line(2.2f) { poly(9.5f, 5.5f, 16f, 12f, 9.5f, 18.5f) } } }
    val ChevronLeft by lazy { icon("ZkChevronLeft") { line(2.2f) { poly(14.5f, 5.5f, 8f, 12f, 14.5f, 18.5f) } } }
    val ExpandMore by lazy { icon("ZkExpandMore") { line(2.2f) { poly(6f, 9.5f, 12f, 15.5f, 18f, 9.5f) } } }
    val ExpandLess by lazy { icon("ZkExpandLess") { line(2.2f) { poly(6f, 14.5f, 12f, 8.5f, 18f, 14.5f) } } }

    val Refresh by lazy {
        icon("ZkRefresh") {
            line { moveTo(19.5f, 12f); arcTo(7.5f, 7.5f, 0f, true, true, 17.3f, 6.7f) }
            line { poly(19.5f, 3.8f, 19.5f, 8.2f, 15.1f, 8.2f) }
        }
    }
    val Sync by lazy {
        icon("ZkSync") {
            line { moveTo(4.5f, 12f); arcTo(7.5f, 7.5f, 0f, false, true, 17.8f, 7.2f) }
            line { poly(18.6f, 3.6f, 18.6f, 7.8f, 14.4f, 7.8f) }
            line { moveTo(19.5f, 12f); arcTo(7.5f, 7.5f, 0f, false, true, 6.2f, 16.8f) }
            line { poly(5.4f, 20.4f, 5.4f, 16.2f, 9.6f, 16.2f) }
        }
    }
    val Autorenew get() = Sync
    val History by lazy {
        icon("ZkHistory") {
            line { moveTo(4.5f, 12f); arcTo(7.5f, 7.5f, 0f, true, true, 6.7f, 17.3f) }
            line { poly(2.3f, 9f, 4.5f, 12f, 7.4f, 9.9f) }
            line { poly(12f, 8f, 12f, 12f, 15f, 14f) }
        }
    }
    val Copy by lazy {
        icon("ZkCopy") {
            line { rrect(8.5f, 8.5f, 20f, 20f, 2.5f) }
            line { moveTo(5f, 15.5f); lineTo(5f, 6.5f); arcTo(2.5f, 2.5f, 0f, false, true, 7.5f, 4f); lineTo(15.5f, 4f) }
        }
    }
    val Delete by lazy {
        icon("ZkDelete") {
            line { poly(4.5f, 6.5f, 19.5f, 6.5f) }
            line { poly(9.5f, 6.5f, 9.5f, 4.5f, 14.5f, 4.5f, 14.5f, 6.5f) }
            line { moveTo(6.5f, 6.5f); lineTo(7.4f, 19f); arcTo(1.5f, 1.5f, 0f, false, false, 8.9f, 20.4f); lineTo(15.1f, 20.4f); arcTo(1.5f, 1.5f, 0f, false, false, 16.6f, 19f); lineTo(17.5f, 6.5f) }
            line(1.8f) { poly(10.3f, 10f, 10.3f, 16.5f) }
            line(1.8f) { poly(13.7f, 10f, 13.7f, 16.5f) }
        }
    }
    val DeleteSweep by lazy {
        icon("ZkDeleteSweep") {
            line { poly(2.5f, 7f, 12.5f, 7f) }
            line { moveTo(4f, 7f); lineTo(4.7f, 18.8f); arcTo(1.3f, 1.3f, 0f, false, false, 6f, 20f); lineTo(9f, 20f); arcTo(1.3f, 1.3f, 0f, false, false, 10.3f, 18.8f); lineTo(11f, 7f) }
            line { poly(6f, 7f, 6f, 5f, 9f, 5f, 9f, 7f) }
            line { poly(15f, 9f, 21f, 9f) }
            line { poly(15f, 13f, 20f, 13f) }
            line { poly(15f, 17f, 18.5f, 17f) }
        }
    }
    val Search by lazy { icon("ZkSearch") { line { circle(10.5f, 10.5f, 6f) }; line(2.2f) { poly(15f, 15f, 20f, 20f) } } }
    val Stop by lazy { icon("ZkStop") { solid { rrect(6.5f, 6.5f, 17.5f, 17.5f, 2.5f) } } }
    val Dot by lazy { icon("ZkDot") { solid { circle(12f, 12f, 5.5f) } } }
    val Settings by lazy {
        icon("ZkSettings") {
            line { poly(12f, 3f, 19.8f, 7.5f, 19.8f, 16.5f, 12f, 21f, 4.2f, 16.5f, 4.2f, 7.5f, closed = true) }
            line { circle(12f, 12f, 3.2f) }
        }
    }
    val Tune by lazy {
        icon("ZkTune") {
            line { poly(4f, 7f, 7f, 7f) }; line { poly(11f, 7f, 20f, 7f) }; line { circle(9f, 7f, 2f) }
            line { poly(4f, 12f, 13f, 12f) }; line { poly(17f, 12f, 20f, 12f) }; line { circle(15f, 12f, 2f) }
            line { poly(4f, 17f, 6f, 17f) }; line { poly(10f, 17f, 20f, 17f) }; line { circle(8f, 17f, 2f) }
        }
    }

    /* ---------------- status ---------------- */

    val Warning by lazy {
        icon("ZkWarning") {
            line { moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close() }
            line(2.2f) { poly(12f, 10f, 12f, 14f) }
            line(2.8f) { poly(12f, 16.9f, 12f, 16.95f) }
        }
    }
    val Shield by lazy { icon("ZkShield") { shield(this) } }
    val Verified by lazy { icon("ZkVerified") { shield(this); line { poly(8.8f, 12f, 11.2f, 14.4f, 15.4f, 9.8f) } } }
    val Security by lazy {
        icon("ZkSecurity") {
            shield(this)
            line(1.8f) { rrect(9.3f, 11.3f, 14.7f, 15.8f, 1.2f) }
            line(1.8f) { moveTo(10.5f, 11.3f); lineTo(10.5f, 10f); arcTo(1.5f, 1.5f, 0f, false, true, 13.5f, 10f); lineTo(13.5f, 11.3f) }
        }
    }
    val ShieldAlert by lazy { icon("ZkShieldAlert") { shield(this); line(2.2f) { poly(12f, 8f, 12f, 12.5f) }; line(2.8f) { poly(12f, 15.5f, 12f, 15.55f) } } }
    val Health by lazy { icon("ZkHealth") { shield(this); line { poly(12f, 8.5f, 12f, 15f) }; line { poly(8.8f, 11.8f, 15.2f, 11.8f) } } }
    val SensorsOff by lazy {
        icon("ZkSensorsOff") {
            line { moveTo(7.8f, 7.8f); curveTo(5.5f, 10.1f, 5.5f, 13.9f, 7.8f, 16.2f) }
            line { moveTo(16.2f, 7.8f); curveTo(18.5f, 10.1f, 18.5f, 13.9f, 16.2f, 16.2f) }
            line { moveTo(4.9f, 4.9f); curveTo(1f, 8.8f, 1f, 15.2f, 4.9f, 19.1f) }
            line { moveTo(19.1f, 4.9f); curveTo(23f, 8.8f, 23f, 15.2f, 19.1f, 19.1f) }
            line { poly(4f, 4f, 20f, 20f) }
        }
    }

    /* ---------------- performance and data ---------------- */

    val Bolt by lazy { icon("ZkBolt") { line { poly(13.5f, 3f, 5.5f, 13.5f, 11.2f, 13.5f, 10.5f, 21f, 18.5f, 10.5f, 12.8f, 10.5f, closed = true) } } }
    val Speed by lazy {
        icon("ZkSpeed") {
            line { moveTo(4f, 16f); arcTo(8f, 8f, 0f, false, true, 20f, 16f) }
            line { poly(12f, 16f, 16f, 10.5f) }
            solid { dot(12f, 16f, 1.6f) }
        }
    }
    val Pulse by lazy { icon("ZkPulse") { line { poly(3f, 12.5f, 7f, 12.5f, 9.5f, 7f, 13.5f, 17.5f, 16f, 12.5f, 21f, 12.5f) } } }
    val Insights by lazy {
        icon("ZkInsights") {
            line { poly(3.5f, 18f, 9f, 12.5f, 13f, 15.5f, 19.5f, 9f) }
            solid { sparkle(18.5f, 4.5f, 3f) }
        }
    }
    val Chart by lazy {
        icon("ZkChart") {
            line(2.6f) { poly(6f, 19f, 6f, 13f) }
            line(2.6f) { poly(12f, 19f, 12f, 6.5f) }
            line(2.6f) { poly(18f, 19f, 18f, 10f) }
        }
    }
    val TrendingDown by lazy { icon("ZkTrendingDown") { line { poly(3f, 7f, 9.5f, 13.5f, 13.5f, 9.5f, 20.5f, 16.5f) }; line { poly(20.5f, 11.5f, 20.5f, 16.5f, 15.5f, 16.5f) } } }
    val Balance by lazy {
        icon("ZkBalance") {
            line { poly(12f, 4f, 12f, 20f) }; line { poly(8f, 20f, 16f, 20f) }; line { poly(4.5f, 7f, 19.5f, 7f) }
            line { poly(2.5f, 13f, 4.5f, 7f, 6.5f, 13f) }; line { moveTo(2.5f, 13f); quadTo(4.5f, 15.5f, 6.5f, 13f) }
            line { poly(17.5f, 13f, 19.5f, 7f, 21.5f, 13f) }; line { moveTo(17.5f, 13f); quadTo(19.5f, 15.5f, 21.5f, 13f) }
        }
    }
    val Profiles by lazy {
        icon("ZkProfiles") {
            line { rrect(8f, 4.5f, 16f, 19.5f, 2.2f) }
            line { poly(4.5f, 7f, 4.5f, 17f) }
            line { poly(19.5f, 7f, 19.5f, 17f) }
        }
    }
    val Thermometer by lazy {
        icon("ZkThermometer") {
            line { moveTo(10f, 14.2f); lineTo(10f, 5.5f); arcTo(2f, 2f, 0f, false, true, 14f, 5.5f); lineTo(14f, 14.2f); arcTo(4f, 4f, 0f, true, true, 10f, 14.2f); close() }
            line { poly(12f, 9f, 12f, 16f) }
            solid { dot(12f, 17.4f, 1.7f) }
        }
    }
    val Memory by lazy {
        icon("ZkMemory") {
            line { rrect(6f, 6f, 18f, 18f, 2f) }
            line(1.6f) { rrect(9.5f, 9.5f, 14.5f, 14.5f, 1f) }
            for (v in listOf(9f, 12f, 15f)) {
                line(1.6f) { poly(v, 3f, v, 6f) }; line(1.6f) { poly(v, 18f, v, 21f) }
                line(1.6f) { poly(3f, v, 6f, v) }; line(1.6f) { poly(18f, v, 21f, v) }
            }
        }
    }
    val Chip by lazy {
        icon("ZkChip") {
            line { rrect(3f, 5f, 21f, 19f, 2.5f) }
            line(1.8f) { rrect(6f, 8f, 12f, 16f, 1.2f) }
            for (y in listOf(9f, 12f, 15f)) line(1.8f) { poly(14.5f, y, 18f, y) }
        }
    }
    val Storage by lazy {
        icon("ZkStorage") {
            line { rrect(4f, 4f, 20f, 11f, 2f) }
            line { rrect(4f, 13f, 20f, 20f, 2f) }
            solid { dot(7.6f, 7.5f) }; solid { dot(7.6f, 16.5f) }
        }
    }
    val Terminal by lazy {
        icon("ZkTerminal") {
            line { rrect(3f, 4.5f, 21f, 19.5f, 2.5f) }
            line { poly(7f, 9.5f, 9.8f, 12f, 7f, 14.5f) }
            line { poly(12f, 15f, 16.5f, 15f) }
        }
    }
    val Android by lazy {
        icon("ZkAndroid") {
            line { moveTo(4.5f, 17f); arcTo(7.5f, 7.5f, 0f, false, true, 19.5f, 17f); close() }
            line { poly(8.2f, 10.6f, 6.5f, 8f) }; line { poly(15.8f, 10.6f, 17.5f, 8f) }
            solid { dot(9.3f, 13.8f) }; solid { dot(14.7f, 13.8f) }
        }
    }
    val Phone by lazy { icon("ZkPhone") { line { rrect(6.5f, 2.5f, 17.5f, 21.5f, 2.5f) }; line { poly(10.5f, 18.5f, 13.5f, 18.5f) } } }
    val Gamepad by lazy {
        icon("ZkGamepad") {
            line {
                moveTo(7f, 8f); lineTo(17f, 8f)
                arcTo(4.5f, 4.5f, 0f, false, true, 21.3f, 13.8f); lineTo(20.1f, 17.6f)
                arcTo(2.4f, 2.4f, 0f, false, true, 16.2f, 18.6f); lineTo(14f, 16.5f); lineTo(10f, 16.5f); lineTo(7.8f, 18.6f)
                arcTo(2.4f, 2.4f, 0f, false, true, 3.9f, 17.6f); lineTo(2.7f, 13.8f)
                arcTo(4.5f, 4.5f, 0f, false, true, 7f, 8f); close()
            }
            line(1.8f) { poly(7.2f, 10.8f, 7.2f, 14.2f) }; line(1.8f) { poly(5.5f, 12.5f, 8.9f, 12.5f) }
            solid { dot(15.8f, 11.4f) }; solid { dot(17.6f, 13.6f) }
        }
    }
    val Battery by lazy { icon("ZkBattery") { battery(this); line(1.8f) { poly(10f, 17f, 14f, 17f) } } }
    val BatteryCharging by lazy { icon("ZkBatteryCharging") { battery(this); line(1.8f) { poly(12.8f, 8.5f, 10f, 13f, 14f, 13f, 11.2f, 17.5f) } } }
    val BatterySaver by lazy { icon("ZkBatterySaver") { battery(this); line(1.8f) { poly(12f, 10f, 12f, 16f) }; line(1.8f) { poly(9f, 13f, 15f, 13f) } } }
    val Plug by lazy {
        icon("ZkPlug") {
            line { poly(9f, 3f, 9f, 7f) }; line { poly(15f, 3f, 15f, 7f) }
            line { moveTo(6f, 7f); lineTo(18f, 7f); lineTo(18f, 10f); arcTo(6f, 6f, 0f, false, true, 6f, 10f); close() }
            line { poly(12f, 16f, 12f, 21f) }
        }
    }
    val Usb by lazy {
        icon("ZkUsb") {
            line { poly(12f, 3.5f, 12f, 18.5f) }; line { poly(9.8f, 6f, 12f, 3.5f, 14.2f, 6f) }
            line { poly(12f, 14f, 7.5f, 11f, 7.5f, 9f) }; line { circle(7.5f, 7.8f, 1.2f) }
            line { poly(12f, 12.5f, 16.5f, 9.5f, 16.5f, 8f) }; line { rrect(15.4f, 5.8f, 17.6f, 8f, 0.4f) }
            solid { dot(12f, 19.8f, 1.7f) }
        }
    }
    val Rocket by lazy {
        icon("ZkRocket") {
            line { moveTo(12f, 3f); curveTo(15.5f, 5.5f, 16.5f, 9.5f, 15.5f, 14.5f); lineTo(8.5f, 14.5f); curveTo(7.5f, 9.5f, 8.5f, 5.5f, 12f, 3f); close() }
            line { circle(12f, 9f, 1.6f) }
            line { poly(8.6f, 11.5f, 5.8f, 15.5f, 8.9f, 16f) }; line { poly(15.4f, 11.5f, 18.2f, 15.5f, 15.1f, 16f) }
            line { poly(10.5f, 17.5f, 12f, 21f, 13.5f, 17.5f) }
        }
    }
    val Sparkle by lazy { icon("ZkSparkle") { solid { sparkle(10f, 13f, 7f) }; solid { sparkle(18.5f, 5.5f, 3.2f) } } }
    val AutoMode by lazy {
        icon("ZkAutoMode") {
            line { moveTo(19.5f, 12f); arcTo(7.5f, 7.5f, 0f, true, true, 17.3f, 6.7f) }
            line { poly(19.5f, 3.8f, 19.5f, 8.2f, 15.1f, 8.2f) }
            solid { sparkle(12f, 12f, 3.6f) }
        }
    }
    val Home by lazy {
        icon("ZkHome") {
            line { moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f); lineTo(20f, 19f); arcTo(1.5f, 1.5f, 0f, false, true, 18.5f, 20.5f); lineTo(5.5f, 20.5f); arcTo(1.5f, 1.5f, 0f, false, true, 4f, 19f); close() }
            line { poly(10f, 20.5f, 10f, 15f, 14f, 15f, 14f, 20.5f) }
        }
    }
    val Palette by lazy {
        icon("ZkPalette") {
            line {
                moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, false, 12f, 21f)
                curveTo(13.4f, 21f, 14f, 20f, 14f, 19f); curveTo(14f, 17.8f, 13f, 17.4f, 13f, 16.4f)
                curveTo(13f, 15.4f, 13.8f, 14.8f, 14.8f, 14.8f); lineTo(17f, 14.8f)
                arcTo(4f, 4f, 0f, false, false, 21f, 10.8f); curveTo(21f, 6.6f, 17f, 3f, 12f, 3f); close()
            }
            solid { dot(7.5f, 11.5f) }; solid { dot(9.5f, 7.5f) }; solid { dot(14.5f, 7.5f) }; solid { dot(17.2f, 10.8f) }
        }
    }

    /* ---------------- legal, setup and settings ---------------- */

    val Document by lazy {
        icon("ZkDocument") {
            line { moveTo(14f, 3f); lineTo(7.5f, 3f); arcTo(1.5f, 1.5f, 0f, false, false, 6f, 4.5f); lineTo(6f, 19.5f); arcTo(1.5f, 1.5f, 0f, false, false, 7.5f, 21f); lineTo(16.5f, 21f); arcTo(1.5f, 1.5f, 0f, false, false, 18f, 19.5f); lineTo(18f, 7f); close() }
            line { poly(14f, 3f, 14f, 7f, 18f, 7f) }
            line(1.8f) { poly(9f, 12f, 15f, 12f) }; line(1.8f) { poly(9f, 15.5f, 15f, 15.5f) }
        }
    }
    val Lock by lazy {
        icon("ZkLock") {
            line { rrect(5f, 10.5f, 19f, 20.5f, 2.5f) }
            line { moveTo(8f, 10.5f); lineTo(8f, 8f); arcTo(4f, 4f, 0f, false, true, 16f, 8f); lineTo(16f, 10.5f) }
            line(2.2f) { poly(12f, 14.5f, 12f, 16.5f) }
        }
    }
    val Key by lazy {
        icon("ZkKey") {
            line { circle(8f, 12f, 3.6f) }
            line { poly(11.6f, 12f, 20.5f, 12f) }
            line { poly(17.2f, 12f, 17.2f, 15f) }; line { poly(20.5f, 12f, 20.5f, 14.5f) }
        }
    }
    val Package by lazy {
        icon("ZkPackage") {
            line { poly(12f, 3f, 20f, 7.5f, 20f, 16.5f, 12f, 21f, 4f, 16.5f, 4f, 7.5f, closed = true) }
            line { poly(4f, 7.5f, 12f, 12f, 20f, 7.5f) }
            line { poly(12f, 12f, 12f, 21f) }
        }
    }
    val Badge by lazy {
        icon("ZkBadge") {
            line { moveTo(3.5f, 12f); lineTo(3.5f, 5f); arcTo(1.5f, 1.5f, 0f, false, true, 5f, 3.5f); lineTo(12f, 3.5f); lineTo(20.5f, 12f); lineTo(12f, 20.5f); close() }
            solid { dot(7.6f, 7.6f, 1.4f) }
        }
    }
    val Contrast by lazy {
        icon("ZkContrast") {
            line { circle(12f, 12f, 8.5f) }
            solid { moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, false, true, 12f, 20.5f); close() }
        }
    }
    val Sun by lazy {
        icon("ZkSun") {
            line { circle(12f, 12f, 4f) }
            for (k in 0 until 8) {
                val a = k * PI / 4
                line { poly((12 + 7 * cos(a)).toFloat(), (12 + 7 * sin(a)).toFloat(), (12 + 9 * cos(a)).toFloat(), (12 + 9 * sin(a)).toFloat()) }
            }
        }
    }
    val Moon by lazy { icon("ZkMoon") { line { moveTo(20f, 14.5f); arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 4f); arcTo(6.6f, 6.6f, 0f, false, false, 20f, 14.5f); close() } } }
    val Bell by lazy {
        icon("ZkBell") {
            line { moveTo(6f, 16.5f); lineTo(6f, 11f); arcTo(6f, 6f, 0f, false, true, 18f, 11f); lineTo(18f, 16.5f); lineTo(19.5f, 18f); lineTo(4.5f, 18f); close() }
            line { moveTo(10f, 20.5f); quadTo(12f, 22f, 14f, 20.5f) }
        }
    }

    /** Every icon, for previews and the render test. */
    val all: List<Pair<String, ImageVector>> by lazy {
        listOf(
            "Globe" to Globe, "Info" to Info, "Mark" to Mark, "Check" to Check, "CheckCircle" to CheckCircle, "Close" to Close,
            "Add" to Add, "ArrowForward" to ArrowForward, "ArrowBack" to ArrowBack, "ChevronRight" to ChevronRight,
            "ChevronLeft" to ChevronLeft, "ExpandMore" to ExpandMore, "ExpandLess" to ExpandLess, "Refresh" to Refresh,
            "Sync" to Sync, "History" to History, "Copy" to Copy, "Delete" to Delete, "DeleteSweep" to DeleteSweep,
            "Search" to Search, "Stop" to Stop, "Dot" to Dot, "Settings" to Settings, "Tune" to Tune, "Warning" to Warning,
            "Shield" to Shield, "Verified" to Verified, "Security" to Security, "ShieldAlert" to ShieldAlert, "Health" to Health,
            "SensorsOff" to SensorsOff, "Bolt" to Bolt, "Speed" to Speed, "Pulse" to Pulse, "Insights" to Insights,
            "Chart" to Chart, "TrendingDown" to TrendingDown, "Balance" to Balance, "Profiles" to Profiles,
            "Thermometer" to Thermometer, "Memory" to Memory, "Chip" to Chip, "Storage" to Storage, "Terminal" to Terminal,
            "Android" to Android, "Phone" to Phone, "Gamepad" to Gamepad, "Battery" to Battery,
            "BatteryCharging" to BatteryCharging, "BatterySaver" to BatterySaver, "Plug" to Plug, "Usb" to Usb,
            "Rocket" to Rocket, "Sparkle" to Sparkle, "AutoMode" to AutoMode, "Home" to Home, "Palette" to Palette,
            "Document" to Document, "Lock" to Lock, "Key" to Key, "Package" to Package, "Badge" to Badge,
            "Contrast" to Contrast, "Sun" to Sun, "Moon" to Moon, "Bell" to Bell,
        )
    }
}
