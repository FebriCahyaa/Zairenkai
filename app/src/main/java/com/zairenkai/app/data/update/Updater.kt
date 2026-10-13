// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The in-app updater: fetch a channel manifest, download the APK into the app's
 * own cache, verify its SHA-256, and hand it to the system package installer.
 *
 * Trust and privacy:
 *  - The app only reaches the network when the user (or an explicit auto-check
 *    they left enabled) asks it to; it talks to GitHub releases only and sends
 *    no identifiers. There is no background polling service.
 *  - The downloaded APK is verified against the SHA-256 in the manifest before
 *    it is offered for install. The install itself still goes through Android's
 *    package installer, which only replaces the app in place when the new APK
 *    is signed with the same key — so a debug build cannot silently overwrite a
 *    release install or vice-versa. We never weaken that.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Outcome of a manifest check. */
sealed interface CheckResult {
    data class UpToDate(val installed: Int) : CheckResult
    data class Available(val manifest: ReleaseManifest) : CheckResult
    data class Failed(val reason: Reason) : CheckResult

    enum class Reason { NO_NETWORK, NOT_PUBLISHED, BAD_MANIFEST }
}

class Updater(private val context: Context) {
    private val timeoutMs = 15_000

    /** Check [channel] against [installedVersionCode]. Runs off the main thread. */
    suspend fun check(channel: UpdateChannel, installedVersionCode: Int): CheckResult =
        withContext(Dispatchers.IO) {
            val body = runCatching { get(channel.manifestUrl()) }.getOrElse { e ->
                return@withContext CheckResult.Failed(
                    if (e is java.io.FileNotFoundException) CheckResult.Reason.NOT_PUBLISHED
                    else CheckResult.Reason.NO_NETWORK,
                )
            } ?: return@withContext CheckResult.Failed(CheckResult.Reason.NOT_PUBLISHED)
            val manifest = ReleaseManifest.parse(body, channel)
                ?: return@withContext CheckResult.Failed(CheckResult.Reason.BAD_MANIFEST)
            if (manifest.isNewerThan(installedVersionCode)) CheckResult.Available(manifest)
            else CheckResult.UpToDate(installedVersionCode)
        }

    /**
     * Download the APK to the app cache, reporting progress in [0f,1f] (or -1f
     * when the total size is unknown). Verifies the SHA-256 when the manifest
     * carries one. Returns the file, or null on any failure.
     */
    suspend fun download(manifest: ReleaseManifest, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // One file per channel version; clear older downloads first.
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "zairenkai-${manifest.versionCode}.apk")
            val ok = runCatching {
                val conn = open(manifest.apkUrl)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } >= 0) {
                            sink.write(buf, 0, read)
                            done += read
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
                conn.disconnect()
                true
            }.getOrDefault(false)
            if (!ok) { out.delete(); return@withContext null }
            if (manifest.sha256.isNotEmpty() && !sha256(out).equals(manifest.sha256, ignoreCase = true)) {
                out.delete()
                return@withContext null
            }
            out
        }

    /** Hand the downloaded APK to the system installer via a content URI. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun get(urlStr: String): String? {
        val conn = open(urlStr)
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also { conn.disconnect() }
    }

    private fun open(urlStr: String): HttpURLConnection {
        var url = URL(urlStr)
        var redirects = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream, application/json")
                setRequestProperty("User-Agent", "Zairenkai-Updater")
            }
            val code = conn.responseCode
            // GitHub asset URLs redirect to a signed CDN URL; follow cross-protocol.
            if (code in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(url, loc)
                redirects++
                continue
            }
            return conn
        }
        return url.openConnection() as HttpURLConnection
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
