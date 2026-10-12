package com.lateapex.collie.auth

import com.lateapex.collie.domain.Connection
import okhttp3.HttpUrl.Companion.toHttpUrl
import mockwebserver3.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProxySignInCoordinatorTest {
    private fun begin(f: ProxyHttpsFixture): okhttp3.HttpUrl {
        f.server.enqueue(MockResponse.Builder().body(f.discovery()).build());f.server.enqueue(MockResponse.Builder().body(f.jwks()).build())
        return f.coordinator.begin(f.actor).toHttpUrl()
    }
    private fun callback(f: ProxyHttpsFixture,url:okhttp3.HttpUrl,issuer:String=f.server.url("/").toString()) = ProxyProfile.REDIRECT+"?state="+url.queryParameter("state")+"&code=one-code&iss="+java.net.URLEncoder.encode(issuer,"UTF-8")
    @Test fun realPinnedIssuerPkceNonceDpopCodeExchangeIsOneShotAndPairingNeverReachesIssuer() {
        ProxyHttpsFixture().use { f ->
            val url=begin(f); val t=f.store.record()!!.transaction!!
            assertEquals(ProxyJose.hash(t.verifier.encodeToByteArray()),url.queryParameter("code_challenge"));assertEquals("S256",url.queryParameter("code_challenge_method"));assertEquals(f.key.thumbprint,url.queryParameter("dpop_jkt"))
            f.server.enqueue(MockResponse.Builder().body(f.tokens(url.queryParameter("nonce")!!)).build())
            assertTrue(f.coordinator.consumeCallback(callback(f,url)));assertFalse(f.coordinator.consumeCallback(callback(f,url)))
            val discovery=f.server.takeRequest();val jwks=f.server.takeRequest();val token=f.server.takeRequest()
            for(request in listOf(discovery,jwks,token)) { assertNull(request.headers["Authorization"]);assertNull(request.headers["Collie-Proxy-Authorization"]);assertNull(request.headers["Cookie"]) }
            assertEquals("POST",token.method);assertEquals("/token",token.target);assertTrue(token.body?.utf8().orEmpty().contains("code_verifier="+t.verifier))
            assertEquals("dpop+jwt",ProxyJson.objectOf(ProxyJose.decode(token.headers["DPoP"]!!.split('.')[0]).toString(Charsets.UTF_8))["typ"]?.toString()?.trim('"'))
            assertEquals(3,f.server.requestCount);assertNotNull(f.coordinator.credentials(f.connection));assertEquals("pairing-secret",f.connection.token)
        }
    }
    @Test fun foreignStateIssuerMixupRetiredConnectionAndExpiredTransactionMakeZeroTokenPosts() {
        ProxyHttpsFixture().use { f ->
            val url=begin(f);assertFalse(f.coordinator.consumeCallback(callback(f,url).replace(url.queryParameter("state")!!,"foreign")))
            assertTrue(runCatching { f.coordinator.consumeCallback(callback(f,url,"https://foreign.example/")) }.isFailure)
            assertEquals(2,f.server.requestCount)
            val second=begin(f);f.connection=Connection(f.connection.origin,"replacement","replacement-pairing");f.generation++
            assertFalse(f.coordinator.consumeCallback(callback(f,second)));assertEquals(4,f.server.requestCount)
        }
    }
    @Test fun queuedOldNavigationActorCannotStartEvenDiscoveryAndReplacementTokensSurviveOldRefusal() {
        ProxyHttpsFixture().use { f ->
            val old=f.actor;f.navigation++
            assertTrue(runCatching { f.coordinator.begin(old) }.isFailure);assertEquals(0,f.server.requestCount)
            f.readyTokens();val oldAccess=f.store.record()!!.tokens!!.accessToken
            f.coordinator.importProfile(f.rawProfile,f.actor);f.readyTokens()
            // Distinct signed access so a late response can never clear a replacement identity.
            val current=f.store.record()!!;val replacement=com.lateapex.collie.data.ProxyTokens("replacement-access","refresh","operator",System.currentTimeMillis()+120_000,f.key.thumbprint,"nonce")
            f.store.write(com.lateapex.collie.data.ProxyRecord(origin=current.origin,connectionIdentity=current.connectionIdentity,profile=current.profile,tokens=replacement))
            f.coordinator.identityFailed(f.connection,oldAccess);assertEquals("replacement-access",f.store.record()!!.tokens!!.accessToken)
        }
    }
    @Test fun expiryAndNewProcessCallbackCannotExchangeOrReconstructOldTransaction() {
        ProxyHttpsFixture().use { f ->
            val first=begin(f);f.clockOffsetMs=300_001L
            assertFalse(f.coordinator.consumeCallback(callback(f,first)));assertEquals(2,f.server.requestCount)
            f.clockOffsetMs=0;val next=begin(f)
            val restarted=ProxySignInCoordinator(f.store,f.key,{f.connection},{f.actor},f.client)
            assertFalse(restarted.consumeCallback(callback(f,next)));assertEquals(4,f.server.requestCount)
        }
    }
    @Test fun genuineRefreshUsesTheSameDpopKeyAndIssuerNonceBeforeAnyProtectedWrite() {
        ProxyHttpsFixture().use { f ->
            f.readyTokens();val r=f.store.record()!!;val prior=r.tokens!!
            f.store.write(com.lateapex.collie.data.ProxyRecord(origin=r.origin,connectionIdentity=r.connectionIdentity,profile=r.profile,
                tokens=com.lateapex.collie.data.ProxyTokens(prior.accessToken,"refresh",prior.subject,System.currentTimeMillis()+1000,prior.thumbprint,prior.nonce)))
            f.server.enqueue(MockResponse.Builder().code(400).setHeader("DPoP-Nonce","issuer-nonce").body("{\"error\":\"use_dpop_nonce\"}").build())
            f.server.enqueue(MockResponse.Builder().body(f.tokens(prior.nonce)).build())
            assertNotNull(f.coordinator.credentials(f.connection));assertEquals(2,f.server.requestCount)
            val first=f.server.takeRequest();val second=f.server.takeRequest()
            assertTrue(first.body?.utf8().orEmpty().contains("grant_type=refresh_token"));assertTrue(second.body?.utf8().orEmpty().contains("refresh_token=refresh"))
            for(request in listOf(first,second)) {assertEquals("/token",request.target);assertEquals("POST",request.method);assertNull(request.headers["Authorization"]);val header=ProxyJson.objectOf(ProxyJose.decode(request.headers["DPoP"]!!.split('.')[0]).toString(Charsets.UTF_8));assertEquals(f.key.publicJwk(),header["jwk"])}
            val proof=ProxyJson.objectOf(ProxyJose.decode(second.headers["DPoP"]!!.split('.')[1]).toString(Charsets.UTF_8));assertEquals("issuer-nonce",proof["nonce"]?.toString()?.trim('"'))
        }
    }
    @Test fun bearerTokenTypeAndWrongIdNonceRefuseWithoutPairingOrBindingRetirement() {
        for(type in listOf("Bearer","DPoP")) ProxyHttpsFixture().use { f ->
            val events=mutableListOf<ProxyBindingRetirement>();f.coordinator.bindingLifecycle.observe {events.add(it)}
            val url=begin(f);f.server.enqueue(MockResponse.Builder().body(f.tokens(if(type=="DPoP") "foreign-nonce" else url.queryParameter("nonce")!!,type=type)).build())
            assertTrue(runCatching {f.coordinator.consumeCallback(callback(f,url))}.isFailure);assertNull(f.store.record()!!.tokens);assertTrue(events.isEmpty());assertEquals("pairing-secret",f.connection.token)
            assertEquals(3,f.server.requestCount)
        }
    }
    @Test fun malformedUtf8CallbackFailsBeforeConsumingOrPostingTheValidTransaction() {
        ProxyHttpsFixture().use { f ->
            val url=begin(f)
            assertTrue(runCatching { f.coordinator.consumeCallback(callback(f,url).replace("one-code","%C0%AF")) }.isFailure)
            assertNotNull(f.store.record()!!.transaction);assertEquals(2,f.server.requestCount)
            f.server.enqueue(MockResponse.Builder().body(f.tokens(url.queryParameter("nonce")!!)).build())
            assertTrue(f.coordinator.consumeCallback(callback(f,url)));assertEquals(3,f.server.requestCount)
        }
    }
    @Test fun retirementCancelsActualDelayedTokenReadAndOldCompletionCannotReplaceNewCustody() {
        ProxyHttpsFixture().use { f ->
            val url=begin(f)
            f.server.takeRequest();f.server.takeRequest()
            val responseRelease = java.util.concurrent.CountDownLatch(1)
            val delayedTokens = f.tokens(url.queryParameter("nonce")!!)
            f.server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    check(responseRelease.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse.Builder().body(delayedTokens).build()
                }
            }
            val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val answer=executor.submit<Boolean> { runCatching { f.coordinator.consumeCallback(callback(f,url)) }.getOrDefault(false) }
                assertNotNull(f.server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS))
                f.generation++;f.coordinator.retire();f.coordinator.importProfile(f.rawProfile,f.actor);f.readyTokens()
                val replacement=f.store.record()!!
                assertFalse(answer.get(5,java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(replacement.revision,f.store.record()!!.revision)
                assertEquals(replacement.tokens!!.accessToken,f.store.record()!!.tokens!!.accessToken)
                assertEquals("pairing-secret",f.connection.token);assertEquals(3,f.server.requestCount)
            } finally { responseRelease.countDown(); executor.shutdownNow() }
        }
    }

}
