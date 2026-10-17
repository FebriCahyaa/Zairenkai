// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.data

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleArchiveVerifierTest {
    @Test
    fun acceptsCompleteArchiveWithMatchingManifest() {
        val archive = createArchive()
        try {
            assertNull(ModuleArchiveVerifier.verify(archive))
        } finally {
            archive.delete()
        }
    }

    @Test
    fun rejectsPayloadChangedWithoutUpdatingManifest() {
        val archive = createArchive(tamperPayloadAfterManifest = true)
        try {
            assertTrue(ModuleArchiveVerifier.verify(archive)?.contains("SHA-256 mismatch") == true)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun rejectsUnlistedPayloadFiles() {
        val archive = createArchive(addUnlistedFile = true)
        try {
            assertNotNull(ModuleArchiveVerifier.verify(archive))
        } finally {
            archive.delete()
        }
    }

    @Test
    fun rejectsUnexpectedInstallerMetadata() {
        val archive = createArchive(addUnexpectedInstallerMetadata = true)
        try {
            assertTrue(
                ModuleArchiveVerifier.verify(archive)
                    ?.contains("archive-level installer metadata mismatch") == true,
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun rejectsPathTraversalEntries() {
        val archive = createArchive(addUnsafePath = true)
        try {
            assertTrue(ModuleArchiveVerifier.verify(archive)?.contains("unsafe ZIP path") == true)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun rejectsPathTraversalDirectoryEntries() {
        val archive = createArchive(addUnsafeDirectory = true)
        try {
            assertTrue(ModuleArchiveVerifier.verify(archive)?.contains("unsafe ZIP path") == true)
        } finally {
            archive.delete()
        }
    }

    private fun createArchive(
        tamperPayloadAfterManifest: Boolean = false,
        addUnlistedFile: Boolean = false,
        addUnsafePath: Boolean = false,
        addUnsafeDirectory: Boolean = false,
        addUnexpectedInstallerMetadata: Boolean = false,
    ): File {
        val payload = linkedMapOf(
            "./module.prop" to "id=zairenkai\n".toByteArray(),
            "./customize.sh" to "#!/system/bin/sh\n".toByteArray(),
            "./service.sh" to "#!/system/bin/sh\n".toByteArray(),
            "./bin/arm64-v8a/zkfctl" to "zkfctl-arm64".toByteArray(),
            "./bin/arm64-v8a/zperfd" to "zperfd-arm64".toByteArray(),
            "./bin/x86_64/zkfctl" to "zkfctl-x64".toByteArray(),
            "./bin/x86_64/zperfd" to "zperfd-x64".toByteArray(),
            "./lkm/android13-5.10-arm64/zkfc.ko" to "kernel-module".toByteArray(),
        )
        val manifest = payload.map { (name, bytes) -> "${sha256(bytes)}  $name" }.joinToString("\n", postfix = "\n")
            .toByteArray()
        val files = linkedMapOf<String, ByteArray>().apply {
            putAll(payload)
            put("./SHA256SUMS", manifest)
            // Installer metadata is present in the ZIP but deliberately outside SHA256SUMS.
            put("./META-INF/com/google/android/update-binary", "#!/sbin/sh\\n".toByteArray())
            put("./META-INF/com/google/android/updater-script", "#MAGISK\\n".toByteArray())
            if (tamperPayloadAfterManifest) put("./service.sh", "tampered after manifest".toByteArray())
            if (addUnlistedFile) put("./extra.bin", "not in manifest".toByteArray())
            if (addUnsafePath) put("../outside.bin", "path traversal".toByteArray())
            if (addUnexpectedInstallerMetadata) {
                put("./META-INF/com/example/unexpected", "unexpected metadata".toByteArray())
            }
        }
        val file = File.createTempFile("zairenkai-module-test", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            if (addUnsafeDirectory) {
                zip.putNextEntry(ZipEntry("../escape/"))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
