// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * The boot-image codec is pure, so it is tested directly against synthetic
 * images built to the AOSP layout: a round-trip must reproduce the bytes
 * exactly, and anything malformed or unmodelled must be refused (null).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.boot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BootImageTest {
    private fun le(size: Int, build: (ByteBuffer) -> Unit): ByteArray {
        val a = ByteArray(size)
        build(ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN))
        return a
    }

    private fun padTo(data: ByteArray, page: Int): ByteArray {
        val rem = data.size % page
        if (rem == 0) return data
        return data + ByteArray(page - rem)
    }

    /** Build a v0/v1/v2 image with the given sections. */
    private fun legacyImage(version: Int, page: Int, kernel: ByteArray, ramdisk: ByteArray, second: ByteArray = ByteArray(0), tail: ByteArray = ByteArray(0)): ByteArray {
        val headerSize = when (version) { 0 -> 1632; 1 -> 1648; else -> 1660 }
        val header = le(headerSize) { b ->
            System.arraycopy("ANDROID!".toByteArray(), 0, b.array(), 0, 8)
            b.putInt(8, kernel.size); b.putInt(16, ramdisk.size); b.putInt(24, second.size)
            b.putInt(36, page); b.putInt(40, version); b.putInt(44, 0x10000)  // os_version
            "cmd=test".toByteArray().copyInto(b.array(), 64)
            if (version >= 1) b.putInt(1644, headerSize)
        }
        val out = ByteArrayOutputStream()
        out.write(padTo(header, page))
        out.write(padTo(kernel, page))
        out.write(padTo(ramdisk, page))
        if (version >= 1) {} // recovery_dtbo empty
        if (second.isNotEmpty()) out.write(padTo(second, page))
        out.write(tail)
        return out.toByteArray()
    }

    /** Build a v3/v4 image (fixed 4096 page). */
    private fun modernImage(version: Int, kernel: ByteArray, ramdisk: ByteArray, tail: ByteArray = ByteArray(0)): ByteArray {
        val headerSize = if (version >= 4) 1584 else 1580
        val header = le(headerSize) { b ->
            System.arraycopy("ANDROID!".toByteArray(), 0, b.array(), 0, 8)
            b.putInt(8, kernel.size); b.putInt(12, ramdisk.size); b.putInt(16, 0x10000)
            b.putInt(20, headerSize); b.putInt(40, version)
            "cmd=modern".toByteArray().copyInto(b.array(), 44)
        }
        val out = ByteArrayOutputStream()
        out.write(padTo(header, 4096))
        out.write(padTo(kernel, 4096))
        out.write(padTo(ramdisk, 4096))
        out.write(tail)
        return out.toByteArray()
    }

    @Test fun roundTripV2() {
        val img = legacyImage(2, 2048, "KERNELDATA".toByteArray(), ByteArray(5000) { it.toByte() })
        val boot = BootImage.parse(img)!!
        assertEquals(2, boot.headerVersion)
        assertEquals(2048, boot.pageSize)
        assertEquals("cmd=test", boot.cmdline)
        assertTrue("v2 round-trip must be byte-identical", boot.roundTrips(img))
        assertTrue(boot.kernel!!.contentEquals("KERNELDATA".toByteArray()))
    }

    @Test fun roundTripV0WithTail() {
        // A trailing AVB-like footer after the last section must be preserved.
        val tail = "AVB0".toByteArray() + ByteArray(60)
        val img = legacyImage(0, 4096, ByteArray(100) { 1 }, ByteArray(200) { 2 }, tail = tail)
        val boot = BootImage.parse(img)!!
        assertEquals(0, boot.headerVersion)
        assertTrue(boot.roundTrips(img))
    }

    @Test fun roundTripV3andV4() {
        val v3 = modernImage(3, ByteArray(9000) { it.toByte() }, ByteArray(3000) { (-it).toByte() })
        val b3 = BootImage.parse(v3)!!
        assertEquals(3, b3.headerVersion)
        assertEquals(4096, b3.pageSize)
        assertTrue(b3.roundTrips(v3))

        val v4 = modernImage(4, ByteArray(100) { 7 }, ByteArray(100) { 9 }, tail = ByteArray(16) { 3 })
        val b4 = BootImage.parse(v4)!!
        assertEquals(4, b4.headerVersion)
        assertTrue(b4.roundTrips(v4))
    }

    @Test fun refusesNonBoot() {
        assertNull(BootImage.parse(ByteArray(4096)))
        assertNull(BootImage.parse("not a boot image".toByteArray()))
        assertNull(BootImage.parse(ByteArray(10)))
    }

    @Test fun refusesTruncated() {
        val img = modernImage(3, ByteArray(9000), ByteArray(3000))
        // Chop off the ramdisk: the declared sizes no longer fit the buffer.
        assertNull(BootImage.parse(img.copyOfRange(0, img.size - 4096)))
    }

    @Test fun refusesBadPageSize() {
        // page_size not a power of two -> unmodelled -> refused.
        val img = legacyImage(2, 2048, "K".toByteArray(), "R".toByteArray())
        ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN).putInt(36, 3000)
        assertNull(BootImage.parse(img))
    }

    @Test fun repackOfModifiedPaddingDiffers() {
        // If the original had non-zero padding, a zero-padded repack would differ,
        // so roundTrips would be false and the patch flow would refuse.
        val img = modernImage(3, ByteArray(10) { 1 }, ByteArray(10) { 2 }).copyOf()
        // Kernel data is 10 bytes at offset 4096; the rest of that page is padding.
        img[4096 + 10 + 5] = 0x5A
        val boot = BootImage.parse(img)!!
        assertFalse(boot.roundTrips(img))
    }
}
