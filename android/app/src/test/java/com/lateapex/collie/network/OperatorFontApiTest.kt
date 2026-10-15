package com.lateapex.collie.network

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Protocol
import okhttp3.ConnectionPool
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real HTTPS transport tests; font parsing/visual appearance requires the Android platform check. */
class OperatorFontApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CollieApiClient
    private lateinit var http1Api: CollieApiClient
    private lateinit var connection: Connection
    private val row = OperatorFontRow("Studio Face", "studio-face.woff2", "350 750")
    private fun summary(result: ApiResult<*>): String = when (result) {
        is ApiResult.Success -> "Success(status=${result.status})"
        is ApiResult.NotModified -> "NotModified"
        is ApiResult.Failure -> "Failure(${result.error.javaClass.simpleName}" +
            ((result.error as? ApiFailure.Http)?.let { ",status=${it.status}" } ?: "") + ")"
    }
    private fun bytes(size: Int = 48) = ByteArray(size).also { data ->
        byteArrayOf(0x77, 0x4f, 0x46, 0x32).copyInto(data)
    }
    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory()); start() }
        val client = CollieApiClient.defaultHttpClient().newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
        api = CollieApiClient(client, Json { ignoreUnknownKeys = true })
        http1Api = CollieApiClient(client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).connectionPool(ConnectionPool()).build(),
            Json { ignoreUnknownKeys = true })
        connection = Connection(CollieOrigin(server.url("/nested/collie/").toString()), "phone", "private-bearer")
    }
    @After fun close() { server.close() }
    @Test fun weightGrammarMatchesSupportedThreeDigitVariableRanges() {
        for (weight in listOf(null, "100", "450", "900", "350 750", "450 450")) assertTrue("weight=$weight", row.copy(weight = weight).valid())
        for (weight in listOf("099", "901", "750 350", "450  750", "45", "1000", "450\n750")) assertFalse("weight=$weight", row.copy(weight = weight).valid())
    }
    @Test fun exactFontBytesUseOnlyConfiguredMountedPairedRead() = runBlocking {
        val font = bytes()
        server.enqueue(MockResponse.Builder().body(Buffer().write(font)).build())
        val response = api.operatorFont(connection, row)
        assertTrue("Expected exact font bytes: " + summary(response), response is ApiResult.Success)
        assertArrayEquals(font, (response as ApiResult.Success).value)
        val request = server.takeRequest()
        assertEquals("GET", request.method); assertEquals("/nested/collie/api/fonts/studio-face.woff2", request.target)
        assertEquals("Bearer private-bearer", request.headers["Authorization"]); assertNull(request.headers["Origin"])
    }
    @Test fun declaredAndChunkedFontBodiesStopAtTwoMiB() = runBlocking {
        val limit = 2 * 1024 * 1024
        val boundary = bytes(limit)
        // MockWebServer chunkedBody includes HTTP/1 framing. Exercise it on HTTP/1; HTTP/2
        // fixtures carry unframed bytes in DATA, matching the existing image boundary tests.
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(boundary), 16 * 1024).build())
        val chunkedResponse = http1Api.operatorFont(connection, row)
        assertTrue("Expected exact HTTP/1 2 MiB boundary: " + summary(chunkedResponse), chunkedResponse is ApiResult.Success)
        assertArrayEquals(boundary, (chunkedResponse as ApiResult.Success).value); server.takeRequest()
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes(limit + 1))).build())
        assertTrue((http1Api.operatorFont(connection, row) as ApiResult.Failure).error is ApiFailure.Protocol); server.takeRequest()
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(bytes(limit + 1)), 16 * 1024).build())
        assertTrue((http1Api.operatorFont(connection, row) as ApiResult.Failure).error is ApiFailure.Protocol); server.takeRequest()
        server.enqueue(MockResponse.Builder().body(Buffer().write(boundary)).build())
        val dataResponse = api.operatorFont(connection, row)
        assertTrue("Expected exact HTTP/2 2 MiB boundary: " + summary(dataResponse), dataResponse is ApiResult.Success)
        assertArrayEquals(boundary, (dataResponse as ApiResult.Success).value); server.takeRequest()
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes(limit + 1))).build())
        assertTrue((api.operatorFont(connection, row) as ApiResult.Failure).error is ApiFailure.Protocol); server.takeRequest()
        assertEquals(5, server.requestCount)
    }
    @Test fun invalidDeclaredRowsNeverDispatchAndInvalidBinaryNeverLoads() = runBlocking {
        for (invalid in listOf(row.copy(basename = "../secret.woff2"), row.copy(basename = "a/b.woff2"), row.copy(basename = "bad.ttf"),
            row.copy(family = "system-ui"), row.copy(weight = "901"))) {
            assertTrue(api.operatorFont(connection, invalid) is ApiResult.Failure)
        }
        assertEquals(0, server.requestCount)
        for (invalid in listOf(bytes(47), ByteArray(48), ByteArray(64) { 0x41 })) {
            server.enqueue(MockResponse.Builder().body(Buffer().write(invalid)).build())
            assertTrue((api.operatorFont(connection, row) as ApiResult.Failure).error is ApiFailure.Protocol); server.takeRequest()
        }
    }
    @Test fun redirectsNeverForwardPairingToAnotherFontOrigin() = runBlocking {
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "https://another.example/font.woff2").build())
        assertTrue((api.operatorFont(connection, row) as ApiResult.Failure).error is ApiFailure.Redirect)
        assertEquals(1, server.requestCount)
        assertEquals("/nested/collie/api/fonts/studio-face.woff2", server.takeRequest().target)
    }
}
