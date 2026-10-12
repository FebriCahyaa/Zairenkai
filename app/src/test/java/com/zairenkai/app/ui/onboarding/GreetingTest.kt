// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The typed greeting: hello types and erases, the brand types, then the
 * tagline types on the second line and the last frame is the final text.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTest {
    @Test
    fun scriptTypesErasesAndEndsOnTheFinalText() {
        val f = greetingFrames("Halo", "Zairenkai", "Performa.")
        assertEquals("H" to "", f.first())
        assertTrue("Halo" to "" in f)
        // Erased fully before the brand starts.
        val erased = f.indexOf("" to "")
        assertTrue(erased > f.indexOf("Halo" to ""))
        assertTrue(f.indexOf("Z" to "") > erased)
        assertEquals("Zairenkai" to "Performa.", f.last())
        // The second line only grows once the brand is complete.
        assertTrue(f.filter { it.second.isNotEmpty() }.all { it.first == "Zairenkai" })
    }
}
