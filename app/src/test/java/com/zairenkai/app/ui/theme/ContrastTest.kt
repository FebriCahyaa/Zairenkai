// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Text colours stay readable in light and dark for every accent (WCAG 2.1):
 * body text and primary-coloured text 4.5:1 on the surfaces they sit on; the
 * large gradient greeting 3:1 at every gradient stop; status colours used as
 * text 4.5:1 on every container and in their tinted pills.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.zairenkai.app.ui.onboarding.brandGradient
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    private val failures = mutableListOf<String>()

    private fun check(what: String, fg: Color, bg: Color, min: Float) {
        val c = contrast(fg, bg)
        if (c < min) failures += "%s: %.2f < %.1f".format(what, c, min)
    }

    @Test
    fun everyAccentReadsInLightAndDark() {
        for (accent in ZkAccents) for (dark in listOf(false, true)) {
            val cs = schemeFor(accent, dark)
            val tag = "${accent.id}/${if (dark) "dark" else "light"}"
            for ((name, bg) in listOf("surface" to cs.surface, "container" to cs.surfaceContainer, "containerHigh" to cs.surfaceContainerHigh)) {
                check("$tag onSurface on $name", cs.onSurface, bg, 4.5f)
                check("$tag onSurfaceVariant on $name", cs.onSurfaceVariant, bg, 4.5f)
                check("$tag primary on $name", cs.primary, bg, 4.5f)
            }
            check("$tag onPrimaryContainer", cs.onPrimaryContainer, cs.primaryContainer, 4.5f)
            check("$tag inverse pill", cs.inverseOnSurface, cs.inverseSurface, 4.5f)
            check("$tag onPrimary", cs.onPrimary, cs.primary, 4.5f)
            brandGradient(dark).forEachIndexed { i, stop -> check("$tag greeting stop $i", stop, cs.surface, 3f) }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** Status colours used as text (home, setup checks) and their tinted pills. */
    @Test
    fun statusInksReadOnEveryContainer() {
        for (accent in ZkAccents) for (dark in listOf(false, true)) {
            val cs = schemeFor(accent, dark)
            val tag = "${accent.id}/${if (dark) "dark" else "light"}"
            for ((name, c) in listOf("good" to ZkGood, "warn" to ZkWarn, "bad" to ZkBad)) {
                val ink = statusInkOn(c, cs)
                for ((bgName, bg) in listOf(
                    "surface" to cs.surface, "container" to cs.surfaceContainer,
                    "containerHigh" to cs.surfaceContainerHigh, "containerHighest" to cs.surfaceContainerHighest,
                )) check("$tag $name on $bgName", ink, bg, 4.5f)
                check("$tag $name pill", ink, lerp(cs.surfaceContainer, ink, 0.12f), 4.5f)
                // Graphics (gauge arc, status dots) need 3:1 (WCAG 1.4.11).
                val graphic = readableInk(c, cs.surfaceContainerHighest, min = 3f)
                for (bg in listOf(cs.surface, cs.surfaceContainer, cs.surfaceContainerHigh, cs.surfaceContainerHighest)) {
                    check("$tag $name graphic", graphic, bg, 3f)
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
