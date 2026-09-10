package com.lateapex.collie.network

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CollieApiClientTest {
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
        val client = CollieApiClient.defaultHttpClient().newBuilder()
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
    fun paneRequestUsesScopedIdentityHeadersAndEtag() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("ETag", "\"rev-7\"")
                .setBody("""{"paneId":"w1:p1","text":"hello","truncated":false,"revision":7}"""),
        )
        val connection = Connection(origin, "phone", "bearer-secret")
        val result = api.pane(
            connection,
            PaneAddress(Scope(host = "a b", session = "work session"), "w1:p1"),
            etag = "\"rev-6\"",
        )

        assertTrue(result is ApiResult.Success)
        assertEquals("\"rev-7\"", (result as ApiResult.Success).etag)
        val request = server.takeRequest()
        assertEquals("/api/pane/w1%3Ap1?lines=600&host=a%20b&session=work%20session", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        assertEquals("Bearer bearer-secret", request.getHeader("Authorization"))
        assertEquals("1", request.getHeader("X-Collie-Seen"))
        assertEquals("\"rev-6\"", request.getHeader("If-None-Match"))
        assertNull(request.getHeader("Origin"))
    }

    @Test
    fun configReadsMuxDisplayNameForTheSharedHeader() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"push":false,"vapidPublicKey":"","mux":{"name":"reference","capabilities":{},"unsupportedKeys":[],"notes":{}}}""",
                ),
        )

        val result = api.config(Connection(origin, "phone", "token"))

        assertEquals("reference", (result as ApiResult.Success).value.mux?.name)
        assertEquals("/api/config", server.takeRequest().path)
    }

    @Test
    fun mutationCarriesOriginAndPreservesPartialDeliveryFailure() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"submit failed","code":"reply.not_submitted","textDelivered":true}"""),
        )
        val result = api.reply(
            Connection(origin, "phone", "token"),
            PaneAddress(Scope(host = "desk", session = "default"), "w1:p1"),
            "hello",
        )

        val failure = (result as ApiResult.Failure).error as ApiFailure.Http
        assertEquals(502, failure.status)
        assertTrue(failure.textDelivered)
        val request = server.takeRequest()
        assertEquals(origin.headerValue, request.getHeader("Origin"))
        assertEquals("/api/pane/w1%3Ap1/reply?host=desk&session=default", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectIsReturnedWithoutFollowingOrCredentialForwarding() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.invalid/login"))
        val result = api.snapshot(Connection(origin, "phone", "token"))

        assertEquals(ApiFailure.Redirect(302), (result as ApiResult.Failure).error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun widenedSnapshotUsesSessionsAllWithoutLeakingBreadthIntoOtherScopedCalls() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"bridge":"connected","agents":[],"shellPanes":[],"workspaces":[],"tabs":[],"ts":1}"""),
        )
        val connection = Connection(origin, "phone", "token")
        api.snapshot(connection, Scope(host = "desk", viewAll = true))

        assertEquals("/api/snapshot?sessions=all&host=desk", server.takeRequest().path)
    }

    @Test
    fun pairingRefusalIsTypedAndNeverStoredByTransport() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("""{"error":"bad-code","code":"pairing.bad_code"}"""),
        )
        val result = api.pair(origin, "123456", "phone")

        assertEquals(PairResult.Refused("bad-code"), (result as ApiResult.Success).value)
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertEquals(origin.headerValue, request.getHeader("Origin"))
    }

    @Test
    fun plainTextAuthorizationFailureIsPreservedForRecovery() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("Content-Type", "text/plain")
                .setBody("device not paired"),
        )

        val result = api.snapshot(Connection(origin, "phone", "revoked-token"))

        val failure = (result as ApiResult.Failure).error as ApiFailure.Http
        assertEquals(403, failure.status)
        assertEquals("device not paired", failure.message)
    }

    @Test
    fun nonJsonPairingFailureIsAnHttpFailureNotAMalformedSuccess() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "text/plain")
                .setBody("pairing temporarily unavailable"),
        )

        val result = api.pair(origin, "123456", "phone")

        val failure = (result as ApiResult.Failure).error as ApiFailure.Http
        assertEquals(503, failure.status)
        assertEquals("pairing temporarily unavailable", failure.message)
    }

    @Test
    fun defaultClientDisablesAllAutomaticRedirectAndConnectionRetries() {
        val client: OkHttpClient = CollieApiClient.defaultHttpClient()
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun structuralRoutesPreserveScopeAndExactJsonContracts() = runBlocking {
        repeat(4) {
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody(if (it == 1) createdPaneResponse() else """{"ok":true}"""),
            )
        }
        val connection = Connection(origin, "phone", "token")
        val scope = Scope(host = "desk", session = "work")

        api.renamePane(connection, PaneAddress(scope, "w1:p1"), "Review")
        api.createTab(connection, scope, "w1", label = "Tests", cwd = "/repo")
        api.createWorktree(connection, scope, "w1", "feature/native")
        api.closeTab(connection, scope, "w1:t2")

        server.takeRequest().also { request ->
            assertEquals("/api/pane/w1%3Ap1/rename?host=desk&session=work", request.path)
            assertEquals("{\"label\":\"Review\"}", request.body.readUtf8())
            assertEquals(origin.headerValue, request.getHeader("Origin"))
        }
        server.takeRequest().also { request ->
            assertEquals("/api/tab?host=desk&session=work", request.path)
            assertEquals("{\"workspaceId\":\"w1\",\"label\":\"Tests\",\"cwd\":\"/repo\"}", request.body.readUtf8())
        }
        server.takeRequest().also { request ->
            assertEquals("/api/workspace/w1/worktree?host=desk&session=work", request.path)
            assertEquals("{\"branch\":\"feature/native\"}", request.body.readUtf8())
        }
        assertEquals("/api/tab/w1%3At2/close?host=desk&session=work", server.takeRequest().path)
    }

    @Test
    fun historyUsesSeenHeaderAndOrdersPaginationBeforeScope() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"paneId":"w1:p1","available":false,"reason":"no-log"}"""),
        )

        val result = api.history(
            Connection(origin, "phone", "token"),
            PaneAddress(Scope("desk", "work"), "w1:p1"),
            limit = 25,
            before = "turn-7",
        )

        assertFalse((result as ApiResult.Success).value.available)
        server.takeRequest().also { request ->
            assertEquals("/api/pane/w1%3Ap1/history?limit=25&before=turn-7&host=desk&session=work", request.path)
            assertEquals("1", request.getHeader("X-Collie-Seen"))
            assertNull(request.getHeader("Origin"))
        }
        Unit
    }

    @Test
    fun settingsAndUpdatePostsEncodeExplicitConsentWithoutOptionalFalse() = runBlocking {
        server.enqueue(jsonResponse("""{"blocked":false,"done":false,"updates":true}"""))
        server.enqueue(jsonResponse("""{"snoozedUntil":null}"""))
        server.enqueue(jsonResponse(updateStartResponse()))
        val connection = Connection(origin, "phone", "token")

        api.setNotificationPreferences(connection, NotifyPreferencesPatch(blocked = false))
        api.setNotificationSnooze(connection, null)
        api.startUpdate(connection, "1.6.0", major = false)

        assertEquals("{\"blocked\":false}", server.takeRequest().body.readUtf8())
        assertEquals("{\"snoozedUntil\":null}", server.takeRequest().body.readUtf8())
        server.takeRequest().also { request ->
            assertEquals("/api/update", request.path)
            assertEquals("{\"confirm\":true,\"target\":\"1.6.0\",\"major\":false}", request.body.readUtf8())
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }
        Unit
    }

    @Test
    fun standbyUpdateReadsTheRootFailoverRouteWithTheStoredCredential() = runBlocking {
        server.enqueue(jsonResponse(updateRunResponse("restarting")))

        val result = api.standbyUpdate(Connection(origin, "phone", "token"))

        assertEquals("restarting", (result as ApiResult.Success).value.state)
        server.takeRequest().also { request ->
            assertEquals("/standby/update", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertNull(request.getHeader("Origin"))
        }
        Unit
    }

    @Test
    fun standbyUpdateAcceptsTheMinimalIdleAnswerButRejectsAnIncompleteActiveRun() = runBlocking {
        server.enqueue(jsonResponse("""{"state":"idle"}"""))
        server.enqueue(jsonResponse("""{"state":"restarting"}"""))
        val connection = Connection(origin, "phone", "token")

        val idle = api.standbyUpdate(connection) as ApiResult.Success
        val malformed = api.standbyUpdate(connection) as ApiResult.Failure

        assertNull(idle.value.activeRunOrNull())
        assertEquals(ApiFailure.Protocol("Malformed standby update response"), malformed.error)
    }

    @Test
    fun uploadIsBoundedMultipartWithScopeAndMutationHeaders() = runBlocking {
        server.enqueue(jsonResponse("""{"ok":true,"path":"/tmp/collie.png"}"""))
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

        val result = api.upload(
            Connection(origin, "phone", "token"),
            PaneAddress(Scope(host = "desk"), "w1:p1"),
            ImageUpload("shot.png", "image/png", bytes),
        )

        assertEquals("/tmp/collie.png", (result as ApiResult.Success).value.path)
        server.takeRequest().also { request ->
            assertEquals("/api/pane/w1%3Ap1/upload?host=desk", request.path)
            assertTrue(request.getHeader("Content-Type")?.startsWith("multipart/form-data; boundary=") == true)
            assertEquals(origin.headerValue, request.getHeader("Origin"))
            assertTrue(request.body.readUtf8().contains("shot.png"))
        }
        Unit
    }

    @Test
    fun transcriptionPostsRawAudioWithoutPaneScope() = runBlocking {
        server.enqueue(jsonResponse("""{"ok":true,"text":"review before sending"}"""))
        val audio = byteArrayOf(0, 1, 2, 3)

        val result = api.transcribe(
            Connection(origin, "phone", "token"),
            AudioUpload("audio/mp4", audio),
        )

        assertEquals("review before sending", (result as ApiResult.Success).value.text)
        server.takeRequest().also { request ->
            assertEquals("/api/stt", request.path)
            assertEquals("audio/mp4", request.getHeader("Content-Type"))
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals(origin.headerValue, request.getHeader("Origin"))
            assertTrue(request.body.readByteArray().contentEquals(audio))
        }
        Unit
    }

    @Test
    fun transcriptionRejectsMalformedSuccessfulResponse() = runBlocking {
        server.enqueue(jsonResponse("""{"ok":true,"text":""}"""))

        val result = api.transcribe(
            Connection(origin, "phone", "token"),
            AudioUpload("audio/mp4", byteArrayOf(1)),
        )

        assertEquals(
            ApiFailure.Protocol("Malformed speech-to-text response"),
            (result as ApiResult.Failure).error,
        )
    }

    @Test
    fun transcriptionTimeoutScalesWithTheBoundedClip() {
        assertEquals(81L, CollieApiClient.sttTimeoutSeconds(1))
        assertTrue(CollieApiClient.sttTimeoutSeconds(AudioUpload.MAX_BYTES) > 300L)
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun createdPaneResponse(): String =
        """{"ok":true,"pane":{"paneId":"w1:p2","workspaceId":"w1","workspaceLabel":"Collie","tabId":"w1:t2","cwd":"/repo"}}"""

    private fun updateStartResponse(): String =
        """{"ok":true,"to":"1.6.0","major":false,"run":null}"""

    private fun updateRunResponse(state: String): String =
        """{"schema":1,"state":"$state","from":"1.5.0","to":"1.6.0","startedAt":1,"updatedAt":2,"pid":7,"attempt":1}"""
}
