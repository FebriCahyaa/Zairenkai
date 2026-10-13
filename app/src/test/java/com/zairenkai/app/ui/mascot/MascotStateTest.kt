// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zai follows the real setup state: it never celebrates or reports ready
 * while a probe is running or when something is missing.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.mascot

import com.zairenkai.app.R
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.ui.onboarding.CheckId
import com.zairenkai.app.ui.onboarding.CheckState
import com.zairenkai.app.ui.onboarding.SetupCheck
import com.zairenkai.app.ui.onboarding.doneMascotState
import org.junit.Assert.assertEquals
import org.junit.Test

class MascotStateTest {
    private fun checks(vararg s: CheckState) = CheckId.entries.zip(s.toList()).map { (id, st) -> SetupCheck(id, st, uiText(R.string.setup_root_ok)) }

    @Test
    fun checkStepFollowsTheProbes() {
        assertEquals(MascotState.INSPECTING, checkMascotState(settled = false, allOk = false))
        assertEquals(MascotState.READY, checkMascotState(settled = true, allOk = true))
        assertEquals(MascotState.WARNING, checkMascotState(settled = true, allOk = false))
    }

    @Test
    fun completionCelebratesOnlyAFullSetup() {
        val ok = CheckState.OK
        assertEquals(MascotState.CELEBRATING, doneMascotState(checks(ok, ok, ok, ok)))
        // An outdated ZKFC API only warns: usable, so ready but no party.
        assertEquals(MascotState.READY, doneMascotState(checks(ok, CheckState.WARN, ok, ok)))
        assertEquals(MascotState.WARNING, doneMascotState(checks(ok, CheckState.MISSING, CheckState.MISSING, CheckState.MISSING)))
        assertEquals(MascotState.INSPECTING, doneMascotState(checks(ok, CheckState.CHECKING, ok, ok)))
    }
}
