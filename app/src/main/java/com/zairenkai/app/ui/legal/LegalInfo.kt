// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Legal information: terms of use, risks and disclaimer, privacy, the app's
 * licence, third-party components, and trademarks. Shown in setup (before
 * consent) and in Settings → Legal information. Each section opens in place.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.legal

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zairenkai.app.R
import com.zairenkai.app.ui.components.CollapsingScaffold
import com.zairenkai.app.ui.icons.ZkIcons

enum class LegalSection(@StringRes val title: Int, @StringRes val summary: Int, @StringRes val body: Int, val icon: ImageVector) {
    TERMS(R.string.legal_terms_title, R.string.legal_terms_summary, R.string.legal_terms_body, ZkIcons.Document),
    RISK(R.string.legal_risk_title, R.string.legal_risk_summary, R.string.legal_risk_body, ZkIcons.Warning),
    PRIVACY(R.string.legal_privacy_title, R.string.legal_privacy_summary, R.string.legal_privacy_body, ZkIcons.Lock),
    LICENSE(R.string.legal_license_title, R.string.legal_license_summary, R.string.legal_license_body, ZkIcons.Key),
    THIRD_PARTY(R.string.legal_third_party_title, R.string.legal_third_party_summary, R.string.legal_third_party_body, ZkIcons.Package),
    TRADEMARKS(R.string.legal_trademarks_title, R.string.legal_trademarks_summary, R.string.legal_trademarks_body, ZkIcons.Badge),
}

/** Every legal section as an expandable card; [onOpenLicenses] opens the open-source list. */
@Composable
fun LegalSections(onOpenLicenses: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LegalSection.entries.forEach { section ->
            LegalCard(section) {
                if (section == LegalSection.THIRD_PARTY) {
                    FilledTonalButton(onClick = onOpenLicenses) {
                        Icon(ZkIcons.Package, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.oss_title))
                    }
                }
            }
        }
    }
}

@Composable
private fun LegalCard(section: LegalSection, extra: @Composable () -> Unit) {
    var open by rememberSaveable(section) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(240), label = "chevron")
    val state = stringResource(if (open) R.string.legal_expanded else R.string.legal_collapsed)
    Surface(shape = RoundedCornerShape(22.dp), color = cs.surfaceContainerLow.copy(alpha = 0.94f), contentColor = cs.onSurface) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { open = !open }
                    .semantics { stateDescription = state }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                    Icon(section.icon, null, Modifier.padding(8.dp).size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(section.title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(section.summary), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Icon(ZkIcons.ExpandMore, null, Modifier.size(22.dp).graphicsLayer { rotationZ = turn }, tint = cs.onSurfaceVariant)
            }
            AnimatedVisibility(open, enter = fadeIn(tween(220)) + expandVertically(tween(280)), exit = fadeOut(tween(150)) + shrinkVertically(tween(220))) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(section.body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    extra()
                }
            }
        }
    }
}

/** Settings → Legal information. */
@Composable
fun LegalInfoScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    CollapsingScaffold(stringResource(R.string.legal_title), onBack = onBack) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.legal_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LegalSections(onOpenLicenses)
        }
    }
}
