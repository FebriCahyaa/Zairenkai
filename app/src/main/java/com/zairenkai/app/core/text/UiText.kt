// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Text produced outside a composable (ViewModels, message builders, input
 * validation) as a string-resource reference, resolved in the UI with the
 * active app language. Engine output stays [UiText.Raw]: it is technical
 * detail shown after a localized reason, not translated.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.core.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

sealed interface UiText {
    /** A string resource; args may themselves be [UiText]. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A quantity string; [args] are the format args (usually including [count]). */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText

    data class Raw(val text: String) : UiText

    /** Parts joined with [separator] (e.g. a reason followed by engine detail lines). */
    data class Join(val parts: List<UiText>, val separator: String = "\n") : UiText

    fun resolve(context: Context): String = when (this) {
        is Raw -> text
        is Plural -> context.resources.getQuantityString(
            id, count, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray(),
        )
        is Join -> parts.joinToString(separator) { it.resolve(context) }
        is Res -> if (args.isEmpty()) context.getString(id)
        else context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)
