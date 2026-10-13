// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * System: identity (UID/GID/groups), API security reports, ZKFC license and
 * access policy. The API token is embedded in the kernel at build time and
 * verified there (Ed25519); the app only reads and reports its status — it
 * never asks the user to supply or install a token.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import com.zairenkai.app.ui.components.CollapsingScaffold
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.R
import com.zairenkai.app.data.SignatureGuard
import com.zairenkai.app.data.SignatureState
import com.zairenkai.app.ui.SystemViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.SectionCard
import com.zairenkai.app.ui.theme.ZkBad
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkWarn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(factory: VmFactory, onOpenRuntime: () -> Unit = {}, onOpenAboutPhone: () -> Unit = {}) {
    val vm: SystemViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current

    CollapsingScaffold(stringResource(R.string.system_title), onBack = null) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            com.zairenkai.app.ui.device.DeviceEntryCard(onOpenAboutPhone)
            val sig = remember { SignatureGuard.check(ctx) }
            SectionCard(stringResource(R.string.system_integrity_title)) {
                val (txt, col) = when (sig) {
                    SignatureState.OFFICIAL -> stringResource(R.string.system_sig_official) to ZkGood
                    SignatureState.MODIFIED -> stringResource(R.string.system_sig_modified) to ZkBad
                    SignatureState.UNKNOWN -> stringResource(R.string.system_sig_unknown) to ZkWarn
                }
                LabeledRow(stringResource(R.string.system_label_status), txt, col)
                SignatureGuard.currentSha256(ctx)?.let {
                    LabeledRow(stringResource(R.string.system_label_signature_fingerprint), it.take(16) + "…")
                }
            }

            SectionCard(stringResource(R.string.system_runtime_title)) {
                Text(
                    stringResource(R.string.system_runtime_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenRuntime) { Text(stringResource(R.string.system_runtime_open)) }
            }

            val lic = ui.license
            SectionCard(stringResource(R.string.system_license_title)) {
                LabeledRow(stringResource(R.string.system_label_status), lic?.state ?: "—",
                    if (lic?.state == "valid") ZkGood else ZkWarn)
                LabeledRow(stringResource(R.string.system_label_token_installed), stringResource(if (lic?.hasToken == true) R.string.system_yes else R.string.system_no))
                LabeledRow(stringResource(R.string.system_label_owner_key), stringResource(if (lic?.ownerKeyProvisioned == true) R.string.system_owner_key_installed else R.string.system_owner_key_missing))
                lic?.ownerKeyFingerprint?.takeIf { it.isNotBlank() }?.let {
                    LabeledRow(stringResource(R.string.system_label_key_fingerprint), it.take(16) + "…")
                }
                lic?.kernelBinding?.takeIf { it.isNotBlank() }?.let {
                    LabeledRow(stringResource(R.string.system_label_kernel_binding), it.take(16) + "…")
                }
                lic?.token?.let { LabeledRow(stringResource(R.string.system_label_licensee), it.licensee) }
                Text(
                    stringResource(R.string.system_license_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            ui.security?.user?.let { u ->
                SectionCard(stringResource(R.string.system_caller_title)) {
                    LabeledRow("UID", u.uid.toString())
                    LabeledRow("EUID", u.euid.toString())
                    LabeledRow("GID", u.gid.toString())
                    LabeledRow("EGID", u.egid.toString())
                    LabeledRow("CAP_SYS_ADMIN", stringResource(if (u.capSysAdmin) R.string.system_yes else R.string.system_no))
                    LabeledRow(stringResource(R.string.system_label_zkfc_caps), "0x%x".format(u.caps))
                }
            }

            ui.security?.system?.let { s ->
                SectionCard(stringResource(R.string.system_security_title)) {
                    LabeledRow("SELinux", stringResource(if (s.selinux) R.string.system_present else R.string.system_no),
                        if (s.selinux) ZkGood else ZkWarn)
                    LabeledRow(stringResource(R.string.system_label_module_signing), stringResource(if (s.moduleSigEnforced) R.string.system_enforced else R.string.system_no))
                    LabeledRow(stringResource(R.string.system_label_lockdown), stringResource(if (s.lockdown) R.string.state_active else R.string.system_no))
                    LabeledRow(stringResource(R.string.system_label_taint), if (s.taintMask == 0L) stringResource(R.string.system_taint_clean) else "0x%x".format(s.taintMask),
                        if (s.taintMask == 0L) ZkGood else ZkWarn)
                    LabeledRow(stringResource(R.string.system_label_config_digest), s.configDigest.take(16) + "…")
                }
            }

            ui.security?.device?.let { d ->
                SectionCard(stringResource(R.string.system_device_title)) {
                    LabeledRow(stringResource(R.string.system_label_model), d.model.ifBlank { "—" })
                    LabeledRow(stringResource(R.string.system_label_compatible), d.compatible.ifBlank { "—" })
                    val empty = stringResource(R.string.system_empty)
                    LabeledRow(stringResource(R.string.system_label_kernel_tag), d.licenseeTag.ifBlank { empty })
                    LabeledRow(stringResource(R.string.system_label_cpu_count), d.cpuCount.toString())
                }
            }

            ui.zperf?.let { z ->
                SectionCard(stringResource(R.string.system_zperf_title)) {
                    LabeledRow(stringResource(R.string.system_label_kernel), z.flavor, if (z.gki) ZkGood else ZkWarn)
                    if (z.release.isNotBlank()) LabeledRow(stringResource(R.string.system_label_release), z.release)
                    LabeledRow(stringResource(R.string.system_label_boost),
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
                SectionCard(stringResource(R.string.system_policy_title)) {
                    if (pol.entries.isEmpty())
                        Text("—", style = MaterialTheme.typography.bodySmall)
                    pol.entries.forEach { e ->
                        LabeledRow(
                            if (e.builtin) stringResource(R.string.system_policy_entry_builtin, e.type, e.id) else "${e.type}:${e.id}",
                            "${if (e.deny) "deny " else ""}0x%x".format(e.caps),
                            if (e.deny) ZkBad else ZkGood,
                        )
                    }
                }
            }
        }
    }
}
