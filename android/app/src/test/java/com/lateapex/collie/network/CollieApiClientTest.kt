package com.lateapex.collie.network

import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.json.put

class CollieApiClientTest {
    @Test
    fun agentCapabilityInventoryIsAnAuthenticatedScopedReadAndRetainsUnknownDimensionStates() = runBlocking {
        val mounted = Connection(CollieOrigin(server.url("/nested/collie/").toString()), "phone", "token")
        server.enqueue(jsonResponse(
            """{"schema":1,"mux":"herdr","muxCapabilities":{"agentDetection":true,"agentSessionRef":false},"runtimeVersion":null,"rows":[{"kind":"gemini","label":"Gemini CLI","launch":"unverified","detection":"implemented","sessionReference":"implemented","journal":"unverified","webSemanticDialog":"unverified","webComposer":"unverified","media":"interface-ready","observedPanes":2,"observedSessionReferences":1,"version":"1.2.3","evidence":{"version":"fixed version probe","journal":"unverified","semantic":"unverified","media":"owner integration pending"}}],"note":"observations are current"}""",
        ))

        val result = api.agentCapabilities(mounted, Scope(host = "peer-1", session = "session-2"))
        assertTrue(result is ApiResult.Success)
        val value = (result as ApiResult.Success).value
        assertEquals("herdr", value.mux)
        assertEquals(false, value.muxCapabilities?.agentSessionRef)
        assertEquals("unverified", value.rows.single().journal)
        assertEquals("interface-ready", value.rows.single().media)
        assertEquals("Gemini CLI", value.rows.single().label)
        assertEquals(2, value.rows.single().observedPanes)
        assertEquals(1, value.rows.single().observedSessionReferences)
        assertEquals("1.2.3", value.rows.single().version)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/nested/collie/api/agents/capabilities?host=peer-1&session=session-2", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertNull(request.getHeader("Origin"))

        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        assertTrue(api.agentCapabilities(mounted, Scope()) is ApiResult.Failure)
        assertEquals("/nested/collie/api/agents/capabilities", server.takeRequest().path)

        server.enqueue(jsonResponse("""{"schema":2,"mux":"herdr","rows":[],"note":"future"}"""))
        val unsupportedSchema = api.agentCapabilities(mounted, Scope())
        assertTrue(unsupportedSchema is ApiResult.Failure)
        assertTrue((unsupportedSchema as ApiResult.Failure).error is ApiFailure.Protocol)
        assertEquals("/nested/collie/api/agents/capabilities", server.takeRequest().path)
    }


    private lateinit var server: MockWebServer
    private lateinit var api: CollieApiClient
    private lateinit var trustedHttpClient: OkHttpClient
    private lateinit var boundaryHttp1Client: OkHttpClient
    private lateinit var boundaryHttp1Api: CollieApiClient
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
        trustedHttpClient = CollieApiClient.defaultHttpClient().newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        api = CollieApiClient(trustedHttpClient, Json { ignoreUnknownKeys = true; encodeDefaults = false })
        boundaryHttp1Client = trustedHttpClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool())
            .build()
        boundaryHttp1Api = CollieApiClient(boundaryHttp1Client, Json { ignoreUnknownKeys = true; encodeDefaults = false })
        origin = CollieOrigin(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun controlReceiptUsesMountedReadAdmissionAndValidatesItsBoundSchema() = runBlocking {
        val mounted = Connection(CollieOrigin(server.url("/mount/").toString()), "phone", "token")
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"operation\":{\"schema\":1,\"requestId\":\"intent\",\"state\":\"unknown\",\"capability\":\"movePane\",\"targetIdentity\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"at\":1,\"updatedAt\":2}}"))
        assertTrue(api.controlOperation(mounted, Scope(host = "peer", session = "work"), "intent") is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/mount/api/control/operations/intent?host=peer&session=work", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals(null, request.getHeader("Origin"))
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"operation\":{\"schema\":2,\"requestId\":\"intent\",\"state\":\"retry\",\"capability\":\"movePane\",\"targetIdentity\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"at\":1,\"updatedAt\":2}}"))
        assertTrue(api.controlOperation(mounted, Scope(), "intent") is ApiResult.Failure)
    }

    @Test
    fun recipeAndOneOffRoutesKeepTheirExactScopedHttpContracts() = runBlocking {
        val connection = Connection(origin, "phone", "token")
        val scope = Scope(host = "builder", session = "review")
        server.enqueue(MockResponse().setBody("""{"ok":true,"noPrompts":false}"""))
        assertTrue(api.checkRun(connection, scope, "printf '%s' \"${'$'}HOME/a b\"") is ApiResult.Success)
        val check = server.takeRequest()
        assertEquals("POST", check.method)
        assertEquals("/api/launch/check?host=builder&session=review", check.path)
        assertEquals("""{"run":"printf '%s' \"${'$'}HOME/a b\""}""", check.body.readUtf8())

        val frozenRun = """{"requestId":"run-id","cwd":"/work tree","run":"printf '%s' \"${'$'}HOME/a b\""}"""
        server.enqueue(MockResponse().setBody("""{"ok":false,"error":"run disabled","code":"launch.run_off"}"""))
        val refusedRun = api.startRunFrozen(connection, scope, frozenRun)
        assertTrue(refusedRun is ApiResult.Success)
        assertFalse((refusedRun as ApiResult.Success).value.ok)
        assertEquals("run disabled", refusedRun.value.error)
        assertEquals("launch.run_off", refusedRun.value.code)
        assertNull(refusedRun.value.pane)
        val run = server.takeRequest()
        assertEquals("POST", run.method)
        assertEquals("/api/launch?host=builder&session=review", run.path)
        assertEquals(frozenRun, run.body.readUtf8())

        server.enqueue(MockResponse().setBody("""{"ok":false,"error":"not owned","code":"launcher.not_owned"}"""))
        api.renameAddedLauncher(connection, scope, "row-1", "New label")
        val rename = server.takeRequest()
        assertEquals("/api/launchers/added/rename?host=builder&session=review", rename.path)
        assertEquals("""{"id":"row-1","label":"New label"}""", rename.body.readUtf8())

        server.enqueue(MockResponse().setBody("""{"ok":true,"removed":1}"""))
        assertTrue(api.removeAddedLauncher(connection, scope, "row-1") is ApiResult.Success)
        val remove = server.takeRequest()
        assertEquals("/api/launchers/added/remove?host=builder&session=review", remove.path)
        assertEquals("""{"id":"row-1"}""", remove.body.readUtf8())

        server.enqueue(MockResponse().setBody("""{"ok":true,"removed":1}"""))
        assertTrue(api.removeRecentRun(connection, scope, "printf ok") is ApiResult.Success)
        val recentRemove = server.takeRequest()
        assertEquals("/api/launch/recent/remove?host=builder&session=review", recentRemove.path)
        assertEquals("""{"line":"printf ok"}""", recentRemove.body.readUtf8())
        server.enqueue(MockResponse().setBody("""{"ok":true,"removed":3}"""))
        assertTrue(api.clearRecentRuns(connection, scope) is ApiResult.Success)
        val recentClear = server.takeRequest()
        assertEquals("/api/launch/recent/clear?host=builder&session=review", recentClear.path)
        assertEquals("{}", recentClear.body.readUtf8())
    }

    @Test
    fun launcherDiscoveryKeepsOldPeerCompatibilityButRejectsMalformedPolicyCaps() = runBlocking {
        val connection = Connection(origin, "phone", "token")
        server.enqueue(MockResponse().setBody("""{"launchers":[],"home":"/home/operator"}"""))
        val legacy = api.launchers(connection, Scope())
        assertTrue(legacy is ApiResult.Success)
        assertNull((legacy as ApiResult.Success).value.adding)

        server.enqueue(MockResponse().setBody("""{"launchers":[],"home":"/home/operator","adding":{"adds":true,"freeText":true,"run":true,"file":"launchers.toml","count":21,"max":20,"recipes":[],"off":[]}}"""))
        assertTrue(api.launchers(connection, Scope()) is ApiResult.Failure)
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
    fun journalBlobUsesOnlyMountedScopedAuthenticatedGetAndRefusesRedirects() = runBlocking {
        val mounted = CollieOrigin(server.url("/mount/").toString())
        val connection = Connection(mounted, "phone", "image-token")
        val hash = "ab".repeat(32)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "image/png")
            .setHeader("X-Collie-Lead-Id", "new-lead")
            .setHeader("X-Collie-Lead-Epoch", "epoch-9")
            .setBody(okio.Buffer().write(png)))
        val result = api.imageBlob(connection, Scope("peer", "work"), hash)
        assertTrue(result is ApiResult.Success)
        assertEquals(png.toList(), (result as ApiResult.Success).value.bytes.toList())
        assertEquals(ControlAuthority("new-lead", "epoch-9"), (result as ApiResult.Success).controlAuthority)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/mount/api/blobs/$hash?host=peer&session=work", request.path)
        assertEquals("Bearer image-token", request.getHeader("Authorization"))
        assertEquals("image/png,image/jpeg,image/gif,image/webp", request.getHeader("Accept"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://outside.example/image.png"))
        assertTrue(api.imageBlob(connection, Scope(), hash) is ApiResult.Failure)
        assertEquals("/mount/api/blobs/$hash", server.takeRequest().path)
        val exactLimit = ByteArray(ImageBlob.MAX_BYTES) { 0x41 }
        // MockResponse.setChunkedBody stores HTTP/1 chunk framing in its body. Keep that fixture
        // on HTTP/1 so HTTP/2 DATA cannot accidentally carry the framing bytes as image payload.
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(byteArrayOf(1, 2, 3)), 16 * 1024))
        boundaryHttp1Client.newCall(okhttp3.Request.Builder().url(server.url("/chunked-diagnostic")).build()).execute().use { response ->
            assertEquals(Protocol.HTTP_1_1, response.protocol)
            assertEquals(listOf<Byte>(1, 2, 3), response.body!!.bytes().toList())
        }
        server.takeRequest()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setChunkedBody(okio.Buffer().write(exactLimit), 16 * 1024))
        val atLimit = boundaryHttp1Api.imageBlob(connection, Scope(), hash)
        assertTrue("exact 10 MiB image response should stream successfully: $atLimit", atLimit is ApiResult.Success && (atLimit as ApiResult.Success).value.bytes.size == ImageBlob.MAX_BYTES)
        assertEquals("/mount/api/blobs/$hash", server.takeRequest().path)
        val oversized = ByteArray(ImageBlob.MAX_BYTES + 1) { 0x41 }
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(oversized), 16 * 1024))
        val tooLarge = boundaryHttp1Api.imageBlob(connection, Scope(), hash)
        assertTrue(tooLarge is ApiResult.Failure && (tooLarge as ApiResult.Failure).error is ApiFailure.Protocol)
        assertEquals("/mount/api/blobs/$hash", server.takeRequest().path)
        // HTTP/2 carries the payload in DATA frames without MockWebServer's HTTP/1 chunk bytes.
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(exactLimit)))
        val h2AtLimit = api.imageBlob(connection, Scope(), hash)
        assertTrue("exact HTTP/2 payload should stream successfully: $h2AtLimit", h2AtLimit is ApiResult.Success && (h2AtLimit as ApiResult.Success).value.bytes.size == ImageBlob.MAX_BYTES)
        assertEquals("/mount/api/blobs/$hash", server.takeRequest().path)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(ImageBlob.MAX_BYTES + 1) { 0x41 })))
        val h2TooLarge = api.imageBlob(connection, Scope(), hash)
        assertTrue(h2TooLarge is ApiResult.Failure && (h2TooLarge as ApiResult.Failure).error is ApiFailure.Protocol)
        assertEquals("/mount/api/blobs/$hash", server.takeRequest().path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun journalBlobRejectsNonSha256PathBeforeNetwork() {
        runBlocking<Unit> {
            api.imageBlob(Connection(origin, "phone", "token"), Scope(), "../outside")
        }
    }

    @Test
    fun paneProcessDetailsAreScopedAndUseTheExplicitDeviceReadRoute() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"ok":true,"paneId":"w1:p1","shellPid":10,"foregroundProcessGroupId":12,"processes":[{"pid":12,"name":"node","argv":["node","app.js"],"cwd":"/repo"}]}""",
        ))
        val connection = Connection(origin, "phone", "bearer-secret")
        val result = api.paneProcesses(connection, PaneAddress(Scope(host = "desk", session = "work"), "w1:p1"))
        assertTrue(result is ApiResult.Success)
        assertEquals("node", (result as ApiResult.Success).value.processes.single().name)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/pane/w1%3Ap1/processes?host=desk&session=work", request.path)
        assertEquals("Bearer bearer-secret", request.getHeader("Authorization"))
        assertEquals("1", request.getHeader("X-Collie-Seen"))
    }

    @Test
    fun agentIdentityReadIsScopedAndDoesNotMarkThePaneSeen() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"ok":true,"paneId":"w1:p1","agent":"codex","status":"working","session":{"source":"codex","agent":"codex","kind":"id","value":"session-42"},"stateAuthority":{"observed":false,"source":null},"interactiveReady":true,"launchPending":false,"stateChangeSeq":"9007199254740993","completionSeq":"18446744073709551615"}""",
        ))
        val address = PaneAddress(Scope(host = "desk", session = "work"), "w1:p1")
        val result = api.paneAgentIdentity(Connection(origin, "phone", "bearer-secret"), address)
        assertTrue(result is ApiResult.Success)
        val identity = (result as ApiResult.Success).value
        assertEquals("session-42", identity.session?.value)
        assertEquals(false, identity.stateAuthority.observed)
        assertEquals("9007199254740993", identity.stateChangeSeq)
        assertEquals("18446744073709551615", identity.completionSeq)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/pane/w1%3Ap1/agent?host=desk&session=work", request.path)
        assertEquals("Bearer bearer-secret", request.getHeader("Authorization"))
        assertEquals(null, request.getHeader("X-Collie-Seen"))
    }

    @Test
    fun agentIdentityAcceptsKnownStatusWithAllOptionalFactsUnknown() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(
            """{"ok":true,"paneId":"w1:p1","agent":"codex","status":"working","stateAuthority":{"observed":false,"source":null},"session":null,"interactiveReady":null,"launchPending":null,"stateChangeSeq":null,"completionSeq":null}""",
        ))
        val result = api.paneAgentIdentity(Connection(origin, "phone", "token"), PaneAddress(Scope(), "w1:p1"))
        assertTrue(result is ApiResult.Success)
        val identity = (result as ApiResult.Success).value
        assertEquals(AgentStatus.WORKING, identity.status)
        assertNull(identity.session)
        assertNull(identity.interactiveReady)
        assertNull(identity.launchPending)
        assertNull(identity.stateChangeSeq)
        assertNull(identity.completionSeq)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun agentIdentityRejectsAnotherPaneOrInferredAuthority() = runBlocking {
        val address = PaneAddress(Scope(), "w1:p1")
        server.enqueue(MockResponse().setBody("""{"ok":true,"paneId":"w1:p2","status":"working","stateAuthority":{"observed":false,"source":null}}"""))
        assertTrue(api.paneAgentIdentity(Connection(origin, "phone", "token"), address) is ApiResult.Failure)
        server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"ok":true,"paneId":"w1:p1","status":"working","stateAuthority":{"observed":true,"source":"codex"}}"""))
        assertTrue(api.paneAgentIdentity(Connection(origin, "phone", "token"), address) is ApiResult.Failure)
    }

    @Test
    fun agentIdentityRejectsSequenceAboveUint64Maximum() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(
            """{"ok":true,"paneId":"w1:p1","status":"working","stateAuthority":{"observed":false,"source":null},"stateChangeSeq":"18446744073709551616"}""",
        ))
        assertTrue(api.paneAgentIdentity(Connection(origin, "phone", "token"), PaneAddress(Scope(), "w1:p1")) is ApiResult.Failure)
        server.takeRequest()
    }

    @Test
    fun paneProcessDetailsRejectAnotherPaneAndUnboundedRows() = runBlocking {
        val address = PaneAddress(Scope(), "w1:p1")
        server.enqueue(MockResponse().setBody("""{"ok":true,"paneId":"w1:p2","processes":[]}"""))
        assertTrue(api.paneProcesses(Connection(origin, "phone", "token"), address) is ApiResult.Failure)
        server.takeRequest()
        val tooMany = (0..64).joinToString(",") { "{\"pid\":$it,\"name\":\"proc\"}" }
        server.enqueue(MockResponse().setBody("""{"ok":true,"paneId":"w1:p1","processes":[$tooMany]}"""))
        assertTrue(api.paneProcesses(Connection(origin, "phone", "token"), address) is ApiResult.Failure)
        val tooLarge = "x".repeat(231 * 1024)
        server.enqueue(MockResponse().setBody("""{"ok":true,"paneId":"w1:p1","processes":[{"pid":1,"name":"node","cmdline":"$tooLarge"}]}"""))
        assertTrue(api.paneProcesses(Connection(origin, "phone", "token"), address) is ApiResult.Failure)
    }

    @Test
    fun paneProcessDetailsRejectsAnOversizedChunkedWireBodyBeforeJsonDecoding(): Unit = runBlocking {
        val address = PaneAddress(Scope(), "w1:p1")
        val oversized = """{"ok":true,"paneId":"w1:p1","processes":[],"padding":"${"x".repeat(1_000_100)}"}"""
        server.enqueue(MockResponse().setChunkedBody(oversized, 4096))
        val result = api.paneProcesses(Connection(origin, "phone", "token"), address)
        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiFailure.Protocol)
    }

    @Test
    fun paneChangesListingUsesOrdinaryScopedReadAndParsesOpaqueNestedPaths(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"paneId":"w1:p1","workspaceId":"w1","workspaceLabel":"Project","paneRepo":"repo","available":true,"root":"/work/project","repos":[{"relPath":".","name":"repo","files":[{"path":"src/a b.kt","status":"M","added":2,"removed":1,"binary":false,"oldPath":null}]}],"truncated":false,"depthLimited":false,"clean":[]}""",
        ))
        val target = ChangesTarget.Pane("w1:p1")
        val result = api.changes(Connection(origin, "phone", "token"), target, Scope(host = "peer", session = "work"), ChangesLookup(4, false))
        val value = (result as ApiResult.Success).value
        val listing = value.state as ChangesListState.Available
        assertEquals("w1:p1", (value.target as ChangesTarget.Pane).id)
        assertEquals("Project", value.workspaceLabel)
        assertEquals("repo", value.paneRepo)
        assertEquals("src/a b.kt", listing.repos.single().files.single().path)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/pane/w1%3Ap1/changes?depth=4&nested=0&host=peer&session=work", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertNull(request.getHeader("Origin"))
        assertNull(request.getHeader("X-Collie-Seen"))
    }

    @Test
    fun chunkedOversizedChangesResponseIsRejectedBeforeJsonParsing(): Unit = runBlocking {
        val limit = 5 * 1024 * 1024
        val payload = "x".repeat(limit + 1)
        server.enqueue(MockResponse().setChunkedBody(payload, 16 * 1024))
        val http1Client = trustedHttpClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool())
            .build()
        val boundedApi = CollieApiClient(http1Client, Json { ignoreUnknownKeys = true; encodeDefaults = false })

        val result = boundedApi.changes(
            Connection(origin, "phone", "token"),
            ChangesTarget.Workspace("w"),
            Scope(),
            ChangesLookup(),
        )

        assertEquals(
            ApiFailure.Protocol("Changes response exceeds the size limit"),
            (result as ApiResult.Failure).error,
        )
        val request = server.takeRequest()
        assertEquals("/api/workspace/w/changes?depth=2&nested=1", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun changesDiffAndCommitRoutesEncodeRepoAndPathWithoutChangingTheirIdentity(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"workspaceId":"work/1","workspaceLabel":"Work","available":true,"repo":"nested/repo","path":"dir/a b.kt","oldPath":"dir/old.kt","status":"R","binary":false,"directory":false,"truncated":false,"diff":"-old\n+new\n"}""",
        ))
        val target = ChangesTarget.Workspace("work/1")
        val connection = Connection(origin, "phone", "token")
        val diff = api.changeDiff(connection, target, Scope(), ChangesLookup(), "nested/repo", "dir/a b.kt")
        val diffValue = ((diff as ApiResult.Success).value.state as ChangeDiffState.Available)
        assertEquals("nested/repo", diffValue.repo)
        assertEquals("dir/a b.kt", diffValue.path)
        assertEquals("dir/old.kt", diffValue.oldPath)
        assertEquals(ChangeStatus.RENAMED, diffValue.status)
        val diffRequest = server.takeRequest()
        assertEquals("/api/workspace/work%2F1/changes?depth=2&nested=1&repo=nested%2Frepo&path=dir%2Fa%20b.kt", diffRequest.path)

        server.enqueue(MockResponse().setBody(
            """{"workspaceId":"work/1","workspaceLabel":"Work","available":true,"repo":"nested/repo","path":"dir/a b.kt","oldPath":null,"status":"M","binary":false,"directory":false,"truncated":false,"hash":"0123456789abcdef0123456789abcdef01234567","diff":"+line\n"}""",
        ))
        val commit = api.changeCommitDiff(connection, target, Scope(), ChangesLookup(3, true), "nested/repo", "dir/a b.kt")
        val commitValue = ((commit as ApiResult.Success).value.state as ChangeDiffState.Available)
        assertEquals("0123456789abcdef0123456789abcdef01234567", commitValue.commitHash)
        val commitRequest = server.takeRequest()
        assertEquals("/api/workspace/work%2F1/changes?depth=3&nested=1&repo=nested%2Frepo&path=dir%2Fa%20b.kt&view=commit", commitRequest.path)
    }

    @Test
    fun commitHistoryPagesAreScopedBoundedAndPinTheFirstHead(): Unit = runBlocking {
        val firstHash = "0123456789abcdef0123456789abcdef01234567"
        val secondHash = "1123456789abcdef0123456789abcdef01234567"
        val pageOne = """{"workspaceId":"w","available":true,"repo":"nested/repo","name":"repo","headHash":"$firstHash","offset":0,"limit":2,"commits":[{"hash":"$firstHash","shortHash":"0123456","subject":"new","author":"A","time":1700000000},{"hash":"$secondHash","shortHash":"1123456","subject":"older","author":"B","time":1690000000}],"nextOffset":2,"truncated":false}"""
        server.enqueue(MockResponse().setBody(pageOne))
        val target = ChangesTarget.Workspace("w")
        val connection = Connection(origin, "phone", "token")
        val first = api.changeCommitHistory(connection, target, Scope(host = "peer", session = "work"), ChangesLookup(), "nested/repo", null, 0, 2)
        val firstPage = ((first as ApiResult.Success).value.state as ChangeCommitHistoryState.Available).page
        assertEquals(firstHash, firstPage.headHash)
        assertEquals(2, firstPage.nextOffset)
        assertEquals("/api/workspace/w/changes?depth=2&nested=1&view=history&repo=nested%2Frepo&offset=0&limit=2&host=peer&session=work", server.takeRequest().path)

        server.enqueue(MockResponse().setBody(
            """{"workspaceId":"w","available":true,"repo":"nested/repo","name":"repo","headHash":"$firstHash","offset":2,"limit":2,"commits":[{"hash":"2123456789abcdef0123456789abcdef01234567","shortHash":"2123456","subject":"oldest","author":"C","time":1680000000}],"nextOffset":null,"truncated":false}""",
        ))
        val next = api.changeCommitHistory(connection, target, Scope(), ChangesLookup(), "nested/repo", firstPage.headHash, firstPage.nextOffset!!, 2)
        val nextPage = ((next as ApiResult.Success).value.state as ChangeCommitHistoryState.Available).page
        assertEquals(firstPage.headHash, nextPage.headHash)
        assertEquals(2, nextPage.offset)
        assertEquals("/api/workspace/w/changes?depth=2&nested=1&view=history&repo=nested%2Frepo&offset=2&limit=2&head=$firstHash", server.takeRequest().path)
        assertTrue((firstPage.commits + nextPage.commits).map { it.hash }.toSet().size == 3)

        server.enqueue(MockResponse().setBody(
            """{"workspaceId":"w","available":true,"repo":"nested/repo","name":"repo","commit":{"hash":"$secondHash","shortHash":"1123456","subject":"older","author":"B","time":1690000000},"files":[],"truncated":false}""",
        ))
        val detail = api.changeCommit(connection, target, Scope(), ChangesLookup(), "nested/repo", secondHash)
        assertTrue((detail as ApiResult.Success).value.state is ChangeCommitState.Available)
        assertEquals("/api/workspace/w/changes?depth=2&nested=1&view=commit&repo=nested%2Frepo&commit=$secondHash", server.takeRequest().path)
    }

    @Test
    fun historyRejectsCrossingCapAndFalseTruncationButAcceptsRealTerminalPages(): Unit = runBlocking {
        val head = "f".repeat(40)
        val connection = Connection(origin, "phone", "token")
        val target = ChangesTarget.Workspace("w")
        suspend fun page(offset: Int, count: Int, limit: Int, truncated: Boolean, next: Int? = null): ApiResult<ChangeCommitHistoryAnswer> {
            val rows = (1..count).joinToString(",") { n ->
                val hash = n.toString(16).padStart(40, '0')
                """{"hash":"$hash","shortHash":"${hash.take(7)}","subject":"row$n","author":"A","time":1}"""
            }
            server.enqueue(MockResponse().setBody("""{"workspaceId":"w","available":true,"repo":".","name":"r","headHash":"$head","offset":$offset,"limit":$limit,"commits":[$rows],"nextOffset":${next ?: "null"},"truncated":$truncated}"""))
            val result = api.changeCommitHistory(connection, target, Scope(), ChangesLookup(), ".", head, offset, limit)
            server.takeRequest()
            return result
        }
        assertTrue(page(9990, 50, 50, false) is ApiResult.Failure)
        assertTrue(page(0, 1, 1, true) is ApiResult.Failure)
        assertTrue(page(9990, 10, 50, true, 10000) is ApiResult.Failure)
        assertTrue(page(9990, 10, 50, false, 10000) is ApiResult.Failure)
        assertTrue(page(3, 2, 1, false) is ApiResult.Failure)
        assertTrue(page(3, 1, 2, false, 4) is ApiResult.Failure)
        assertTrue(page(9990, 10, 50, true) is ApiResult.Success)
        assertTrue(page(9999, 1, 50, true) is ApiResult.Success)
        assertTrue(page(9990, 10, 50, false) is ApiResult.Success)
        assertTrue(page(3, 1, 2, false) is ApiResult.Success)
    }

    @Test
    fun maximumEscapedRealBridgeHistoryPagePassesChunkedNativeTransport(): Unit = runBlocking {
        val body = requireNotNull(javaClass.classLoader?.getResource("changes-history-upper-bound.json")).readText()
        assertTrue(body.toByteArray(Charsets.UTF_8).size < 1024 * 1024)
        val tree = Json.parseToJsonElement(body)
        val rows = tree.jsonObject.getValue("commits").jsonArray
        assertEquals(50, rows.size)
        assertTrue(rows.all { it.jsonObject.getValue("subject").jsonPrimitive.content.length == 500 })
        assertTrue(rows.all { it.jsonObject.getValue("author").jsonPrimitive.content.length == 1024 })
        assertTrue("Real bridge fixture failed direct typed parser", ChangesWire.history(tree, ChangesTarget.Workspace("w"), ".", null, 0, 50) != null)
        // MockWebServer 4.12's setChunkedBody pre-encodes HTTP/1 chunk framing. Use the
        // existing HTTP/1 fixture client for it; HTTP/2 DATA frames use setBody below.
        server.enqueue(MockResponse().setChunkedBody(body, 4096))
        val result = boundaryHttp1Api.changeCommitHistory(Connection(origin, "phone", "token"), ChangesTarget.Workspace("w"), Scope(), ChangesLookup(), ".", null, 0, 50)
        val failureKind = (result as? ApiResult.Failure)?.error?.let { error ->
            if (error is ApiFailure.Protocol) error.message else error.javaClass.simpleName
        }
        assertTrue("Real bridge history transport failed: $failureKind", result is ApiResult.Success)
        val page = ((result as ApiResult.Success).value.state as ChangeCommitHistoryState.Available).page
        assertEquals(50, page.commits.size)
        assertTrue(page.commits.all { it.author == "\u0001".repeat(1024) && it.subject == "\u0002".repeat(500) })
        assertEquals(50, page.commits.map { it.hash }.toSet().size)
        assertNull(page.nextOffset)
        assertFalse(page.truncated)
        assertEquals("/api/workspace/w/changes?depth=2&nested=1&view=history&repo=.&offset=0&limit=50", server.takeRequest().path)
        server.enqueue(MockResponse().setBody(body))
        val http2 = api.changeCommitHistory(Connection(origin, "phone", "token"), ChangesTarget.Workspace("w"), Scope(), ChangesLookup(), ".", null, 0, 50)
        assertTrue("Real bridge page must also pass native HTTP/2 transport", http2 is ApiResult.Success)
        assertEquals(page, ((http2 as ApiResult.Success).value.state as ChangeCommitHistoryState.Available).page)
        assertEquals("/api/workspace/w/changes?depth=2&nested=1&view=history&repo=.&offset=0&limit=50", server.takeRequest().path)
    }

    @Test
    fun historyParserRejectsDuplicateRowsAndChangedPinnedHead(): Unit = runBlocking {
        val head = "0123456789abcdef0123456789abcdef01234567"
        val body = """{"workspaceId":"w","available":true,"repo":".","name":"r","headHash":"$head","offset":2,"limit":1,"commits":[{"hash":"$head","shortHash":"0123456","subject":"x","author":"A","time":1}],"nextOffset":3,"truncated":false}"""
        server.enqueue(MockResponse().setBody(body))
        val result = api.changeCommitHistory(Connection(origin, "phone", "token"), ChangesTarget.Workspace("w"), Scope(), ChangesLookup(), ".", "1123456789abcdef0123456789abcdef01234567", 2, 1)
        assertEquals(ApiFailure.Protocol("Malformed Changes history response"), (result as ApiResult.Failure).error)
        server.takeRequest()
    }

    @Test
    fun changesCommitAndUnavailableShapesAreTypedAndMalformedSuccessFailsClosed(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"workspaceId":"w","available":true,"repo":".","name":"root","commit":{"hash":"0123456789abcdef0123456789abcdef01234567","shortHash":"0123456","subject":"update","author":"A","time":1700000000},"files":[{"path":"a.txt","status":"A","added":3,"removed":0,"binary":false}],"truncated":false}""",
        ))
        val connection = Connection(origin, "phone", "token")
        val target = ChangesTarget.Workspace("w")
        val result = api.changeCommit(connection, target, Scope(), ChangesLookup(), ".")
        val commit = ((result as ApiResult.Success).value.state as ChangeCommitState.Available)
        assertEquals("0123456", commit.commit.shortHash)
        assertEquals(3L, commit.files.single().added)
        server.takeRequest()

        server.enqueue(MockResponse().setBody("""{"workspaceId":"w","available":false,"reason":"no-git"}"""))
        assertEquals("no-git", ((api.changes(connection, target, Scope(), ChangesLookup()) as ApiResult.Success).value.state as ChangesListState.Unavailable).reason)
        assertEquals("/api/workspace/w/changes?depth=2&nested=1", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val legacy = api.changes(connection, target, Scope(), ChangesLookup()) as ApiResult.Failure
        assertEquals(404, (legacy.error as ApiFailure.Http).status)
        server.takeRequest()

        server.enqueue(MockResponse().setBody("""{"workspaceId":"w","available":true,"repo":"wrong","path":"a.txt","status":"M","binary":false,"directory":false,"truncated":false,"diff":"+bad"}"""))
        val malformed = api.changeDiff(connection, target, Scope(), ChangesLookup(), ".", "a.txt")
        assertEquals(ApiFailure.Protocol("Malformed Changes diff response"), (malformed as ApiResult.Failure).error)
        server.takeRequest()
        Unit
    }

    @Test
    fun guardedQueuedSendPostsOnlyDeclaredKeysAndOpaqueQueueIdentity() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        val connection = Connection(origin, "phone", "bearer-secret")
        val guard = QueuedSendGuard(7, 12, "a".repeat(64), "b".repeat(64))
        assertTrue(api.queuedSendNow(
            connection,
            PaneAddress(Scope(host = "peer", session = "work"), "w1:p1"),
            listOf("ctrl+Enter"),
            "› ",
            guard,
        ) is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/pane/w1%3Ap1/keys?host=peer&session=work", request.path)
        assertEquals("Bearer bearer-secret", request.getHeader("Authorization"))
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"queued_send\""))
        assertTrue(body.contains("\"queue_digest\":\"${"a".repeat(64)}\""))
        assertTrue(body.contains("\"occupant\":\"${"b".repeat(64)}\""))
        assertTrue(body.contains("\"keys\":[\"ctrl+Enter\"]"))
        assertFalse(body.contains("queued message"))
    }

    @Test
    fun changesDiffPreservesBinaryAndTruncatedAvailabilityFlags(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"paneId":"p","available":true,"repo":".","path":"archive.bin","oldPath":null,"status":"M","binary":true,"directory":false,"truncated":true,"diff":""}""",
        ))
        val result = api.changeDiff(
            Connection(origin, "phone", "token"),
            ChangesTarget.Pane("p"),
            Scope(),
            ChangesLookup(),
            ".",
            "archive.bin",
        )
        val state = ((result as ApiResult.Success).value.state as ChangeDiffState.Available)
        assertTrue(state.binary)
        assertTrue(state.truncated)
        assertEquals("archive.bin", state.path)
        server.takeRequest()
        Unit
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
            val body = Json.parseToJsonElement(request.body.readUtf8()) as kotlinx.serialization.json.JsonObject
            assertEquals("feature/native", (body["branch"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertTrue((body["requestId"] as kotlinx.serialization.json.JsonPrimitive).content.isNotBlank())
            assertEquals(setOf("branch", "requestId"), body.keys)
        }
        assertEquals("/api/tab/w1%3At2/close?host=desk&session=work", server.takeRequest().path)
    }

    @Test
    fun frozenWorkspaceWorktreePreservesCallerBodyAndTargetRoute() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(createdPaneResponse()))
        val connection = Connection(origin, "phone", "token")
        val scope = Scope(host = "desk", session = "work")
        val frozen = "{\"branch\":\"feature/native\",\"requestId\":\"55555555-5555-4555-8555-555555555555\"}"

        api.createWorktreeFrozen(connection, scope, "w1", frozen)

        server.takeRequest().also { request ->
            assertEquals("/api/workspace/w1/worktree?host=desk&session=work", request.path)
            assertEquals(frozen, request.body.readUtf8())
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }
        Unit
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
    fun anUnchangedTranscriptPageComesBackAsNotModified() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"page-1\""))

        val result = api.history(
            Connection(origin, "phone", "token"),
            PaneAddress(paneId = "w1:p1"),
            limit = 60,
            etag = "\"page-1\"",
        )

        assertEquals("\"page-1\"", (result as ApiResult.NotModified).etag)
        assertEquals("\"page-1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun aTranscriptReadWithoutAnETagSendsNoValidator() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setHeader("ETag", "\"page-2\"")
                .setBody("""{"paneId":"w1:p1","available":true,"entries":[]}"""),
        )

        val result = api.history(Connection(origin, "phone", "token"), PaneAddress(paneId = "w1:p1"), limit = 60)

        assertEquals("\"page-2\"", (result as ApiResult.Success).etag)
        assertNull(server.takeRequest().getHeader("If-None-Match"))
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
    fun cacheCatalogUsesItsEtagAndWatchCallsKeepTheExactHostSessionPaneAddress() = runBlocking<Unit> {
        val mounted = Connection(CollieOrigin(server.url("/base/").toString()), "phone", "token")
        server.enqueue(jsonResponse("""{"rules":[]}""").setHeader("ETag", "\"cache-v1\""))
        val catalog = api.cacheRules(mounted)
        assertEquals("\"cache-v1\"", (catalog as ApiResult.Success).etag)
        assertEquals("/base/api/cache-rules", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"cache-v1\""))
        assertEquals("\"cache-v1\"", (api.cacheRules(mounted, "\"cache-v1\"") as ApiResult.NotModified).etag)
        assertEquals("\"cache-v1\"", server.takeRequest().getHeader("If-None-Match"))

        val address = PaneAddress(Scope(host = "peer-east", session = "work"), "w2:p7")
        server.enqueue(jsonResponse("""{"on":false,"global":true,"watchable":true,"warnSeconds":120}"""))
        assertTrue(api.cacheWatch(mounted, address) is ApiResult.Success)
        server.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals("/base/api/notifications/cache-watch", request.requestUrl?.encodedPath)
            assertEquals("w2:p7", request.requestUrl?.queryParameter("pane"))
            assertEquals("peer-east", request.requestUrl?.queryParameter("host"))
            assertEquals("work", request.requestUrl?.queryParameter("session"))
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }

        server.enqueue(jsonResponse("""{"on":true,"global":false,"watchable":true,"warnSeconds":120}"""))
        assertTrue(api.setCacheWatch(mounted, address, true) is ApiResult.Success)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/base/api/notifications/cache-watch", request.requestUrl?.encodedPath)
            assertEquals("w2:p7", request.requestUrl?.queryParameter("pane"))
            assertEquals("peer-east", request.requestUrl?.queryParameter("host"))
            assertEquals("work", request.requestUrl?.queryParameter("session"))
            assertEquals("{\"on\":true}", request.body.readUtf8())
            assertEquals(mounted.origin.headerValue, request.getHeader("Origin"))
        }

        server.enqueue(jsonResponse("""{"entries":[{"id":"opaque","label":"agent","host":"peer-east","session":"work","paneId":"w2:p7"}]}"""))
        assertTrue(api.cacheWatchList(mounted) is ApiResult.Success)
        assertEquals("/base/api/notifications/cache-watch/list", server.takeRequest().path)

        server.enqueue(jsonResponse("""{"entries":[]}"""))
        assertTrue(api.forgetCacheWatch(mounted, "opaque") is ApiResult.Success)
        server.takeRequest().also { request ->
            assertEquals("/base/api/notifications/cache-watch/forget", request.path)
            assertEquals("{\"id\":\"opaque\"}", request.body.readUtf8())
        }
    }

    @Test
    fun cacheWatchListRequiresAnEntriesArrayAndDistinguishesMalformedFromEmpty() = runBlocking<Unit> {
        val connection = Connection(origin, "phone", "token")
        for (body in listOf("{}", "{\"entries\":null}", "{\"entries\":\"not-an-array\"}")) {
            server.enqueue(jsonResponse(body))
            val result = api.cacheWatchList(connection)
            assertTrue("Expected a typed protocol refusal for $body", result is ApiResult.Failure && result.error is ApiFailure.Protocol)
            server.takeRequest()
        }

        server.enqueue(jsonResponse("{\"entries\":[]}"))
        val empty = api.cacheWatchList(connection)
        assertTrue(empty is ApiResult.Success)
        assertTrue((empty as ApiResult.Success).value.entries.isEmpty())
        server.takeRequest()
    }

    @Test
    fun machineAndCachePreferencesAreAnAdditivePartialPatch() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"blocked":true,"done":false,"updates":true,"machines":false,"cache":true}"""))
        val connection = Connection(origin, "phone", "token")

        val result = api.setNotificationPreferences(connection, NotifyPreferencesPatch(machines = false, cache = true))

        assertEquals(false, (result as ApiResult.Success).value.machines)
        assertEquals(true, result.value.cache)
        server.takeRequest().also { request ->
            assertEquals("/api/notifications/prefs", request.path)
            assertEquals("{\"cache\":true,\"machines\":false}", request.body.readUtf8())
        }
    }

    @Test
    fun updateDismissalUsesMountedReadRouteAndDistinctOfferAndCrewScopes(): Unit = runBlocking {
        val mounted = Connection(CollieOrigin(server.url("/mount/").toString()), "phone", "token")
        server.enqueue(jsonResponse(updateInfoResponse(dismissed = "1.6.0")))
        server.enqueue(jsonResponse(updateInfoResponse(dismissedCrew = "1.7.0")))

        assertEquals("1.6.0", (api.dismissUpdate(mounted, "1.6.0", UpdateDismissalScope.OFFER) as ApiResult.Success).value.dismissedVersion)
        assertEquals("1.7.0", (api.dismissUpdate(mounted, "1.7.0", UpdateDismissalScope.CREW) as ApiResult.Success).value.dismissedCrewVersion)

        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/mount/api/update/dismiss", request.path)
            assertEquals("{\"version\":\"1.6.0\",\"scope\":\"offer\"}", request.body.readUtf8())
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals(mounted.origin.headerValue, request.getHeader("Origin"))
        }
        server.takeRequest().also { request ->
            assertEquals("/mount/api/update/dismiss", request.path)
            assertEquals("{\"version\":\"1.7.0\",\"scope\":\"crew\"}", request.body.readUtf8())
            assertEquals(mounted.origin.headerValue, request.getHeader("Origin"))
        }
        Unit
    }

    @Test
    fun standbyUpdateReadsThePublicFailoverRouteWithoutThePairingCredential() = runBlocking {
        server.enqueue(jsonResponse(updateRunResponse("restarting")))

        val result = api.standbyUpdate(Connection(origin, "phone", "token"))

        assertEquals("restarting", (result as ApiResult.Success).value.state)
        server.takeRequest().also { request ->
            assertEquals("/standby/update", request.path)
            assertNull(request.getHeader("Authorization"))
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
    fun documentUploadIsScopedOneShotAndDeletesItsPrivateSpoolAfter413() = runBlocking {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(413).setBody("too large"))
        val spool = java.io.File.createTempFile("collie-upload-test-", ".part").apply { writeText("safe brief") }
        val source = UploadSource.Document("brief.txt", "text/plain", spool, spool.length())

        val result = api.upload(
            Connection(origin, "phone", "token"),
            PaneAddress(Scope(host = "desk", session = "main"), "w1:p1"),
            source,
        )

        assertTrue(result is ApiResult.Failure)
        assertFalse(spool.exists())
        try {
            source.openOnce()
            fail("refused request must not become replayable")
        } catch (_: IllegalStateException) { }
        server.takeRequest().also { request ->
            assertEquals("/api/pane/w1%3Ap1/upload?host=desk&session=main", request.path)
            assertTrue(request.body.readUtf8().contains("brief.txt"))
            assertTrue(request.body.readUtf8().isEmpty())
        }
        assertEquals(1, server.requestCount)
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

    @Test
    fun crewUsesTheRenamedEndpointAndDecodesTheCrewIdentity() = runBlocking {
        server.enqueue(jsonResponse("""{"crew":{"id":"c","name":"Lab","secretGeneration":1,"rotatedAt":0},"self":{"id":"lead","name":"Lead","version":"1.19.0"},"deputy":null,"members":[],"ts":1}"""))
        val result = api.crew(Connection(origin, "phone", "token")) as ApiResult.Success
        assertEquals("Lab", result.value.crew.name)
        assertEquals("/api/crew", server.takeRequest().path)
    }

    @Test
    fun mountedConfigRequestsKeepBearerScopeAndAnAuthorityOnlyOriginForWrites() = runBlocking {
        val mounted = Connection(CollieOrigin(server.url("/tools/collie/").toString()), "phone", "token")
        server.enqueue(jsonResponse("""{"mux":{"name":"tmux","capabilities":{"createWorktree":false}},"redact":true}"""))
        val config = api.config(mounted, Scope(host = "peer", session = "work")) as ApiResult.Success
        assertEquals(false, config.value.mux?.supports("createWorktree"))
        val read = server.takeRequest()
        assertEquals("/tools/collie/api/config?host=peer&session=work", read.path)
        assertEquals("Bearer token", read.getHeader("Authorization"))
        assertEquals("XMLHttpRequest", read.getHeader("X-Requested-With"))
        server.enqueue(jsonResponse("""{"ok":true}"""))
        api.reply(mounted, PaneAddress(paneId = "p"), "hi")
        val write = server.takeRequest()
        assertEquals("/tools/collie/api/pane/p/reply", write.path)
        assertEquals(origin.headerValue, write.getHeader("Origin"))
    }

    @Test
    fun liveChatUsesGenerationAndRevisionWhileOlderPagesUseTheTurnCursor() = runBlocking {
        val connection = Connection(origin, "phone", "token")
        val pane = PaneAddress(Scope(host = "peer", session = "work"), "p")
        server.enqueue(jsonResponse("""{"paneId":"p","available":true,"page":"live","gen":2,"rev":3,"head":1,"oldest":1,"hasOlder":false,"upserts":[{"uuid":"u","ts":"","role":"assistant","seq":1,"parts":[]}],"queued":["pending"],"sendQueuedNow":["ctrl+Enter"],"queuedDigest":"${"a".repeat(64)}","queuedSendOccupant":"${"b".repeat(64)}"}""").setHeader("ETag", "live-3"))
        val first = api.chat(connection, pane, etag = "must-not-send") as ApiResult.Success
        assertEquals(listOf("ctrl+Enter"), first.value.sendQueuedNow)
        assertEquals("a".repeat(64), first.value.queuedDigest)
        assertEquals("b".repeat(64), first.value.queuedSendOccupant)
        val initial = server.takeRequest()
        assertNull(initial.getHeader("If-None-Match"))
        assertEquals("1", initial.getHeader("X-Collie-Seen"))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "live-3"))
        assertTrue(api.chat(connection, pane, after = ChatAfter(2, 3), etag = first.etag) is ApiResult.NotModified)
        val poll = server.takeRequest()
        assertEquals("/api/pane/p/chat?limit=200&after=2%3A3&host=peer&session=work", poll.path)
        assertEquals("live-3", poll.getHeader("If-None-Match"))
        server.enqueue(jsonResponse("""{"paneId":"p","available":true,"page":"older","gen":2,"hasOlder":false,"upserts":[]}"""))
        api.chat(connection, pane, before = ChatBefore(1, "u:a"), etag = "must-not-send")
        val older = server.takeRequest()
        assertEquals("/api/pane/p/chat?limit=200&before=1%3Au%3Aa&host=peer&session=work", older.path)
        assertNull(older.getHeader("If-None-Match"))
    }

    @Test
    fun malformedQueuedSendIdentityIsATypedRefusalBeforeAnyNetworkRequest() = runBlocking {
        val result = api.queuedSendNow(
            Connection(origin, "phone", "token"),
            PaneAddress(paneId = "p"),
            listOf("ctrl+Enter"),
            expectedPrompt = "prompt",
            guard = QueuedSendGuard(2, 3, "malformed", "b".repeat(64)),
        )

        assertEquals(ApiFailure.Protocol("Queued send action is malformed"), (result as ApiResult.Failure).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun actualLegacyLiveChatWithoutRevisionRemainsDisplayOnlyAndClearsTheCursor(): Unit = runBlocking {
        val connection = Connection(CollieOrigin(server.url("/tools/collie/").toString()), "phone", "token")
        val pane = PaneAddress(Scope(host = "peer", session = "work"), "p")
        server.enqueue(jsonResponse("""{"paneId":"p","available":true,"page":"live","gen":2,"head":1,"oldest":1,"upserts":[{"uuid":"legacy","ts":"","role":"assistant","seq":1,"parts":[]}],"queued":["pending"],"sendQueuedNow":["ctrl+Enter"],"queuedDigest":"${"a".repeat(64)}","queuedSendOccupant":"${"b".repeat(64)}"}""").setHeader("ETag", "legacy"))
        val result = api.chat(connection, pane, after = ChatAfter(2, 3), etag = "accepted") as ApiResult.Success
        val request = server.takeRequest()
        assertEquals("/tools/collie/api/pane/p/chat?limit=200&after=2%3A3&host=peer&session=work", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertNull(result.value.rev)
        val window = com.lateapex.collie.ui.LiveChatWindow()
        assertTrue(window.merge(result.value))
        assertEquals(listOf("legacy"), window.entries.map { it.uuid })
        assertEquals(listOf("pending"), window.queued)
        assertNull(window.sendNow)
        assertNull(window.after)

        server.enqueue(jsonResponse("""{"paneId":"p","available":true,"page":"live","gen":2,"rev":4,"head":1,"oldest":1,"upserts":[]}"""))
        assertTrue(api.chat(connection, pane, after = window.after, etag = result.etag) is ApiResult.Success)
        val next = server.takeRequest()
        assertFalse(next.path!!.contains("after="))
        assertNull(next.getHeader("If-None-Match"))
        Unit
    }

    @Test
    fun actualLegacyChatStillRequiresNonnegativeGenerationAndNumberedTurns(): Unit = runBlocking {
        val malformed = listOf(
            """{"gen":2,"head":1,"oldest":1,"upserts":[{"uuid":"u","ts":"","role":"assistant","parts":[]}]}""",
            """{"head":1,"oldest":1,"upserts":[]}""",
            """{"gen":2,"oldest":1,"upserts":[]}""",
            """{"gen":2,"head":1,"upserts":[]}""",
            """{"gen":-1,"head":1,"oldest":1,"upserts":[]}""",
            """{"gen":2,"head":-1,"oldest":1,"upserts":[]}""",
            """{"gen":2,"head":1,"oldest":-1,"upserts":[]}""",
            """{"gen":2,"rev":-1,"head":1,"oldest":1,"upserts":[]}""",
            """{"gen":2,"head":1,"oldest":1,"upserts":[{"uuid":"u","ts":"","role":"assistant","seq":-1,"parts":[]}]}""",
        )
        for (fields in malformed) {
            server.enqueue(jsonResponse("""{"paneId":"p","available":true,"page":"live",${fields.drop(1)}"""))
            val result = api.chat(Connection(origin, "phone", "token"), PaneAddress(paneId = "p"))
            assertTrue(result is ApiResult.Failure && result.error is ApiFailure.Protocol)
            assertEquals("GET", server.takeRequest().method)
        }
        assertEquals(malformed.size, server.requestCount)
        Unit
    }

    @Test
    fun malformedLiveChatCannotReplaceTheThreadWithAnUnnumberedTurn() = runBlocking {
        server.enqueue(jsonResponse("""{"paneId":"p","available":true,"page":"live","gen":2,"rev":3,"head":1,"oldest":1,"upserts":[{"uuid":"u","ts":"","role":"assistant","parts":[]}]}"""))
        assertTrue(api.chat(Connection(origin, "phone", "token"), PaneAddress(paneId = "p")) is ApiResult.Failure)
    }

    @Test
    fun launchByIdCarriesTheSameIntentIdAndDoesNotRetryAWrittenRequest() = runBlocking {
        server.enqueue(jsonResponse(createdPaneResponse()))
        api.startLaunch(Connection(origin, "phone", "token"), Scope(host = "peer"), StartTarget(harness = "codex"), "intent-1", "/repo")
        val request = server.takeRequest()
        assertEquals("/api/launch?host=peer", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).toString()
        assertTrue(body.contains("\"harness\":\"codex\""))
        assertTrue(body.contains("\"requestId\":\"intent-1\""))
        assertFalse(body.contains("command"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun folderReadsAndStarWritesStayOnTheSelectedMachineAndValidateBounds() = runBlocking {
        val connection = Connection(origin, "phone", "token")
        server.enqueue(jsonResponse("""{"home":"/srv/peer","recent":["/srv/peer/project"],"favourites":[]}"""))
        val folders = api.folders(connection, Scope(host = "peer", session = "work")) as ApiResult.Success
        assertEquals("/srv/peer", folders.value.home)
        assertEquals("/api/folders?host=peer&session=work", server.takeRequest().path)

        server.enqueue(jsonResponse("""{"home":"/srv/peer","recent":[],"favourites":["/srv/peer/project"]}"""))
        assertTrue(api.starFolder(connection, Scope(host = "peer"), "/srv/peer/project", true) is ApiResult.Success)
        val star = server.takeRequest()
        assertEquals("POST", star.method)
        assertEquals("/api/folders/star?host=peer", star.path)
        assertEquals(origin.headerValue, star.getHeader("Origin"))
        assertEquals("{\"folder\":\"/srv/peer/project\",\"starred\":true}", Json.parseToJsonElement(star.body.readUtf8()).toString())

        server.enqueue(jsonResponse("""{"home":"/srv/peer","recent":["/same"],"favourites":["/same"]}"""))
        assertTrue(api.folders(connection, Scope(host = "peer")) is ApiResult.Failure)
    }

    @Test
    fun legacyCrewFallbackIsReadOnlyAndDoesNotHideCurrentRoleRefusals() = runBlocking {
        val connection = Connection(origin, "phone", "token")
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(jsonResponse("""{"pack":{"id":"c","name":"Legacy","secretGeneration":1,"rotatedAt":0},"self":{"id":"lead","name":"Lead","version":"1.5.0"},"deputy":null,"members":[],"ts":1}"""))
        assertEquals("Legacy", (api.crew(connection) as ApiResult.Success).value.crew.name)
        assertEquals("/api/crew", server.takeRequest().path)
        assertEquals("/api/pack", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"error":"not lead","code":"crew.not_lead"}"""))
        assertTrue(api.crew(connection) is ApiResult.Failure)
        assertEquals("/api/crew", server.takeRequest().path)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun worktreeFolderPlanAndCreateUseTheCurrentBaseAndFolderContracts() = runBlocking {
        val connection = Connection(origin, "phone", "token")
        server.enqueue(jsonResponse("""{"ok":true,"repoRoot":"/repo","currentBranch":"topic","defaultBranch":"main","branchValid":true,"remembered":{"base":"current","folder":"parent","parent":"/trees"},"defaultTarget":{"path":"/default/topic","exists":false},"parentTarget":{"ok":true,"path":"/trees/topic"}}"""))
        val plan = api.planWorktree(connection, Scope(session = "work"), "/repo/sub", "next", "/trees") as ApiResult.Success
        assertEquals("topic", plan.value.currentBranch)
        assertEquals("/api/worktree/plan?cwd=%2Frepo%2Fsub&branch=next&parent=%2Ftrees&session=work", server.takeRequest().path)
        server.enqueue(jsonResponse("""{"ok":true,"pane":{"paneId":"p","workspaceId":"w","workspaceLabel":"next","tabId":"t","cwd":"/trees/next"},"launcherStarted":true,"replayed":false}"""))
        api.createWorktreeAt(connection, Scope(), "/repo/sub", "next", StartTarget(shell = true), "intent-tree", WorktreeBase("ref", "topic"), WorktreeFolder("parent", "/trees"))
        val created = server.takeRequest()
        assertEquals("/api/worktree", created.path)
        assertEquals(Json.parseToJsonElement("""{"cwd":"/repo/sub","branch":"next","base":{"kind":"ref","ref":"topic"},"folder":{"kind":"parent","parent":"/trees"},"requestId":"intent-tree","shell":true}"""), Json.parseToJsonElement(created.body.readUtf8()))
        server.enqueue(jsonResponse("""{"ok":true,"pane":{"paneId":"p","workspaceId":"w","workspaceLabel":"next","tabId":"t","cwd":"/default/next"}}"""))
        api.createWorktreeAt(connection, Scope(), "/repo", "next", StartTarget(shell = true), "intent-default")
        val defaultBody = server.takeRequest().body.readUtf8()
        assertTrue(defaultBody.contains("\"base\":{\"kind\":\"default\"}"))
        assertTrue(defaultBody.contains("\"folder\":{\"kind\":\"default\"}"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun frozenLauncherAddRequiresBoundRowsForRecipeAndTextVariants(): Unit = runBlocking {
        val connection = Connection(origin, "phone", "token")
        val recipeId = "11111111-1111-4111-8111-111111111111"
        val recipeBody = """{"requestId":"$recipeId","recipe":{"harness":"claude","options":[]}}"""
        server.enqueue(jsonResponse(
            """{"ok":true,"row":{"source":"added","id":"$recipeId","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
        ))
        assertTrue(api.addLauncherFrozen(connection, Scope(), recipeBody) is ApiResult.Success)
        val recipeRequest = server.takeRequest()
        assertEquals("/api/launchers/added", recipeRequest.path)
        assertEquals(recipeBody, recipeRequest.body.readUtf8())

        val textId = "22222222-2222-4222-8222-222222222222"
        val textBody = """{"requestId":"$textId","text":"python app.py","kind":"command"}"""
        server.enqueue(jsonResponse(
            """{"ok":true,"row":{"source":"added","id":"$textId","kind":"command","command":"python app.py","label":"python","noPrompts":false,"addedBy":"phone","addedAt":1700000000001,"addedAs":"text"}}""",
        ))
        assertTrue(api.addLauncherFrozen(connection, Scope(), textBody) is ApiResult.Success)
        val textRequest = server.takeRequest()
        assertEquals("/api/launchers/added", textRequest.path)
        assertEquals(textBody, textRequest.body.readUtf8())

        val agentId = "22222222-2222-4222-8222-222222222223"
        val agentBody = """{"requestId":"$agentId","text":"claude --continue","kind":"agent","harness":"claude"}"""
        server.enqueue(jsonResponse(
            """{"ok":true,"row":{"source":"added","id":"$agentId","kind":"agent","harness":"claude","command":"claude --continue","label":"claude","noPrompts":false,"addedBy":"phone","addedAt":1700000000002,"addedAs":"text"}}""",
        ))
        assertTrue(api.addLauncherFrozen(connection, Scope(), agentBody) is ApiResult.Success)
        val agentRequest = server.takeRequest()
        assertEquals("/api/launchers/added", agentRequest.path)
        assertEquals(agentBody, agentRequest.body.readUtf8())

        val recipeBinaries = linkedMapOf(
            "claude" to "claude", "codex" to "codex", "opencode" to "opencode",
            "pi" to "pi", "omp" to "omp", "grok" to "grok", "hermes" to "hermes",
            "muse" to "muse", "agy" to "agy",
        )
        for ((index, pair) in recipeBinaries.entries.withIndex()) {
            val (harness, executable) = pair
            val id = "33333333-3333-4333-8333-${(index + 1).toString().padStart(12, '0')}"
            val body = """{"requestId":"$id","recipe":{"harness":"$harness","options":[]}}"""
            val label = when (harness) {
                "claude" -> "Claude Code"
                "codex" -> "Codex"
                "grok" -> "Grok"
                "hermes" -> "Hermes"
                "muse" -> "Muse"
                "agy" -> "Antigravity"
                else -> harness
            }
            server.enqueue(jsonResponse(
                """{"ok":true,"row":{"source":"added","id":"$id","kind":"agent","harness":"$harness","command":"$executable","label":"$label","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
            ))
            val result = api.addLauncherFrozen(connection, Scope(), body)
            assertTrue("$harness recipe row should match its rendered executable", result is ApiResult.Success)
            server.takeRequest()
        }

        val codexId = "44444444-4444-4444-8444-444444444444"
        val codexBody = """{"requestId":"$codexId","recipe":{"harness":"codex","options":["never"]}}"""
        server.enqueue(jsonResponse(
            """{"ok":true,"row":{"source":"added","id":"$codexId","kind":"agent","harness":"codex","command":"codex --ask-for-approval never","label":"Codex, Never ask","noPrompts":true,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
        ))
        assertTrue(api.addLauncherFrozen(connection, Scope(), codexBody) is ApiResult.Success)
        server.takeRequest()

        val typedId = "77777777-7777-4777-8777-777777777777"
        val typedBody = """{"requestId":"$typedId","text":"codex -a never","kind":"command"}"""
        server.enqueue(jsonResponse(
            """{"ok":true,"row":{"source":"added","id":"$typedId","kind":"command","command":"codex -a never","label":"codex","noPrompts":true,"addedBy":"phone","addedAt":1700000000000,"addedAs":"text"}}""",
        ))
        assertTrue(api.addLauncherFrozen(connection, Scope(), typedBody) is ApiResult.Success)
        server.takeRequest()
        Unit
    }

    @Test
    fun frozenLauncherAddRejectsUnboundOrMalformedSuccessfulRows(): Unit = runBlocking {
        val connection = Connection(origin, "phone", "token")
        val requestId = "33333333-3333-4333-8333-333333333333"
        val body = """{"requestId":"$requestId","recipe":{"harness":"claude","options":[]}}"""
        val longCommand = "c".repeat(201)
        val invalidRows = listOf(
            """{"ok":true}""",
            """{"ok":true,"row":{"source":"added","id":"44444444-4444-4444-8444-444444444444","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"command","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"text"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"codex","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"$longCommand","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":"false","addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":1,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":"1700000000000","addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":-1,"addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":1700000000000.5,"addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"claude","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":9007199254740992,"addedAs":"recipe"}}""",
            """{"ok":true,"row":{"source":"added","id":"$requestId","kind":"agent","harness":"claude","command":"claude --continue","label":"Claude Code","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
        )
        for (response in invalidRows) {
            server.enqueue(jsonResponse(response))
            val result = api.addLauncherFrozen(connection, Scope(), body)
            assertEquals(ApiFailure.Protocol("Malformed launcher add response"), (result as ApiResult.Failure).error)
            server.takeRequest()
        }

        val codexId = "66666666-6666-4666-8666-666666666666"
        val codexBody = """{"requestId":"$codexId","recipe":{"harness":"codex","options":["never"]}}"""
        server.enqueue(jsonResponse(
            """{"ok":true,"row":{"source":"added","id":"$codexId","kind":"agent","harness":"codex","command":"codex --ask-for-approval never","label":"Codex, Never ask","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"recipe"}}""",
        ))
        assertEquals(
            ApiFailure.Protocol("Malformed launcher add response"),
            (api.addLauncherFrozen(connection, Scope(), codexBody) as ApiResult.Failure).error,
        )
        server.takeRequest()

        val textId = "55555555-5555-4555-8555-555555555555"
        val textBody = """{"requestId":"$textId","text":"python app.py","kind":"command"}"""
        server.enqueue(jsonResponse(
            """{"ok":true,"row":{"source":"added","id":"$textId","kind":"command","command":"python other.py","label":"python","noPrompts":false,"addedBy":"phone","addedAt":1700000000000,"addedAs":"text"}}""",
        ))
        val textResult = api.addLauncherFrozen(connection, Scope(), textBody)
        assertEquals(ApiFailure.Protocol("Malformed launcher add response"), (textResult as ApiResult.Failure).error)
        server.takeRequest()
        Unit
    }

    @Test
    fun filesReadsEscapeOpaquePathsAndPreserveDeviceReadScope() = runBlocking {
        val connection = Connection(CollieOrigin(server.url("/mounted/").toString()), "phone", "bearer-secret")
        server.enqueue(jsonResponse("""{"paneId":"w1:p1","available":true,"root":"/repo","dir":"src dir","entries":[{"name":"Main λ.kt","kind":"file","size":3}],"truncated":false}"""))
        val target = FilesTarget.Pane("w1:p1")
        val result = api.files(connection, target, Scope(host = "a b", session = "work"), dir = RelativeFilePath.parse("src dir", allowRoot = true), ignored = true)
        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/mounted/api/pane/w1%3Ap1/files?dir=src%20dir&ignored=1&host=a%20b&session=work", request.path)
        assertEquals("Bearer bearer-secret", request.getHeader("Authorization"))
        assertNull(request.getHeader("Origin"))

        server.enqueue(jsonResponse("""{"paneId":"w1:p1","available":true,"root":"/repo","path":"src dir/Main λ.kt","size":3,"mtimeMs":8,"binary":false,"truncated":false,"text":"fun"}"""))
        val read = api.files(connection, target, Scope(), path = RelativeFilePath.parse("src dir/Main λ.kt")) as ApiResult.Success
        assertEquals("fun", (read.value as FilesAnswer.Source).text)
        assertEquals("/mounted/api/pane/w1%3Ap1/files?path=src%20dir%2FMain%20%CE%BB.kt", server.takeRequest().path)
    }

    @Test
    fun filesRejectsNonCanonicalSuccessfulStatusBeforePublishingAnswer() = runBlocking {
        val connection = Connection(CollieOrigin(server.url("/mounted/").toString()), "phone", "token")
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
            .setBody("""{"paneId":"w1:p1","available":true,"root":"/repo","dir":"","entries":[{"name":"private","kind":"file"}],"truncated":false}"""))
        val result = api.files(connection, FilesTarget.Pane("w1:p1"), Scope(), dir = RelativeFilePath.parse("", allowRoot = true))
        assertTrue(result is ApiResult.Failure && result.error is ApiFailure.Protocol)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun filesExistUsesOnlyTheExactBoundedRootRelativeRequestBody() = runBlocking {
        val connection = Connection(origin, "phone", "token")
        server.enqueue(jsonResponse("""{"exists":["src/Main.kt"]}"""))
        val paths = listOf(RelativeFilePath.parse("src/Main.kt")!!, RelativeFilePath.parse("docs/read me.md")!!)
        val result = api.filesExist(connection, FilesTarget.Workspace("ws-1"), Scope(host = "peer", session = "s 1"), paths)
        assertTrue(result is ApiResult.Success)
        assertEquals(setOf("src/Main.kt"), (result as ApiResult.Success).value.exists)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/workspace/ws-1/files/exist?host=peer&session=s%201", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("{\"paths\":[\"src/Main.kt\",\"docs/read me.md\"]}", request.body.readUtf8())
        assertFalse(request.body.toString().contains("/repo"))
    }

    @Test
    fun filesImageUsesTheMountedScopedRouteAndPreservesExactVersionHeaders() = runBlocking {
        val connection = Connection(CollieOrigin(server.url("/mount/").toString()), "phone", "secret")
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setHeader("X-Collie-File-Size", "3").setHeader("X-Collie-File-Mtime", "1234.5").setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        val result = api.fileImage(connection, FilesTarget.Workspace("ws1"), Scope(host = "peer", session = "work"), RelativeFilePath.parse("images/diagram.png")!!) as ApiResult.Success
        assertEquals(byteArrayOf(1, 2, 3).toList(), result.value.bytes.toList())
        assertEquals(3L, result.value.size)
        assertEquals(1234.5, result.value.mtimeMs!!, 0.0)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/mount/api/workspace/ws1/files/image?path=images%2Fdiagram.png&host=peer&session=work", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertNull(request.getHeader("Origin"))
        assertFalse(request.getHeader("Accept")?.contains("svg") == true)
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun createdPaneResponse(): String =
        """{"ok":true,"pane":{"paneId":"w1:p2","workspaceId":"w1","workspaceLabel":"Collie","tabId":"w1:t2","cwd":"/repo"}}"""

    private fun updateStartResponse(): String =
        """{"ok":true,"to":"1.6.0","major":false,"run":null}"""

    private fun updateInfoResponse(dismissed: String? = null, dismissedCrew: String? = null): String =
        Json.encodeToString(
            UpdateInfo(
                current = "1.5.0",
                latest = "1.8.0",
                latestUrl = null,
                releaseAvailable = true,
                majorAvailable = null,
                majorUrl = null,
                bridgeStale = false,
                checkedAt = 1,
                dismissedVersion = dismissed,
                dismissedCrewVersion = dismissedCrew,
            ),
        )

    private fun updateRunResponse(state: String): String =
        """{"schema":1,"state":"$state","from":"1.5.0","to":"1.6.0","startedAt":1,"updatedAt":2,"pid":7,"attempt":1}"""

    @Test
    fun layoutPlanAndMutationUseMountedScopedRoutesAndPreserveUnknownAsNonSuccess() = runBlocking {
        val mounted = Connection(CollieOrigin(server.url("/nested/mount/").toString()), "phone", "token")
        val address = PaneAddress(Scope(host = "peer", session = "work"), "pane:one")
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"kind":"layout","layout":{"workspaceId":"space","tabId":"tab","zoomed":false,"area":{"x":0,"y":0,"width":80,"height":24},"focusedPaneId":"pane:one","panes":[{"paneId":"pane:one","focused":true,"rect":{"x":0,"y":0,"width":80,"height":24}}],"splits":[]}}}"""))
        assertTrue(api.paneLayout(mounted, address) is ApiResult.Success)
        assertEquals("/nested/mount/api/pane/pane%3Aone/layout?host=peer&session=work", server.takeRequest().path)

        val future = System.currentTimeMillis() + 30_000
        server.enqueue(MockResponse().setBody("""{"ok":true,"planToken":"plan-token","expiresAt":$future,"action":"split","effects":{"verb":"split","targetPaneId":"pane:one","direction":"right","ratio":0.5,"focusChanges":false},"layout":{"kind":"layout","layout":{"workspaceId":"space","tabId":"tab","zoomed":false,"area":{"x":0,"y":0,"width":80,"height":24},"focusedPaneId":"pane:one","panes":[{"paneId":"pane:one","focused":true,"rect":{"x":0,"y":0,"width":80,"height":24}}],"splits":[]}}}"""))
        val action = kotlinx.serialization.json.buildJsonObject { put("kind", "split"); put("direction", "right"); put("ratio", 0.5); put("workspaceId", "space") }
        assertTrue(api.planPaneLayout(mounted, address, action) is ApiResult.Success)
        val plan = server.takeRequest()
        assertEquals("POST", plan.method)
        assertEquals("/nested/mount/api/pane/pane%3Aone/layout/plan?host=peer&session=work", plan.path)
        assertEquals("Bearer token", plan.getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":false,"code":"layout.outcome_unknown","operation":{"schema":1,"requestId":"same-id","state":"unknown","capability":"paneSplit","targetIdentity":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","at":1,"updatedAt":2,"code":"layout.outcome_unknown"}}"""))
        val unknown = api.applyPaneLayout(mounted, address, "same-id", "plan-token", action)
        assertTrue(unknown is ApiResult.Success && !(unknown as ApiResult.Success).value.ok)
        val write = server.takeRequest()
        assertEquals("POST", write.method)
        assertEquals("/nested/mount/api/pane/pane%3Aone/layout/split?host=peer&session=work", write.path)
        assertEquals("same-id", Json.parseToJsonElement(write.body.readUtf8()).jsonObject["requestId"]?.toString()?.trim('"'))
    }

    @Test
    fun mountedHttpsNullablePlanEffectsRejectWrongTypesBeforeAnyApply() = runBlocking {
        val mounted = Connection(CollieOrigin(server.url("/nested/mount/").toString()), "phone", "token")
        val address = PaneAddress(Scope(host = "peer", session = "work"), "pane:one")
        val layout = """{"workspaceId":"space","tabId":"tab","zoomed":false,"area":{"x":0,"y":0,"width":80,"height":24},"focusedPaneId":"pane:one","panes":[{"paneId":"pane:one","focused":true,"rect":{"x":0,"y":0,"width":40,"height":24}},{"paneId":"pane:two","focused":false,"rect":{"x":40,"y":0,"width":40,"height":24}}],"splits":[]}"""
        val families = listOf(
            Triple("split", """{"kind":"split","direction":"right","ratio":null}""", """{"verb":"split","targetPaneId":"pane:one","direction":"right","ratio":VALUE,"focusChanges":false}"""),
            Triple("resize", """{"kind":"resize","direction":"right","amount":null}""", """{"verb":"resize","targetPaneId":"pane:one","direction":"right","amount":VALUE}"""),
            Triple("swap", """{"kind":"swap","direction":null,"sourcePaneId":"pane:one","targetPaneId":"pane:two"}""", """{"verb":"swap","sourcePaneId":"pane:one","targetPaneId":"pane:two","direction":VALUE}"""),
        )
        for ((kind, body, effect) in families) {
            val action = Json.parseToJsonElement(body).jsonObject
            for (value in listOf("null", "true", "{}", "[]", "\"bad\"", "\"0.5\"")) {
                server.enqueue(MockResponse().setBody("""{"ok":true,"planToken":"plan-token","expiresAt":${System.currentTimeMillis() + 30_000},"action":"$kind","effects":${effect.replace("VALUE", value)},"layout":{"kind":"layout","layout":$layout}}"""))
                val result = api.planPaneLayout(mounted, address, action)
                if (value == "null") assertTrue(result is ApiResult.Success)
                else assertTrue(result is ApiResult.Failure && result.error is ApiFailure.Protocol)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/nested/mount/api/pane/pane%3Aone/layout/plan", request.requestUrl!!.encodedPath)
                assertEquals("Bearer token", request.getHeader("Authorization"))
            }
        }
        assertEquals(18, server.requestCount) // All are read-classified plans, zero apply dispatch.
    }

    @Test
    fun mountedLayoutEdgesAndEveryNeighborDirectionUseTheNestedLayoutRoutes() = runBlocking {
        val mounted = Connection(CollieOrigin(server.url("/nested/mount/").toString()), "phone", "token")
        val address = PaneAddress(Scope(host = "peer", session = "work"), "pane:one")
        val layout = """{"workspaceId":"space","tabId":"tab","zoomed":false,"area":{"x":0,"y":0,"width":80,"height":24},"focusedPaneId":"pane:one","panes":[{"paneId":"pane:one","focused":true,"rect":{"x":0,"y":0,"width":80,"height":24}}],"splits":[]}"""
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"kind":"edges","paneId":"pane:one","left":true,"right":false,"up":true,"down":false,"layout":$layout}}"""))
        assertTrue(api.paneLayoutRead(mounted, address, "edges") is ApiResult.Success)
        assertEquals("/nested/mount/api/pane/pane%3Aone/layout/edges?host=peer&session=work", server.takeRequest().path)
        for (direction in listOf("left", "right", "up", "down")) {
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"kind":"neighbor","paneId":"pane:one","direction":"$direction","neighborPaneId":null,"layout":$layout}}"""))
            assertTrue(api.paneLayoutRead(mounted, address, "neighbor", direction) is ApiResult.Success)
            val read = server.takeRequest()
            assertEquals("/nested/mount/api/pane/pane%3Aone/layout/neighbor", read.requestUrl!!.encodedPath)
            assertEquals("peer", read.requestUrl!!.queryParameter("host"))
            assertEquals("work", read.requestUrl!!.queryParameter("session"))
            assertEquals(direction, read.requestUrl!!.queryParameter("direction"))
        }
    }

    @Test
    fun enabledDiagnosticsCannotConsumeMountedLayoutRepliesBeforeTheirWireLimit() = runBlocking<Unit> {
        val recorded = mutableListOf<Pair<String, Map<String, Any?>>>()
        val readBytes = java.util.concurrent.atomic.AtomicLong()
        val client = boundaryHttp1Client.newBuilder()
            .addInterceptor(com.lateapex.collie.diagnostics.DiagnosticsInterceptor(
                com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(recorded)))
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = response.body!!
                val counted = object : okio.ForwardingSource(original.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val count = super.read(sink, byteCount)
                        if (count > 0) readBytes.addAndGet(count)
                        return count
                    }
                }.buffer()
                response.newBuilder().body(object : okhttp3.ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = counted
                }).build()
            }.build()
        val diagnosticApi = CollieApiClient(client, Json { ignoreUnknownKeys = true })
        val mounted = Connection(CollieOrigin(server.url("/nested/mount/").toString()), "phone", "token")
        val address = PaneAddress(Scope("peer", "work"), "pane:one")
        val sentinel = "PRIVATE-LAYOUT-ENV-FOLDER-SENTINEL"
        val huge = "{\"private\":\"$sentinel" + "x".repeat(CollieApiClient.MAX_LAYOUT_RESPONSE_BYTES + 65536) + "\"}"
        val action = Json.parseToJsonElement("""{"kind":"split","direction":"right","ratio":0.5,"env":{"PRIVATE":"$sentinel"}}""").jsonObject
        for (mime in listOf(null, "application/json", "text/plain", "application/octet-stream")) {
          for (kind in listOf("layout", "edges", "neighbor", "plan", "apply")) {
            readBytes.set(0)
            val reply = MockResponse().setChunkedBody(huge, 4096)
            if (mime != null) reply.setHeader("Content-Type", mime)
            server.enqueue(reply)
            val result = when (kind) {
                "layout" -> diagnosticApi.paneLayout(mounted, address)
                "edges" -> diagnosticApi.paneLayoutRead(mounted, address, "edges")
                "neighbor" -> diagnosticApi.paneLayoutRead(mounted, address, "neighbor", "left")
                "plan" -> diagnosticApi.planPaneLayout(mounted, address, action)
                else -> diagnosticApi.applyPaneLayout(mounted, address, "request-1", "plan-token", action)
            }
            assertTrue(result is ApiResult.Failure && result.error is ApiFailure.Protocol)
            assertTrue("response is actually read", readBytes.get() > 0)
            assertTrue("diagnostics cannot preconsume MIME $mime: ${readBytes.get()}",
                readBytes.get() <= CollieApiClient.MAX_LAYOUT_RESPONSE_BYTES + 8192L)
            val fields = recorded.last().second
            assertEquals(true, fields["responseBodyRedacted"])
            assertEquals(true, fields["responseBodyStreaming"])
            assertFalse(fields.containsKey("responseBody"))
            assertFalse(fields.containsKey("responsePatch"))
            assertFalse(fields.values.any { it.toString().contains(sentinel) })
            if (kind in listOf("plan", "apply")) {
                assertEquals(true, fields["requestBodyRedacted"])
                assertFalse(fields.containsKey("requestBody"))
            }
            val suffix = when (kind) { "layout" -> ""; "apply" -> "/split"; else -> "/$kind" }
            assertEquals("/nested/mount/api/pane/pane%3Aone/layout$suffix", server.takeRequest().requestUrl!!.encodedPath)
          }
        }
        // The metadata-only branch also preserves a valid body for the real typed consumer.
        server.enqueue(MockResponse().setChunkedBody("""{"ok":true,"result":{"kind":"layout","layout":{"workspaceId":"space","tabId":"tab","zoomed":false,"area":{"x":0,"y":0,"width":80,"height":24},"focusedPaneId":"pane:one","panes":[{"paneId":"pane:one","focused":true,"rect":{"x":0,"y":0,"width":80,"height":24}}],"splits":[]}}}""", 64))
        assertTrue(diagnosticApi.paneLayout(mounted, address) is ApiResult.Success)
        server.takeRequest()
    }
}
