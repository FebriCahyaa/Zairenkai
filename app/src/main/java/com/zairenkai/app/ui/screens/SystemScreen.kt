// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * System: identity (UID/GID/groups), API security reports, ZKFC license and
 * access policy. Includes ZKFC API Token install.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.data.SignatureGuard
import com.zairenkai.app.data.SignatureState
import com.zairenkai.app.data.ZkfctlClient
import com.zairenkai.app.ui.SystemViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.SectionCard
import com.zairenkai.app.ui.theme.ZkBad
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkWarn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(factory: VmFactory, onOpenRuntime: () -> Unit = {}) {
    val vm: SystemViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tokenMsg by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { copyToCache(ctx, uri) }
            if (path == null) { tokenMsg = "Gagal membaca file token."; return@launch }
            val res = withContext(Dispatchers.IO) { ZkfctlClient().installToken(path) }
            tokenMsg = if (res.state == "valid") "Token terpasang untuk ${res.token?.licensee ?: "perangkat"}."
            else "Ditolak: ${res.state}"
            vm.refresh()
        }
    }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = { TopAppBar(title = { Text("Sistem & Keamanan") }) }) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val sig = remember { SignatureGuard.check(ctx) }
            SectionCard("Integritas aplikasi") {
                val (txt, col) = when (sig) {
                    SignatureState.OFFICIAL -> "Resmi (tanda tangan cocok)" to ZkGood
                    SignatureState.MODIFIED -> "Termodifikasi / dikemas ulang" to ZkBad
                    SignatureState.UNKNOWN -> "Tidak diverifikasi (debug/spoofed)" to ZkWarn
                }
                LabeledRow("Status", txt, col)
                SignatureGuard.currentSha256(ctx)?.let {
                    LabeledRow("Sidik tanda tangan", it.take(16) + "…")
                }
            }

            SectionCard("Arsitektur runtime") {
                Text(
                    "Identitas, capability runtime, thermal authority, dan subsystem registry diproyeksikan dari observation boundary Zairenkai.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenRuntime) { Text("Buka Zairenkai Runtime") }
            }

            val lic = ui.license
            SectionCard("Lisensi API (ZKFC)") {
                LabeledRow("Status", lic?.state ?: "—",
                    if (lic?.state == "valid") ZkGood else ZkWarn)
                LabeledRow("Token terpasang", if (lic?.hasToken == true) "Ya" else "Tidak")
                LabeledRow("Kunci owner", if (lic?.ownerKeyProvisioned == true) "Terpasang" else "Belum")
                lic?.ownerKeyFingerprint?.takeIf { it.isNotBlank() }?.let {
                    LabeledRow("Sidik kunci", it.take(16) + "…")
                }
                lic?.kernelBinding?.takeIf { it.isNotBlank() }?.let {
                    LabeledRow("Binding kernel", it.take(16) + "…")
                }
                lic?.token?.let { LabeledRow("Licensee", it.licensee) }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pasang token (.zkl)") }
                tokenMsg?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "Token API hanya diterbitkan oleh pemilik Zairenkai atas permintaan. " +
                        "Lihat docs/security/API_TOKENS.md.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            ui.security?.user?.let { u ->
                SectionCard("Identitas pemanggil") {
                    LabeledRow("UID", u.uid.toString())
                    LabeledRow("EUID", u.euid.toString())
                    LabeledRow("GID", u.gid.toString())
                    LabeledRow("EGID", u.egid.toString())
                    LabeledRow("CAP_SYS_ADMIN", if (u.capSysAdmin) "Ya" else "Tidak")
                    LabeledRow("Kapabilitas ZKFC", "0x%x".format(u.caps))
                }
            }

            ui.security?.system?.let { s ->
                SectionCard("Keamanan sistem") {
                    LabeledRow("SELinux", if (s.selinux) "Ada" else "Tidak",
                        if (s.selinux) ZkGood else ZkWarn)
                    LabeledRow("Module signing", if (s.moduleSigEnforced) "Dipaksa" else "Tidak")
                    LabeledRow("Kernel lockdown", if (s.lockdown) "Aktif" else "Tidak")
                    LabeledRow("Taint", if (s.taintMask == 0L) "Bersih" else "0x%x".format(s.taintMask),
                        if (s.taintMask == 0L) ZkGood else ZkWarn)
                    LabeledRow("Config digest", s.configDigest.take(16) + "…")
                }
            }

            ui.security?.device?.let { d ->
                SectionCard("Perangkat") {
                    LabeledRow("Model", d.model.ifBlank { "—" })
                    LabeledRow("Compatible", d.compatible.ifBlank { "—" })
                    LabeledRow("Tag kernel", d.licenseeTag.ifBlank { "(kosong)" })
                    LabeledRow("Jumlah CPU", d.cpuCount.toString())
                }
            }

            ui.zperf?.let { z ->
                SectionCard("Mesin performa (zperfd)") {
                    LabeledRow("Kernel", z.flavor, if (z.gki) ZkGood else ZkWarn)
                    if (z.release.isNotBlank()) LabeledRow("Rilis", z.release)
                    LabeledRow("Boost",
                        buildList {
                            if (z.boost.uclamp) add("uclamp")
                            if (z.boost.schedtune) add("schedtune")
                            if (z.boost.cpuBoost) add("input")
                        }.joinToString("+").ifEmpty { "—" })
                    z.policies.forEach { p ->
                        LabeledRow(
                            p.name,
                            "${p.minHw / 1000}–${p.maxHw / 1000} MHz · ${p.opps} OPP",
                        )
                    }
                    z.gpu?.let { g ->
                        val div = if (g.max > 10_000_000) 1_000_000 else 1000
                        LabeledRow("GPU (${g.kind})", "${g.min / div}–${g.max / div} MHz · ${g.opps} OPP")
                    }
                }
            }

            ui.policy?.let { pol ->
                SectionCard("Policy akses (UID/GID/Grup)") {
                    if (pol.entries.isEmpty())
                        Text("—", style = MaterialTheme.typography.bodySmall)
                    pol.entries.forEach { e ->
                        LabeledRow(
                            "${e.type}:${e.id}${if (e.builtin) " (builtin)" else ""}",
                            "${if (e.deny) "deny " else ""}0x%x".format(e.caps),
                            if (e.deny) ZkBad else ZkGood,
                        )
                    }
                }
            }
        }
    }
}

private fun copyToCache(ctx: Context, uri: android.net.Uri): String? = try {
    val dir = File(ctx.cacheDir, "token").apply { mkdirs() }
    val out = File(dir, "import.zkl")
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    }
    // Make it readable by root-run zkfctl.
    out.setReadable(true, false)
    out.absolutePath
} catch (e: Exception) {
    null
}
