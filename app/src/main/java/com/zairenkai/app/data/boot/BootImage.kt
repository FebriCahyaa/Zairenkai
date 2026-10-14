// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Conservative Android boot-image reader and kernel-payload replacer.
 *
 * This is an original implementation based on the public AOSP boot-image
 * layouts (ANDROID! header versions 0..4). It deliberately does not claim to
 * be a full MagiskBoot replacement: compression/CPIO ramdisk editing, AVB
 * signing and partition flashing are out of scope. Unknown layouts, images
 * with trailers/signatures that cannot be regenerated, and any image that
 * fails an exact source round-trip are rejected before output is written.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.boot

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private val MAGIC = "ANDROID!".toByteArray(Charsets.US_ASCII)

/** One boot-image component (kernel, ramdisk, second stage, DTBO, DTB, signature). */
data class BootSection(val name: String, val data: ByteArray)

class BootImage internal constructor(
    val headerVersion: Int,
    val pageSize: Int,
    val osVersion: Long,
    val cmdline: String,
    /** Header plus its page padding, preserved exactly as parsed. */
    internal val header: ByteArray,
    val sections: List<BootSection>,
    /** Bytes after the last page-aligned component, including possible AVB/footer data. */
    internal val tail: ByteArray,
    /** Original input bytes; never exposed to callers. */
    private val originalImage: ByteArray,
) {
    val kernel: ByteArray? get() = sections.firstOrNull { it.name == "kernel" }?.data
    val ramdisk: ByteArray? get() = sections.firstOrNull { it.name == "ramdisk" }?.data
    val hasBootSignature: Boolean get() = headerVersion >= 4 && (sections.firstOrNull { it.name == "signature" }?.data?.isNotEmpty() == true)
    val hasTrailer: Boolean get() = tail.isNotEmpty()

    /** Rebuild the image. Original inter-component padding is canonical zero padding. */
    fun repack(): ByteArray {
        val out = ByteArrayOutputStream(originalImage.size)
        out.write(header)
        for (section in sections) writePadded(out, section.data)
        out.write(tail)
        return out.toByteArray()
    }

    /** True only if this codec reconstructs the source byte-for-byte. */
    fun roundTrips(original: ByteArray): Boolean = repack().contentEquals(original)

    /**
     * Replace only the kernel payload and rebuild the enclosing boot-image container.
     *
     * The ramdisk and all other components are retained unchanged. Since this code
     * cannot regenerate AVB or v4 boot signatures, those inputs are refused. The
     * caller must also verify the returned image before saving it.
     */
    fun replaceKernel(kernelImage: ByteArray): ByteArray {
        require(kernelImage.isNotEmpty()) { "Kernel payload is empty" }
        require(kernelImage.size <= MAX_COMPONENT_BYTES) { "Kernel payload exceeds the 256 MiB safety limit" }
        require(kernel?.isNotEmpty() == true) { "Boot image does not contain a replaceable kernel" }
        require(roundTrips(originalImage)) {
            "Source image cannot be rebuilt byte-for-byte; refusing to patch an unsupported layout"
        }
        require(!hasTrailer) {
            "Image contains trailing footer/metadata. AVB/footer data cannot be safely regenerated"
        }
        require(!hasBootSignature) {
            "Boot image v4 contains a boot signature. Replace/signing it with a trusted key is not supported"
        }

        val patchedHeader = header.copyOf()
        val headerBuffer = ByteBuffer.wrap(patchedHeader).order(ByteOrder.LITTLE_ENDIAN)
        headerBuffer.putInt(8, kernelImage.size)

        val patchedSections = sections.map { section ->
            if (section.name == "kernel") BootSection(section.name, kernelImage.copyOf()) else section
        }

        // Legacy boot-image IDs are SHA-1 over each present component followed by
        // its 32-bit little-endian size. AOSP v0 includes kernel/ramdisk/second;
        // v1 adds recovery_dtbo; v2 adds dtb.
        if (headerVersion <= 2) {
            if (headerVersion >= 1) {
                val recovery = patchedSections.firstOrNull { it.name == "recovery_dtbo" }?.data ?: ByteArray(0)
                if (recovery.isNotEmpty()) {
                    val second = patchedSections.firstOrNull { it.name == "second" }?.data ?: ByteArray(0)
                    val ramdiskData = patchedSections.firstOrNull { it.name == "ramdisk" }?.data ?: ByteArray(0)
                    var recoveryOffset = header.size.toLong()
                    recoveryOffset += alignedSize(kernelImage.size, pageSize).toLong()
                    recoveryOffset += alignedSize(ramdiskData.size, pageSize).toLong()
                    recoveryOffset += alignedSize(second.size, pageSize).toLong()
                    headerBuffer.putLong(1636, recoveryOffset)
                } else {
                    // A zero-size recovery_dtbo must advertise offset 0 in the AOSP layout.
                    headerBuffer.putLong(1636, 0L)
                }
            }
            updateLegacyId(patchedHeader, patchedSections, headerVersion)
        }

        val output = ByteArrayOutputStream(estimateSize(patchedSections))
        output.write(patchedHeader)
        for (section in patchedSections) writePadded(output, section.data)
        val bytes = output.toByteArray()

        val reparsed = parse(bytes)
            ?: throw IllegalStateException("Patched output failed Android boot-image parsing")
        require(reparsed.roundTrips(bytes)) {
            "Patched output did not pass byte-for-byte reconstruction verification"
        }
        require(reparsed.kernel?.contentEquals(kernelImage) == true) {
            "Patched output kernel payload does not match the selected kernel"
        }
        require(reparsed.headerVersion == headerVersion) { "Patched output header version changed unexpectedly" }
        return bytes
    }

    private fun writePadded(out: ByteArrayOutputStream, data: ByteArray) {
        if (data.isEmpty()) return
        out.write(data)
        val rem = data.size % pageSize
        if (rem != 0) out.write(ByteArray(pageSize - rem))
    }

    private fun alignedSize(size: Int, page: Int): Int {
        if (size == 0) return 0
        return ((size.toLong() + page - 1) / page * page).toInt()
    }

    private fun estimateSize(patchedSections: List<BootSection>): Int {
        var total = header.size.toLong()
        for (section in patchedSections) total += alignedSize(section.data.size, pageSize).toLong()
        require(total <= Int.MAX_VALUE) { "Patched image is too large" }
        return total.toInt()
    }

    private fun updateLegacyId(headerBytes: ByteArray, patchedSections: List<BootSection>, version: Int) {
        val digest = MessageDigest.getInstance("SHA-1")
        val names = buildList {
            add("kernel")
            add("ramdisk")
            add("second")
            if (version >= 1) add("recovery_dtbo")
            if (version >= 2) add("dtb")
        }
        for (name in names) {
            val bytes = patchedSections.firstOrNull { it.name == name }?.data ?: ByteArray(0)
            digest.update(bytes)
            digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
        }
        // The legacy ID field is 32 bytes: 20-byte SHA-1 digest + 12 reserved bytes.
        val field = ByteArray(32)
        digest.digest().copyInto(field)
        field.copyInto(headerBytes, LEGACY_ID_OFFSET)
    }

    companion object {
        const val MAX_COMPONENT_BYTES = 256 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 512 * 1024 * 1024
        private const val LEGACY_ID_OFFSET = 576

        /** Parse an image; null means non-ANDROID! or a layout this codec cannot model. */
        fun parse(bytes: ByteArray): BootImage? {
            if (bytes.size < 64 || bytes.size > MAX_IMAGE_BYTES || !bytes.copyOfRange(0, 8).contentEquals(MAGIC)) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = runCatching { b.getInt(40) }.getOrNull() ?: return null
            return when (version) {
                0, 1, 2 -> parseV0toV2(bytes, b, version)
                3, 4 -> parseV3toV4(bytes, b, version)
                else -> null
            }
        }

        private fun u32(b: ByteBuffer, off: Int): Long = (b.getInt(off).toLong() and 0xFFFFFFFFL)

        private fun parseV0toV2(bytes: ByteArray, b: ByteBuffer, version: Int): BootImage? {
            val kernelSize = u32(b, 8)
            val ramdiskSize = u32(b, 16)
            val secondSize = u32(b, 24)
            val pageSize = u32(b, 36).toInt()
            if (pageSize !in 256..65536 || Integer.bitCount(pageSize) != 1) return null
            val recoveryDtboSize = if (version >= 1) u32(b, 1632) else 0
            val dtbSize = if (version >= 2) u32(b, 1648) else 0
            val headerSize = when (version) { 0 -> 1632L; 1 -> 1648L; else -> 1660L }
            if (version >= 1 && u32(b, 1644) != headerSize) return null
            if (version >= 2 && bytes.size < 1660) return null

            val cmdline = readStr(bytes, 64, 512)
            val sizes = listOf(
                "kernel" to kernelSize,
                "ramdisk" to ramdiskSize,
                "second" to secondSize,
                "recovery_dtbo" to recoveryDtboSize,
                "dtb" to dtbSize,
            )
            return assemble(bytes, version, pageSize, u32(b, 44), cmdline, headerSize.toInt(), sizes)
        }

        private fun parseV3toV4(bytes: ByteArray, b: ByteBuffer, version: Int): BootImage? {
            val pageSize = 4096
            val kernelSize = u32(b, 8)
            val ramdiskSize = u32(b, 12)
            val headerSize = u32(b, 20)
            val signatureSize = if (version >= 4) u32(b, 1580) else 0
            val expected = if (version >= 4) 1584L else 1580L
            if (headerSize != expected || bytes.size < 4096) return null
            if (version >= 4 && bytes.size < 1584) return null
            val cmdline = readStr(bytes, 44, 1536)
            val sizes = listOf(
                "kernel" to kernelSize,
                "ramdisk" to ramdiskSize,
                "signature" to signatureSize,
            )
            return assemble(bytes, version, pageSize, u32(b, 16), cmdline, headerSize.toInt(), sizes)
        }

        /** Carve page-aligned sections and preserve everything after the last known section. */
        private fun assemble(
            bytes: ByteArray,
            version: Int,
            pageSize: Int,
            osVersion: Long,
            cmdline: String,
            headerSize: Int,
            sizes: List<Pair<String, Long>>,
        ): BootImage? {
            if (sizes.any { it.second < 0 || it.second > MAX_COMPONENT_BYTES }) return null
            val headerPadded = align(headerSize, pageSize) ?: return null
            if (headerPadded > bytes.size) return null
            val header = bytes.copyOfRange(0, headerPadded)
            var off = headerPadded
            val sections = ArrayList<BootSection>(sizes.size)
            for ((name, size) in sizes) {
                if (size == 0L) {
                    sections.add(BootSection(name, ByteArray(0)))
                    continue
                }
                val endLong = off.toLong() + size
                if (endLong > bytes.size || endLong > Int.MAX_VALUE) return null
                val end = endLong.toInt()
                sections.add(BootSection(name, bytes.copyOfRange(off, end)))
                off = align(end, pageSize) ?: return null
            }
            if (off > bytes.size) return null
            val tail = bytes.copyOfRange(off, bytes.size)
            return BootImage(version, pageSize, osVersion, cmdline, header, sections, tail, bytes)
        }

        private fun readStr(bytes: ByteArray, off: Int, len: Int): String {
            if (off !in 0..bytes.size) return ""
            val end = minOf(off + len, bytes.size)
            val slice = bytes.copyOfRange(off, end)
            val nul = slice.indexOf(0.toByte()).let { if (it < 0) slice.size else it }
            return String(slice, 0, nul, Charsets.US_ASCII)
        }

        private fun align(value: Int, page: Int): Int? {
            val aligned = ((value.toLong() + page - 1) / page) * page
            return aligned.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }
    }
}
