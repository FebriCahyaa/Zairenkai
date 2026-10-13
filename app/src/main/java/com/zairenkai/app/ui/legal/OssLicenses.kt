// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Open-source licences ("Lisensi terbuka"): every library packaged in this
 * build, sorted by name, with its version, developers and licences; a sheet
 * shows the description, links to the source and website, and the full
 * licence text. The catalogue (assets/oss_licenses.json) is generated at
 * build time from the actual runtime classpath (see OssLicensesTask).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.legal

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zairenkai.app.R
import com.zairenkai.app.ui.components.CollapsingScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OssLicense(val spdx: String, val name: String, val url: String? = null)

@Serializable
data class OssLibrary(
    val id: String,
    val version: String,
    val name: String,
    val description: String? = null,
    val developers: List<String> = emptyList(),
    val website: String? = null,
    val source: String? = null,
    val licenses: List<OssLicense> = emptyList(),
)

@Serializable
data class OssCatalog(val libraries: List<OssLibrary>, val texts: Map<String, String> = emptyMap()) {
    /** How many libraries use each licence, most used first. */
    val licenseCounts: List<Pair<String, Int>>
        get() = libraries.flatMap { l -> l.licenses.map { it.spdx }.distinct() }.groupingBy { it }.eachCount()
            .toList().sortedByDescending { it.second }

    fun filter(query: String): List<OssLibrary> {
        val q = query.trim()
        if (q.isEmpty()) return libraries
        return libraries.filter { l ->
            l.name.contains(q, true) || l.id.contains(q, true) || l.developers.any { it.contains(q, true) } ||
                l.licenses.any { it.spdx.contains(q, true) || it.name.contains(q, true) }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): OssCatalog = json.decodeFromString(serializer(), text)

        /** Blocking: reads the asset. */
        fun load(context: Context): OssCatalog =
            parse(context.assets.open("oss_licenses.json").bufferedReader().use { it.readText() })
    }
}

/**
 * Licence texts are hard-wrapped at ~80 columns; on a phone that leaves
 * ragged half lines. Join the lines of each paragraph (blank lines separate
 * paragraphs) so the text flows to the screen width.
 */
fun reflowLicense(text: String): String =
    text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        .map { p -> p.lines().joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

/** A readable licence label: "Apache License 2.0" for "Apache-2.0". */
fun licenseLabel(spdx: String): String = when (spdx) {
    "Apache-2.0" -> "Apache License 2.0"
    "MIT" -> "MIT License"
    "BSD-3-Clause" -> "BSD 3-Clause"
    "BSD-2-Clause" -> "BSD 2-Clause"
    "OFL-1.1" -> "SIL OFL 1.1"
    else -> spdx
}

@Composable
fun rememberOssCatalog(): OssCatalog? {
    val context = LocalContext.current
    val catalog by produceState<OssCatalog?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { OssCatalog.load(context) }.getOrNull() }
    }
    return catalog
}

@Composable
fun OssLicensesScreen(onBack: () -> Unit) {
    CollapsingScaffold(stringResource(R.string.oss_title), onBack = onBack) { inner ->
        OssLicensesContent(rememberOssCatalog(), Modifier.padding(inner))
    }
}

@Composable
fun OssLicensesContent(catalog: OssCatalog?, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val cs = MaterialTheme.colorScheme
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (catalog != null) {
                    Text(
                        pluralStringResource(R.plurals.oss_summary, catalog.libraries.size, catalog.libraries.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    LicenseChips(catalog.licenseCounts.map { (id, n) -> "${licenseLabel(id)} · $n" })
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.oss_search)) },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val list = catalog?.filter(query).orEmpty()
        if (catalog != null && list.isEmpty()) {
            item { Text(stringResource(R.string.oss_empty), Modifier.padding(16.dp), color = cs.onSurfaceVariant) }
        }
        items(list, key = { it.id }) { lib -> LibraryRow(lib) { open = lib.id } }
    }
    val selected = catalog?.libraries?.firstOrNull { it.id == open }
    if (catalog != null && selected != null) LibrarySheet(selected, catalog.texts) { open = null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LicenseChips(labels: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun LibraryRow(lib: OssLibrary, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(lib.name, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(lib.version, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurfaceVariant, maxLines = 1)
        }
        if (lib.developers.isNotEmpty()) {
            Text(lib.developers.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (lib.licenses.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            LicenseChips(lib.licenses.map { licenseLabel(it.spdx) }.distinct())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySheet(lib: OssLibrary, texts: Map<String, String>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    var showText by rememberSaveable(lib.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(lib.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
            Text(
                (lib.developers.take(3) + lib.version).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Text(lib.id, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurfaceVariant)
            lib.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lib.source?.let { OutlinedButton(onClick = { runCatching { uri.openUri(it) } }) { Text(stringResource(R.string.oss_source)) } }
                lib.website?.let { OutlinedButton(onClick = { runCatching { uri.openUri(it) } }) { Text(stringResource(R.string.oss_website)) } }
                FilledTonalButton(onClick = { showText = !showText }) {
                    Text(stringResource(if (showText) R.string.oss_hide_license else R.string.oss_view_license))
                }
            }
            AnimatedVisibility(showText, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lib.licenses.distinctBy { it.spdx }.forEach { l ->
                        Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh, contentColor = cs.onSurface) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(l.name, style = MaterialTheme.typography.titleSmall)
                                l.url?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.primary, modifier = Modifier.clickable { runCatching { uri.openUri(it) } }) }
                                Text(
                                    texts[l.spdx]?.let(::reflowLicense) ?: stringResource(R.string.oss_text_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
