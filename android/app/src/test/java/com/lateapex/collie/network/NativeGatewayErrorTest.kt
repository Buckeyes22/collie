package com.lateapex.collie.network

import android.content.Context
import com.lateapex.collie.auth.*
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class NativeGatewayErrorTest {
    private data class BadField(val name: String, val value: JsonElement, val factor: String, val code: String)
    private val fields = listOf("source", "factor", "code", "requestId")
    private val wrongTypes = listOf(JsonArray(emptyList()), JsonObject(emptyMap()), JsonNull, JsonPrimitive(7), JsonPrimitive(true))

    private class Transport(val f: ProxyHttpsFixture) {
        val bad = AtomicReference<BadField?>(null)
        val genuine = AtomicReference<Pair<String, String>?>(null)
        val events = mutableListOf<ProxyBindingRetirement>()
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val api: CollieApiClient
        init {
            // Observe only this disposable transport's workers; never replace the process-wide
            // Android uncaught handler or turn unexpected interceptor exceptions into success.
            val executor = f.client.dispatcher.executorService as ThreadPoolExecutor
            val originalFactory = executor.threadFactory
            executor.threadFactory = java.util.concurrent.ThreadFactory { runnable ->
                originalFactory.newThread(runnable).apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.add(error) } }
            }
            f.readyTokens()
            f.coordinator.bindingLifecycle.observe { events.add(it) }
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.target == "/.well-known/openid-configuration") return MockResponse.Builder().body(f.discovery()).build()
                    if (request.target == "/jwks") return MockResponse.Builder().body(f.jwks()).build()
                    val proof = request.headers["DPoP"]?.let { ProxyJson.objectOf(ProxyJose.decode(it.split('.')[1]).toString(Charsets.UTF_8)) }
                    if (proof != null && proof["nonce"] == null) return MockResponse.Builder().code(401).setHeader("DPoP-Nonce", "owned-nonce").body("{}").build()
                    val id = request.headers["X-Collie-Native-Request-Id"]!!
                    val broken = bad.get()
                    val typed = genuine.get()
                    if (broken != null || typed != null) {
                        val factor = broken?.factor ?: typed!!.first
                        val originalCode = broken?.code ?: typed!!.second
                        // Numeric/boolean code headers deliberately match the coerced content:
                        // treating JsonPrimitive.content as a string would falsely admit them.
                        val code = if (broken?.name == "code" && broken.value is JsonPrimitive && broken.value != JsonNull && !broken.value.isString) broken.value.content else originalCode
                        val body = buildJsonObject {
                            put("source", "native_gateway"); put("factor", factor); put("code", code); put("requestId", id)
                            if (broken != null) put(broken.name, broken.value)
                        }
                        return MockResponse.Builder().code(403).setHeader("X-Collie-Response-Source", "native_gateway")
                            .setHeader("X-Collie-Error-Source", "native_gateway").setHeader("X-Collie-Error-Factor", factor)
                            .setHeader("X-Collie-Error-Code", code).setHeader("X-Collie-Native-Request-Id", id)
                            .setHeader("X-Collie-Native-Binding-Id", "binding-fixture-id").setHeader("X-Collie-Native-Binding-Revision", "1")
                            .body(body.toString()).build()
                    }
                    return MockResponse.Builder().setHeader("X-Collie-Response-Source", "collie").setHeader("X-Collie-Native-Request-Id", id)
                        .setHeader("X-Collie-Native-Binding-Id", "binding-fixture-id").setHeader("X-Collie-Native-Binding-Revision", "1")
                        .body("{\"ok\":true,\"version\":\"fixture\",\"deposed\":false,\"mode\":\"solo\"}").build()
                }
            }
            val interceptor = ProxyIdentityInterceptor(f.coordinator, { f.connection }, { original ->
                val live = f.actor
                original.connection === f.connection && original.key == live.key && original.navigationGeneration == live.navigationGeneration
            })
            api = CollieApiClient(f.client.newBuilder().addInterceptor(interceptor).build(), Json { ignoreUnknownKeys = true })
        }
        suspend fun establishCustody() {
            assertTrue(RequestActorCustody.withActor(f.actor) { api.health(f.connection) } is ApiResult.Success)
            assertEquals(listOf("GET", "GET"), (1..2).map { take().method })
            assertNotNull(f.store.record()!!.binding)
            f.coordinator.begin(f.actor)
            assertEquals(listOf("/.well-known/openid-configuration", "/jwks"), (1..2).map { take().target })
            assertNotNull(f.store.record()!!.transaction)
        }
        fun take(): RecordedRequest = checkNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
        fun encryptedCustodyHash(): String {
            val encoded = f.context.getSharedPreferences("collie_proxy_identity_v1", Context.MODE_PRIVATE).getString("record", null)!!
            return ProxyJose.hash(encoded.encodeToByteArray())
        }
        fun unchanged(hash: String, revision: String, original: Connection) {
            assertEquals(hash, encryptedCustodyHash()); assertEquals(revision, f.store.record()!!.revision)
            assertSame(original, f.connection); assertEquals("pairing-secret", f.connection.token)
            assertNotNull(f.store.record()!!.tokens); assertNotNull(f.store.record()!!.binding); assertNotNull(f.store.record()!!.transaction)
            assertTrue(events.isEmpty()); assertTrue(f.coordinator.bindingLifecycle.pending().isEmpty())
            assertTrue("No unexpected interceptor worker throwable", uncaught.isEmpty())
        }
        fun assertWorkersSettled() {
            val executor = f.client.dispatcher.executorService
            executor.shutdown()
            assertTrue("Owned transport workers must settle within five seconds", executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue("No unexpected interceptor worker throwable after transport completion", uncaught.isEmpty())
        }
    }

    private fun unknown(label: String, result: ApiResult<*>) {
        assertTrue("$label returns a typed failure without an escaping exception", result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertTrue("$label retains the HTTP refusal", error is ApiFailure.Http)
        val http = error as ApiFailure.Http
        assertEquals(label, 403, http.status); assertEquals(label, FailureSource.UNKNOWN, http.source)
        assertEquals(label, FailureFactor.UNKNOWN, http.factor); assertFalse(label, http.retiresPairing())
    }

    @Test fun pinnedHttpsOrdinaryReadAndOneShotWriteRejectEveryWrongTypeWithoutRetiringCustodyThenGenuineExpiryWorks() = runBlocking {
        ProxyHttpsFixture().use { f ->
            val transport = Transport(f); transport.establishCustody()
            val hash = transport.encryptedCustodyHash(); val revision = f.store.record()!!.revision; val original = f.connection
            for ((factor, code) in listOf("identity" to "identity.expired", "device" to "device.binding_revoked")) {
                for (field in fields) for ((type, wrong) in wrongTypes.withIndex()) {
                    transport.bad.set(BadField(field, wrong, factor, code))
                    val label = "$factor/$field/type$type"
                    val before = f.server.requestCount
                    unknown("$label/read", RequestActorCustody.withActor(f.actor) { transport.api.health(f.connection) })
                    unknown("$label/write", RequestActorCustody.withActor(f.actor) { transport.api.keys(f.connection, PaneAddress(paneId = "w1:p1"), listOf("Enter"), null) })
                    assertEquals(label, before + 2, f.server.requestCount)
                    val read = transport.take(); val write = transport.take()
                    assertEquals("GET", read.method); assertEquals("POST", write.method)
                    for (request in listOf(read, write)) {
                        assertEquals("Bearer pairing-secret", request.headers["Authorization"]); assertNotNull(request.headers["DPoP"])
                    }
                    transport.unchanged(hash, revision, original)
                }
            }
            transport.bad.set(null)
            // A gateway cannot turn its own otherwise well-formed envelope into Collie pairing evidence.
            transport.genuine.set("pairing" to "pairing.expired")
            unknown("gateway pairing spoof", RequestActorCustody.withActor(f.actor) { transport.api.health(f.connection) })
            transport.take(); transport.unchanged(hash, revision, original)
            val before = f.server.requestCount
            transport.genuine.set("identity" to "identity.expired")
            val positive = RequestActorCustody.withActor(f.actor) { transport.api.health(f.connection) } as ApiResult.Failure
            val http = positive.error as ApiFailure.Http
            assertEquals(FailureSource.GATEWAY, http.source); assertEquals(FailureFactor.IDENTITY, http.factor); assertFalse(http.retiresPairing())
            assertEquals(before + 1, f.server.requestCount); assertEquals("GET", transport.take().method)
            assertNull(f.store.record()!!.tokens); assertNull(f.store.record()!!.transaction); assertNotNull(f.store.record()!!.binding)
            assertEquals("pairing-secret", f.connection.token); assertTrue(transport.events.isEmpty()); assertTrue(f.coordinator.bindingLifecycle.pending().isEmpty())
            transport.assertWorkersSettled()
        }
    }

    @Test fun pinnedHttpsActualBootstrapHealthAndPairRejectEveryWrongTypeWithoutBorrowingOrChangingCustody() = runBlocking {
        ProxyHttpsFixture().use { f ->
            val transport = Transport(f); transport.establishCustody()
            val hash = transport.encryptedCustodyHash(); val revision = f.store.record()!!.revision; val original = f.connection
            val candidate = Connection(original.origin, "candidate", null)
            for (field in fields) for ((type, wrong) in wrongTypes.withIndex()) {
                transport.bad.set(BadField(field, wrong, "device", "device.binding_revoked"))
                val label = "bootstrap/$field/type$type"; val before = f.server.requestCount
                unknown("$label/health", transport.api.health(candidate))
                unknown("$label/pair", transport.api.pair(candidate.origin, "fixture-code", "candidate"))
                assertEquals(label, before + 2, f.server.requestCount)
                val health = transport.take(); val pair = transport.take()
                assertEquals("GET", health.method); assertEquals("POST", pair.method)
                assertEquals("/mount/api/health", health.target); assertEquals("/mount/api/pair", pair.target)
                for (request in listOf(health, pair)) {
                    assertNull(request.headers["Authorization"]); assertNull(request.headers["Collie-Proxy-Authorization"])
                    assertNull(request.headers["DPoP"]); assertNull(request.headers["Cookie"])
                }
                transport.unchanged(hash, revision, original)
            }
            transport.bad.set(null); transport.genuine.set("identity" to "identity.required")
            val before = f.server.requestCount
            for (answer in listOf(transport.api.health(candidate), transport.api.pair(candidate.origin, "fixture-code", "candidate"))) {
                val http = (answer as ApiResult.Failure).error as ApiFailure.Http
                assertEquals(FailureSource.GATEWAY, http.source); assertEquals(FailureFactor.IDENTITY, http.factor); assertFalse(http.retiresPairing())
            }
            assertEquals(before + 2, f.server.requestCount); transport.take(); transport.take()
            transport.unchanged(hash, revision, original)
            transport.assertWorkersSettled()
        }
    }
}
