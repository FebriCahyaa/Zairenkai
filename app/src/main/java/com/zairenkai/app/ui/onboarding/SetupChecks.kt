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
import com.zairenkai.app.data.diag.BinState
import com.zairenkai.app.data.diag.InstallFacts
import com.zairenkai.app.data.diag.ModuleState
import com.zairenkai.app.data.diag.RootState
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
    /** Why root is unavailable; refines [root] == false. */
    val rootState: RootState? = null,
    /** Installed components (one root shell); null when not read. */
    val facts: InstallFacts? = null,
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
    "subkey_scope" to R.string.license_state_subkey_scope,
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
        false -> when (p.rootState) {
            RootState.NO_SU -> SetupCheck(
                CheckId.ROOT, CheckState.MISSING, uiText(R.string.setup_root_no_su),
                fix = uiText(R.string.setup_root_no_su_fix),
            )
            RootState.TIMEOUT -> SetupCheck(
                CheckId.ROOT, CheckState.MISSING, uiText(R.string.setup_root_timeout),
                fix = uiText(R.string.setup_root_timeout_fix),
            )
            else -> SetupCheck(
                CheckId.ROOT, CheckState.MISSING, uiText(R.string.setup_root_missing),
                fix = uiText(R.string.setup_root_fix),
            )
        }
    }

    val info = p.info
    val zkfc = when {
        p.root == false -> SetupCheck(CheckId.ZKFC, CheckState.MISSING, needRoot)
        !p.infoDone -> SetupCheck(CheckId.ZKFC, CheckState.CHECKING, uiText(R.string.setup_zkfc_checking))
        info == null || !info.ok -> zkfcIssue(info, p.facts).let { issue ->
            SetupCheck(CheckId.ZKFC, CheckState.MISSING, issue.first, fix = issue.second)
        }
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
        p.facts?.engine == BinState.NOT_EXECUTABLE -> SetupCheck(
            CheckId.ENGINE, CheckState.MISSING, uiText(R.string.setup_engine_not_executable),
            fix = uiText(R.string.setup_reinstall_module_fix),
        )
        p.engineInstalled != true -> SetupCheck(
            CheckId.ENGINE, CheckState.MISSING, uiText(R.string.setup_engine_missing),
            fix = uiText(
                if (p.facts?.module == ModuleState.INSTALLED) R.string.setup_reinstall_module_fix
                else R.string.setup_engine_missing_fix,
            ),
        )
        status == null || !status.ok -> SetupCheck(
            CheckId.ENGINE, CheckState.WARN,
            uiText(if (p.facts != null && !p.facts.engineRunning) R.string.setup_engine_not_running else R.string.setup_engine_silent),
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
        info == null || !info.ok -> SetupCheck(
            CheckId.TOKEN, CheckState.MISSING,
            uiText(if (p.facts?.tokenFile == true) R.string.setup_token_stored_waiting else R.string.setup_token_needs_zkfc),
        )
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

/**
 * Why `zkfctl info` gave no answer, from what is installed. Raw shell output
 * never becomes the user-facing reason; it goes to [diagnosticsReport].
 */
internal fun zkfcIssue(info: InfoResult?, facts: InstallFacts?): Pair<UiText, UiText?> {
    val reinstall = uiText(R.string.setup_reinstall_module_fix)
    if (facts != null) {
        when (facts.module) {
            ModuleState.REMOVING -> return uiText(R.string.setup_zkfc_module_removing) to uiText(R.string.setup_zkfc_module_removing_fix)
            ModuleState.DISABLED -> return uiText(R.string.setup_zkfc_module_disabled) to uiText(R.string.setup_zkfc_module_disabled_fix)
            ModuleState.UPDATE_PENDING -> return uiText(R.string.setup_zkfc_reboot_pending) to uiText(R.string.setup_zkfc_reboot_pending_fix)
            else -> Unit
        }
        when (facts.zkfctl) {
            BinState.MISSING -> return if (facts.module == ModuleState.MISSING) {
                uiText(R.string.setup_zkfc_module_missing) to uiText(R.string.setup_zkfc_fix)
            } else {
                uiText(R.string.setup_zkfc_tool_missing, facts.abi.ifBlank { "?" }) to reinstall
            }
            BinState.NOT_EXECUTABLE -> return uiText(R.string.setup_zkfc_tool_not_executable) to reinstall
            BinState.OK -> if (!facts.devNode) {
                return if (facts.bundledKo) {
                    uiText(R.string.setup_zkfc_ko_failed, facts.kernel) to uiText(R.string.setup_zkfc_ko_failed_fix)
                } else {
                    uiText(R.string.setup_zkfc_not_in_kernel, facts.kernel) to uiText(R.string.setup_zkfc_fix)
                }
            }
        }
    }
    return when (info?.kind) {
        "timeout" -> uiText(R.string.setup_zkfc_timeout) to uiText(R.string.setup_zkfc_timeout_fix)
        "malformed" -> uiText(R.string.setup_zkfc_malformed) to reinstall
        "not_found" -> uiText(R.string.setup_zkfc_module_missing) to uiText(R.string.setup_zkfc_fix)
        "not_executable" -> uiText(R.string.setup_zkfc_tool_not_executable) to reinstall
        else -> uiText(R.string.setup_zkfc_missing) to uiText(R.string.setup_zkfc_fix)
    }
}

/**
 * Plain-text report for support: component states, versions and the raw
 * reason each probe gave. Holds no token contents or key material.
 */
fun diagnosticsReport(p: SetupProbe, appVersion: String): String = buildString {
    appendLine("Zairenkai diagnostics")
    appendLine("app: $appVersion")
    appendLine("root: ${p.rootState ?: p.root ?: "pending"}")
    p.facts?.let { f ->
        appendLine("kernel: ${f.kernel.ifBlank { "?" }} abi: ${f.abi.ifBlank { "?" }}")
        appendLine("module: ${f.module}")
        appendLine("zkfctl: ${f.zkfctl}${f.zkfctlPath?.let { " ($it)" } ?: ""}")
        appendLine("/dev/zkfc: ${if (f.devNode) "present" else "absent"}; kernel module: ${if (f.kernelModule) "loaded" else "not listed"}; bundled zkfc.ko: ${f.bundledKo}")
        appendLine("zperfd: ${f.engine}; running: ${f.engineRunning}")
        appendLine("token file: ${if (f.tokenFile) "present" else "absent"}")
    } ?: appendLine("install facts: not read")
    val info = p.info
    when {
        !p.infoDone -> appendLine("zkfctl info: not run")
        info == null -> appendLine("zkfctl info: no result")
        info.ok -> appendLine("zkfctl info: ok api=${info.api.major}.${info.api.minor}.${info.api.patch} outdated=${info.api.outdated} arch=${info.arch} type=${info.kernelType} hook=${info.hookMode} license=${info.licenseState}")
        else -> appendLine("zkfctl info: failed kind=${info.kind ?: "reported"} reason=${info.error?.replace('\n', ' ')?.take(200) ?: "-"}")
    }
    when {
        !p.engineDone -> appendLine("zperfd status: not run")
        p.engineStatus == null -> appendLine("zperfd status: no answer (installed=${p.engineInstalled})")
        else -> p.engineStatus.let { appendLine("zperfd status: ok=${it.ok} desired=${it.desired} effective=${it.effective} hold=${it.hold ?: "-"} safe_mode=${it.safeMode}") }
    }
}

/** Every probe has answered. */
fun List<SetupCheck>.settled(): Boolean = none { it.state == CheckState.CHECKING }

/** A check that does not prevent applying a profile (an outdated ZKFC API only warns). */
fun SetupCheck.allowsProfiles(): Boolean = state == CheckState.OK || (id == CheckId.ZKFC && state == CheckState.WARN)

/** Profiles can be applied now: root, ZKFC, a responsive engine and a valid token. */
fun List<SetupCheck>.profilesReady(): Boolean = all { it.allowsProfiles() }
