// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * About phone: the brand-aware device hero, then software, hardware and
 * identity facts from public Android APIs (no root). Every value shown was
 * read from the device; a field Android does not report is left out.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.device

import com.zairenkai.app.ui.icons.ZkIcons
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zairenkai.app.R
import com.zairenkai.app.ui.components.CollapsingScaffold
import com.zairenkai.app.ui.components.MeterBar
import com.zairenkai.app.ui.components.SpecTile
import com.zairenkai.app.ui.components.ZkCard
import com.zairenkai.app.ui.onboarding.reveal

@Composable
fun AboutPhoneScreen(onBack: () -> Unit) {
    val info = rememberDeviceInfo()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val title = stringResource(R.string.about_phone_title)
    // Rebuilt when the language or configuration changes, not on every recomposition.
    val configuration = LocalConfiguration.current
    val sections = remember(info, configuration) { info?.let { aboutPhoneSections(context, it) } }
    CollapsingScaffold(
        title,
        onBack = onBack,
        actions = {
            IconButton(
                enabled = sections != null,
                onClick = {
                    val text = (listOf(title, info?.productName.orEmpty()) + sections.orEmpty().flatMap { (head, rows) ->
                        listOf("", head) + rows.map { "${it.label}: ${it.value}" }
                    }).joinToString("\n")
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, text))
                    copied = true
                },
            ) { Icon(ZkIcons.Copy, stringResource(R.string.info_copy)) }
        },
    ) { inner ->
        AboutPhoneContent(info, sections, copied, Modifier.padding(inner))
    }
}

/** One fact; [wide] values (long strings) span the whole row. */
data class PhoneRow(val label: String, val value: String, val wide: Boolean)

/** One section: a heading and its rows. */
typealias PhoneSection = Pair<String, List<PhoneRow>>

private val WIDE = setOf(R.string.about_phone_build, R.string.info_kernel, R.string.about_phone_baseband, R.string.info_soc, R.string.about_phone_display)

/** The facts, grouped. Blank values are dropped, never shown as placeholders. */
fun aboutPhoneSections(context: Context, info: DeviceInfo): List<PhoneSection> {
    fun s(id: Int, vararg a: Any) = context.getString(id, *a)
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)
    fun rows(vararg r: Pair<Int, String?>) = r.mapNotNull { (id, v) -> v?.takeIf { it.isNotBlank() }?.let { PhoneRow(s(id), it, id in WIDE) } }
    val display = if (info.displayWidth != null && info.displayHeight != null) {
        listOfNotNull(
            "${info.displayWidth} × ${info.displayHeight}",
            info.refreshRateMax?.let { s(R.string.device_hz, it.toInt()) },
            "${info.densityDpi} dpi",
        ).joinToString(" · ")
    } else null
    return listOf(
        s(R.string.about_phone_software) to rows(
            R.string.info_android to s(R.string.about_phone_android_value, info.androidRelease, info.sdk),
            R.string.about_phone_patch to info.securityPatch,
            R.string.about_phone_build to info.buildNumber,
            R.string.info_kernel to info.kernel,
            R.string.about_phone_baseband to info.baseband,
            R.string.about_phone_bootloader to info.bootloader,
        ),
        s(R.string.about_phone_hardware) to rows(
            R.string.info_soc to info.soc,
            R.string.about_phone_cpu to context.resources.getQuantityString(
                R.plurals.about_phone_cpu_value, info.cores, info.cores, info.abis.firstOrNull().orEmpty(),
            ),
            R.string.info_ram to size(info.ramTotal),
            R.string.about_phone_display to display,
        ),
        s(R.string.about_phone_identity) to rows(
            R.string.about_phone_brand to (if (info.brand == DeviceBrand.OTHER) info.manufacturer else info.brand.label),
            R.string.about_phone_manufacturer to info.manufacturer,
            R.string.about_phone_model to info.model,
            R.string.about_phone_codename to info.codename,
            R.string.about_phone_device_name to info.deviceName,
        ),
    )
}

@Composable
internal fun AboutPhoneContent(info: DeviceInfo?, sections: List<PhoneSection>?, copied: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DeviceHero(info, Modifier.reveal(0))
        if (copied) Text(stringResource(R.string.info_copied), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (info == null || sections == null) return@Column
        // Storage gets a bar: how full the data partition is.
        if (info.storageTotal > 0) {
            ZkCard(Modifier.reveal(1), title = stringResource(R.string.info_storage)) {
                val used = info.storageTotal - info.storageFree
                Text(
                    stringResource(
                        R.string.about_phone_storage_value,
                        Formatter.formatShortFileSize(context, used),
                        Formatter.formatShortFileSize(context, info.storageTotal),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                MeterBar(used.toFloat() / info.storageTotal, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.about_phone_storage_free, Formatter.formatShortFileSize(context, info.storageFree)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        sections.forEachIndexed { i, (head, rows) ->
            if (rows.isEmpty()) return@forEachIndexed
            ZkCard(Modifier.reveal(2 + i), title = head) {
                val (wide, narrow) = rows.partition { it.wide }
                narrow.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { SpecTile(it.label, it.value, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                wide.forEach { SpecTile(it.label, it.value, Modifier.fillMaxWidth(), maxLines = 3) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** A compact entry to About phone: the brand mark, the product name and a chevron. */
@Composable
fun DeviceEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val info = rememberDeviceInfo()
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = cs.surfaceContainer,
        contentColor = cs.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            BrandMark(info?.brand ?: DeviceBrand.OTHER, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(info?.productName ?: stringResource(R.string.about_phone_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    info?.let { stringResource(R.string.about_phone_entry_sub, it.androidRelease, it.model) } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Icon(ZkIcons.ChevronRight, stringResource(R.string.about_phone_title), tint = cs.onSurfaceVariant)
        }
    }
}
