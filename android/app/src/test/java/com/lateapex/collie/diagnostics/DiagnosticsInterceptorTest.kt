package com.lateapex.collie.diagnostics

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var recorded: MutableList<Pair<String, Map<String, Any?>>>
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        recorded = mutableListOf()
        val recorder = RecordingRecorder(recorded)
        client = OkHttpClient.Builder()
            .addInterceptor(DiagnosticsInterceptor(recorder))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun recordsMethodPathStatusAndAddsATraceIdHeaderTheServerReceives() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
        val request = Request.Builder().url(server.url("/api/health")).build()
        client.newCall(request).execute().use { it.body?.string() }

        val sent = server.takeRequest()
        assertNotNull(sent.getHeader("X-Collie-Trace-Id"))

        val (category, fields) = recorded.single()
        assertEquals("network", category)
        assertEquals("GET", fields["method"])
        assertEquals("/api/health", fields["path"])
        assertEquals(200L, (fields["status"] as Number).toLong())
        assertEquals(sent.getHeader("X-Collie-Trace-Id"), fields["traceId"])
    }

    @Test
    fun neverRecordsTheAuthorizationHeaderValueOnlyThatOneWasPresent() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val request = Request.Builder()
            .url(server.url("/api/devices"))
            .header("Authorization", "Bearer super-secret-token")
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        val (_, fields) = recorded.single()
        assertEquals(true, fields["hadCredential"])
        assertFalse(fields.values.any { it.toString().contains("super-secret-token") })
    }

    @Test
    fun capturesJsonRequestAndResponseBodiesInFull() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"label\":\"phone\"}"))
        val request = Request.Builder()
            .url(server.url("/api/pair"))
            .header("Content-Type", "application/json")
            .post("{\"code\":\"ABC123\"}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        val (_, fields) = recorded.single()
        assertTrue(fields["requestBody"].toString().contains("ABC123"))
        assertTrue(fields["responseBody"].toString().contains("phone"))
    }

    @Test
    fun capturesBinaryBodiesAsSizeAndTypeOnlyNeverRawBytes() {
        val bytes = ByteArray(1024) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(bytes)))
        val request = Request.Builder()
            .url(server.url("/api/pane/x/upload"))
            .header("Content-Type", "image/png")
            .post(bytes.toRequestBody("image/png".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.body?.bytes() }

        val (_, fields) = recorded.single()
        assertEquals(1024L, (fields["requestBodyBytes"] as Number).toLong())
        assertFalse(fields.containsKey("requestBody"))
        assertEquals(1024L, (fields["responseBodyBytes"] as Number).toLong())
        assertFalse(fields.containsKey("responseBody"))
    }

    private class RecordingRecorder(
        private val sink: MutableList<Pair<String, Map<String, Any?>>>,
    ) : DiagnosticsRecorder(FakeAppendable(), { true }) {
        override fun record(category: String, fields: Map<String, Any?>) {
            synchronized(sink) { sink.add(category to fields) }
        }
    }

    private class FakeAppendable : DiagnosticsAppendable {
        override fun appendLine(line: String) = Unit
    }
}
