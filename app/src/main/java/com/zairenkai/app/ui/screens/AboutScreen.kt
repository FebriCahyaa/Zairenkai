// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * About — an expressive hero with an animated liquid gradient and the mascot,
 * then grouped identity rows and the licence/credits. All original artwork.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import com.zairenkai.app.ui.icons.ZkIcons
import com.zairenkai.app.ui.components.CollapsingScaffold
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.R
import com.zairenkai.app.core.identity.ZairenkaiIdentity
import com.zairenkai.app.ui.mascot.Mascot
import com.zairenkai.app.ui.mascot.MascotState
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    CollapsingScaffold(stringResource(R.string.about_title), onBack = onBack) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroBanner()

            InfoGroup {
                InfoRow(ZkIcons.Info, stringResource(R.string.about_version), BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                InfoDivider()
                InfoRow(ZkIcons.Package, stringResource(R.string.about_package), BuildConfig.APPLICATION_ID)
                InfoDivider()
                InfoRow(ZkIcons.Badge, stringResource(R.string.about_product_id), ZairenkaiIdentity.PRODUCT_ID)
                InfoDivider()
                InfoRow(ZkIcons.Chip, stringResource(R.string.about_core_id), ZairenkaiIdentity.CORE_ID)
                InfoDivider()
                InfoRow(ZkIcons.Verified, stringResource(R.string.about_author), "FEBRIAN RAHMAD CAHYA")
                InfoDivider()
                InfoRow(ZkIcons.Bolt, stringResource(R.string.about_zkfc_api), ZairenkaiIdentity.CORE_API.toString())
            }

            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(ZkIcons.Document)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.about_license_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.about_license_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.about_copyright),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    "github.com/FebriCahyaa/Zairenkai",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun InfoGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoDivider() =
    HorizontalDivider(Modifier.padding(start = 62.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun IconTile(icon: ImageVector) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Icon(icon, null, Modifier.padding(8.dp).size(18.dp))
    }
}

/**
 * Raised hero: a drifting liquid gradient, the mascot resting on it, and the
 * brand name with the version on a translucent glass chip.
 */
@Composable
private fun HeroBanner() {
    val t = rememberInfiniteTransition(label = "liquid")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val blobs = listOf(
        Color(0xFF5B5BF0), Color(0xFF8B46E6), Color(0xFFE5484D),
        Color(0xFFFF8A4C), Color(0xFFD23B6B), Color(0xFF00C2B8),
    )
    Surface(
        modifier = Modifier.fillMaxWidth().height(230.dp),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 10.dp,
        tonalElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B2AE0), Color(0xFF8B2AD0), Color(0xFFE5484D)))))
            Canvas(Modifier.fillMaxSize().blur(26.dp)) {
                val w = size.width
                val h = size.height
                blobs.forEachIndexed { i, c ->
                    val a = (phase + i * 1.04f).toDouble()
                    val cx = w * (0.5f + 0.42f * cos(a + i).toFloat())
                    val cy = h * (0.5f + 0.4f * sin(a * 0.8 + i).toFloat())
                    val r = minOf(w, h) * (0.34f + 0.12f * sin(a * 1.3).toFloat())
                    drawCircle(
                        brush = Brush.radialGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0f)), center = Offset(cx, cy), radius = r),
                        radius = r, center = Offset(cx, cy),
                    )
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent))))
            Mascot(MascotState.IDLE, Modifier.align(Alignment.TopEnd).padding(8.dp), size = 118.dp)
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.16f),
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ZkIcons.Bolt, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Zairenkai", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.about_version) + " " + BuildConfig.VERSION_NAME,
                            color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
