// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.data.boot

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes the separately licensed GPL boot-image helper bundled in the APK.
 * The helper writes a new file only; this repository never flashes partitions
 * and does not require root merely to create a patched image.
 */
object BootPatchRepository {
    private const val MAX_BOOT_BYTES = 256L * 1024L * 1024L
    private const val MAX_MODULE_BYTES = 64L * 1024L * 1024L

    data class Result(
        val outputFile: File,
        val log: String,
    )

    data class KernelDetection(
        val kmi: String?,
        val kernelRelease: String?,
        val kmiSource: String?,
    )

    suspend fun patch(
        context: Context,
        bootUri: Uri,
        kernelReferenceUri: Uri?,
        kmiReferenceModuleUri: Uri?,
        moduleUri: Uri?,
        kmi: String,
    ): Result = withContext(Dispatchers.IO) {
        val workspace = File(context.cacheDir, "zk-bootpatch-${UUID.randomUUID()}")
        if (!workspace.mkdirs()) throw IOException("Cannot create a private boot-patch workspace")
        val bootFile = File(workspace, "input-ramdisk.img")
        val kernelReferenceFile = File(workspace, "kernel-reference-boot.img")
        val kmiReferenceModuleFile = File(workspace, "kmi-reference-vendor.ko")
        val moduleFile = File(workspace, "zkfc.ko")
        val outputFile = File(workspace, "boot_zairenkai_patched.img")

        try {
            copyUri(context, bootUri, bootFile, MAX_BOOT_BYTES)
            if (kernelReferenceUri != null) {
                copyUri(context, kernelReferenceUri, kernelReferenceFile, MAX_BOOT_BYTES)
            }
            if (kmiReferenceModuleUri != null) {
                copyUri(context, kmiReferenceModuleUri, kmiReferenceModuleFile, MAX_MODULE_BYTES)
            }
            val selectedModuleAsset = if (moduleUri == null) bundledModuleAsset(context, kmi) else null
            if (moduleUri != null) {
                copyUri(context, moduleUri, moduleFile, MAX_MODULE_BYTES)
            } else {
                val asset = selectedModuleAsset ?: throw IOException(
                    "This APK has no bundled ZKFC module for $kmi and the current ABI. Select a matching zkfc.ko file.",
                )
                context.assets.open(asset).use { source -> copyStream(source, moduleFile, MAX_MODULE_BYTES) }
            }

            val helper = File(context.applicationInfo.nativeLibraryDir, "libzkbootpatch.so")
            if (!helper.isFile || !helper.canRead()) {
                throw IOException(
                    "Native boot-patch helper is missing. Build it with tools/bootpatch/build-android.sh " +
                        "and install an APK that includes libzkbootpatch.so.",
                )
            }

            val arguments = mutableListOf(
                "--boot", bootFile.absolutePath,
            )
            if (kernelReferenceUri != null) {
                arguments += listOf("--kernel-reference", kernelReferenceFile.absolutePath)
            }
            if (kmiReferenceModuleUri != null) {
                arguments += listOf("--kmi-reference-module", kmiReferenceModuleFile.absolutePath)
            }
            arguments += listOf(
                "--module", moduleFile.absolutePath,
                "--kmi", kmi,
                "--out", outputFile.absolutePath,
            )
            val log = runHelper(helper, arguments)
            if (!outputFile.isFile || outputFile.length() == 0L) {
                throw IOException("The patch helper reported success but no output image was created.")
            }
            Result(outputFile, log.trim())
        } catch (error: Throwable) {
            workspace.deleteRecursively()
            throw error
        }
    }

    /** Extracts the Android KMI hint and complete Linux release from the selected image pair. */
    suspend fun detectKernel(
        context: Context,
        bootUri: Uri,
        kernelReferenceUri: Uri? = null,
        kmiReferenceModuleUri: Uri? = null,
    ): KernelDetection = withContext(Dispatchers.IO) {
        val workspace = File(context.cacheDir, "zk-kmi-detect-${UUID.randomUUID()}")
        if (!workspace.mkdirs()) throw IOException("Cannot create a private KMI-detection workspace")
        try {
            val bootFile = File(workspace, "input-ramdisk.img")
            val kernelReferenceFile = File(workspace, "kernel-reference-boot.img")
            val kmiReferenceModuleFile = File(workspace, "kmi-reference-vendor.ko")
            copyUri(context, bootUri, bootFile, MAX_BOOT_BYTES)
            if (kernelReferenceUri != null) {
                copyUri(context, kernelReferenceUri, kernelReferenceFile, MAX_BOOT_BYTES)
            }
            if (kmiReferenceModuleUri != null) {
                copyUri(context, kmiReferenceModuleUri, kmiReferenceModuleFile, MAX_MODULE_BYTES)
            }
            val helper = File(context.applicationInfo.nativeLibraryDir, "libzkbootpatch.so")
            if (!helper.isFile || !helper.canRead()) {
                throw IOException("Native boot-patch helper is missing from this APK")
            }
            val arguments = mutableListOf("--detect-kmi", "--boot", bootFile.absolutePath)
            if (kernelReferenceUri != null) {
                arguments += listOf("--kernel-reference", kernelReferenceFile.absolutePath)
            }
            if (kmiReferenceModuleUri != null) {
                arguments += listOf("--kmi-reference-module", kmiReferenceModuleFile.absolutePath)
            }
            val log = runHelper(helper, arguments)
            val values = log.lineSequence()
                .filter {
                    it.startsWith("DETECTED_KMI=") ||
                        it.startsWith("DETECTED_KERNEL_RELEASE=") ||
                        it.startsWith("DETECTED_KMI_SOURCE=")
                }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val detectedKmi = values["DETECTED_KMI"]?.takeUnless { it == "NONE" }
            val detectedRelease = values["DETECTED_KERNEL_RELEASE"]?.takeUnless { it == "NONE" }
            val detectedSource = values["DETECTED_KMI_SOURCE"]?.takeUnless { it == "none" }
            if (values.isEmpty()) throw IOException("Kernel detection returned no result")
            KernelDetection(detectedKmi, detectedRelease, detectedSource)
        } finally {
            workspace.deleteRecursively()
        }
    }

    suspend fun detectKmi(
        context: Context,
        bootUri: Uri,
        kernelReferenceUri: Uri? = null,
        kmiReferenceModuleUri: Uri? = null,
    ): String? = detectKernel(context, bootUri, kernelReferenceUri, kmiReferenceModuleUri).kmi

    suspend fun deleteOutput(outputFile: File) = withContext(Dispatchers.IO) {
        val workspace = outputFile.parentFile ?: return@withContext
        if (workspace.name.startsWith("zk-bootpatch-")) workspace.deleteRecursively()
    }

    suspend fun saveOutput(context: Context, outputFile: File, target: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val output = resolver.openOutputStream(target, "w")
            ?: throw IOException("Android could not open the selected destination")
        output.use { sink ->
            outputFile.inputStream().use { source -> source.copyTo(sink) }
        }
    }

    suspend fun displayName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            }
        }
        uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
    }

    private fun bundledModuleAsset(context: Context, kmi: String): String? {
        val moduleAbis = android.os.Build.SUPPORTED_ABIS.mapNotNull { abi ->
            when (abi) {
                "arm64-v8a" -> "arm64"
                "x86_64" -> "x86_64"
                else -> null
            }
        }.distinct()
        for (abi in moduleAbis) {
            val asset = "zkfc/$kmi-$abi/zkfc.ko"
            val exists = runCatching { context.assets.open(asset).use { } }.isSuccess
            if (exists) return asset
        }
        return null
    }

    private fun runHelper(helper: File, arguments: List<String>): String {
        if (!helper.isFile || !helper.canRead() || !helper.canExecute()) {
            throw IOException("Native boot-patch helper is missing or not executable in this APK")
        }
        val command = listOf(helper.absolutePath) + arguments
        val process = try {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            val nativeDir = helper.parentFile?.absolutePath
            if (!nativeDir.isNullOrBlank()) {
                val inherited = builder.environment()["LD_LIBRARY_PATH"].orEmpty()
                builder.environment()["LD_LIBRARY_PATH"] = listOfNotNull(
                    nativeDir,
                    inherited.takeIf { it.isNotBlank() },
                ).joinToString(File.pathSeparator)
            }
            builder.start()
        } catch (error: Exception) {
            throw IOException(
                "Could not execute the bundled boot-patch helper. Verify the APK includes the correct native ABI binaries.",
                error,
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException(output.trim().ifEmpty {
                "Boot patch helper failed (exit code $exitCode). Check the selected image, module, and KMI."
            })
        }
        return output.trim()
    }

    private fun copyUri(context: Context, uri: Uri, target: File, maxBytes: Long) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open selected file: $uri")
        input.use { source -> copyStream(source, target, maxBytes) }
    }

    private fun copyStream(source: java.io.InputStream, target: File, maxBytes: Long) {
        FileOutputStream(target).use { sink ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > maxBytes) {
                    throw IOException("Selected file exceeds the safe size limit of ${maxBytes / (1024 * 1024)} MiB")
                }
                sink.write(buffer, 0, count)
            }
            sink.fd.sync()
        }
        if (!target.isFile || target.length() == 0L) {
            throw IOException("Selected file is empty")
        }
    }

}
