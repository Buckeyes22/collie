package com.lateapex.collie.diagnostics

import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsWriterTest {
    private lateinit var directory: File
    private val cipher = XorCipher()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        directory = File.createTempFile("diagnostics", "dir").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun appendsLinesToOneGrowingActiveFile() {
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 1_000_000L)
        writer.appendLine("""{"a":1}""")
        writer.appendLine("""{"a":2}""")
        val active = writer.activeFile()
        assertEquals(
            listOf("""{"a":1}""", """{"a":2}"""),
            active.readLines(),
        )
        assertTrue(writer.sealedFiles().isEmpty())
    }

    @Test
    fun sealsTheActiveFileOnceItCrossesTheSizeCapAndStartsAFreshOne() {
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 50L)
        writer.appendLine("x".repeat(60))
        assertEquals(1, writer.sealedFiles().size)
        assertEquals(0L, writer.activeFile().length())
    }

    @Test
    fun sealedFileContentsRoundTripThroughDecrypt() {
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 10L)
        writer.appendLine("""{"a":"hello"}""")
        val sealed = writer.sealedFiles().single()
        assertEquals("""{"a":"hello"}""" + "\n", writer.decrypt(sealed))
        // the sealed FILE on disk is the encrypted envelope, not the plaintext line — the point
        // of encrypting on seal at all is exactly that this must never appear in it.
        assertTrue(!sealed.readText().contains("hello"))
    }

    @Test
    fun keepsSealedFilesWithinTheByteBudgetOldestDroppedFirst() {
        // maxActiveBytes = 1 means every appendLine seals at once: one call, one sealed file.
        val budget = 600L
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 1L, maxSealedBytes = budget)
        repeat(20) { index -> writer.appendLine("v$index") }

        val sealed = writer.sealedFiles()
        assertTrue(sealed.size in 1 until 20)
        assertTrue(sealed.sumOf { it.length() } <= budget)
        val remaining = sealed.map { writer.decrypt(it) }
        assertTrue(remaining.none { it == "v0\n" })
        assertEquals("v19\n", remaining.last())
    }

    @Test
    fun sealedFilesAreCompressedSoRepetitiveTraceTextTakesFarLessDisk() {
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 10_000_000L)
        val poll = """{"category":"network","responseBody":"${"⏺ agent output line ".repeat(200)}"}"""
        repeat(40) { writer.appendLine(poll) }
        writer.seal()

        val sealed = writer.sealedFiles().single()
        val raw = (poll.length + 1) * 40
        assertTrue("sealed ${sealed.length()} bytes vs $raw raw", sealed.length() * 5 < raw)
        assertEquals(raw, writer.decrypt(sealed).length)
    }

    private class XorCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())

        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
