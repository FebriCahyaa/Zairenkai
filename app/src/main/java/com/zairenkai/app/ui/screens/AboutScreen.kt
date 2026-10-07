// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * About — simple but expressive. A raised banner with an animated liquid
 * gradient (morphing blobs), then Material 3 info cards.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.core.identity.ZairenkaiIdentity
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Tentang") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ChevronLeft, "Kembali")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LiquidBanner()

            ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp)) {
                    AboutRow("Versi", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                    AboutRow("Paket", BuildConfig.APPLICATION_ID)
                    AboutRow("Product ID", ZairenkaiIdentity.PRODUCT_ID)
                    AboutRow("Core ID", ZairenkaiIdentity.CORE_ID)
                    AboutRow("Pembuat", "FEBRIAN RAHMAD CAHYA")
                    AboutRow("ZKFC API", ZairenkaiIdentity.CORE_API.toString())
                }
            }

            ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Lisensi & kredit", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Kernel ZKFC berlisensi GPL-2.0. Aplikasi, engine, modul, dan tools " +
                            "berlisensi Zairenkai Proprietary. Font Roboto Flex (OFL-1.1). " +
                            "Verifier Ed25519/SHA-512 berbasis TweetNaCl (domain publik).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("© 2026 FebriCahyaa. Hak cipta dilindungi.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(
                "github.com/FebriCahyaa/Zairenkai",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AboutRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * Raised banner with an animated liquid gradient: several blobs drift and
 * breathe on a blurred layer so they melt into each other, with the brand
 * title floating above on a translucent glass chip.
 */
@Composable
private fun LiquidBanner() {
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
        modifier = Modifier.fillMaxWidth().height(220.dp),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 10.dp,
        tonalElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            // deep base gradient
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF3B2AE0), Color(0xFF8B2AD0), Color(0xFFE5484D))),
                ),
            )
            // morphing blobs on a blurred layer
            Canvas(Modifier.fillMaxSize().blur(26.dp)) {
                val w = size.width
                val h = size.height
                blobs.forEachIndexed { i, c ->
                    val a = (phase + i * 1.04f).toDouble()
                    val cx = w * (0.5f + 0.42f * cos(a + i).toFloat())
                    val cy = h * (0.5f + 0.4f * sin(a * 0.8 + i).toFloat())
                    val r = minOf(w, h) * (0.34f + 0.12f * sin(a * 1.3).toFloat())
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0f)),
                            center = Offset(cx, cy), radius = r,
                        ),
                        radius = r, center = Offset(cx, cy),
                    )
                }
            }
            // subtle sheen
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)),
                ),
            )
            // floating glass title chip ("timbul")
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.16f),
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Zairenkai", color = Color.White, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall)
                        Text("Tweaks Suite • Material 3 Expressive",
                            color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
