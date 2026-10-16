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
        val json = """{"schemaVersion":1,"versionCode":5,"versionName":"1.2.0","apkUrl":"https://github.com/FebriCahyaa/Zairenkai/releases/download/v1.2.0/Zairenkai-release.apk","sha256":"${"A".repeat(64)}","apiMajor":1,"buildId":"deadbeef-12-1"}"""
        val m = ReleaseManifest.parse(json, UpdateChannel.RELEASE)!!
        assertEquals(5, m.versionCode)
        assertEquals("1.2.0", m.versionName)
        assertEquals("https://github.com/FebriCahyaa/Zairenkai/releases/download/v1.2.0/Zairenkai-release.apk", m.apkUrl)
        assertEquals("a".repeat(64), m.sha256)          // normalized to lowercase
        assertEquals(1, m.apiMajor)
        assertEquals("deadbeef-12-1", m.buildId)
        assertTrue(m.isNewerThan(2))
        assertFalse(m.isNewerThan(5))
        assertFalse(m.isNewerThan(9))
    }

    @Test fun fallsBackToStableApkName() {
        // No apkUrl in the manifest -> derive the channel's stable asset URL.
        val m = ReleaseManifest.parse("""{"schemaVersion":1,"versionCode":2,"versionName":"1.1.0","apiMajor":1,"buildId":"deadbeef-1-1","sha256":"${"b".repeat(64)}"}""", UpdateChannel.CANARY)!!
        assertTrue(m.apkUrl.endsWith("/releases/download/canary/Zairenkai-canary.apk"))
        assertEquals("b".repeat(64), m.sha256)
    }

    @Test fun refusesJunkOrMissingFields() {
        assertNull(ReleaseManifest.parse("not json", UpdateChannel.RELEASE))
        assertNull(ReleaseManifest.parse("""{"schemaVersion":1,"versionName":"1.0"}""", UpdateChannel.RELEASE))   // no code
        assertNull(ReleaseManifest.parse("""{"schemaVersion":1,"versionCode":1}""", UpdateChannel.RELEASE))        // no name
        assertNull(ReleaseManifest.parse("""{"schemaVersion":1,"versionCode":1,"versionName":"1.0","apiMajor":1,"buildId":"b","sha256":"bad"}""", UpdateChannel.RELEASE))
    }


    @Test fun refusesUnsupportedSchemaAndUnsafeUrls() {
        val hash = "${"c".repeat(64)}"
        val base = "\"versionCode\":1,\"versionName\":\"1.0.0\",\"apiMajor\":1,\"buildId\":\"build-1\",\"sha256\":\"$hash\""
        assertNull(ReleaseManifest.parse("""{"schemaVersion":2,$base}""", UpdateChannel.RELEASE))
        assertNull(ReleaseManifest.parse("""{"schemaVersion":1,"versionCode":0,"versionName":"1.0.0","apiMajor":1,"buildId":"build-1","sha256":"$hash"}""", UpdateChannel.RELEASE))
        assertNull(ReleaseManifest.parse("""{"schemaVersion":1,"versionCode":1,"versionName":"1.0.0","apiMajor":1,"buildId":"build-1","sha256":"$hash","apkUrl":"https://user@github.com/FebriCahyaa/Zairenkai/releases/latest/download/app.apk"}""", UpdateChannel.RELEASE))
        assertNull(ReleaseManifest.parse("""{"schemaVersion":1,"versionCode":1,"versionName":"1.0.0","apiMajor":1,"buildId":"build-1","sha256":"$hash","apkUrl":"https://github.com:8443/FebriCahyaa/Zairenkai/releases/latest/download/app.apk"}""", UpdateChannel.RELEASE))
        assertNull(ReleaseManifest.parse("""{"schemaVersion":1,"versionCode":1,"versionName":"1.0.0","apiMajor":1,"buildId":"build-1","sha256":"$hash","apkUrl":"https://github.com/FebriCahyaa/Zairenkai/releases/latest/download/Zairenkai-canary.apk"}""", UpdateChannel.RELEASE))
        assertNull(ReleaseManifest.parse("""{"schemaVersion":1,"versionCode":1,"versionName":"1.0.0","apiMajor":1,"buildId":"build-1","sha256":"$hash","apkUrl":"https://github.com/FebriCahyaa/Zairenkai/releases/download/canary/Zairenkai-release.apk"}""", UpdateChannel.CANARY))
    }

    @Test fun buildTypeSelectsChannel() {
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.forBuildType("release"))
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.forBuildType("debug"))
        assertEquals(UpdateChannel.CANARY, UpdateChannel.forBuildType("canary"))
    }

    @Test fun urlsPointAtTheChannel() {
        // Signed metadata is proxied through License Server; APK downloads remain on Releases.
        assertTrue(UpdateChannel.RELEASE.manifestUrl("https://license.example").endsWith("/v1/updates/release"))
        assertTrue(UpdateChannel.CANARY.manifestUrl("https://license.example").endsWith("/v1/updates/canary"))
        assertTrue(UpdateChannel.RELEASE.signatureUrl("https://license.example").endsWith("/v1/updates/release.sig"))
        assertTrue(UpdateChannel.RELEASE.pageUrl().endsWith("/releases/latest"))
        assertTrue(UpdateChannel.CANARY.pageUrl().endsWith("/releases/tag/canary"))
    }
}
