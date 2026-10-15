package com.lateapex.collie.ui

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OperatorWoff2BoundsTest {
    private fun base128(value: Long): ByteArray {
        var remaining = value
        val bytes = mutableListOf((remaining and 127L).toByte())
        remaining = remaining ushr 7
        while (remaining > 0) { bytes.add(0, ((remaining and 127L) or 128L).toByte()); remaining = remaining ushr 7 }
        return bytes.toByteArray()
    }
    private fun entry(flags: Int, original: Long, transformed: Long? = null, tag: String? = null): ByteArray =
        byteArrayOf(flags.toByte()) + (tag?.toByteArray(Charsets.US_ASCII) ?: byteArrayOf()) + base128(original) + (transformed?.let(::base128) ?: byteArrayOf())
    private fun font(entries: List<ByteArray>, declared: Long, compressed: Int = 1): ByteArray {
        val directory = entries.fold(byteArrayOf()) { bytes, next -> bytes + next }
        val bytes = ByteArray(48 + directory.size + 1)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 0x774f4632); putInt(4, 0x00010000); putInt(8, bytes.size)
            putShort(12, entries.size.toShort()); putInt(16, declared.toInt()); putInt(20, compressed)
        }
        directory.copyInto(bytes, 48)
        return bytes
    }
    private fun refused(bytes: ByteArray) { assertThrows(IllegalArgumentException::class.java) { OperatorWoff2Bounds.validate(bytes) } }

    @Test fun actualBundledOperatorFontHasBoundedOriginalAndTransformedDirectories() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "android/app/src/androidTest/assets/fonts/operator-space.woff2") }.first { it.isFile }
        assertEquals(67_420L, OperatorWoff2Bounds.validate(root.readBytes()))
    }

    @Test fun originalTablesAreAlignedAndExplicitTagsAreParsed() {
        assertEquals(52L, OperatorWoff2Bounds.validate(font(listOf(entry(0, 1), entry(63, 2, tag = "TEST")), 52)))
        // Reserved header bits do not change admission: the specification prohibits this refusal.
        val reserved = font(listOf(entry(0, 1)), 32)
        reserved[15] = 1
        assertEquals(32L, OperatorWoff2Bounds.validate(reserved))
    }

    @Test fun tinyHeaderCannotHideHugeOriginalOrTransformedTableLengths() {
        refused(font(listOf(entry(0, 16L * 1024 * 1024)), 32))
        refused(font(listOf(entry(10, 4, 16L * 1024 * 1024 + 1), entry(11, 4, 0)), 52))
        refused(font(listOf(entry(10, 4, 16L * 1024 * 1024), entry(11, 4, 0), entry(0, 1)), 72))
        refused(font(listOf(entry(0, 4)), 36)) // Correct directory estimate is 32, not 36.
    }

    @Test fun metricAndGlyphNullTransformsHaveDifferentVersionRules() {
        assertEquals(72L, OperatorWoff2Bounds.validate(font(listOf(entry(10, 4, 2), entry(11, 4, 0), entry(67, 4, 1)), 72)))
        assertEquals(72L, OperatorWoff2Bounds.validate(font(listOf(entry(202, 4), entry(203, 4), entry(3, 4)), 72)))
        assertEquals(52L, OperatorWoff2Bounds.validate(font(listOf(entry(63, 4, 2, "glyf"), entry(63, 4, 0, "loca")), 52)))
    }

    @Test fun unknownTransformsMissingPairsAndNonzeroTransformedLocaAreRefused() {
        refused(font(listOf(entry(74, 4, 2), entry(75, 4, 0)), 52))
        refused(font(listOf(entry(131, 4, 2)), 32))
        refused(font(listOf(entry(64, 4)), 32))
        refused(font(listOf(entry(10, 4, 2), entry(203, 4)), 52))
        refused(font(listOf(entry(10, 4, 2), entry(11, 4, 1)), 52))
        refused(font(listOf(entry(67, 4, 1)), 32))
        refused(font(listOf(entry(0, 1), entry(63, 1, tag = "cmap")), 52))
    }

    @Test fun malformedAndTruncatedBase128NeverReachNativeDecoding() {
        listOf(
            byteArrayOf(0, 0x80.toByte(), 1), // Non-minimal leading zero.
            byteArrayOf(0, 0x81.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0),
            byteArrayOf(0, 0x90.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0), // 2^32 overflow.
        ).forEach { refused(font(listOf(it), 32)) }
        val truncated = font(listOf(entry(0, 4)), 32).copyOf(49)
        ByteBuffer.wrap(truncated).order(ByteOrder.BIG_ENDIAN).putInt(8, truncated.size)
        refused(truncated)
        val tagTruncated = font(listOf(byteArrayOf(63, 'A'.code.toByte(), 'B'.code.toByte())), 32)
        refused(tagTruncated)
    }

    @Test fun truncatedCompressedPayloadAndHeaderLimitsAreRefused() {
        refused(font(listOf(entry(0, 4)), 32, compressed = 2))
        refused(font(listOf(entry(0, 4)), 32).also { it[0] = 0 })
        refused(font(listOf(entry(0, 4)), 32).also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(8, it.size - 1) })
        refused(font(listOf(entry(0, 4)), 32).also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(4, 0x74746366) })
        refused(font(emptyList(), 12))
        refused(font(listOf(entry(0, 4)), 32).also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putShort(12, 257.toShort()) })
        refused(ByteArray(2 * 1024 * 1024 + 1))
    }
}
