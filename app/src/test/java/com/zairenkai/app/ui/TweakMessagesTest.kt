// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Every failure class zperfd or the client can report must map to a specific
 * message; none may fall through to the generic text or read as success.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.data.TweakWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakMessagesTest {
    private val generic = tweakFailureMessage(TweakWriteResult(kind = "something-new"))

    @Test
    fun everyKnownFailureKindHasASpecificMessage() {
        val kinds = listOf(
            "engine_unavailable", "framework_unavailable", "framework_incompatible", "license",
            "unauthorized", "sentinel", "lite", "unknown", "rejected", "not_observed",
            "rollback_failed", "state", "timeout",
        )
        val messages = kinds.map { tweakFailureMessage(TweakWriteResult(kind = it)) }
        messages.forEach { assertNotEquals(generic, it) }
        assertEquals("messages must be distinct", kinds.size, messages.toSet().size)
    }

    @Test
    fun frameworkAvailabilityIsNotReportedAsAuthorization() {
        val missing = tweakFailureMessage(TweakWriteResult(kind = "framework_unavailable"))
        val unlicensed = tweakFailureMessage(TweakWriteResult(kind = "license"))
        assertTrue(missing.contains("ZKFC"))
        assertTrue(unlicensed.contains("Token"))
        assertNotEquals(missing, unlicensed)
    }

    @Test
    fun engineDetailIsAppendedAndSuccessNamesTargets() {
        val msg = tweakFailureMessage(TweakWriteResult(kind = "rejected", error = "'westwood' is not supported by this device"))
        assertTrue(msg.endsWith("'westwood' is not supported by this device"))
        val ok = tweakSuccessMessage(TweakWriteResult(ok = true, id = "io_scheduler", value = "kyber", targets = 3))
        assertTrue(ok.contains("3 target"))
        assertTrue(ok.contains("terverifikasi"))
    }
}
