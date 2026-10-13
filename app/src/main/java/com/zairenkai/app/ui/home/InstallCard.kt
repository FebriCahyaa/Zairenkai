// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Home install card, shown when the ZKFC framework is not detected. Zairenkai
 * runs as a kernel module and installs on GKI kernels; for safety the app does
 * not flash the kernel itself. The chooser lets you pick the Zairenkai module
 * or AnyKernel .zip and hands it to your root/module manager, then you reboot.
 * Nothing here is a reproduction of another project's installer; it is a
 * guided chooser over the app's own detection.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zairenkai.app.R
import com.zairenkai.app.ui.icons.ZkIcons

/** The install methods offered; wording mirrors a kernel installer's choices. */
private enum class InstallMethod(val title: Int, val sub: Int) {
    FILE(R.string.install_method_file, R.string.install_method_file_sub),
    DIRECT(R.string.install_method_direct, R.string.install_method_direct_sub),
    INACTIVE(R.string.install_method_inactive, R.string.install_method_inactive_sub),
    ANYKERNEL(R.string.install_method_anykernel, R.string.install_method_anykernel_sub),
}

@Composable
fun InstallCard(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ZkIcons.Rocket, null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.home_install_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.home_install_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Button(onClick = { open = true }) {
                Icon(ZkIcons.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_install_action))
            }
        }
    }
    if (open) InstallSheet(onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var method by rememberSaveable { mutableStateOf(InstallMethod.DIRECT) }
    var pickedName by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val noHandler = stringResource(R.string.install_no_handler)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedName = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.install_sheet_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.install_sheet_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InstallMethod.entries.forEach { m ->
                    val selected = method == m
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { method = m },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(m.title), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(m.sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            FilledTonalButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(ZkIcons.Package, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.install_pick))
            }
            pickedName?.let { Text(stringResource(R.string.install_picked, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

            Button(
                onClick = {
                    val uri = pickedUri
                    if (uri == null) { message = noHandler; return@Button }
                    // Honest hand-off: let a root/module manager that handles the
                    // package take over. The app never flashes the kernel itself.
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/zip")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    message = runCatching { context.startActivity(intent); null }.getOrDefault(noHandler)
                },
                enabled = pickedUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.install_continue)) }

            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(14.dp)) {
                    Icon(ZkIcons.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.install_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
