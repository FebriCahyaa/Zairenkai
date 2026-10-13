// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Brand-aware device identity: a brand mark and the device hero card used by
 * About phone and the setup info sheet.
 *
 * The marks are Zairenkai's own: a squircle in colours associated with the
 * brand, carrying a monogram set in the app's typeface, and the brand name
 * as plain text. They are not the makers' logos, which are trademarks and are
 * not bundled. To show an official logo you are entitled to use, give the
 * brand a drawable in [officialLogo]; [BrandMark] then draws it instead.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.device

import com.zairenkai.app.ui.icons.ZkIcons
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zairenkai.app.R
import com.zairenkai.app.ui.theme.readableInk

@Immutable
data class BrandStyle(
    /** Two gradient stops for the squircle (associated colours, not logo artwork). */
    val colors: List<Color>,
    /** The original emblem drawn inside the squircle. */
    val emblem: BrandEmblem,
    /** A faint watermark letter for the device hero; null uses the emblem's initial. */
    val monogram: String?,
    val uppercase: Boolean = false,
    val tracking: TextUnit = 0.sp,
    val weight: FontWeight = FontWeight.SemiBold,
    @DrawableRes val officialLogo: Int? = null,
)

/**
 * An original, brand-evocative emblem. These are Zairenkai's own geometric
 * marks — a motif and palette that read as "this is a Xiaomi / a Pixel / a
 * Nothing phone" at a glance — not reproductions of the makers' trademarked
 * logos, which are never bundled. A maker you are entitled to represent with
 * its official artwork can still be given a drawable through [BrandStyle.officialLogo].
 */
enum class BrandEmblem { SPARK, OVAL, SQUARE_DOT, PETAL, CHEVRON, UNIT_DOT, RISE, DOT_MATRIX, ARCS, ANDROID }

fun brandStyle(brand: DeviceBrand): BrandStyle = when (brand) {
    DeviceBrand.GOOGLE -> BrandStyle(listOf(Color(0xFF4285F4), Color(0xFF1A73E8)), BrandEmblem.SQUARE_DOT, "G", weight = FontWeight.Medium)
    DeviceBrand.SAMSUNG -> BrandStyle(listOf(Color(0xFF1428A0), Color(0xFF2743D6)), BrandEmblem.OVAL, "S", uppercase = true, tracking = 0.18.em, weight = FontWeight.Bold)
    DeviceBrand.XIAOMI -> BrandStyle(listOf(Color(0xFFFF6900), Color(0xFFFF8A3D)), BrandEmblem.SPARK, "X")
    DeviceBrand.REDMI -> BrandStyle(listOf(Color(0xFFE3262C), Color(0xFFFF5A47)), BrandEmblem.PETAL, "R")
    DeviceBrand.POCO -> BrandStyle(listOf(Color(0xFFFFD400), Color(0xFFFFAE00)), BrandEmblem.CHEVRON, "P", uppercase = true, tracking = 0.06.em, weight = FontWeight.Bold)
    DeviceBrand.ONEPLUS -> BrandStyle(listOf(Color(0xFFEB0028), Color(0xFFFF3B49)), BrandEmblem.UNIT_DOT, "1")
    DeviceBrand.REALME -> BrandStyle(listOf(Color(0xFFFFC915), Color(0xFFFFA200)), BrandEmblem.RISE, "r", weight = FontWeight.Bold)
    DeviceBrand.NOTHING -> BrandStyle(listOf(Color(0xFF2A2A2A), Color(0xFF060606)), BrandEmblem.DOT_MATRIX, "N", tracking = 0.08.em)
    DeviceBrand.MOTOROLA -> BrandStyle(listOf(Color(0xFF0050B4), Color(0xFF3B7BE8)), BrandEmblem.ARCS, "M")
    DeviceBrand.OTHER -> BrandStyle(listOf(Color(0xFF3DDC84), Color(0xFF0E9F6E)), BrandEmblem.ANDROID, null)
}

/** Dark or light glyph, whichever reads on the squircle's first colour. */
private fun glyphOn(c: Color) = if (c.luminance() > 0.45f) Color(0xFF1B1B1F) else Color.White

/**
 * An official brand logo the owner is entitled to use, if present.
 *
 * Zairenkai bundles no maker logos (they are trademarks). To show a real logo,
 * drop a drawable you have the right to use at `res/drawable/brand_<key>.<ext>`
 * — the key is the lower-cased brand name, e.g. `brand_xiaomi.xml`,
 * `brand_samsung.webp`. It is then resolved by name at runtime and drawn in
 * place of the original emblem; if the file is absent, the emblem is used.
 * Nothing is referenced at build time, so the project compiles with no logos.
 */
@Composable
private fun officialLogoRes(brand: DeviceBrand): Int {
    val style = brandStyle(brand)
    if (style.officialLogo != null) return style.officialLogo
    val context = LocalContext.current
    return remember(brand) {
        @Suppress("DiscouragedApi")
        context.resources.getIdentifier("brand_${brand.name.lowercase()}", "drawable", context.packageName)
    }
}

/** The brand's squircle mark. Decorative: the brand name is always shown next to it as text. */
@Composable
fun BrandMark(brand: DeviceBrand, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val style = brandStyle(brand)
    val logo = officialLogoRes(brand)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(Brush.linearGradient(style.colors))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val ink = glyphOn(style.colors.first())
        when {
            logo != 0 -> Image(painterResource(logo), null, Modifier.size(size * 0.62f))
            style.emblem == BrandEmblem.ANDROID -> Icon(ZkIcons.Android, null, Modifier.size(size * 0.56f), tint = ink)
            else -> Canvas(Modifier.size(size * 0.56f)) { drawBrandEmblem(style.emblem, ink) }
        }
    }
}

/** Draws an original brand emblem to fill the canvas; [ink] is the on-colour glyph tint. */
private fun DrawScope.drawBrandEmblem(emblem: BrandEmblem, ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.14f
    when (emblem) {
        // Four rounded blades around a gap — an abstract nod to a multi-colour identity.
        BrandEmblem.SQUARE_DOT -> {
            val r = w * 0.30f
            val g = w * 0.14f
            listOf(Offset(w / 2 - r / 2 - g / 2, h / 2 - r / 2 - g / 2), Offset(w / 2 + r / 2 + g / 2, h / 2 - r / 2 - g / 2),
                Offset(w / 2 - r / 2 - g / 2, h / 2 + r / 2 + g / 2), Offset(w / 2 + r / 2 + g / 2, h / 2 + r / 2 + g / 2)).forEach {
                drawRoundRect(ink, topLeft = Offset(it.x - r / 2, it.y - r / 2), size = Size(r, r), cornerRadius = CornerRadius(r * 0.32f))
            }
        }
        // A tilted oval ring.
        BrandEmblem.OVAL -> rotate(-20f) {
            drawOval(ink, topLeft = Offset(w * 0.1f, h * 0.28f), size = Size(w * 0.8f, h * 0.44f), style = Stroke(stroke))
        }
        // A four-point spark.
        BrandEmblem.SPARK -> {
            val p = Path().apply {
                moveTo(w / 2, h * 0.08f)
                cubicTo(w * 0.56f, h * 0.44f, w * 0.56f, h * 0.44f, w * 0.92f, h / 2)
                cubicTo(w * 0.56f, h * 0.56f, w * 0.56f, h * 0.56f, w / 2, h * 0.92f)
                cubicTo(w * 0.44f, h * 0.56f, w * 0.44f, h * 0.56f, w * 0.08f, h / 2)
                cubicTo(w * 0.44f, h * 0.44f, w * 0.44f, h * 0.44f, w / 2, h * 0.08f)
                close()
            }
            drawPath(p, ink)
        }
        // A leaf / petal.
        BrandEmblem.PETAL -> {
            val p = Path().apply {
                moveTo(w * 0.2f, h * 0.8f)
                cubicTo(w * 0.2f, h * 0.3f, w * 0.5f, h * 0.15f, w * 0.85f, h * 0.2f)
                cubicTo(w * 0.85f, h * 0.7f, w * 0.6f, h * 0.85f, w * 0.2f, h * 0.8f)
                close()
            }
            drawPath(p, ink)
        }
        // A bold chevron.
        BrandEmblem.CHEVRON -> {
            val p = Path().apply {
                moveTo(w * 0.12f, h * 0.24f); lineTo(w / 2, h * 0.72f); lineTo(w * 0.88f, h * 0.24f)
            }
            drawPath(p, ink, style = Stroke(stroke * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // A rounded square outline with a centred dot — an original "unit" mark.
        BrandEmblem.UNIT_DOT -> {
            drawRoundRect(ink, topLeft = Offset(w * 0.16f, h * 0.16f), size = Size(w * 0.68f, h * 0.68f),
                cornerRadius = CornerRadius(w * 0.2f), style = Stroke(stroke))
            drawCircle(ink, radius = w * 0.1f, center = Offset(w / 2, h / 2))
        }
        // A rising line with a dot — momentum.
        BrandEmblem.RISE -> {
            drawLine(ink, Offset(w * 0.16f, h * 0.78f), Offset(w * 0.74f, h * 0.24f), strokeWidth = stroke * 1.3f, cap = StrokeCap.Round)
            drawCircle(ink, radius = w * 0.12f, center = Offset(w * 0.82f, h * 0.18f))
        }
        // A 3x3 dot matrix — the pared-back, dotted aesthetic.
        BrandEmblem.DOT_MATRIX -> {
            val r = w * 0.07f
            for (col in 0..2) for (row in 0..2) {
                drawCircle(ink, radius = r, center = Offset(w * (0.24f + col * 0.26f), h * (0.24f + row * 0.26f)))
            }
        }
        // Two concentric arcs.
        BrandEmblem.ARCS -> {
            drawArc(ink, startAngle = 150f, sweepAngle = 240f, useCenter = false,
                topLeft = Offset(w * 0.1f, h * 0.1f), size = Size(w * 0.8f, h * 0.8f), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(ink, startAngle = 150f, sweepAngle = 240f, useCenter = false,
                topLeft = Offset(w * 0.28f, h * 0.28f), size = Size(w * 0.44f, h * 0.44f), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        BrandEmblem.ANDROID -> {}
    }
}

/** The brand's name as text; other makers show their own name. */
@Composable
fun BrandName(brand: DeviceBrand, manufacturer: String, modifier: Modifier = Modifier) {
    val style = brandStyle(brand)
    val name = if (brand == DeviceBrand.OTHER) manufacturer.ifBlank { brand.label } else brand.label
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Text(
        if (style.uppercase) name.uppercase() else name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = style.weight,
        letterSpacing = style.tracking,
        // The brand colour, darkened or lightened until it reads as text (4.5:1).
        color = readableInk(style.colors.first(), surface),
        modifier = modifier,
    )
}

/**
 * The device hero: brand mark and name, the retail name large, the model and
 * codename, and a few facts as chips. A soft wash of the brand's colours and
 * a large monogram watermark give each brand its own look.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceHero(info: DeviceInfo?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val style = brandStyle(info?.brand ?: DeviceBrand.OTHER)
    val wash = Brush.linearGradient(listOf(style.colors[0].copy(alpha = 0.20f), style.colors[1].copy(alpha = 0.05f), Color.Transparent))
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = cs.surfaceContainer,
        contentColor = cs.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.background(wash)) {
            if (info == null) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                return@Box
            }
            // Watermark: the monogram, very large and faint, bleeding off the corner.
            // matchParentSize: the watermark never makes the card taller.
            style.monogram?.let {
                Box(Modifier.matchParentSize().clipToBounds().clearAndSetSemantics {}) {
                    Text(
                        it,
                        fontSize = 220.sp,
                        fontWeight = FontWeight.Bold,
                        color = style.colors[0].copy(alpha = 0.08f),
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .wrapContentSize(Alignment.BottomEnd, unbounded = true)
                            .offset(x = 24.dp, y = 72.dp),
                    )
                }
            }
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(info.brand, size = 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        BrandName(info.brand, info.manufacturer)
                        Text(
                            listOf(info.model, info.codename).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    info.productName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = cs.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                if (info.deviceName.isNotBlank() && info.deviceName != info.productName && info.deviceName != info.model) {
                    Text(
                        stringResource(R.string.device_user_name, info.deviceName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip(stringResource(R.string.device_chip_android, info.androidRelease))
                    info.socModel.takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.let { HeroChip(it) }
                    HeroChip(stringResource(R.string.device_chip_ram, Formatter.formatShortFileSize(context, info.ramTotal)))
                    if (info.displayWidth != null && info.refreshRateMax != null) {
                        HeroChip(stringResource(R.string.device_hz, info.refreshRateMax.toInt()))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroChip(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
