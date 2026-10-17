// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Update app — shows the installed version, checks GitHub for a newer build on
 * the channel this build follows, and downloads + installs it in place. Opening
 * the browser is kept only as a fallback.
 *
 * Privacy: the app reaches the network only when you check (or leave the
 * auto-check on), talks to GitHub releases only, and sends no identifiers. The
 * auto-check can be turned off, which stops the app contacting the network at
 * all. Downloaded APKs are verified against the published SHA-256 before the
 * system installer is offered them.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.R
import com.zairenkai.app.data.AppSettings
import com.zairenkai.app.data.Changelog
import com.zairenkai.app.data.update.CheckResult
import com.zairenkai.app.ui.UpdateUiState
import com.zairenkai.app.ui.UpdateViewModel
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.components.CollapsingScaffold
import com.zairenkai.app.ui.icons.ZkIcons

@Composable
fun UpdateScreen(factory: VmFactory, onBack: () -> Unit) {
    val vm: UpdateViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState(initial = AppSettings())
    val context = LocalContext.current

    // Auto-check once when the screen opens, unless the user turned it off.
    LaunchedEffect(settings.autoUpdateCheck) {
        if (settings.autoUpdateCheck && state is UpdateUiState.Idle) vm.check()
    }

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    UpdateContent(
        state = state,
        autoCheck = settings.autoUpdateCheck,
        channel = vm.channel,
        onBack = onBack,
        onCheck = vm::check,
        onDownload = { vm.download(it) },
        onInstall = { vm.install(it) },
        onAutoCheckChange = vm::setAutoUpdateCheck,
        onOpenUrl = openUrl,
    )
}

/** Stateless body, so previews and tests render it without a ViewModel or network. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateContent(
    state: UpdateUiState,
    autoCheck: Boolean,
    channel: com.zairenkai.app.data.update.UpdateChannel,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: (com.zairenkai.app.data.update.ReleaseManifest) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    CollapsingScaffold(stringResource(R.string.update_title), onBack = onBack) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CurrentVersionCard(channelLabel = stringResource(channelLabelRes(channel)))

            StatusCard(
                state = state,
                onCheck = onCheck,
                onDownload = onDownload,
                onInstall = onInstall,
                onOpenBrowser = { onOpenUrl(channel.pageUrl()) },
            )

            AutoCheckRow(checked = autoCheck, onChange = onAutoCheckChange)

            BrowserFallbackCard(
                onOpenRelease = { onOpenUrl(com.zairenkai.app.data.update.UpdateChannel.RELEASE.pageUrl()) },
                onOpenCanary = { onOpenUrl(com.zairenkai.app.data.update.UpdateChannel.CANARY.pageUrl()) },
            )

            ChangelogCard()
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun channelLabelRes(channel: com.zairenkai.app.data.update.UpdateChannel): Int =
    when (channel) {
        com.zairenkai.app.data.update.UpdateChannel.RELEASE -> R.string.update_channel_release
        com.zairenkai.app.data.update.UpdateChannel.CANARY -> R.string.update_channel_canary
    }

@Composable
private fun CurrentVersionCard(channelLabel: String) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ZkIcons.Package, null, Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_installed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.update_channel_of, channelLabel),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: (com.zairenkai.app.data.update.ReleaseManifest) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onOpenBrowser: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is UpdateUiState.Idle -> {
                    StatusHead(ZkIcons.Refresh, stringResource(R.string.update_status_idle))
                    Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_check_now))
                    }
                }
                is UpdateUiState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.update_status_checking), style = MaterialTheme.typography.bodyLarge)
                }
                is UpdateUiState.UpToDate -> {
                    StatusHead(ZkIcons.CheckCircle, stringResource(R.string.update_status_uptodate, state.versionName))
                    OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_check_again))
                    }
                }
                is UpdateUiState.Available -> {
                    StatusHead(ZkIcons.Sparkle, stringResource(R.string.update_status_available, state.manifest.versionName))
                    Button(onClick = { onDownload(state.manifest) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(ZkIcons.ArrowForward, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.update_download_install))
                    }
                }
                is UpdateUiState.Downloading -> {
                    StatusHead(ZkIcons.Sync, stringResource(R.string.update_status_downloading, state.manifest.versionName))
                    if (state.progress < 0f) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is UpdateUiState.ReadyToInstall -> {
                    StatusHead(ZkIcons.CheckCircle, stringResource(R.string.update_status_ready, state.manifest.versionName))
                    Button(onClick = { onInstall(state.file) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(ZkIcons.Rocket, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.update_install))
                    }
                }
                is UpdateUiState.Failed -> {
                    StatusHead(ZkIcons.Warning, stringResource(failReasonRes(state.reason)))
                    OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_retry))
                    }
                    OutlinedButton(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_open_browser))
                    }
                }
                is UpdateUiState.DownloadFailed -> {
                    StatusHead(ZkIcons.Warning, stringResource(R.string.update_download_failed))
                    OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_retry))
                    }
                    OutlinedButton(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_open_browser))
                    }
                }
            }
        }
    }
}

private fun failReasonRes(reason: CheckResult.Reason): Int = when (reason) {
    CheckResult.Reason.SPOOFED_BUILD -> R.string.update_fail_spoofed
    CheckResult.Reason.SERVER_NOT_CONFIGURED -> R.string.update_fail_server
    CheckResult.Reason.NO_NETWORK -> R.string.update_fail_network
    CheckResult.Reason.NOT_PUBLISHED -> R.string.update_fail_notpublished
    CheckResult.Reason.BAD_MANIFEST -> R.string.update_fail_manifest
}

@Composable
private fun StatusHead(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AutoCheckRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ZkIcons.Autorenew, null, Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_auto_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.update_auto_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun BrowserFallbackCard(onOpenRelease: () -> Unit, onOpenCanary: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.update_browser_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.update_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpenRelease, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_open_release))
            }
            OutlinedButton(onClick = onOpenCanary, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_open_canary))
            }
        }
    }
}

@Composable
private fun ChangelogCard() {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Icon(ZkIcons.History, null, Modifier.padding(8.dp).size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.update_changelog_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Changelog.entries.forEach { entry ->
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.update_changelog_version, entry.version),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                entry.highlights.forEach { res ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
