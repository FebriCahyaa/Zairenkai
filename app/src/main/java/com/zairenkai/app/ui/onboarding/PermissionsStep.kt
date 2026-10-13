// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * App permissions step. Only what the app really uses:
 *  - Notifications (POST_NOTIFICATIONS, runtime): the FPS recorder and the
 *    usage-history recorder run as foreground services and must show one.
 *  - Usage access (PACKAGE_USAGE_STATS, special access): per-app profiles,
 *    Auto mode and usage history need the foreground app.
 *  - Battery optimisation (recommended): so Android does not stop the
 *    background recorders. Opens the system list; nothing is requested
 *    silently.
 * Root is granted by the root manager and checked in the previous step.
 * Every item is optional; the status is read from the system and refreshed
 * whenever the app comes back to the foreground.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zairenkai.app.R
import com.zairenkai.app.ui.icons.ZkIcons
import com.zairenkai.app.ui.mascot.Mascot
import com.zairenkai.app.ui.mascot.MascotState
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.statusInk

enum class AppPermission(val title: Int, val body: Int, val icon: ImageVector, val required: Boolean) {
    NOTIFICATIONS(R.string.perm_notifications_title, R.string.perm_notifications_body, ZkIcons.Bell, true),
    USAGE_ACCESS(R.string.perm_usage_title, R.string.perm_usage_body, ZkIcons.Chart, true),
    BATTERY(R.string.perm_battery_title, R.string.perm_battery_body, ZkIcons.Battery, false),
}

/** The current grant of each permission, read from the system. */
fun permissionStatus(context: Context): Map<AppPermission, Boolean> {
    val pkg = context.packageName
    // Android 13+ has a runtime permission; Android 12 only the app's notification switch.
    val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val ops = context.getSystemService(AppOpsManager::class.java)
    val usage = runCatching {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    val battery = runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg) }.getOrDefault(false)
    return mapOf(AppPermission.NOTIFICATIONS to notif, AppPermission.USAGE_ACCESS to usage, AppPermission.BATTERY to battery)
}

/** Live permission state plus the action for each item; refreshed on resume. */
@Composable
fun rememberPermissions(): Pair<Map<AppPermission, Boolean>, (AppPermission) -> Unit> {
    val context = LocalContext.current
    var status by remember { mutableStateOf(permissionStatus(context)) }
    LifecycleResumeEffect(Unit) {
        status = permissionStatus(context)
        onPauseOrDispose { }
    }
    var asked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        status = permissionStatus(context)
    }
    fun open(intent: Intent, fallback: Intent? = null) {
        val i = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { fallback?.let { f -> runCatching { context.startActivity(f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
    }
    val act: (AppPermission) -> Unit = { p ->
        when (p) {
            AppPermission.NOTIFICATIONS ->
                // After a denial the system stops showing the dialog; send the user to the app's notification settings.
                if (!asked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            AppPermission.USAGE_ACCESS ->
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri()), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            AppPermission.BATTERY -> open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
    return status to act
}

@Composable
internal fun PermissionsStep(status: Map<AppPermission, Boolean>, onAction: (AppPermission) -> Unit) {
    val allRequired = AppPermission.entries.filter { it.required }.all { status[it] == true }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Mascot(if (allRequired) MascotState.READY else MascotState.IDLE, size = 150.dp)
    }
    StepTitle(stringResource(R.string.setup_perm_title), stringResource(R.string.setup_perm_body))
    AppPermission.entries.forEachIndexed { i, p -> PermissionRow(p, status[p] == true, Modifier.reveal(2 + i)) { onAction(p) } }
    Text(stringResource(R.string.setup_perm_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.reveal(6))
}

@Composable
private fun PermissionRow(p: AppPermission, granted: Boolean, modifier: Modifier, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val title = stringResource(p.title)
    val state = stringResource(if (granted) R.string.perm_granted else R.string.perm_not_granted)
    Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLow.copy(alpha = 0.94f), contentColor = cs.onSurface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$title: $state" }) {
                Surface(shape = RoundedCornerShape(14.dp), color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                    Icon(p.icon, null, Modifier.padding(10.dp).size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (p.required) R.string.perm_needed else R.string.perm_recommended),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
                // The status swaps with a small scale-in, never a jump.
                AnimatedContent(granted, transitionSpec = { (fadeIn(tween(200)) + scaleIn(tween(240), 0.8f)).togetherWith(fadeOut(tween(120))) }, label = "perm") { g ->
                    if (g) {
                        Surface(shape = CircleShape, color = statusInk(ZkGood).copy(alpha = 0.14f), contentColor = statusInk(ZkGood)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(ZkIcons.Check, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.perm_granted), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    } else {
                        FilledTonalButton(onClick = onAction, shape = CircleShape) {
                            Text(stringResource(if (p == AppPermission.NOTIFICATIONS) R.string.perm_allow else R.string.perm_open_settings))
                        }
                    }
                }
            }
            Text(stringResource(p.body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}
