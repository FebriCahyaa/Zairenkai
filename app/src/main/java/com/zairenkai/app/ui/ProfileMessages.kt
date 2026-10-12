// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * User-facing wording for profile state and outcomes, as [UiText] so the
 * hold reasons, failure classes and mode summaries are unit tested and shown
 * in the active app language. Engine error text is appended untranslated.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.R
import com.zairenkai.app.data.CustomWriteResult
import com.zairenkai.app.data.ModeApplyResult
import com.zairenkai.app.data.ModeSummary

/** One line describing what an engine mode requests on this device. */
fun modeSummaryLine(mode: ModeSummary): UiText {
    val parts = mutableListOf<UiText>()
    val cpu = mode.cpu
    cpu.minFreq?.takeIf { it != "min" }?.let { parts += uiText(R.string.mode_cpu_min, it) }
    when {
        cpu.maxPerfPct != null -> parts += uiText(R.string.mode_cpu_max_pct, cpu.maxPerfPct)
        cpu.maxFreq != null && cpu.maxFreq != "max" -> parts += uiText(R.string.mode_cpu_max, cpu.maxFreq)
    }
    cpu.uclampMinPct?.takeIf { it > 0 }?.let { parts += uiText(R.string.mode_uclamp, it) }
    val boostPct = cpu.inputBoostPct
    val boostMs = cpu.inputBoostMs
    if (boostPct != null && boostPct > 0 && boostMs != null && boostMs > 0) {
        parts += uiText(R.string.mode_input_boost, boostPct, boostMs)
    } else if (boostMs == 0) {
        parts += uiText(R.string.mode_no_input_boost)
    }
    val gpu = mode.gpu
    gpu.minFreq?.takeIf { it != "min" }?.let { parts += uiText(R.string.mode_gpu_min, it) }
    when {
        gpu.maxPerfPct != null -> parts += uiText(R.string.mode_gpu_max_pct, gpu.maxPerfPct)
        gpu.maxFreq != null && gpu.maxFreq != "max" -> parts += uiText(R.string.mode_gpu_max, gpu.maxFreq)
    }
    mode.mem.swappiness?.let { parts += uiText(R.string.mode_swappiness, it) }
    return if (parts.isEmpty()) uiText(R.string.mode_kernel_defaults) else UiText.Join(parts, " · ")
}

/** Why the engine holds the stock baseline, phrased for the user. [savedName] null: no saved profile. */
fun holdMessage(reason: String?, savedName: UiText?): UiText? = when (reason) {
    "revalidation_required" ->
        uiText(R.string.hold_revalidation, savedName ?: uiText(R.string.hold_revalidation_saved_fallback))
    "safe_mode" -> uiText(R.string.hold_safe_mode)
    "stock" -> uiText(R.string.hold_stock)
    null -> null
    else -> uiText(R.string.hold_other, reason)
}

/** What the engine actually did, by outcome class, for a successful apply. */
fun profileSuccessMessage(name: UiText, result: ModeApplyResult): UiText {
    val parts = mutableListOf(uiText(R.string.apply_outcome_verified, result.applied.size))
    if (result.constrained.isNotEmpty()) parts += uiText(R.string.apply_outcome_constrained, result.constrained.size)
    if (result.unverified.isNotEmpty()) parts += uiText(R.string.apply_outcome_unverified, result.unverified.size)
    if (result.skipped.isNotEmpty()) parts += uiText(R.string.apply_outcome_skipped, result.skipped.size)
    return uiText(R.string.apply_success, name, UiText.Join(parts, ", "))
}

private val APPLY_FAILURE = mapOf(
    "engine_unavailable" to R.string.apply_fail_engine_unavailable,
    "framework_unavailable" to R.string.apply_fail_framework_unavailable,
    "framework_incompatible" to R.string.apply_fail_framework_incompatible,
    "license" to R.string.apply_fail_license,
    "unauthorized" to R.string.apply_fail_unauthorized,
    "sentinel" to R.string.apply_fail_sentinel,
    "thermal_guard" to R.string.apply_fail_thermal_guard,
    "blocked" to R.string.apply_fail_blocked,
    "rejected" to R.string.apply_fail_rejected,
    "not_observed" to R.string.apply_fail_not_observed,
    "failed" to R.string.apply_fail_failed,
    "rollback_failed" to R.string.apply_fail_rollback_failed,
    "state" to R.string.apply_fail_state,
    "timeout" to R.string.apply_fail_timeout,
)

fun profileFailureMessage(result: ModeApplyResult): UiText {
    val reason = uiText(APPLY_FAILURE[result.kind] ?: R.string.apply_fail_unknown)
    val detail = listOfNotNull(result.error?.takeIf { it.isNotBlank() }) + result.failed.take(3)
    return if (detail.isEmpty()) reason else UiText.Join(listOf(reason) + detail.map { UiText.Raw(it) })
}

private val CUSTOM_WRITE_FAILURE = mapOf(
    "rejected" to R.string.custom_fail_rejected,
    "limit" to R.string.custom_fail_limit,
    "in_use" to R.string.custom_fail_in_use,
    "not_found" to R.string.custom_fail_not_found,
    "state" to R.string.custom_fail_state,
    "unauthorized" to R.string.custom_fail_unauthorized,
    "profile" to R.string.custom_fail_profile,
    "engine_unavailable" to R.string.custom_fail_engine_unavailable,
    "timeout" to R.string.custom_fail_timeout,
)

/** Why zperfd refused to store or remove a custom profile. */
fun customWriteFailureMessage(result: CustomWriteResult): UiText {
    val reason = uiText(CUSTOM_WRITE_FAILURE[result.kind] ?: R.string.custom_fail_unknown)
    val detail = result.error?.takeIf { it.isNotBlank() && result.kind != "in_use" }
    return if (detail != null) UiText.Join(listOf(reason, UiText.Raw(detail))) else reason
}
