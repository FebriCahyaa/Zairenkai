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
        assertEquals(uiText(R.string.setup_zkfc_missing_detail, "cannot open /dev/zkfc (module loaded? root?)"), zkfc.detail)
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
}
