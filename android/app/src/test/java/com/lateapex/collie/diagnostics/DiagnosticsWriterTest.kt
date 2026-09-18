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
    fun keepsOnlyTheNewestFourSealedFilesOldestDroppedFirst() {
        // maxActiveBytes = 1 means any single non-empty line already crosses the cap, so each
        // appendLine call seals immediately — one call, one sealed file, no batching across calls.
        val writer = DiagnosticsWriter(directory, cipher, json, maxActiveBytes = 1L, maxSealedFiles = 4)
        repeat(6) { index ->
            writer.appendLine("v$index")
            Thread.sleep(2) // sealed file names are millisecond timestamps; force distinct names
        }
        val sealed = writer.sealedFiles()
        assertEquals(4, sealed.size)
        // decrypt every remaining sealed file and confirm the two oldest payloads are gone
        val remainingContents = sealed.map { writer.decrypt(it) }
        assertTrue(remainingContents.none { it.contains("v0") })
        assertTrue(remainingContents.none { it.contains("v1") })
        assertTrue(remainingContents.any { it.contains("v5") })
    }

    private class XorCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())

        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
