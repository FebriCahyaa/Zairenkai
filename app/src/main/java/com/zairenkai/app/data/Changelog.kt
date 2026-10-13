// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The app's user-facing changelog, bundled in the install so it needs no
 * network. Each entry names a version and a few highlights as string
 * resources, so the changelog is translated like the rest of the UI. This is
 * deliberately short and reader-facing — the full, technical module history
 * lives in module/changelog.md, not here.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import androidx.annotation.StringRes
import com.zairenkai.app.R

/** One released version and the highlights worth reading. */
data class ChangelogEntry(
    val version: String,
    @StringRes val highlights: List<Int>,
)

/** The bundled changelog, newest first. */
object Changelog {
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "1.1.0",
            highlights = listOf(
                R.string.changelog_1_1_0_nav,
                R.string.changelog_1_1_0_redesign,
                R.string.changelog_1_1_0_monitor,
                R.string.changelog_1_1_0_language,
                R.string.changelog_1_1_0_update,
            ),
        ),
        ChangelogEntry(
            version = "1.0.0",
            highlights = listOf(
                R.string.changelog_1_0_0_first,
                R.string.changelog_1_0_0_engine,
            ),
        ),
    )
}
