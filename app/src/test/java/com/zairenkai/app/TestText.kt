// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Renders UiText without Android resources, for JVM unit tests: raw text
 * stays as-is, resources show as #<id>(args).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app

import com.zairenkai.app.core.text.UiText

fun UiText.debug(): String = when (this) {
    is UiText.Raw -> text
    is UiText.Plural -> "#$id[$count](" + args.joinToString(",") { if (it is UiText) it.debug() else it.toString() } + ")"
    is UiText.Join -> parts.joinToString(separator) { it.debug() }
    is UiText.Res -> "#$id(" + args.joinToString(",") { if (it is UiText) it.debug() else it.toString() } + ")"
}
