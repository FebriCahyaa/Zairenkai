// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Verifies the release-bundled module against a hash pinned in the signed APK,
 * validates every file against the module manifest, then asks the active root
 * manager's supported CLI to install the ZIP. APatch/no-CLI environments fall
 * back to sharing the already-verified ZIP instead of guessing an undocumented
 * command. Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import android.content.Context
import com.zairenkai.app.BuildConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ModuleInstallCode {
    INSTALLED,
    NOT_BUNDLED,
    APK_MODIFIED,
    HASH_MISMATCH,
    INVALID_ARCHIVE,
    ROOT_INSTALL_FAILED,
    MANAGER_UNAVAILABLE,
}

data class ModuleInstallOutcome(
    val code: ModuleInstallCode,
    val detail: String = "",
    val shareableZip: Boolean = false,
)

/** Pure archive verifier; it never extracts untrusted ZIP paths to the filesystem. */
internal object ModuleArchiveVerifier {
    private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024
    private val hashLine = Regex("^([0-9a-fA-F]{64})\\s+(.+?)\\s*$")

    fun verify(file: File, targetKmi: String? = null): String? = try {
        ZipFile(file).use { zip ->
            val files = linkedMapOf<String, ZipEntry>()
            val all = zip.entries()
            var uncompressedTotal = 0L
            while (all.hasMoreElements()) {
                val entry = all.nextElement()
                val rawName = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                val name = normalize(rawName) ?: return "unsafe ZIP path: ${entry.name}"
                if (entry.isDirectory) continue
                if (entry.size < 0L || entry.size > MAX_ENTRY_BYTES) return "invalid or oversized ZIP entry: $name"
                uncompressedTotal += entry.size
                if (uncompressedTotal > MAX_UNCOMPRESSED_BYTES) return "module ZIP expands beyond the safety limit"
                if (files.put(name, entry) != null) return "duplicate ZIP path: $name"
            }

            val manifest = files["SHA256SUMS"] ?: return "SHA256SUMS is missing"
            val required = listOf(
                "module.prop", "customize.sh", "service.sh",
                "bin/arm64-v8a/zkfctl", "bin/arm64-v8a/zperfd",
                "bin/x86_64/zkfctl", "bin/x86_64/zperfd",
            )
            val missing = required.filterNot(files::containsKey)
            if (missing.isNotEmpty()) return "required module payload missing: ${missing.joinToString()}"
            if (files.keys.none { it.startsWith("lkm/") && it.endsWith("/zkfc.ko") }) {
                return "no zkfc.ko kernel-module payload is present"
            }
            if (targetKmi != null) {
                if (SUPPORTED_KERNEL_KMIS.none { it.id == targetKmi }) {
                    return "unsupported target KMI: $targetKmi"
                }
                if (files.keys.none { it.startsWith("lkm/$targetKmi-") && it.endsWith("/zkfc.ko") }) {
                    return "selected KMI has no zkfc.ko payload: $targetKmi"
                }
            }

            val listed = linkedSetOf<String>()
            zip.getInputStream(manifest).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val match = hashLine.matchEntire(line)
                        ?: throw IllegalArgumentException("malformed SHA256SUMS line")
                    val expected = match.groupValues[1].lowercase()
                    val rawName = match.groupValues[2].removePrefix("*").trim()
                    val name = normalize(rawName)
                        ?: throw IllegalArgumentException("unsafe checksum path: $rawName")
                    if (name == "SHA256SUMS") throw IllegalArgumentException("checksum manifest must not hash itself")
                    if (!listed.add(name)) throw IllegalArgumentException("duplicate checksum entry: $name")
                    val entry = files[name] ?: throw IllegalArgumentException("manifest entry missing from ZIP: $name")
                    val actual = zip.getInputStream(entry).use(::sha256)
                    if (!constantTimeEquals(expected, actual)) {
                        throw IllegalArgumentException("SHA-256 mismatch: $name")
                    }
                }
            }
            // Root managers consume these two installer entry points before creating MODPATH.
            // They are archive-level metadata, not module payload, and are intentionally
            // excluded from SHA256SUMS. Keep the allowlist exact: arbitrary META-INF
            // files must still be rejected.
            val allowedInstallerMetadata = setOf(
                "META-INF/com/google/android/update-binary",
                "META-INF/com/google/android/updater-script",
            )
            val installerMetadata = files.keys.filter {
                it == "META-INF" || it.startsWith("META-INF/")
            }.toSet()
            if (installerMetadata != allowedInstallerMetadata) {
                val unexpected = (installerMetadata - allowedInstallerMetadata).take(8)
                val missingMetadata = (allowedInstallerMetadata - installerMetadata).take(8)
                return buildString {
                    append("archive-level installer metadata mismatch")
                    if (unexpected.isNotEmpty()) append("; unexpected: ").append(unexpected.joinToString())
                    if (missingMetadata.isNotEmpty()) append("; missing: ").append(missingMetadata.joinToString())
                }
            }

            val payloadFiles = files.keys - "SHA256SUMS" - allowedInstallerMetadata
            if (listed != payloadFiles) {
                val omitted = (payloadFiles - listed).take(8)
                val extra = (listed - payloadFiles).take(8)
                return buildString {
                    append("SHA256SUMS does not cover the exact ZIP file set")
                    if (omitted.isNotEmpty()) append("; unlisted: ").append(omitted.joinToString())
                    if (extra.isNotEmpty()) append("; missing: ").append(extra.joinToString())
                }
            }
            null
        }
    } catch (e: Exception) {
        e.message ?: "could not verify module ZIP"
    }

    private fun normalize(path: String): String? {
        if (path.isBlank() || path.contains('\\') || path.startsWith('/')) return null
        var value = path
        while (value.startsWith("./")) value = value.removePrefix("./")
        if (value.isBlank() || value.split('/').any { it == ".." || it == "." }) return null
        return value
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toHex()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
        return difference == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

class EmbeddedModuleInstaller(private val context: Context) {
    fun cachedZip(): File = File(context.cacheDir, "updates/Zairenkai-module.zip")

    suspend fun install(targetKmi: String): ModuleInstallOutcome = withContext(Dispatchers.IO) {
        if (SUPPORTED_KERNEL_KMIS.none { it.id == targetKmi }) {
            return@withContext ModuleInstallOutcome(
                ModuleInstallCode.INVALID_ARCHIVE,
                "unsupported target KMI: $targetKmi",
            )
        }
        val expected = BuildConfig.EMBEDDED_MODULE_SHA256.trim().lowercase()
        if (!expected.matches(Regex("[0-9a-f]{64}"))) {
            return@withContext ModuleInstallOutcome(ModuleInstallCode.NOT_BUNDLED)
        }
        if (SignatureGuard.check(context) == SignatureState.MODIFIED) {
            return@withContext ModuleInstallOutcome(ModuleInstallCode.APK_MODIFIED)
        }

        val archive = cachedZip()
        try {
            archive.parentFile?.mkdirs()
            context.assets.open(ASSET_PATH).use { input ->
                BufferedOutputStream(FileOutputStream(archive)).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ARCHIVE_BYTES) throw IllegalArgumentException("embedded module ZIP exceeds 128 MiB")
                        output.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            archive.delete()
            return@withContext ModuleInstallOutcome(
                ModuleInstallCode.NOT_BUNDLED,
                e.message ?: "embedded module asset is unavailable",
            )
        }

        val actual = sha256(archive)
        if (!constantTimeEquals(expected, actual)) {
            return@withContext ModuleInstallOutcome(
                ModuleInstallCode.HASH_MISMATCH,
                "expected=$expected actual=$actual",
            )
        }
        ModuleArchiveVerifier.verify(archive, targetKmi)?.let { problem ->
            return@withContext ModuleInstallOutcome(
                ModuleInstallCode.INVALID_ARCHIVE,
                problem,
            )
        }

        val destination = "/data/adb/.zairenkai/module-${actual.take(16)}.zip"
        val command = """
            set +e
            SRC=${shellQuote(archive.absolutePath)}
            DST=${shellQuote(destination)}
            EXPECTED=${shellQuote(expected)}
            TARGET_KMI=${shellQuote(targetKmi)}
            PENDING_KMI=/data/adb/.zairenkai/target-kmi.pending
            cleanup() { rm -f "${'$'}PENDING_KMI" "${'$'}DST"; }
            trap cleanup EXIT
            case "${'$'}TARGET_KMI" in
              android12-5.10|android13-5.10|android13-5.15|android14-5.15|android14-6.1|android15-6.6|android16-6.12|android17-6.18) ;;
              *) echo "UNSUPPORTED_TARGET_KMI=${'$'}TARGET_KMI"; exit 75 ;;
            esac
            mkdir -p /data/adb/.zairenkai || exit 70
            chmod 0700 /data/adb/.zairenkai || exit 70
            cp -f "${'$'}SRC" "${'$'}DST" || exit 71
            chmod 0600 "${'$'}DST" || { rm -f "${'$'}DST"; exit 72; }
            if command -v sha256sum >/dev/null 2>&1; then
              ACTUAL="${'$'}(sha256sum "${'$'}DST" 2>/dev/null | awk '{print ${'$'}1}')"
            elif command -v toybox >/dev/null 2>&1; then
              ACTUAL="${'$'}(toybox sha256sum "${'$'}DST" 2>/dev/null | awk '{print ${'$'}1}')"
            else
              echo SHA256_TOOL_UNAVAILABLE
              rm -f "${'$'}DST"
              exit 73
            fi
            ACTUAL="${'$'}(echo "${'$'}ACTUAL" | tr 'A-F' 'a-f')"
            [ "${'$'}ACTUAL" = "${'$'}EXPECTED" ] || { echo COPIED_ZIP_HASH_MISMATCH; exit 74; }
            printf '%s\n' "${'$'}TARGET_KMI" > "${'$'}PENDING_KMI" || { echo TARGET_KMI_HANDOFF_WRITE_FAILED; exit 76; }
            chmod 0600 "${'$'}PENDING_KMI" || { echo TARGET_KMI_HANDOFF_PERMISSION_FAILED; exit 76; }
            echo "ZAIRENKAI_TARGET_KMI=${'$'}TARGET_KMI"
            if [ -x /data/adb/ksu/bin/ksud ]; then
              ZAIRENKAI_TARGET_KMI="${'$'}TARGET_KMI" /data/adb/ksu/bin/ksud module install "${'$'}DST"
              RC=${'$'}?
            elif command -v ksud >/dev/null 2>&1; then
              ZAIRENKAI_TARGET_KMI="${'$'}TARGET_KMI" ksud module install "${'$'}DST"
              RC=${'$'}?
            elif command -v magisk >/dev/null 2>&1; then
              ZAIRENKAI_TARGET_KMI="${'$'}TARGET_KMI" magisk --install-module "${'$'}DST"
              RC=${'$'}?
            else
              echo NO_SUPPORTED_ROOT_MODULE_INSTALLER
              RC=86
            fi
            rm -f "${'$'}DST"
            exit "${'$'}RC"
        """.trimIndent()
        val result = RootShell.run(command, 120_000)
        val details = (result.stdout + "\n" + result.stderr).trim().takeLast(2400)
        when {
            result.ok -> ModuleInstallOutcome(ModuleInstallCode.INSTALLED, details, shareableZip = false)
            result.exitCode == 86 -> ModuleInstallOutcome(ModuleInstallCode.MANAGER_UNAVAILABLE, details, shareableZip = true)
            else -> ModuleInstallOutcome(ModuleInstallCode.ROOT_INSTALL_FAILED, "exit=${result.exitCode}: $details", shareableZip = true)
        }
    }

    private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
        return difference == 0
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val ASSET_PATH = "embedded/Zairenkai-module.zip"
        const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
    }
}
