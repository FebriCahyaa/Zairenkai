// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * First-run system check, as pure functions: what each layer of the stack
 * (root -> ZKFC kernel framework -> zperfd engine -> API token) reports, in
 * words a user can act on. Raw fields are only shown when they were actually
 * reported; a failed read is never presented as a ready component.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import androidx.annotation.StringRes
import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.data.InfoResult
import com.zairenkai.app.data.ZperfStatus
import com.zairenkai.app.domain.Profiles

enum class CheckState { CHECKING, OK, WARN, MISSING }

enum class CheckId(@StringRes val title: Int) {
    ROOT(R.string.setup_check_root),
    ZKFC(R.string.setup_check_zkfc),
    ENGINE(R.string.setup_check_engine),
    TOKEN(R.string.setup_check_token),
}

data class SetupCheck(
    val id: CheckId,
    val state: CheckState,
    val detail: UiText,
    /** What the user can do about a non-OK state; null when nothing is needed. */
    val fix: UiText? = null,
)

/**
 * What the probes returned. A null field means that probe has not finished
 * (CHECKING); [root] false short-circuits everything that needs root.
 */
data class SetupProbe(
    val root: Boolean? = null,
    val info: InfoResult? = null,
    val infoDone: Boolean = false,
    val engineInstalled: Boolean? = null,
    val engineStatus: ZperfStatus? = null,
    val engineDone: Boolean = false,
)

private val LICENSE_STATES = mapOf(
    "valid" to R.string.license_state_valid,
    "missing" to R.string.license_state_missing,
    "expired" to R.string.license_state_expired,
    "bad_signature" to R.string.license_state_bad_signature,
    "wrong_binding" to R.string.license_state_wrong_binding,
    "api_outdated" to R.string.license_state_api_outdated,
    "api_incompatible" to R.string.license_state_api_incompatible,
    "malformed" to R.string.license_state_malformed,
    "no_owner_key" to R.string.license_state_no_owner_key,
    "revoked" to R.string.license_state_revoked,
)

fun licenseLabel(state: String): UiText = LICENSE_STATES[state]?.let { uiText(it) } ?: UiText.Raw(state)

private fun kernelSummary(info: InfoResult): UiText = UiText.Join(
    buildList {
        add(UiText.Raw("API v${info.api.major}.${info.api.minor}.${info.api.patch}"))
        info.arch.takeIf { it.isNotBlank() && it != "unknown" }?.let { add(UiText.Raw(it)) }
        when (info.kernelType) {
            "gki" -> add(UiText.Raw("GKI"))
            "non-gki" -> add(UiText.Raw("non-GKI"))
        }
        info.kernelRelease.takeIf { it.isNotBlank() }?.let { add(UiText.Raw(it)) }
    },
    " · ",
)

fun setupChecks(p: SetupProbe): List<SetupCheck> {
    val needRoot = uiText(R.string.setup_needs_root)
    val root = when (p.root) {
        null -> SetupCheck(CheckId.ROOT, CheckState.CHECKING, uiText(R.string.setup_root_checking))
        true -> SetupCheck(CheckId.ROOT, CheckState.OK, uiText(R.string.setup_root_ok))
        false -> SetupCheck(
            CheckId.ROOT, CheckState.MISSING, uiText(R.string.setup_root_missing),
            fix = uiText(R.string.setup_root_fix),
        )
    }

    val info = p.info
    val zkfc = when {
        p.root == false -> SetupCheck(CheckId.ZKFC, CheckState.MISSING, needRoot)
        !p.infoDone -> SetupCheck(CheckId.ZKFC, CheckState.CHECKING, uiText(R.string.setup_zkfc_checking))
        info == null || !info.ok -> SetupCheck(
            CheckId.ZKFC, CheckState.MISSING,
            info?.error?.takeIf { it.isNotBlank() }?.let { uiText(R.string.setup_zkfc_missing_detail, it) }
                ?: uiText(R.string.setup_zkfc_missing),
            fix = uiText(R.string.setup_zkfc_fix),
        )
        info.api.outdated -> SetupCheck(
            CheckId.ZKFC, CheckState.WARN, uiText(R.string.setup_zkfc_outdated, kernelSummary(info)),
            fix = uiText(R.string.setup_zkfc_outdated_fix),
        )
        else -> SetupCheck(CheckId.ZKFC, CheckState.OK, kernelSummary(info))
    }

    val status = p.engineStatus
    val engine = when {
        p.root == false -> SetupCheck(CheckId.ENGINE, CheckState.MISSING, needRoot)
        !p.engineDone -> SetupCheck(CheckId.ENGINE, CheckState.CHECKING, uiText(R.string.setup_engine_checking))
        p.engineInstalled != true -> SetupCheck(
            CheckId.ENGINE, CheckState.MISSING, uiText(R.string.setup_engine_missing),
            fix = uiText(R.string.setup_engine_missing_fix),
        )
        status == null || !status.ok -> SetupCheck(
            CheckId.ENGINE, CheckState.WARN, uiText(R.string.setup_engine_silent),
            fix = uiText(R.string.setup_engine_silent_fix),
        )
        else -> {
            val mode = Profiles.byMode(status.desired)?.let { uiText(it.nameRes) } ?: UiText.Raw(status.desired)
            val detail = when {
                status.safeMode -> uiText(R.string.setup_engine_safe_mode)
                status.saved -> uiText(R.string.setup_engine_saved, mode)
                else -> uiText(R.string.setup_engine_default, mode)
            }
            SetupCheck(CheckId.ENGINE, if (status.safeMode) CheckState.WARN else CheckState.OK, detail)
        }
    }

    val token = when {
        p.root == false -> SetupCheck(CheckId.TOKEN, CheckState.MISSING, needRoot)
        !p.infoDone -> SetupCheck(CheckId.TOKEN, CheckState.CHECKING, uiText(R.string.setup_token_checking))
        info == null || !info.ok -> SetupCheck(CheckId.TOKEN, CheckState.MISSING, uiText(R.string.setup_token_needs_zkfc))
        info.licenseState == "valid" -> SetupCheck(CheckId.TOKEN, CheckState.OK, uiText(R.string.setup_token_ok))
        info.licenseState == "missing" -> SetupCheck(
            CheckId.TOKEN, CheckState.WARN, uiText(R.string.setup_token_missing),
            fix = uiText(R.string.setup_token_missing_fix),
        )
        else -> SetupCheck(
            CheckId.TOKEN, CheckState.MISSING, uiText(R.string.setup_token_bad, licenseLabel(info.licenseState)),
            fix = uiText(R.string.setup_token_bad_fix),
        )
    }
    return listOf(root, zkfc, engine, token)
}

/** Every probe has answered. */
fun List<SetupCheck>.settled(): Boolean = none { it.state == CheckState.CHECKING }

/** A check that does not prevent applying a profile (an outdated ZKFC API only warns). */
fun SetupCheck.allowsProfiles(): Boolean = state == CheckState.OK || (id == CheckId.ZKFC && state == CheckState.WARN)

/** Profiles can be applied now: root, ZKFC, a responsive engine and a valid token. */
fun List<SetupCheck>.profilesReady(): Boolean = all { it.allowsProfiles() }
