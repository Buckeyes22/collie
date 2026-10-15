package com.lateapex.collie.network

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real HTTPS parsing of the existing error provenance convention, without changing client policy. */
class LauncherDuplicateErrorMetadataTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CollieApiClient

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory()); start() }
        val client = CollieApiClient.defaultHttpClient().newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
        api = CollieApiClient(client, Json { ignoreUnknownKeys = true; encodeDefaults = false })
    }

    @After fun tearDown() { server.close() }

    @Test fun markedLocalDuplicateUsesCollieOperationProvenanceAndUnmarkedDuplicateStaysUnknown() = runBlocking {
        val connection = Connection(CollieOrigin(server.url("/mount/").toString()), "fixture phone", "fixture-token")
        val scope = Scope(session = "work")
        val id = "d02d44dc-0b03-445c-8a8c-0b608e8697de"
        val body = """{"requestId":"$id","text":"printf fixed","kind":"command","label":"Fixed","noPrompts":false}"""
        val responseBody = """{"ok":false,"error":"this command is already a launcher","code":"launcher.duplicate"}"""
        for (marked in listOf(true, false)) {
            val response = MockResponse.Builder().code(409).body(responseBody)
            if (marked) {
                response.addHeader("x-collie-error-source", "collie")
                response.addHeader("x-collie-error-factor", "operation")
                response.addHeader("x-collie-error-code", "launcher.duplicate")
            }
            server.enqueue(response.build())
            val result = api.addLauncherFrozen(connection, scope, body)
            assertTrue(result is ApiResult.Failure)
            val error = (result as ApiResult.Failure).error as ApiFailure.Http
            assertEquals(409, error.status)
            assertEquals("launcher.duplicate", error.code)
            assertEquals(if (marked) FailureSource.COLLIE else FailureSource.UNKNOWN, error.source)
            assertEquals(if (marked) FailureFactor.OPERATION else FailureFactor.UNKNOWN, error.factor)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/mount/api/launchers/added?session=work", request.target)
            assertEquals(body, request.body!!.utf8())
        }
        assertEquals("Each deliberate test call sends exactly once", 2, server.requestCount)
    }
}
