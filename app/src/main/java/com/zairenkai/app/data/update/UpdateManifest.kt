// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The update channels and the small JSON manifest each one publishes. This is
 * pure logic (parsing + version comparison), kept apart from the networking so
 * it can be unit-tested directly.
 *
 * Each release carries a stable-named manifest file and a stable-named APK, so
 * the app can fetch a fixed URL per channel instead of scraping a release page.
 * The CI that produces these assets lives in .github/workflows/app-apks.yml.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Where the installed build looks for its updates. */
enum class UpdateChannel(val tag: String, val manifestAsset: String, val apkAsset: String) {
    /** Stable, officially signed build, published as a full GitHub release. */
    RELEASE("release", "version-release.json", "Zairenkai-release.apk"),

    /** Debug-signed preview build, published as a GitHub prerelease. */
    DEBUG("preview", "version-debug.json", "Zairenkai-debug.apk");

    /** Stable download base for this channel's assets. RELEASE uses
     *  /releases/latest/download/, which GitHub resolves to the newest
     *  (non-prerelease) release whatever its version tag; the DEBUG prerelease
     *  stays on the fixed "preview" tag. */
    fun downloadBase(repo: String = REPO): String = when (this) {
        RELEASE -> "https://github.com/$repo/releases/latest/download"
        DEBUG -> "https://github.com/$repo/releases/download/$tag"
    }

    /** Raw download URL of this channel's manifest. */
    fun manifestUrl(repo: String = REPO): String = "${downloadBase(repo)}/$manifestAsset"

    /** Detached signature asset; required for the stable production channel. */
    fun signatureUrl(repo: String = REPO): String = "${manifestUrl(repo)}.sig"

    /** Human-facing releases page for this channel (browser fallback). */
    fun pageUrl(repo: String = REPO): String = when (this) {
        RELEASE -> "https://github.com/$repo/releases/latest"
        DEBUG -> "https://github.com/$repo/releases/tag/preview"
    }

    companion object {
        const val REPO = "FebriCahyaa/Zairenkai"

        /** The channel a given build type updates along. A debug-signed build
         *  (debug or the optimized "preview") can only be replaced in place by
         *  another debug-signed build, so it follows the debug channel. */
        fun forBuildType(buildType: String): UpdateChannel =
            if (buildType.equals("release", ignoreCase = true)) RELEASE else DEBUG
    }
}

/** One published build, as described by a channel manifest. */
data class ReleaseManifest(
    val versionCode: Int,
    val versionName: String,
    /** Direct download URL of the APK (falls back to the channel's stable name). */
    val apkUrl: String,
    /** Lowercase, 64-character SHA-256 of the APK, required and checked after download. */
    val sha256: String,
    /** Kernel/API contract supported by this release. */
    val apiMajor: Int,
    /** Unique CI build identity, not a license token or secret. */
    val buildId: String,
) {
    /** True when this build is newer than [installedVersionCode]. */
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parse a channel manifest; null if it is missing required fields. */
        fun parse(json: String, channel: UpdateChannel, repo: String = UpdateChannel.REPO): ReleaseManifest? {
            val o = runCatching { lenient.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val schema = o["schemaVersion"]?.jsonPrimitive?.intOrNull ?: return null
            if (schema != 1) return null
            val code = o["versionCode"]?.jsonPrimitive?.intOrNull ?: return null
            val name = o["versionName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            val apiMajor = o["apiMajor"]?.jsonPrimitive?.intOrNull ?: return null
            val buildId = o["buildId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            if (code < 1 || apiMajor < 1 || buildId.length > 160 || buildId.any { it.isISOControl() }) return null
            val apk = o["apkUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "${channel.downloadBase(repo)}/${channel.apkAsset}"
            val parsedUrl = runCatching { java.net.URI(apk) }.getOrNull() ?: return null
            if (parsedUrl.scheme != "https" || parsedUrl.host != "github.com" ||
                parsedUrl.userInfo != null || parsedUrl.port !in listOf(-1, 443) ||
                parsedUrl.query != null || parsedUrl.fragment != null ||
                !parsedUrl.path.orEmpty().contains("/releases/")) return null
            val sha = (o["sha256"]?.jsonPrimitive?.contentOrNull ?: "").trim().lowercase()
            if (!sha.matches(Regex("[0-9a-f]{64}"))) return null
            return ReleaseManifest(
                versionCode = code,
                versionName = name,
                apkUrl = apk,
                sha256 = sha,
                apiMajor = apiMajor,
                buildId = buildId,
            )
        }
    }
}
