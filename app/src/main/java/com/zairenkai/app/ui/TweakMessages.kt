// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * User-facing wording for tweak write outcomes. Kept pure so every failure
 * class reported by zperfd is covered by unit tests. Returned as [UiText] so
 * it follows the active app language; engine error text is appended untranslated.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.data.TweakWriteResult

fun tweakSuccessMessage(result: TweakWriteResult): UiText {
    val value = result.value ?: "—"
    return if (result.targets > 1) {
        UiText.Plural(R.plurals.tweaks_success_targets, result.targets, listOf(result.id, value, result.targets))
    } else {
        uiText(R.string.tweaks_success, result.id, value)
    }
}

private val TWEAK_FAILURE = mapOf(
    "engine_unavailable" to R.string.tweaks_fail_engine_unavailable,
    "framework_unavailable" to R.string.tweaks_fail_framework_unavailable,
    "framework_incompatible" to R.string.tweaks_fail_framework_incompatible,
    "license" to R.string.tweaks_fail_license,
    "unauthorized" to R.string.tweaks_fail_unauthorized,
    "sentinel" to R.string.tweaks_fail_sentinel,
    "lite" to R.string.tweaks_fail_lite,
    "unknown" to R.string.tweaks_fail_unknown_tweak,
    "rejected" to R.string.tweaks_fail_rejected,
    "not_observed" to R.string.tweaks_fail_not_observed,
    "rollback_failed" to R.string.tweaks_fail_rollback_failed,
    "state" to R.string.tweaks_fail_state,
    "timeout" to R.string.tweaks_fail_timeout,
)

fun tweakFailureMessage(result: TweakWriteResult): UiText {
    val reason = uiText(TWEAK_FAILURE[result.kind] ?: R.string.tweaks_fail_generic)
    val detail = result.error?.takeIf { it.isNotBlank() }
    return if (detail != null) UiText.Join(listOf(reason, UiText.Raw(detail))) else reason
}
