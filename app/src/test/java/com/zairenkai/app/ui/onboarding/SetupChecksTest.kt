// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * First-run system check: each layer reports what it actually returned and a
 * failed read is never shown as a ready component.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import com.zairenkai.app.R
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.data.ApiVersion
import com.zairenkai.app.debug
import com.zairenkai.app.data.InfoResult
import com.zairenkai.app.data.ZperfStatus
import com.zairenkai.app.data.diag.BinState
import com.zairenkai.app.data.diag.InstallFacts
import com.zairenkai.app.data.diag.ModuleState
import com.zairenkai.app.data.diag.RootState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChecksTest {
    private val goodInfo = InfoResult(
        ok = true, api = ApiVersion(1, 4, 0), arch = "arm64", kernelType = "gki",
        kernelRelease = "6.1.75-android14", licenseState = "valid",
    )
    private val goodStatus = ZperfStatus(ok = true, desired = "balance", saved = true)

    private fun probe(
        root: Boolean? = true,
        info: InfoResult? = goodInfo,
        installed: Boolean? = true,
        status: ZperfStatus? = goodStatus,
    ) = SetupProbe(
        root = root, info = info, infoDone = root == true,
        engineInstalled = installed, engineStatus = status, engineDone = root == true,
    )

    private fun List<SetupCheck>.of(id: CheckId) = single { it.id == id }

    @Test
    fun rootWithoutTheKernelFrameworkIsNotReportedAsReady() {
        // The screen recording: root granted, `zkfctl info` answered ok=false.
        val failed = InfoResult(ok = false, error = "cannot open /dev/zkfc (module loaded? root?)")
        val checks = setupChecks(probe(info = failed))
        assertEquals(CheckState.OK, checks.of(CheckId.ROOT).state)
        val zkfc = checks.of(CheckId.ZKFC)
        assertEquals(CheckState.MISSING, zkfc.state)
        // The raw reason goes to the diagnostics report, not the visible detail.
        assertFalse("/dev/zkfc" in zkfc.detail.debug())
        assertNotNull(zkfc.fix)
        assertEquals(CheckState.MISSING, checks.of(CheckId.TOKEN).state)
        // Defaults of a failed read ("unknown", "none") never reach the user.
        assertTrue(checks.none { "unknown" in it.detail.debug() || "hook none" in it.detail.debug() })
        assertFalse(checks.profilesReady())
        assertTrue(checks.settled())
    }

    @Test
    fun aHealthyStackIsReadyAndDescribedFromReportedFieldsOnly() {
        val checks = setupChecks(probe())
        assertTrue(checks.all { it.state == CheckState.OK && it.fix == null })
        assertEquals("API v1.4.0 · arm64 · GKI · 6.1.75-android14", checks.of(CheckId.ZKFC).detail.debug())
        assertEquals(uiText(R.string.setup_engine_saved, uiText(R.string.profile_balanced_name)), checks.of(CheckId.ENGINE).detail)
        assertTrue(checks.profilesReady())

        val sparse = setupChecks(probe(info = goodInfo.copy(arch = "unknown", kernelType = "", kernelRelease = "")))
        assertEquals("API v1.4.0", sparse.of(CheckId.ZKFC).detail.debug())
    }

    @Test
    fun noRootShortCircuitsEveryLayerWithoutProbingThem() {
        val checks = setupChecks(SetupProbe(root = false))
        assertTrue(checks.all { it.state == CheckState.MISSING })
        assertNotNull(checks.of(CheckId.ROOT).fix)
        assertTrue(checks.settled())
    }

    @Test
    fun pendingProbesAreShownAsChecking() {
        val checks = setupChecks(SetupProbe(root = true))
        assertEquals(CheckState.OK, checks.of(CheckId.ROOT).state)
        assertTrue(checks.drop(1).all { it.state == CheckState.CHECKING })
        assertFalse(checks.settled())
        assertEquals(CheckState.CHECKING, setupChecks(SetupProbe()).of(CheckId.ROOT).state)
    }

    @Test
    fun engineAndTokenStatesExplainWhatIsLocked() {
        assertEquals(CheckState.MISSING, setupChecks(probe(installed = false, status = null)).of(CheckId.ENGINE).state)
        assertEquals(CheckState.WARN, setupChecks(probe(status = null)).of(CheckId.ENGINE).state)
        val safe = setupChecks(probe(status = goodStatus.copy(safeMode = true)))
        assertEquals(CheckState.WARN, safe.of(CheckId.ENGINE).state)
        assertFalse(safe.profilesReady())

        val noToken = setupChecks(probe(info = goodInfo.copy(licenseState = "missing")))
        assertEquals(CheckState.WARN, noToken.of(CheckId.TOKEN).state)
        assertFalse(noToken.profilesReady())
        val expired = setupChecks(probe(info = goodInfo.copy(licenseState = "expired")))
        assertEquals(uiText(R.string.setup_token_bad, uiText(R.string.license_state_expired)), expired.of(CheckId.TOKEN).detail)

        // An outdated API is a warning, not a blocker.
        val outdated = setupChecks(probe(info = goodInfo.copy(api = ApiVersion(1, 0, 0, outdated = true))))
        assertEquals(CheckState.WARN, outdated.of(CheckId.ZKFC).state)
        assertTrue(outdated.profilesReady())
    }

    private val nothing = InstallFacts(abi = "arm64-v8a", kernel = "5.10.269-Zen-Itsu+")
    private val notFound = InfoResult(ok = false, kind = "not_found", error = "/system/bin/sh: zkfctl: inaccessible or not found")

    @Test
    fun missingModuleIsExplainedWithoutRawShellOutput() {
        // The screen recording: root granted, module never flashed.
        val p = probe(info = notFound, installed = false, status = null).copy(facts = nothing)
        val checks = setupChecks(p)
        val zkfc = checks.of(CheckId.ZKFC)
        assertEquals(uiText(R.string.setup_zkfc_module_missing), zkfc.detail)
        assertEquals(uiText(R.string.setup_zkfc_fix), zkfc.fix)
        assertTrue(checks.none { "inaccessible" in it.detail.debug() || "0.0.0" in it.detail.debug() })
        assertEquals(CheckState.MISSING, checks.of(CheckId.ENGINE).state)
        assertEquals(uiText(R.string.setup_token_needs_zkfc), checks.of(CheckId.TOKEN).detail)
        // The raw reason is kept for support, in the diagnostics report.
        val report = diagnosticsReport(p, "1.0")
        assertTrue("inaccessible or not found" in report)
        assertTrue("module: MISSING" in report)
    }

    @Test
    fun eachInstallStateGetsItsOwnReason() {
        fun zkfc(f: InstallFacts) = setupChecks(probe(info = notFound).copy(facts = f)).of(CheckId.ZKFC).detail
        assertEquals(uiText(R.string.setup_zkfc_reboot_pending), zkfc(nothing.copy(module = ModuleState.UPDATE_PENDING)))
        assertEquals(uiText(R.string.setup_zkfc_module_disabled), zkfc(nothing.copy(module = ModuleState.DISABLED)))
        assertEquals(uiText(R.string.setup_zkfc_tool_missing, "arm64-v8a"), zkfc(nothing.copy(module = ModuleState.INSTALLED)))
        assertEquals(
            uiText(R.string.setup_zkfc_tool_not_executable),
            zkfc(nothing.copy(module = ModuleState.INSTALLED, zkfctl = BinState.NOT_EXECUTABLE)),
        )
        val tool = nothing.copy(module = ModuleState.INSTALLED, zkfctl = BinState.OK)
        assertEquals(uiText(R.string.setup_zkfc_not_in_kernel, "5.10.269-Zen-Itsu+"), zkfc(tool))
        assertEquals(uiText(R.string.setup_zkfc_ko_failed, "5.10.269-Zen-Itsu+"), zkfc(tool.copy(bundledKo = true)))
        // Without facts the failure kind still picks a reason.
        assertEquals(
            uiText(R.string.setup_zkfc_timeout),
            setupChecks(probe(info = InfoResult(ok = false, kind = "timeout"))).of(CheckId.ZKFC).detail,
        )
    }

    @Test
    fun rootFailureReasonsAreDistinct() {
        fun root(s: RootState) = setupChecks(SetupProbe(root = false, rootState = s)).of(CheckId.ROOT).detail
        assertEquals(uiText(R.string.setup_root_no_su), root(RootState.NO_SU))
        assertEquals(uiText(R.string.setup_root_timeout), root(RootState.TIMEOUT))
        assertEquals(uiText(R.string.setup_root_missing), root(RootState.DENIED))
    }

    @Test
    fun engineInstalledButStoppedSaysSo() {
        val f = nothing.copy(module = ModuleState.INSTALLED, zkfctl = BinState.OK, devNode = true, engine = BinState.OK)
        val stopped = setupChecks(probe(status = null).copy(facts = f)).of(CheckId.ENGINE)
        assertEquals(uiText(R.string.setup_engine_not_running), stopped.detail)
        val noexec = setupChecks(probe(installed = false, status = null).copy(facts = f.copy(engine = BinState.NOT_EXECUTABLE)))
        assertEquals(uiText(R.string.setup_engine_not_executable), noexec.of(CheckId.ENGINE).detail)
    }

    @Test
    fun diagnosticsReportHoldsNoTokenMaterial() {
        val report = diagnosticsReport(probe().copy(facts = nothing.copy(tokenFile = true)), "1.0")
        assertTrue("token file: present" in report)
        assertFalse("licensee" in report)
        assertFalse("fingerprint" in report)
    }
}
