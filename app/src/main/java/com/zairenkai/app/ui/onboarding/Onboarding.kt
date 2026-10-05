// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * First-run setup. Material 3 Expressive motion: spring-driven step
 * transitions, an animated logo reveal, and a live root/ZKFC check.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zairenkai.app.AppContainer
import com.zairenkai.app.domain.Profiles
import com.zairenkai.app.ui.theme.ZkAqua
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkWarn
import kotlinx.coroutines.launch

private enum class RootCheck { CHECKING, OK, MISSING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Onboarding(container: AppContainer, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val steps = 4
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf(RootCheck.CHECKING) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val ok = container.repository.rootAvailable()
        if (ok) {
            val v = runCatching { container.repository.info() }.getOrNull()
            info = v?.let { "${it.arch} • ${it.kernelType.uppercase()} • hook ${it.hookMode}" }
        }
        root = if (ok) RootCheck.OK else RootCheck.MISSING
    }

    val progress by animateFloatAsState(
        targetValue = (step + 1f) / steps,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "progress",
    )

    Scaffold(
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step > 0) {
                        OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) {
                            Text("Kembali")
                        }
                    }
                    Button(
                        onClick = {
                            if (step < steps - 1) step++
                            else scope.launch { container.settings.setOnboarded(true); onDone() }
                        },
                        modifier = Modifier.weight(if (step > 0) 1.6f else 1f),
                    ) {
                        Text(if (step < steps - 1) "Lanjut" else "Mulai", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            if (step < steps - 1) Icons.Rounded.ArrowForward else Icons.Rounded.Check,
                            contentDescription = null,
                        )
                    }
                }
            }
        },
    ) { inner ->
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(spring(stiffness = Spring.StiffnessLow)) { w -> dir * w } + fadeIn())
                    .togetherWith(slideOutHorizontally(tween(260)) { w -> -dir * w } + fadeOut())
            },
            modifier = Modifier.padding(inner).fillMaxSize(),
            label = "step",
        ) { s ->
            when (s) {
                0 -> StepWelcome()
                1 -> StepFeatures()
                2 -> StepRoot(root, info)
                else -> StepProfiles()
            }
        }
    }
}

@Composable
private fun StepScaffold(
    icon: ImageVector,
    title: String,
    body: String,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val scale by animateFloatAsState(
            1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "iconScale",
        )
        Surface(
            modifier = Modifier.size(104.dp).scale(scale),
            shape = RoundedCornerShape(32.dp),
            color = accent.copy(alpha = 0.16f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(52.dp), tint = accent)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
private fun StepWelcome() {
    val rot by animateFloatAsState(0f, tween(600), label = "logoRot")
    StepScaffold(
        icon = Icons.Rounded.Bolt,
        title = "Selamat datang di Zairenkai",
        body = "Suite tweak performa bertenaga root dengan kerangka kernel bertanda tangan. " +
            "Mari siapkan dalam beberapa langkah.",
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(28.dp).rotate(rot), tint = ZkAqua)
    }
}

@Composable
private fun StepFeatures() {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Yang bisa kamu lakukan", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        val items = listOf(
            Triple(Icons.Rounded.Speed, "Monitor realtime", "FPS, CPU/GPU per-core, dan thermal."),
            Triple(Icons.Rounded.Tune, "Tweaks menyeluruh", "CPU, GPU, RAM, zram, I/O, jaringan, layar."),
            Triple(Icons.Rounded.AutoMode, "Profil otomatis", "Game, Harian, Media, Hemat — atau Auto."),
            Triple(Icons.Rounded.Shield, "Aman & terkunci", "Fitur performa dibuka oleh token API bertanda tangan."),
        )
        items.forEachIndexed { i, (ic, t, b) ->
            val alpha by animateFloatAsState(1f, tween(400, delayMillis = i * 90), label = "feat$i")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).scale(alpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(ic, null, Modifier.padding(12.dp).size(24.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(t, style = MaterialTheme.typography.titleMedium)
                    Text(b, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StepRoot(root: RootCheck, info: String?) {
    val (icon, title, body, accent) = when (root) {
        RootCheck.CHECKING -> arrayOf(Icons.Rounded.Sync, "Memeriksa root…", "Sebentar, kami cek akses root dan modul ZKFC.", ZkWarn)
        RootCheck.OK -> arrayOf(Icons.Rounded.VerifiedUser, "Root terdeteksi", info ?: "ZKFC siap digunakan.", ZkGood)
        RootCheck.MISSING -> arrayOf(Icons.Rounded.GppMaybe, "Root belum tersedia", "Pasang Magisk/KernelSU/APatch + modul ZKFC, lalu buka lagi. Kamu tetap bisa menjelajah app.", ZkWarn)
    }
    StepScaffold(
        icon = icon as ImageVector,
        title = title as String,
        body = body as String,
        accent = accent as androidx.compose.ui.graphics.Color,
    ) {
        if (root == RootCheck.CHECKING) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun StepProfiles() {
    StepScaffold(
        icon = Icons.Rounded.Rocket,
        title = "Siap dioptimalkan",
        body = "Setelah ini, pilih profil seperti ${Profiles.GAME.name} atau ${Profiles.DAILY.name} di tab Profil, " +
            "atau nyalakan mode Otomatis. Semua bisa diubah kapan saja.",
        accent = ZkAqua,
    )
}
