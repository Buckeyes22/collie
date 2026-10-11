package com.lateapex.collie.diagnostics

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApiClient
import com.lateapex.collie.network.ImageBlob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

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
        for (path in listOf("/api/pair", "/collie/api/pair", "/nested/collie/api/pair")) {
            for (status in listOf(200, 400)) {
                val body = "{\"token\":\"private-pairing-token\",\"label\":\"phone\"}"
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                val request = Request.Builder()
                    .url(server.url(path))
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
    fun intentCreationBodiesAreRedactedForMountedRoutesRegardlessOfMimeType() {
        val paths = listOf(
            "/mount/api/launch",
            "/mount/api/worktree",
            "/mount/api/workspace/ws-1/worktree",
            "/mount/api/launchers/added",
        )
        paths.forEach { path ->
            repeat(2) { // Initial POST and deliberate same-route replay share the same privacy rule.
                val sentinel = "COMMAND-SECRET-SENTINEL"
                server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"command\":\"$sentinel\"}"))
                val request = Request.Builder()
                    .url(server.url(path))
                    .post("{\"requestId\":\"request-1\",\"command\":\"$sentinel\"}".toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    assertTrue(response.body!!.string().contains(sentinel))
                }
                val (_, fields) = recorded.last()
                assertEquals(true, fields["requestBodyRedacted"])
                assertEquals(true, fields["responseBodyRedacted"])
                assertFalse(fields.containsKey("requestBody"))
                assertFalse(fields.containsKey("responseBody"))
                assertFalse(fields.values.any { it.toString().contains(sentinel) })
            }
        }
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
    fun enabledDiagnosticsLeavesMountedBlobBodiesUntouchedForTheApiStreamingLimit() {
        // CollieOrigin intentionally rejects cleartext; exercise the production HTTPS API path
        // with a locally trusted fixture certificate rather than weakening that invariant.
        server.shutdown()
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        val oversized = ByteArray(ImageBlob.MAX_BYTES + 384 * 1024) { (it and 0xff).toByte() }
        val mimeHeaders = listOf(null, "image/svg+xml", "text/plain", "image/png")
        for (mime in mimeHeaders) {
            val sourceBytesRead = AtomicLong()
            val recorder = RecordingDiagnosticsRecorder(recorded)
            val diagnosticClient = CollieApiClient.defaultHttpClient().newBuilder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .addInterceptor(DiagnosticsInterceptor(recorder))
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val originalBody = response.body ?: return@addNetworkInterceptor response
                    val countedSource = object : ForwardingSource(originalBody.source()) {
                        override fun read(sink: okio.Buffer, byteCount: Long): Long {
                            val read = super.read(sink, byteCount)
                            if (read > 0) sourceBytesRead.addAndGet(read)
                            return read
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = originalBody.contentType()
                        override fun contentLength() = originalBody.contentLength()
                        override fun source(): BufferedSource = countedSource
                    }).build()
                }
                .build()
            val api = CollieApiClient(diagnosticClient, Json { ignoreUnknownKeys = true })
            val response = MockResponse().setChunkedBody(okio.Buffer().write(oversized), 16 * 1024)
            if (mime != null) response.setHeader("Content-Type", mime)
            server.enqueue(response)

            val result = kotlinx.coroutines.runBlocking {
                api.imageBlob(
                    Connection(CollieOrigin(server.url("/mounted/").toString()), "phone", "token"),
                    Scope("peer", "work"),
                    "ab".repeat(32),
                )
            }

            assertTrue("over-limit response is refused for MIME $mime", result is ApiResult.Failure && (result as ApiResult.Failure).error is ApiFailure.Protocol)
            assertTrue(
                "enabled diagnostics must not pre-buffer MIME $mime: read ${sourceBytesRead.get()} bytes",
                sourceBytesRead.get() <= ImageBlob.MAX_BYTES + 16 * 1024L,
            )
            val fields = recorded.last().second
            assertEquals("/mounted/api/blobs/${"ab".repeat(32)}", fields["path"])
            assertEquals(true, fields["responseBodyStreaming"])
            assertFalse(fields.containsKey("responseBody"))
            server.takeRequest()
        }
    }

    @Test
    fun enabledDiagnosticsLeavesMountedFilesResponsesStreamedForEachApiBound() {
        server.shutdown()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        val recorder = RecordingDiagnosticsRecorder(recorded)
        val counted = java.util.concurrent.atomic.AtomicLong()
        val http = CollieApiClient.defaultHttpClient(recorder).newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body ?: return@addNetworkInterceptor response
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val read = super.read(sink, byteCount)
                        if (read > 0) counted.addAndGet(read)
                        return read
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource = source
                }).build()
            }
            .build()
        val api = CollieApiClient(http, Json { ignoreUnknownKeys = true })
        val connection = Connection(CollieOrigin(server.url("/mounted/").toString()), "phone", "token")
        val target = com.lateapex.collie.network.FilesTarget.Pane("w1:p1")
        val routes = listOf("listing", "existence", "image")
        val smallImage = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        for (route in routes) {
            val body = when (route) {
                "listing" -> "{\"paneId\":\"w1:p1\",\"available\":true,\"root\":\"/repo\",\"path\":\"ok.txt\",\"size\":2,\"mtimeMs\":1,\"binary\":false,\"truncated\":false,\"text\":\"ok\"}".toByteArray()
                "existence" -> "{\"exists\":[\"a\"]}".toByteArray()
                else -> smallImage
            }
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", if (route == "image") "image/png" else "application/json").setBody(okio.Buffer().write(body)))
            val result = kotlinx.coroutines.runBlocking {
                when (route) {
                    "listing" -> api.files(connection, target, Scope(), path = com.lateapex.collie.network.RelativeFilePath.parse("ok.txt"))
                    "existence" -> api.filesExist(connection, target, Scope(), listOf(com.lateapex.collie.network.RelativeFilePath.parse("a")!!))
                    else -> api.fileImage(connection, target, Scope(), com.lateapex.collie.network.RelativeFilePath.parse("ok.png")!!)
                }
            }
            assertTrue("$route success remains readable to its API consumer", result is ApiResult.Success)
            val fields = recorded.last().second
            assertTrue(fields["responseBodyStreaming"] == true)
            assertFalse(fields.containsKey("responseBody"))
            server.takeRequest()
        }
        for (route in routes) {
            val limit = if (route == "image") com.lateapex.collie.network.FilesImageBlob.MAX_BYTES else 8 * 1024 * 1024
            val oversizedText = when (route) {
                "image" -> "x"
                "existence" -> "{\"exists\":[\"" + "a".repeat(limit + 1024) + "\"]}"
                else -> "{\"paneId\":\"w1:p1\",\"available\":true,\"root\":\"/repo\",\"path\":\"large.txt\",\"size\":0,\"mtimeMs\":null,\"binary\":false,\"truncated\":false,\"text\":\"" + "a".repeat(limit + 1024) + "\"}"
            }
            val bytes = if (route == "image") ByteArray(limit + 1024) { (it and 0xff).toByte() } else oversizedText.toByteArray()
            val response = MockResponse().setResponseCode(200).setHeader("Content-Type", if (route == "image") "text/plain" else "application/json")
                .setChunkedBody(okio.Buffer().write(bytes), 16 * 1024)
            counted.set(0)
            server.enqueue(response)
            val result = kotlinx.coroutines.runBlocking {
                when (route) {
                    "listing" -> api.files(connection, target, Scope(), path = com.lateapex.collie.network.RelativeFilePath.parse("large.txt"))
                    "existence" -> api.filesExist(connection, target, Scope(), listOf(com.lateapex.collie.network.RelativeFilePath.parse("a")!!))
                    else -> api.fileImage(connection, target, Scope(), com.lateapex.collie.network.RelativeFilePath.parse("large.txt")!!)
                }
            }
            assertTrue("$route response exceeds its API cap", result is ApiResult.Failure)
            assertTrue("diagnostics must not drain $route beyond the API limit", counted.get() <= limit + 16 * 1024L)
            val fields = recorded.last().second
            assertTrue("the mounted API path is classified and never copied", fields["responseBodyStreaming"] == true)
            assertFalse(fields.containsKey("responseBody"))
            assertFalse(fields.containsKey("requestBody"))
            val requestPath = server.takeRequest().path!!.substringBefore('?')
            assertTrue(requestPath.endsWith(when (route) {
                "listing" -> "/files"
                "existence" -> "/files/exist"
                else -> "/files/image"
            }))
        }
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
    @Test
    fun enabledDiagnosticsDoesNotReadMountedIdentityBodiesBeforeTheNativeJsonBound() {
        server.shutdown()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        val payload = "{\"ok\":true,\"paneId\":\"w1:p1\",\"privateSession\":\"never-record-this\",\"padding\":\"${"x".repeat(80 * 1024)}\"}"
        for (mime in listOf(null, "text/plain", "application/octet-stream")) {
            val sourceBytesRead = AtomicLong()
            val diagnosticClient = CollieApiClient.defaultHttpClient().newBuilder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .addInterceptor(DiagnosticsInterceptor(RecordingDiagnosticsRecorder(recorded)))
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val originalBody = response.body ?: return@addNetworkInterceptor response
                    val counted = object : ForwardingSource(originalBody.source()) {
                        override fun read(sink: okio.Buffer, byteCount: Long): Long {
                            val read = super.read(sink, byteCount)
                            if (read > 0) sourceBytesRead.addAndGet(read)
                            return read
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = originalBody.contentType()
                        override fun contentLength() = originalBody.contentLength()
                        override fun source(): BufferedSource = counted
                    }).build()
                }.build()
            val api = CollieApiClient(diagnosticClient, Json { ignoreUnknownKeys = true })
            val response = MockResponse().setChunkedBody(payload, 1024)
            if (mime != null) response.setHeader("Content-Type", mime)
            server.enqueue(response)
            val result = kotlinx.coroutines.runBlocking {
                api.paneAgentIdentity(
                    Connection(CollieOrigin(server.url("/mounted/").toString()), "phone", "token"),
                    com.lateapex.collie.domain.PaneAddress(Scope("host-a", "session-a"), "w1:p1"),
                )
            }
            assertTrue("oversized identity JSON is refused for $mime", result is ApiResult.Failure)
            assertTrue("the API bound, not diagnostics, limits reads for $mime: ${sourceBytesRead.get()}", sourceBytesRead.get() <= 16 * 1024L + 8 * 1024L)
            val fields = recorded.last().second
            assertEquals("/mounted/api/pane/w1%3Ap1/agent", fields["path"])
            assertEquals(true, fields["responseBodyStreaming"])
            assertFalse(fields.containsKey("responseBody"))
            assertFalse(fields.values.any { it.toString().contains("never-record-this") })
            server.takeRequest()
        }
    }
    @Test
    fun mountedLifecyclePostBodiesAreMetadataOnlyAndRemainReadableByTheCaller() {
        for (path in listOf("/mounted/api/pane/w1%3Ap1/agent/state", "/nested/mounted/api/pane/w1%3Ap1/agent/authority/clear")) {
            recorded.clear()
            val requestSecret = "private-report-session-credential"
            val responseSecret = "private-lifecycle-response-payload"
            server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "text/plain")
                .setChunkedBody("$responseSecret ${"x".repeat(32 * 1024)}", 512))
            val request = Request.Builder().url(server.url(path)).header("Content-Type", "application/json")
                .post(object : okhttp3.RequestBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun writeTo(sink: okio.BufferedSink) {
                        sink.writeUtf8("{\"session\":\"$requestSecret\"}")
                    }
                }).build()
            val body = client.newCall(request).execute().use { it.body!!.string() }
            assertTrue(body.contains(responseSecret))
            val (_, fields) = recorded.single()
            assertEquals(true, fields["requestBodyRedacted"])
            assertEquals(true, fields["responseBodyStreaming"])
            assertFalse(fields.containsKey("requestBody"))
            assertFalse(fields.containsKey("responseBody"))
            assertFalse(fields.values.any { it.toString().contains(requestSecret) || it.toString().contains(responseSecret) })
            server.takeRequest()
        }
    }
}
