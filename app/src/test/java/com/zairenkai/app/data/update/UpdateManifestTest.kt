// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The update manifest parsing and version comparison are pure, so they are
 * tested directly: a well-formed manifest parses, a newer versionCode is
 * detected, junk is refused, and each build type maps to the right channel.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    @Test fun parsesAndComparesVersion() {
        val json = """{"versionCode":5,"versionName":"1.2.0","apkUrl":"https://x/app.apk","sha256":"ABCDEF"}"""
        val m = ReleaseManifest.parse(json, UpdateChannel.RELEASE)!!
        assertEquals(5, m.versionCode)
        assertEquals("1.2.0", m.versionName)
        assertEquals("https://x/app.apk", m.apkUrl)
        assertEquals("abcdef", m.sha256)               // normalised to lowercase
        assertTrue(m.isNewerThan(2))
        assertFalse(m.isNewerThan(5))
        assertFalse(m.isNewerThan(9))
    }

    @Test fun fallsBackToStableApkName() {
        // No apkUrl in the manifest -> derive the channel's stable asset URL.
        val m = ReleaseManifest.parse("""{"versionCode":2,"versionName":"1.1.0"}""", UpdateChannel.DEBUG)!!
        assertTrue(m.apkUrl.endsWith("/releases/download/preview/Zairenkai-debug.apk"))
        assertEquals("", m.sha256)
    }

    @Test fun refusesJunkOrMissingFields() {
        assertNull(ReleaseManifest.parse("not json", UpdateChannel.RELEASE))
        assertNull(ReleaseManifest.parse("""{"versionName":"1.0"}""", UpdateChannel.RELEASE))   // no code
        assertNull(ReleaseManifest.parse("""{"versionCode":1}""", UpdateChannel.RELEASE))        // no name
    }

    @Test fun buildTypeSelectsChannel() {
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.forBuildType("release"))
        assertEquals(UpdateChannel.DEBUG, UpdateChannel.forBuildType("debug"))
        assertEquals(UpdateChannel.DEBUG, UpdateChannel.forBuildType("preview"))
    }

    @Test fun urlsPointAtTheChannel() {
        // RELEASE resolves through /releases/latest/download so a versioned tag
        // (v1.2.0, …) still maps to a stable URL; DEBUG stays on "preview".
        assertTrue(UpdateChannel.RELEASE.manifestUrl().endsWith("/releases/latest/download/version-release.json"))
        assertTrue(UpdateChannel.DEBUG.manifestUrl().endsWith("/releases/download/preview/version-debug.json"))
        assertTrue(UpdateChannel.RELEASE.pageUrl().endsWith("/releases/latest"))
        assertTrue(UpdateChannel.DEBUG.pageUrl().endsWith("/releases/tag/preview"))
    }
}
