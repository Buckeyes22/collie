package com.lateapex.collie.network

import com.lateapex.collie.auth.*
import com.lateapex.collie.data.copyCustody
import com.lateapex.collie.domain.PaneAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProxyIdentityInterceptorTest {
    private fun response(request:RecordedRequest,marker:String="native_gateway",source:String="native_gateway",factor:String="identity",code:String="identity.expired",bodyFactor:String=factor):MockResponse {
        val id=request.getHeader("X-Collie-Native-Request-Id")!!
        return MockResponse().setResponseCode(401).setHeader("X-Collie-Response-Source",marker).setHeader("X-Collie-Error-Source",source)
            .setHeader("X-Collie-Error-Factor",factor).setHeader("X-Collie-Error-Code",code).setHeader("X-Collie-Native-Request-Id",id)
            .setHeader("X-Collie-Native-Binding-Id","binding-fixture-id").setHeader("X-Collie-Native-Binding-Revision","1")
            .setBody(buildJsonObject {put("source",source);put("factor",bodyFactor);put("code",code);put("requestId",id)}.toString())
    }
    @Test fun actualHttpsWriteObtainsNonceBeforeSinglePostAndNeverReplaysIdentityRefusal() = runBlocking {
        ProxyHttpsFixture().use { f ->
            f.readyTokens();val events=mutableListOf<ProxyBindingRetirement>();f.coordinator.bindingLifecycle.observe {events.add(it)}
            f.server.dispatcher=object: Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.method=="GET") return MockResponse().setResponseCode(401).setHeader("DPoP-Nonce","nonce-fixture").setBody("{}");
                    return response(request)
                }
            }
            val interceptor=ProxyIdentityInterceptor(f.coordinator,{f.connection},{ original -> val live = f.actor; original.connection === f.connection && original.key == live.key && original.navigationGeneration == live.navigationGeneration })
            val api=CollieApiClient(f.client.newBuilder().addInterceptor(interceptor).build(),Json {ignoreUnknownKeys=true})
            val answer=RequestActorCustody.withActor(f.actor) { api.keys(f.connection,PaneAddress(paneId="w1:p1"),listOf("Enter"),null) } as ApiResult.Failure
            val http=answer.error as ApiFailure.Http
            assertEquals(FailureSource.GATEWAY,http.source);assertEquals(FailureFactor.IDENTITY,http.factor);assertFalse(http.retiresPairing())
            assertEquals(2,f.server.requestCount);val probe=f.server.takeRequest();val write=f.server.takeRequest()
            assertEquals("GET",probe.method);assertEquals("/mount/api/health",probe.path);assertEquals("POST",write.method)
            assertEquals("Bearer pairing-secret",write.getHeader("Authorization"));assertTrue(write.getHeader("Collie-Proxy-Authorization")!!.startsWith("DPoP "));assertNotNull(write.getHeader("DPoP"))
            assertNull(f.store.record()!!.tokens);assertEquals("pairing-secret",f.connection.token);assertTrue(events.isEmpty())
        }
    }
    @Test fun exactRequestMarkerAndEnvelopeAreRequiredBeforeAnyCredentialRetirement() = runBlocking {
        for(mode in listOf("mixed-factor","mixed-source","wrong-marker","genuine-pairing")) ProxyHttpsFixture().use { f ->
            f.readyTokens()
            f.server.dispatcher=object: Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.getHeader("DPoP")!!.let { ProxyJson.objectOf(ProxyJose.decode(it.split('.')[1]).toString(Charsets.UTF_8))["nonce"] } == null) return MockResponse().setResponseCode(401).setHeader("DPoP-Nonce","nonce-fixture").setBody("{}")
                    return when(mode) {
                        "mixed-factor" -> response(request,bodyFactor="device")
                        "mixed-source" -> response(request,source="collie",factor="pairing",code="pairing.expired")
                        "wrong-marker" -> response(request,marker="untrusted")
                        else -> response(request,marker="collie",source="collie",factor="pairing",code="pairing.expired")
                    }
                }
            }
            val api=CollieApiClient(f.client.newBuilder().addInterceptor(ProxyIdentityInterceptor(f.coordinator,{f.connection},{ original -> val live = f.actor; original.connection === f.connection && original.key == live.key && original.navigationGeneration == live.navigationGeneration })).build(),Json {ignoreUnknownKeys=true})
            val http=(RequestActorCustody.withActor(f.actor) { api.health(f.connection) } as ApiResult.Failure).error as ApiFailure.Http
            assertEquals(mode=="genuine-pairing",http.retiresPairing());assertEquals(if(mode=="genuine-pairing") FailureSource.COLLIE else FailureSource.UNKNOWN,http.source)
            assertNotNull(f.store.record()!!.tokens);assertEquals(2,f.server.requestCount)
        }
    }
    @Test fun corruptEncryptedIdentityCustodyRefusesLocallyInsteadOfBorrowingDirectUnconfiguredTransport() = runBlocking {
        ProxyHttpsFixture().use { f ->
            f.context.getSharedPreferences("collie_proxy_identity_v1",android.content.Context.MODE_PRIVATE).edit().putString("record","broken-ciphertext").commit()
            val api=CollieApiClient(f.client.newBuilder().addInterceptor(ProxyIdentityInterceptor(f.coordinator,{f.connection},{ original -> val live = f.actor; original.connection === f.connection && original.key == live.key && original.navigationGeneration == live.navigationGeneration })).build(),Json {ignoreUnknownKeys=true})
            val failure=(RequestActorCustody.withActor(f.actor) { api.health(f.connection) } as ApiResult.Failure).error as ApiFailure.Http
            assertEquals(FailureSource.GATEWAY,failure.source);assertEquals(FailureFactor.IDENTITY,failure.factor);assertFalse(failure.retiresPairing());assertEquals(0,f.server.requestCount);assertEquals("pairing-secret",f.connection.token)
        }
    }
    @Test fun retiredTaggedConnectionCannotUseDirectFallbackOrIssueAnyTransport() {
        ProxyHttpsFixture().use { f ->
            val original=f.connection;f.connection=com.lateapex.collie.domain.Connection(original.origin,"new","new-pairing");f.generation++
            val client=f.client.newBuilder().addInterceptor(ProxyIdentityInterceptor(f.coordinator,{f.connection},{ original -> val live = f.actor; original.connection === f.connection && original.key == live.key && original.navigationGeneration == live.navigationGeneration })).build()
            val request=okhttp3.Request.Builder().url(original.origin.value+"api/health").tag(com.lateapex.collie.domain.Connection::class.java,original).build()
            assertTrue(runCatching {client.newCall(request).execute()}.isFailure);assertEquals(0,f.server.requestCount)
        }
    }
    @Test fun replacementAccessTokenObtainsItsOwnNonceBeforeEachOneShotWrite() = runBlocking {
        ProxyHttpsFixture().use { f ->
            f.readyTokens()
            val issued=java.util.concurrent.ConcurrentHashMap<String,String>()
            f.server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val access=request.getHeader("Collie-Proxy-Authorization")!!
                    if(request.method=="GET") {val nonce="nonce-"+issued.size;issued[access]=nonce;return MockResponse().setResponseCode(401).setHeader("DPoP-Nonce",nonce).setBody("{}")}
                    val proof=ProxyJson.objectOf(ProxyJose.decode(request.getHeader("DPoP")!!.split('.')[1]).toString(Charsets.UTF_8))
                    if(proof["nonce"]?.jsonPrimitive?.content!=issued[access]) return response(request,code="identity.proof_invalid")
                    return MockResponse().setHeader("X-Collie-Response-Source","collie").setHeader("X-Collie-Native-Request-Id",request.getHeader("X-Collie-Native-Request-Id")!!).setBody("{\"ok\":true}")
                }
            }
            val interceptor=ProxyIdentityInterceptor(f.coordinator,{f.connection},{ original -> val live = f.actor; original.connection === f.connection && original.key == live.key && original.navigationGeneration == live.navigationGeneration })
            val api=CollieApiClient(f.client.newBuilder().addInterceptor(interceptor).build(),Json {ignoreUnknownKeys=true})
            assertTrue(RequestActorCustody.withActor(f.actor) { api.keys(f.connection,PaneAddress(paneId="w1:p1"),listOf("Enter"),null) } is ApiResult.Success)
            val record=f.store.record()!!;val prior=record.tokens!!
            val next=ProxyJson.string(ProxyJson.objectOf(f.tokens(prior.nonce,expires=f.now+121)),"access_token",16_384)
            assertNotEquals(prior.accessToken,next)
            f.store.write(com.lateapex.collie.data.ProxyRecord(origin=record.origin,connectionIdentity=record.connectionIdentity,profile=record.profile,
                tokens=com.lateapex.collie.data.ProxyTokens(next,"refresh",prior.subject,System.currentTimeMillis()+121_000,prior.thumbprint,prior.nonce)))
            assertTrue(RequestActorCustody.withActor(f.actor) { api.keys(f.connection,PaneAddress(paneId="w1:p1"),listOf("Enter"),null) } is ApiResult.Success)
            assertEquals(4,f.server.requestCount);assertEquals(2,issued.size)
            assertEquals(listOf("GET","POST","GET","POST"),(1..4).map {f.server.takeRequest().method})
            assertEquals("pairing-secret",f.connection.token)
        }
    }

    @Test fun actualAuthenticatedBindingProducerSurvivesIssuerRefreshAndEmitsExactDurableRevocation() = runBlocking {
        ProxyHttpsFixture().use { f ->
            f.readyTokens(); val events = mutableListOf<ProxyBindingRetirement>()
            f.coordinator.bindingLifecycle.observe { events.add(it) }
            var revoked = false
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/token") return MockResponse().setBody(f.tokens("nonce", expires = f.now + 121))
                    val proof = ProxyJson.objectOf(ProxyJose.decode(request.getHeader("DPoP")!!.split('.')[1]).toString(Charsets.UTF_8))
                    if (proof["nonce"] == null) return MockResponse().setResponseCode(401).setHeader("DPoP-Nonce", "gateway-nonce").setBody("{}")
                    if (revoked) return response(request, factor = "device", code = "device.binding_revoked")
                    return MockResponse().setHeader("X-Collie-Response-Source", "collie")
                        .setHeader("X-Collie-Native-Request-Id", request.getHeader("X-Collie-Native-Request-Id")!!)
                        .setHeader("X-Collie-Native-Binding-Id", "binding-fixture-id").setHeader("X-Collie-Native-Binding-Revision", "1")
                        .setBody("{\"ok\":true,\"version\":\"fixture\",\"deposed\":false,\"mode\":\"solo\"}")
                }
            }
            val interceptor = ProxyIdentityInterceptor(f.coordinator, { f.connection }, { original ->
                val live = f.actor; original.connection === f.connection && original.key == live.key && original.navigationGeneration == live.navigationGeneration
            })
            val api = CollieApiClient(f.client.newBuilder().addInterceptor(interceptor).build(), Json { ignoreUnknownKeys = true })
            assertTrue(RequestActorCustody.withActor(f.actor) { api.health(f.connection) } is ApiResult.Success)
            val accepted = f.store.record()!!; val token = accepted.tokens!!
            assertEquals("binding-fixture-id", accepted.binding!!.bindingId); assertTrue(events.isEmpty())
            f.store.write(accepted.copyCustody(tokens = com.lateapex.collie.data.ProxyTokens(token.accessToken, "refresh", token.subject, 1, token.thumbprint, token.nonce)))
            revoked = true
            val failed = RequestActorCustody.withActor(f.actor) { api.health(f.connection) } as ApiResult.Failure
            val http = failed.error as ApiFailure.Http
            assertEquals(FailureSource.GATEWAY, http.source); assertEquals(FailureFactor.DEVICE, http.factor); assertFalse(http.retiresPairing())
            val exact = ProxyBindingRetirement("binding-fixture-id", 1)
            assertEquals(listOf(exact), events); assertEquals(listOf(exact), f.coordinator.bindingLifecycle.pending())
            assertNull(f.store.record()!!.tokens); assertNull(f.store.record()!!.binding); assertEquals("pairing-secret", f.connection.token)
            assertEquals(listOf("GET", "GET", "POST", "GET", "GET"), (1..5).map { f.server.takeRequest().method })
        }
    }

}
