// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * A reader/writer for the Android boot image container (the "ANDROID!" format,
 * header versions 0..4). This is an original implementation of the public AOSP
 * boot-image layout — it is not derived from any third-party boot tool.
 *
 * Safety by construction: [parse] returns null for anything it does not fully
 * understand, and [BootImage.repack] must reproduce the source bytes exactly
 * ([roundTrips]). The patch flow refuses to proceed unless the round-trip is
 * byte-identical, so the app never hands the user an image it could not have
 * rebuilt from the original. Flashing a repacked image is the user's own step.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.boot

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val MAGIC = "ANDROID!".toByteArray(Charsets.US_ASCII)

/** One section of the image (kernel, ramdisk, ...) with its page-padded extent. */
data class BootSection(val name: String, val data: ByteArray)

class BootImage internal constructor(
    val headerVersion: Int,
    val pageSize: Int,
    val osVersion: Long,          // packed os version + patch level (header field)
    val cmdline: String,
    /** The raw header bytes exactly as read, so repack reproduces them verbatim. */
    internal val header: ByteArray,
    val sections: List<BootSection>,
    /** Anything after the last padded section (e.g. an AVB footer); preserved as-is. */
    internal val tail: ByteArray,
) {
    val kernel: ByteArray? get() = sections.firstOrNull { it.name == "kernel" }?.data
    val ramdisk: ByteArray? get() = sections.firstOrNull { it.name == "ramdisk" }?.data

    /** Rebuild the image: header, then each section zero-padded to the page size, then the tail. */
    fun repack(): ByteArray {
        val out = ByteArrayOutputStream()
        writePadded(out, header)
        for (s in sections) if (s.data.isNotEmpty()) writePadded(out, s.data)
        out.write(tail)
        return out.toByteArray()
    }

    /** True when repacking reproduces [original] byte-for-byte. The patch gate. */
    fun roundTrips(original: ByteArray): Boolean = repack().contentEquals(original)

    private fun writePadded(out: ByteArrayOutputStream, data: ByteArray) {
        out.write(data)
        val rem = data.size % pageSize
        if (rem != 0) out.write(ByteArray(pageSize - rem))
    }

    companion object {
        private const val MAX = 256 * 1024 * 1024  // sane cap: 256 MiB

        /** Parse an image; null if it is not an ANDROID! image or uses a layout we do not fully model. */
        fun parse(bytes: ByteArray): BootImage? {
            if (bytes.size < 64 || !bytes.copyOfRange(0, 8).contentEquals(MAGIC)) return null
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

            val cmdline = readStr(bytes, 64, 512)
            val sizes = listOf(
                "kernel" to kernelSize, "ramdisk" to ramdiskSize, "second" to secondSize,
                "recovery_dtbo" to recoveryDtboSize, "dtb" to dtbSize,
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
            if (headerSize != expected) return null
            val cmdline = readStr(bytes, 44, 1536)
            val sizes = listOf(
                "kernel" to kernelSize, "ramdisk" to ramdiskSize, "signature" to signatureSize,
            )
            return assemble(bytes, version, pageSize, u32(b, 16), cmdline, headerSize.toInt(), sizes)
        }

        /** Carve the page-aligned sections out of [bytes] and keep the header and tail verbatim. */
        private fun assemble(
            bytes: ByteArray, version: Int, pageSize: Int, osVersion: Long, cmdline: String,
            headerSize: Int, sizes: List<Pair<String, Long>>,
        ): BootImage? {
            if (sizes.any { it.second < 0 || it.second > MAX }) return null
            val headerPadded = pad(headerSize, pageSize)
            if (headerPadded > bytes.size) return null
            val header = bytes.copyOfRange(0, headerPadded)
            var off = headerPadded
            val sections = ArrayList<BootSection>()
            for ((name, size) in sizes) {
                if (size == 0L) { sections.add(BootSection(name, ByteArray(0))); continue }
                val end = off + size.toInt()
                if (end > bytes.size) return null
                sections.add(BootSection(name, bytes.copyOfRange(off, end)))
                off = pad(end, pageSize)
            }
            if (off > bytes.size) return null
            val tail = bytes.copyOfRange(off, bytes.size)
            return BootImage(version, pageSize, osVersion, cmdline, header, sections, tail)
        }

        private fun readStr(bytes: ByteArray, off: Int, len: Int): String {
            val end = minOf(off + len, bytes.size)
            val slice = bytes.copyOfRange(off, end)
            val nul = slice.indexOf(0.toByte()).let { if (it < 0) slice.size else it }
            return String(slice, 0, nul, Charsets.US_ASCII)
        }

        private fun pad(n: Int, page: Int): Int = ((n + page - 1) / page) * page
    }
}
