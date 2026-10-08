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
        val recorder = RecordingDiagnosticsRecorder(recorded)
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
    fun pairingBodiesNeverEnterDiagnosticsAndRemainReadableByTheCaller() {
        for (status in listOf(200, 400)) {
            val body = "{\"token\":\"private-pairing-token\",\"label\":\"phone\"}"
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            val request = Request.Builder()
                .url(server.url("/api/pair"))
                .post("{\"code\":\"PRIVATE-CODE\"}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { assertEquals(body, it.body?.string()) }

            val (_, fields) = recorded.last()
            assertEquals(status, fields["status"])
            assertFalse(fields.containsKey("requestBody"))
            assertFalse(fields.containsKey("responseBody"))
            assertFalse(fields.containsKey("responsePatch"))
            assertFalse(fields.values.any { it.toString().contains("PRIVATE-CODE") })
            assertFalse(fields.values.any { it.toString().contains("private-pairing-token") })
        }
    }

    @Test
    fun capturesJsonRequestAndResponseBodiesInFull() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"label\":\"phone\"}"))
        val request = Request.Builder()
            .url(server.url("/api/pane/test/send"))
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

    @Test
    fun aPollWhoseAnswerHasNotChangedIsNotRecordedAgain() {
        // S25 Ultra, 2026-09-18: 70% of a 20-minute trace was the same transcript page, re-read
        // every 2 seconds and stored whole each time.
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("{\"entries\":[1]}")) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"entries\":[1,2]}"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"entries\":[1,2]}"))
        repeat(5) {
            client.newCall(Request.Builder().url(server.url("/api/pane/w1/history")).build()).execute().use { it.body?.string() }
        }

        assertEquals(
            listOf("{\"entries\":[1]}", "{\"entries\":[1,2]}", "{\"entries\":[1,2]}"),
            recorded.map { it.second["responseBody"] },
        )
        assertEquals(listOf(200L, 200L, 503L), recorded.map { (it.second["status"] as Number).toLong() })
    }

    @Test
    fun aTranscriptPollRecordsOnlyTheEntriesItHasNotRecordedBefore() {
        fun page(vararg entries: String) =
            """{"paneId":"w1","available":true,"entries":[${entries.joinToString(",")}],"hasMore":true}"""
        val a = """{"uuid":"a","text":"one"}"""
        val b = """{"uuid":"b","text":"two"}"""
        val c = """{"uuid":"c","text":"three"}"""
        val cLater = """{"uuid":"c","text":"three, and its tool result"}"""
        val d = """{"uuid":"d","text":"four"}"""
        listOf(page(a, b), page(a, b), page(a, b, c), page(b, c, d), page(b, cLater, d)).forEach {
            server.enqueue(MockResponse().setResponseCode(200).setBody(it))
        }
        repeat(5) {
            val request = Request.Builder().url(server.url("/api/pane/w1/history?limit=60")).build()
            client.newCall(request).execute().use { response ->
                // What the app reads is untouched: the whole page, whatever the trace keeps.
                assertTrue(response.body!!.string().contains("\"entries\""))
            }
        }

        val bodies = recorded.map { kotlinx.serialization.json.Json.parseToJsonElement(it.second["responseBody"] as String) }
        fun uuids(i: Int) = (bodies[i] as kotlinx.serialization.json.JsonObject)["entries"].toString()
        assertEquals("the unchanged second poll is skipped", 4, recorded.size)
        assertTrue(uuids(0).contains("\"a\"") && uuids(0).contains("\"b\""))
        assertEquals(listOf("c"), Regex("\"uuid\":\"(\\w)\"").findAll(uuids(1)).map { it.groupValues[1] }.toList())
        assertEquals(2, (recorded[1].second["entriesUnchanged"] as Number).toInt())
        assertEquals(listOf("d"), Regex("\"uuid\":\"(\\w)\"").findAll(uuids(2)).map { it.groupValues[1] }.toList())
        assertTrue("a later tool result on an entry is news", uuids(3).contains("its tool result"))
    }

    @Test
    fun aSnapshotThatOnlyMovedItsClocksIsNotRecordedAgain() {
        fun snapshot(ts: Int, seen: Int, status: String) =
            """{"ts":$ts,"agents":[{"paneId":"w1","status":"$status","lastSeenAt":$seen}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(snapshot(1, 1, "working")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(snapshot(2, 2, "working")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(snapshot(3, 3, "blocked")))
        repeat(3) {
            client.newCall(Request.Builder().url(server.url("/api/snapshot")).build()).execute().use { it.body?.string() }
        }

        assertEquals(2, recorded.size)
        // The change is recorded as a patch of just that field, not the whole snapshot again.
        assertEquals(null, recorded[1].second["responseBody"])
        assertTrue(recorded[1].second["responsePatch"].toString().contains("blocked"))
    }

    @Test
    fun aRepeatedSendIsAlwaysRecorded() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}")) }
        repeat(2) {
            val request = Request.Builder()
                .url(server.url("/api/pane/w1/keys"))
                .post("{\"keys\":[\"Enter\"]}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { it.body?.string() }
        }

        assertEquals(2, recorded.size)
    }

    @Test
    fun anUnchangedAnswerForADifferentPaneIsStillRecorded() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\"same\"}")) }
        client.newCall(Request.Builder().url(server.url("/api/pane/w1")).build()).execute().use { it.body?.string() }
        client.newCall(Request.Builder().url(server.url("/api/pane/w2")).build()).execute().use { it.body?.string() }

        assertEquals(2, recorded.size)
    }

    @Test
    fun switchedOffStillTagsTheRequestButRecordsNothing() {
        val off = object : RecordingDiagnosticsRecorder(recorded) {
            override val isEnabled = false
        }
        val offClient = OkHttpClient.Builder().addInterceptor(DiagnosticsInterceptor(off)).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
        offClient.newCall(Request.Builder().url(server.url("/api/health")).build()).execute().use {
            assertEquals("{\"ok\":true}", it.body?.string())
        }

        assertNotNull(server.takeRequest().getHeader("X-Collie-Trace-Id"))
        assertTrue(recorded.isEmpty())
    }
}
