package com.lateapex.collie.network

import com.lateapex.collie.AppContainer
import com.lateapex.collie.auth.*
import com.lateapex.collie.data.*
import com.lateapex.collie.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import mockwebserver3.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ProxyActorAdmissionTest {
    private class Store(initial: Connection) : ConnectionStore {
        private val state = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = state
        override fun save(connection: Connection) { state.value = connection }
        override fun clear() { state.value = null }
    }
    @Test fun actualAppContainerBootstrapReachesExactOwnedHttpsWithoutBorrowingProfileOrPairing() = runBlocking {
        ProxyHttpsFixture().use { f ->
            // Keep the actual AppContainer -> repository -> CollieApiClient -> always-installed
            // interceptor. The test replaces only this API's TLS transport with a generated pinned
            // socket; production TLS verification and interceptor configuration remain intact.
            val app = AppContainer(f.context)
            val client = app.httpClient.newBuilder().sslSocketFactory(f.client.sslSocketFactory, f.client.x509TrustManager!!).build()
            val field = CollieApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }
            field.set(app.api, client)
            f.store.clear()
            f.server.enqueue(MockResponse.Builder().body("{\"ok\":true,\"version\":\"fixture\",\"deposed\":false,\"mode\":\"solo\"}").build())
            assertTrue(app.repository.probe(f.connection.origin.value) is ApiResult.Success)
            f.server.enqueue(MockResponse.Builder().code(400).body("{\"ok\":false,\"error\":\"fixture-code-refused\"}").build())
            val pair = app.repository.pair(f.connection.origin.value, "phone", "owned-code") as ApiResult.Success
            assertTrue(pair.value is PairResult.Refused)
            for (request in listOf(f.server.takeRequest(), f.server.takeRequest())) {
                assertNull(request.headers["Authorization"]); assertNull(request.headers["Collie-Proxy-Authorization"]); assertNull(request.headers["DPoP"]); assertNull(request.headers["Cookie"])
                assertTrue(request.target in listOf("/mount/api/health", "/mount/api/pair"))
            }
            assertEquals(2, f.server.requestCount)
            // Presence of configured identity custody must still reach the configured gateway's
            // typed refusal; it cannot borrow or decrypt that custody for a fresh candidate.
            f.context.getSharedPreferences("collie_proxy_identity_v1", android.content.Context.MODE_PRIVATE).edit().putString("record", "configured-custody-unavailable-to-bootstrap").commit()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val id = request.headers["X-Collie-Native-Request-Id"]!!
                    return MockResponse.Builder().code(401).setHeader("X-Collie-Response-Source", "native_gateway")
                        .setHeader("X-Collie-Error-Source", "native_gateway").setHeader("X-Collie-Error-Factor", "identity")
                        .setHeader("X-Collie-Error-Code", "identity.required").setHeader("X-Collie-Native-Request-Id", id)
                        .body("{\"source\":\"native_gateway\",\"factor\":\"identity\",\"code\":\"identity.required\",\"requestId\":\"$id\"}").build()
                }
            }
            for (answer in listOf(app.repository.probe(f.connection.origin.value), app.repository.pair(f.connection.origin.value, "phone", "owned-code"))) {
                val failure = (answer as ApiResult.Failure).error as ApiFailure.Http
                assertEquals(FailureSource.GATEWAY, failure.source); assertEquals(FailureFactor.IDENTITY, failure.factor); assertEquals("identity.required", failure.code)
            }
            assertEquals(4, f.server.requestCount)
            for (request in listOf(f.server.takeRequest(), f.server.takeRequest())) { assertNull(request.headers["Authorization"]); assertNull(request.headers["Collie-Proxy-Authorization"]); assertNull(request.headers["DPoP"]) }
            val candidate = Connection(f.connection.origin, "candidate", null)
            val bad = okhttp3.Request.Builder().url(candidate.origin.value + "api/config").tag(Connection::class.java, candidate)
                .tag(NativeBootstrapAdmission::class.java, NativeBootstrapAdmission(candidate, "GET", candidate.origin.value + "api/health")).build()
            assertTrue(runCatching { client.newCall(bad).execute() }.isFailure); assertEquals(4, f.server.requestCount)
        }
    }

    @Test fun canonicalRepositoryOriginalActorAcrossHeldNonceAndQueuedInterceptorMakesZeroRetiredPostsAndFreshOne() = runBlocking {
        for (transition in listOf("subject", "scope", "lead", "saved-copy")) ProxyHttpsFixture().use { f ->
            f.store.clear(); val store = Store(f.connection)
            lateinit var repository: CollieRepository
            val coordinator = ProxySignInCoordinator(f.store, f.key, { store.connection.value }, { repository.readContext() }, f.client,
                actorCurrent = { repository.isCurrent(it) })
            val interceptor = ProxyIdentityInterceptor(coordinator, { store.connection.value }, { repository.isCurrent(it) })
            val real = CollieApiClient(f.client.newBuilder().addInterceptor(interceptor).build(), Json { ignoreUnknownKeys = true })
            var primeSnapshot = true
            val api = object : CollieApi by real {
                override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> = if (!primeSnapshot) real.snapshot(connection, scope) else ApiResult.Success(
                    SnapshotResponse("connected", device = DeviceAuthorization(true, "writer", true), agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            }
            repository = CollieRepository(api, store, OriginValidator())
            repository.onReadContextInvalidated { coordinator.retire(); interceptor.retire() }
            val scope = Scope(host = "desk", session = "session-a")
            val address = PaneAddress(scope, "pane-a")
            repository.selectSubject(scope, address.paneId)
            coordinator.importProfile(f.rawProfile, repository.readContext()!!)
            fun ready() {
                val record = f.store.record()!!
                f.store.write(record.copyCustody(tokens = ProxyTokens(f.access(), "refresh", "operator", System.currentTimeMillis() + 120_000, f.key.thumbprint, "nonce")))
            }
            ready(); assertTrue(repository.snapshot(scope) is ApiResult.Success); assertTrue(repository.writesAllowed(scope, address.paneId))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET" && request.target!!.substringBefore('?').endsWith("/health")) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return MockResponse.Builder().code(401).setHeader("DPoP-Nonce", "gateway-nonce").body("{}").build() }
                    val body = if (request.target!!.substringBefore('?').endsWith("/config")) "{}" else if (request.target!!.substringBefore('?').endsWith("/folders")) "{\"home\":\"/owned-fixture-home\",\"favourites\":[],\"recent\":[]}" else if (request.method == "GET") "{\"bridge\":\"connected\",\"device\":{\"enforced\":true,\"device\":\"writer\",\"authorized\":true},\"agents\":[],\"shellPanes\":[],\"workspaces\":[],\"tabs\":[],\"ts\":1}" else "{\"ok\":true}"
                    return MockResponse.Builder().setHeader("X-Collie-Response-Source", "collie").setHeader("X-Collie-Native-Request-Id", request.headers["X-Collie-Native-Request-Id"]!!).body(body).build()
                }
            }
            val original = repository.readContext()!!
            val pending = async(Dispatchers.IO) { repository.keys(address, listOf("Enter")) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                when (transition) {
                    "subject" -> repository.selectSubject(scope, "pane-b")
                    "scope" -> repository.selectSubject(Scope(host = "other", session = "session-b"), "pane-a")
                    "lead" -> repository.setLeadEpoch(9)
                    else -> repository.showSavedCopy(1)
                }
                assertFalse(repository.isCurrent(original)); release.countDown()
                assertTrue(pending.await() is ApiResult.Failure); assertEquals(1, f.server.requestCount)
                val queued = RequestActorCustody.withActor(original) {
                    real.keys(original.connection, address, listOf("Enter"), null)
                }
                assertTrue(queued is ApiResult.Failure); assertEquals(1, f.server.requestCount)
                // Deliberate current selection and fresh snapshot restore only current authority.
                repository.selectSubject(scope, address.paneId); ready()
                assertTrue(repository.snapshot(scope) is ApiResult.Success)
                assertTrue(repository.keys(address, listOf("Enter")) is ApiResult.Success)
                assertEquals(listOf("GET", "GET", "POST"), (1..3).map { f.server.takeRequest().method })
                assertEquals(3, f.server.requestCount)
                // Explicit null dimensions/viewAll remain this captured actor, even when the
                // selected defaults name a desk/session/pane. Actual API child dispatcher and URL
                // building must retain the explicit request rather than reminting default scope.
                primeSnapshot = false
                val wide = Scope(viewAll = true)
                val explicit = repository.readContext(wide, null)!!
                assertTrue(withContext(Dispatchers.Default) { repository.snapshot(wide, explicit) } is ApiResult.Success)
                assertEquals(4, f.server.requestCount)
                val wideRequest = f.server.takeRequest()
                assertTrue(wideRequest.target!!.contains("sessions=all")); assertFalse(wideRequest.target!!.contains("host=")); assertFalse(wideRequest.target!!.contains("session="))
                assertTrue(withContext(Dispatchers.Default) { repository.config(wide, explicit) } is ApiResult.Success)
                assertEquals(5, f.server.requestCount)
                val configRequest = f.server.takeRequest()
                assertTrue(configRequest.target!!.startsWith("/mount/api/config")); assertFalse(configRequest.target!!.contains("host=")); assertFalse(configRequest.target!!.contains("session="))
                // The same explicit null survives the shared folders helper as well. Its request
                // reaches the exact transport even though the selected default subject is a pane.
                assertTrue(withContext(Dispatchers.Default) { repository.folders(wide, explicit) } is ApiResult.Success)
                assertEquals(6, f.server.requestCount)
                val foldersRequest = f.server.takeRequest()
                assertTrue(foldersRequest.target!!.startsWith("/mount/api/folders")); assertFalse(foldersRequest.target!!.contains("host=")); assertFalse(foldersRequest.target!!.contains("session="))
            } finally { release.countDown(); pending.cancel() }
        }
    }

}
