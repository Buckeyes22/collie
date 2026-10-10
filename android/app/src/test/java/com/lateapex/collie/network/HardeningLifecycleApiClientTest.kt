package com.lateapex.collie.network

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okio.buffer

class HardeningLifecycleApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CollieApiClient
    private lateinit var client: OkHttpClient
    private var origin = CollieOrigin("https://localhost/")

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = CollieApiClient.defaultHttpClient().newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        api = CollieApiClient(client, Json { ignoreUnknownKeys = true; encodeDefaults = false })
        origin = CollieOrigin(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun terminalWriteIsNotReplayedForServiceUnavailableRetryAfterZero() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "0")
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"temporarily unavailable"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}"""),
        )

        val result = api.reply(
            Connection(origin, "phone", "token"),
            PaneAddress(paneId = "w1:p1"),
            "run once",
        )

        assertTrue(result is ApiResult.Failure)
        val failure = (result as ApiResult.Failure).error as ApiFailure.Http
        assertEquals(503, failure.status)
        assertEquals(1, server.requestCount)
        assertEquals("/api/pane/w1%3Ap1/reply", server.takeRequest().path)
    }

    @Test
    fun truncatedBodiesBecomeFailuresForReadsWritesAndPairingWithoutReplaying() = runBlocking {
        val connection = Connection(origin, "phone", "token")
        val calls: List<suspend () -> ApiResult<*>> = listOf(
            { api.health(connection) },
            { api.reply(connection, PaneAddress(paneId = "w1:p1"), "send once") },
            { api.pair(origin, "fake-code", "phone") },
        )
        calls.forEachIndexed { index, invoke ->
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"padding\":\"${"x".repeat(2048)}\"}")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val result = invoke()
            assertTrue("body I/O errors must not escape to a UI coroutine", result is ApiResult.Failure)
            assertTrue((result as ApiResult.Failure).error is ApiFailure.Network)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test
    fun responseBodyIsReadOffTheCallingThreadAndClosedBeforeReturning() = runBlocking {
        val caller = Thread.currentThread()
        var bodyThread: Thread? = null
        var closed = false
        val inspectedClient = client.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body!!
            val source = object : okio.ForwardingSource(original.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    bodyThread = Thread.currentThread()
                    return super.read(sink, byteCount)
                }
                override fun close() { closed = true; super.close() }
            }
            response.newBuilder().body(object : okhttp3.ResponseBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source() = source.buffer()
            }).build()
        }.build()
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"version\":\"1.19.0\",\"deposed\":false,\"mode\":\"bridge\"}"))
        val result = CollieApiClient(inspectedClient, Json { ignoreUnknownKeys = true }).health(Connection(origin, "phone", "token"))
        assertTrue(result is ApiResult.Success)
        assertTrue(bodyThread != null && bodyThread !== caller)
        assertTrue(closed)
    }

    @Test
    fun cancellationWhileReadingTheBodyClosesTheResponseWithoutReplaying() = runBlocking {
        val reading = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val inspectedClient = client.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body!!
            val source = object : okio.ForwardingSource(original.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    reading.countDown()
                    return super.read(sink, byteCount)
                }
                override fun close() {
                    try { super.close() } finally { closed.countDown() }
                }
            }.buffer()
            response.newBuilder().body(object : okhttp3.ResponseBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source() = source
            }).build()
        }.build()
        server.enqueue(MockResponse().setBody("{\"ok\":true}")
            .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
        val call = async(kotlinx.coroutines.Dispatchers.Default) {
            CollieApiClient(inspectedClient, Json { ignoreUnknownKeys = true })
                .health(Connection(origin, "phone", "token"))
        }
        try {
            assertTrue(reading.await(3, java.util.concurrent.TimeUnit.SECONDS))
            call.cancel()
            call.join()
            assertTrue(closed.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
        } finally { call.cancel() }
    }

    @Test
    fun speechBudgetAlsoAppliesToSocketReadsAndWrites() = runBlocking {
        var readTimeout = 0
        var writeTimeout = 0
        val inspectedClient = client.newBuilder().addInterceptor { chain ->
            readTimeout = chain.readTimeoutMillis()
            writeTimeout = chain.writeTimeoutMillis()
            chain.proceed(chain.request())
        }.build()
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"text\":\"speech\"}"))
        val audio = AudioUpload("audio/mp4", byteArrayOf(1))
        val result = CollieApiClient(inspectedClient, Json { ignoreUnknownKeys = true })
            .transcribe(Connection(origin, "phone", "token"), audio)
        assertTrue(result is ApiResult.Success)
        val expected = (CollieApiClient.sttTimeoutSeconds(audio.bytes.size) * 1000).toInt()
        assertEquals(expected, readTimeout)
        assertEquals(expected, writeTimeout)
    }

}
