package com.lateapex.collie.diagnostics

import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse

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

    @Test
    fun aSealedFileWhoseKeyIsGoneIsNamedInTheExportInsteadOfFailingIt() {
        val lostKey = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = cipher.encrypt(plaintext)
            override fun decrypt(envelope: CipherEnvelope): ByteArray =
                throw javax.crypto.AEADBadTagException("key permanently invalidated")
        }
        val writer = DiagnosticsWriter(traceDir, lostKey, json, maxActiveBytes = 1_000_000L)
        writer.appendLine("""{"a":"sealed-one"}""")
        writer.seal()
        writer.appendLine("""{"a":"active-one"}""")

        ZipFile(DiagnosticsExport(writer, exportDir).buildZip()).use { file ->
            val allText = file.entries().asSequence().joinToString("\n") { entry ->
                file.getInputStream(entry).bufferedReader().readText()
            }
            assertTrue(allText.contains("\"unreadable\""))
            assertTrue(allText.contains("active-one"))
        }
    }

    @Test
    fun intentCommandSentinelNeverEntersPlaintextActiveTraceOrExport() {
        val writer = DiagnosticsWriter(traceDir, cipher, json, maxActiveBytes = 1_000_000L)
        val executor = Executors.newSingleThreadExecutor()
        val recorder = DiagnosticsRecorder(writer, enabled = { true }, executor = executor)
        val server = MockWebServer().apply { start() }
        try {
            val sentinel = "B02-ARTIFICIAL-COMMAND-SECRET-77e2"
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"command\":\"$sentinel\"}"))
            val client = OkHttpClient.Builder().addInterceptor(DiagnosticsInterceptor(recorder)).build()
            val request = Request.Builder()
                .url(server.url("/mounted/api/launch"))
                .post("{\"requestId\":\"request-77\",\"command\":\"$sentinel\"}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response -> assertTrue(response.body!!.string().contains(sentinel)) }
            // recordNow is ordered behind the interceptor's async capture on the same executor.
            recorder.recordNow("test_barrier")

            val active = writer.activeFile().readText()
            assertTrue(active.contains("requestBodyRedacted"))
            assertTrue(active.contains("responseBodyRedacted"))
            assertTrue(active.contains("request-77").not())
            assertTrue(active.contains(sentinel).not())
            ZipFile(DiagnosticsExport(writer, exportDir).buildZip()).use { file ->
                val exported = file.entries().asSequence().joinToString("\n") { entry ->
                    file.getInputStream(entry).bufferedReader().readText()
                }
                assertTrue(exported.contains("requestBodyRedacted"))
                assertTrue(exported.contains(sentinel).not())
            }
        } finally {
            server.shutdown()
            executor.shutdownNow()
        }
    }

    private class XorCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    @Test
    fun paneLayoutSecretNeverEntersActiveDiagnosticsOrExportedTrace() {
        val writer = DiagnosticsWriter(traceDir, cipher, json, maxActiveBytes = 1_000_000L)
        val executor = Executors.newSingleThreadExecutor()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
            val recorder = DiagnosticsRecorder(writer, { true }, executor = executor)
            val client = OkHttpClient.Builder().addInterceptor(DiagnosticsInterceptor(recorder)).build()
            val sentinel = "B12_LAYOUT_SECRET_SENTINEL"
            val request = Request.Builder()
                .url(server.url("/proxy/api/pane/pane-1/layout/split"))
                .post("""{"requestId":"intent-1","planToken":"plan-1","action":{"kind":"split","env":{"TOKEN":"$sentinel"}}}""".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { it.body?.string() }
            recorder.recordNow("test-barrier")

            val active = writer.activeFile().readText()
            assertTrue(active.contains("requestBodyRedacted"))
            assertFalse(active.contains(sentinel))
            ZipFile(DiagnosticsExport(writer, exportDir).buildZip()).use { file ->
                val exported = file.entries().asSequence().joinToString("\n") { entry ->
                    file.getInputStream(entry).bufferedReader().readText()
                }
                assertTrue(exported.contains("requestBodyRedacted"))
                assertFalse(exported.contains(sentinel))
            }
        } finally {
            server.shutdown()
            executor.shutdownNow()
        }
    }
}
