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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    /** The monogram; null draws the Android mark (other makers). */
    val monogram: String?,
    val uppercase: Boolean = false,
    val tracking: TextUnit = 0.sp,
    val weight: FontWeight = FontWeight.SemiBold,
    /** A small accent dot on the squircle. */
    val dot: Color? = null,
    @DrawableRes val officialLogo: Int? = null,
)

fun brandStyle(brand: DeviceBrand): BrandStyle = when (brand) {
    DeviceBrand.GOOGLE -> BrandStyle(listOf(Color(0xFF4285F4), Color(0xFF34A853)), "G", weight = FontWeight.Medium)
    DeviceBrand.SAMSUNG -> BrandStyle(listOf(Color(0xFF1428A0), Color(0xFF3D5AFE)), "S", uppercase = true, tracking = 0.18.em, weight = FontWeight.Bold)
    DeviceBrand.XIAOMI -> BrandStyle(listOf(Color(0xFFFF6900), Color(0xFFFF9248)), "X")
    DeviceBrand.REDMI -> BrandStyle(listOf(Color(0xFFE3262C), Color(0xFFFF6A52)), "R")
    DeviceBrand.POCO -> BrandStyle(listOf(Color(0xFFFFD400), Color(0xFFFFB000)), "P", uppercase = true, tracking = 0.06.em, weight = FontWeight.Bold)
    DeviceBrand.ONEPLUS -> BrandStyle(listOf(Color(0xFFEB0028), Color(0xFFFF4D5E)), "O")
    DeviceBrand.REALME -> BrandStyle(listOf(Color(0xFFFFC915), Color(0xFFFFA200)), "r", weight = FontWeight.Bold)
    DeviceBrand.NOTHING -> BrandStyle(listOf(Color(0xFF2A2A2A), Color(0xFF050505)), "N", tracking = 0.08.em, dot = Color(0xFFD71921))
    DeviceBrand.MOTOROLA -> BrandStyle(listOf(Color(0xFF0050B4), Color(0xFF5C92FA)), "M")
    DeviceBrand.OTHER -> BrandStyle(listOf(Color(0xFF3DDC84), Color(0xFF0E9F6E)), null)
}

/** Dark or light glyph, whichever reads on the squircle's first colour. */
private fun glyphOn(c: Color) = if (c.luminance() > 0.45f) Color(0xFF1B1B1F) else Color.White

/** The brand's squircle mark. Decorative: the brand name is always shown next to it as text. */
@Composable
fun BrandMark(brand: DeviceBrand, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val style = brandStyle(brand)
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
            style.officialLogo != null -> Image(painterResource(style.officialLogo), null, Modifier.size(size * 0.6f))
            style.monogram == null -> Icon(ZkIcons.Android, null, Modifier.size(size * 0.56f), tint = ink)
            else -> {
                val fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() }
                Text(
                    style.monogram,
                    color = ink,
                    fontSize = fontSize,
                    fontWeight = style.weight,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.offset(y = -(size * 0.02f)),
                )
            }
        }
        style.dot?.let {
            Box(Modifier.align(Alignment.TopEnd).padding(size * 0.16f).size(size * 0.12f).clip(CircleShape).background(it))
        }
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
