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

import com.zairenkai.app.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Where the installed build looks for its updates. */
enum class UpdateChannel(val tag: String, val manifestAsset: String, val apkAsset: String) {
    /** Stable, officially signed build, published as a full GitHub release. */
    RELEASE("release", "version-release.json", "Zairenkai-release.apk"),

    /** Early-access build, signed and published as the fixed canary prerelease. */
    CANARY("canary", "version-canary.json", "Zairenkai-canary.apk");

    /** GitHub Releases hosts APK bytes; the License Server proxies signed metadata. */
    fun downloadBase(repo: String = REPO): String = when (this) {
        RELEASE -> "https://github.com/$repo/releases/latest/download"
        CANARY -> "https://github.com/$repo/releases/download/canary"
    }

    /** Update metadata always enters the app through our License Server API. */
    fun manifestUrl(serverBaseUrl: String = BuildConfig.ZKFC_LICENSE_SERVER_URL): String =
        "${serverBaseUrl.trimEnd('/')}/v1/updates/$tag"

    /** Detached metadata signature is proxied by the same server route. */
    fun signatureUrl(serverBaseUrl: String = BuildConfig.ZKFC_LICENSE_SERVER_URL): String =
        "${manifestUrl(serverBaseUrl)}.sig"

    /** Human-facing Releases page for a manual/browser fallback. */
    fun pageUrl(repo: String = REPO): String = when (this) {
        RELEASE -> "https://github.com/$repo/releases/latest"
        CANARY -> "https://github.com/$repo/releases/tag/canary"
    }

    companion object {
        const val REPO = "FebriCahyaa/Zairenkai"

        /** Only the Canary and Release distribution channels are supported. */
        fun forBuildType(buildType: String): UpdateChannel =
            if (buildType.equals("canary", ignoreCase = true)) CANARY else RELEASE
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
            if (code !in 1..2_100_000_000 || apiMajor < 1 || apiMajor > 999 ||
                !name.matches(Regex("v?\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?")) ||
                buildId.length > 160 || buildId.any { it.isISOControl() }) return null
            val apk = o["apkUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "${channel.downloadBase(repo)}/${channel.apkAsset}"
            val parsedUrl = runCatching { java.net.URI(apk) }.getOrNull() ?: return null
            val assetPath = parsedUrl.path.orEmpty()
            if (parsedUrl.scheme != "https" || parsedUrl.host != "github.com" ||
                parsedUrl.userInfo != null || parsedUrl.port !in listOf(-1, 443) ||
                parsedUrl.query != null || parsedUrl.fragment != null ||
                !assetPath.startsWith("/$repo/releases/") ||
                assetPath.substringAfterLast('/') != channel.apkAsset) return null
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
