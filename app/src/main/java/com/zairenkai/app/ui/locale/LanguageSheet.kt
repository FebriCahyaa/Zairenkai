// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * App-language picker, shared by the first-run setup and Settings. Applying a
 * language goes through AppLocale (per-app locale), which recreates the
 * activity; the choice survives restarts.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.locale

import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.zairenkai.app.R
import com.zairenkai.app.ui.icons.ZkIcons

/** Display name of the active choice, for Settings rows. */
@Composable
fun currentLanguageLabel(): String {
    val context = LocalContext.current
    val current = remember { AppLocale.current(context) }
    return current.endonym ?: stringResource(R.string.language_system)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    val current = remember { AppLocale.current(context) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(ZkIcons.Globe, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.language_body), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { lang ->
                    val title = lang.endonym ?: stringResource(R.string.language_system)
                    val subtitle = if (lang == AppLanguage.SYSTEM) {
                        val system = Resources.getSystem().configuration.locales[0]
                        stringResource(R.string.language_system_current, system.getDisplayName(system))
                    } else null
                    LanguageOption(title, subtitle, selected = lang == current) {
                        // Run the transition in the Activity scope, not the sheet's
                        // composition scope. onDismiss removes LanguageSheet from
                        // composition and would cancel a coroutine launched by
                        // rememberCoroutineScope before the locale was applied.
                        val owner = activity
                        val transitionScope = owner?.lifecycleScope ?: scope
                        transitionScope.launch {
                            sheet.hide()
                            onDismiss()
                            if (lang != current && owner != null) {
                                AppLocale.apply(owner, lang)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (selected) 28.dp else 18.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clip(shape).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.75f)) }
            }
            if (selected) Icon(ZkIcons.Check, null)
        }
    }
}
