// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.data.boot

import java.security.MessageDigest

/** Result from replacing a boot image's kernel payload without touching its ramdisk. */
data class BootImagePatchResult(
    val image: ByteArray,
    val headerVersion: Int,
    val pageSize: Int,
    val originalKernelSize: Int,
    val patchedKernelSize: Int,
    val sha256: String,
)

/** Guarded patch entry point shared by the UI and tests. */
object BootImagePatcher {
    fun replaceKernel(originalImage: ByteArray, kernelPayload: ByteArray): BootImagePatchResult {
        val boot = BootImage.parse(originalImage)
            ?: throw IllegalArgumentException("Not a supported Android ANDROID! boot image (versions 0–4 only)")
        if (!boot.roundTrips(originalImage)) {
            throw IllegalArgumentException("This image uses padding or layout data Zairenkai cannot preserve exactly")
        }
        val oldSize = boot.kernel?.size
            ?: throw IllegalArgumentException("The selected image does not contain a kernel payload")
        val patched = try {
            boot.replaceKernel(kernelPayload)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException(error.message ?: "Kernel image patch failed", error)
        }

        val verified = BootImage.parse(patched)
            ?: throw IllegalStateException("Internal verification failed: output is not a valid boot image")
        if (!verified.roundTrips(patched) || verified.kernel?.contentEquals(kernelPayload) != true) {
            throw IllegalStateException("Internal verification failed: output does not match the requested kernel")
        }
        return BootImagePatchResult(
            image = patched,
            headerVersion = verified.headerVersion,
            pageSize = verified.pageSize,
            originalKernelSize = oldSize,
            patchedKernelSize = kernelPayload.size,
            sha256 = sha256(patched),
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
