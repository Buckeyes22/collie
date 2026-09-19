package com.lateapex.collie.diagnostics

import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsExportTest {
    private lateinit var traceDir: File
    private lateinit var exportDir: File
    private val cipher = XorCipher()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        traceDir = File.createTempFile("trace", "dir").apply { delete(); mkdirs() }
        exportDir = File.createTempFile("export", "dir").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        traceDir.deleteRecursively()
        exportDir.deleteRecursively()
    }

    @Test
    fun zipsTheActiveFileAndEverySealedFileDecryptedToPlaintext() {
        val writer = DiagnosticsWriter(traceDir, cipher, json, maxActiveBytes = 1_000_000L)
        writer.appendLine("""{"a":"sealed-one"}""")
        writer.seal() // force this line into a sealed file regardless of size, deterministically
        writer.appendLine("""{"a":"active-one"}""") // stays in the fresh active file (well under the cap)

        val export = DiagnosticsExport(writer, exportDir)
        val zip = export.buildZip()

        assertTrue(zip.exists())
        ZipFile(zip).use { file ->
            val names = file.entries().asSequence().map { it.name }.toList()
            assertTrue(names.any { it.endsWith(".jsonl") })
            val allText = names.joinToString("\n") { name ->
                file.getInputStream(file.getEntry(name)).bufferedReader().readText()
            }
            assertTrue(allText.contains("sealed-one"))
            assertTrue(allText.contains("active-one"))
        }
    }

    private class XorCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
