package com.lateapex.collie.network

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
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

class HardeningLifecycleApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CollieApiClient
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
        val client: OkHttpClient = CollieApiClient.defaultHttpClient().newBuilder()
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

}
