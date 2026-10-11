package com.lateapex.collie.auth

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProxyIssuerCompatibilityTest {
    private fun callback(f: ProxyHttpsFixture, url: okhttp3.HttpUrl, issuer: String = f.issuer) =
        ProxyProfile.REDIRECT + "?state=" + url.queryParameter("state") + "&code=one-code&iss=" + java.net.URLEncoder.encode(issuer, "UTF-8")

    @Test fun rootIssuerWithoutSlashCompletesRealPinnedExchangeWithExactIssuerBinding() {
        ProxyHttpsFixture(issuerWithoutSlash = true).use { f ->
            val profile = ProxyProfile.parse(f.rawProfile)
            assertFalse(profile.issuer.endsWith("/"))
            assertEquals(f.issuer, profile.issuer)
            f.server.enqueue(MockResponse().setBody(f.discovery()))
            f.server.enqueue(MockResponse().setBody(f.jwks()))
            val url = f.coordinator.begin(f.actor).toHttpUrl()
            assertEquals("S256", url.queryParameter("code_challenge_method"))
            assertEquals(f.key.thumbprint, url.queryParameter("dpop_jkt"))
            assertTrue(runCatching { f.coordinator.consumeCallback(callback(f, url, f.issuer + "/")) }.isFailure)
            assertEquals(2, f.server.requestCount)
            assertNull(f.store.record()!!.transaction)
            assertNull(f.store.record()!!.tokens)
            assertFalse(f.coordinator.consumeCallback(callback(f, url)))
            assertEquals(2, f.server.requestCount)
            // A rejected callback consumes its single-use transaction; deliberate retry begins anew.
            f.server.enqueue(MockResponse().setBody(f.discovery()))
            f.server.enqueue(MockResponse().setBody(f.jwks()))
            val retryUrl = f.coordinator.begin(f.actor).toHttpUrl()
            assertNotEquals(url.queryParameter("state"), retryUrl.queryParameter("state"))
            assertNotEquals(url.queryParameter("nonce"), retryUrl.queryParameter("nonce"))
            assertNotNull(f.store.record()!!.transaction)
            f.server.enqueue(MockResponse().setBody(f.tokens(retryUrl.queryParameter("nonce")!!)))
            assertTrue(f.coordinator.consumeCallback(callback(f, retryUrl)))
            assertFalse(f.coordinator.consumeCallback(callback(f, retryUrl)))
            assertEquals(5, f.server.requestCount)
            val discovery = f.server.takeRequest()
            val jwks = f.server.takeRequest()
            val retryDiscovery = f.server.takeRequest()
            val retryJwks = f.server.takeRequest()
            val token = f.server.takeRequest()
            assertEquals("/.well-known/openid-configuration", retryDiscovery.path)
            assertEquals("/jwks", retryJwks.path)
            assertEquals("/.well-known/openid-configuration", discovery.path)
            assertEquals("/jwks", jwks.path)
            assertEquals("/token", token.path)
            assertEquals("POST", token.method)
            for (request in listOf(discovery, jwks, retryDiscovery, retryJwks, token)) {
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Collie-Proxy-Authorization"))
                assertNull(request.getHeader("Cookie"))
            }
            assertNotNull(f.coordinator.credentials(f.connection))
            assertEquals("pairing-secret", f.connection.token)
            assertEquals(f.issuer, ProxyProfile.parse(f.store.record()!!.profile).issuer)
        }
    }

    @Test fun discoveryAndSignedTokenIssuerMustRemainLiteralWithoutSlashNormalization() {
        for (stage in listOf("discovery", "token")) ProxyHttpsFixture(issuerWithoutSlash = true).use { f ->
            val events = mutableListOf<ProxyBindingRetirement>()
            f.coordinator.bindingLifecycle.observe { events.add(it) }
            val discovery = if (stage == "discovery") JsonObject(ProxyJson.objectOf(f.discovery()) + ("issuer" to JsonPrimitive(f.issuer + "/"))).toString() else f.discovery()
            f.server.enqueue(MockResponse().setBody(discovery))
            if (stage == "discovery") {
                assertTrue(runCatching { f.coordinator.begin(f.actor) }.isFailure)
                assertEquals(1, f.server.requestCount)
            } else {
                f.server.enqueue(MockResponse().setBody(f.jwks()))
                val url = f.coordinator.begin(f.actor).toHttpUrl()
                f.server.enqueue(MockResponse().setBody(f.tokens(url.queryParameter("nonce")!!, signedIssuer = f.issuer + "/")))
                assertTrue(runCatching { f.coordinator.consumeCallback(callback(f, url)) }.isFailure)
                assertEquals(3, f.server.requestCount)
            }
            assertNull(f.store.record()!!.tokens)
            assertTrue(events.isEmpty())
            assertEquals("pairing-secret", f.connection.token)
        }
    }

    @Test fun issuerExceptionRefusesCanonicalizationAliasesAndNeverAppliesToOtherEndpoints() {
        ProxyHttpsFixture().use { f ->
            val original = ProxyJson.objectOf(f.rawProfile)
            fun parse(key: String, value: String) = ProxyProfile.parse(JsonObject(original + (key to JsonPrimitive(value))).toString())
            for (issuer in listOf("https://LOCALHOST", "https://localhost:443", "https://user@localhost", "https://localhost?x=1", "https://localhost#fragment", "https://localhost/%2f", "https://localhost/%5c", "https://localhost/../", "https://localhost.evil")) {
                assertTrue(issuer, runCatching { parse("issuer", issuer) }.isFailure)
            }
            for (key in listOf("authorizationEndpoint", "tokenEndpoint", "jwksEndpoint", "gatewayOrigin")) {
                assertTrue(key, runCatching { parse(key, f.server.url("/").toString().removeSuffix("/")) }.isFailure)
            }
            assertEquals(0, f.server.requestCount)
        }
    }
}
