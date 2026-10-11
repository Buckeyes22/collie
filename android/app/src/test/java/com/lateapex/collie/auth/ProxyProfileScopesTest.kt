package com.lateapex.collie.auth

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProxyProfileScopesTest {
    private fun profile(f: ProxyHttpsFixture, scopes: JsonElement): String =
        JsonObject(ProxyJson.objectOf(f.rawProfile) + ("scopes" to scopes)).toString()

    @Test fun declaredApiScopesReachActualPinnedAuthorizationAndExactSignedTokenValidation() {
        ProxyHttpsFixture().use { f ->
            assertEquals("openid offline_access", ProxyProfile.parse(f.rawProfile).authorizationScope)
            val raw = profile(f, buildJsonArray { add("collie.native"); add("Resource:Read") })
            f.coordinator.importProfile(raw, f.actor)
            f.server.enqueue(MockResponse().setBody(f.discovery()))
            f.server.enqueue(MockResponse().setBody(f.jwks()))
            val authorization = f.coordinator.begin(f.actor).toHttpUrl()
            assertEquals("openid offline_access collie.native Resource:Read", authorization.queryParameter("scope"))
            assertEquals("gateway", authorization.queryParameter("audience"))
            assertEquals("S256", authorization.queryParameter("code_challenge_method"))
            assertEquals(f.key.thumbprint, authorization.queryParameter("dpop_jkt"))
            f.server.enqueue(MockResponse().setBody(f.tokens(authorization.queryParameter("nonce")!!)))
            val callback = ProxyProfile.REDIRECT + "?state=" + authorization.queryParameter("state") +
                "&code=one-code&iss=" + java.net.URLEncoder.encode(f.issuer, "UTF-8")
            assertTrue(f.coordinator.consumeCallback(callback, f.actor))
            assertFalse(f.coordinator.consumeCallback(callback, f.actor))
            assertNotNull(f.coordinator.credentials(f.connection, f.actor))
            assertEquals("pairing-secret", f.connection.token)
            assertEquals(3, f.server.requestCount)
            repeat(3) {
                val request = f.server.takeRequest()
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Collie-Proxy-Authorization"))
                assertNull(request.getHeader("Cookie"))
            }
        }
    }

    @Test fun invalidOrOversizedAdditionalScopesNeverCreateIssuerCallsOrTransaction() {
        ProxyHttpsFixture().use { f ->
            val invalid = listOf(JsonNull, JsonPrimitive("collie.native"), JsonArray(emptyList()),
                buildJsonArray { add(1) }, buildJsonArray { add(buildJsonObject {}) }) +
                listOf("", "openid", "offline_access", "two scopes", "tab\tscope", "line\nscope",
                    "quote\"scope", "back\\slash", "unicodeé", "\u007f", "a".repeat(129)).map { text -> buildJsonArray { add(text) } } +
                listOf(buildJsonArray { add("collie.native"); add("collie.native") },
                    JsonArray((0..8).map { JsonPrimitive("scope$it") }),
                    JsonArray((0..7).map { JsonPrimitive("$it" + "a".repeat(60)) }))
            for (scopes in invalid) {
                assertTrue(runCatching { f.coordinator.importProfile(profile(f, scopes), f.actor) }.isFailure)
                assertNull(f.store.record()!!.transaction)
                assertNull(f.store.record()!!.tokens)
            }
            for (scopes in listOf(listOf("!#[]~"), listOf("a".repeat(128)), listOf("case", "Case"),
                (0..7).map { "$it" + "a".repeat(59) })) {
                assertEquals((listOf("openid", "offline_access") + scopes).joinToString(" "),
                    ProxyProfile.parse(profile(f, JsonArray(scopes.map { JsonPrimitive(it) }))).authorizationScope)
            }
            assertEquals(0, f.server.requestCount)
            assertEquals("pairing-secret", f.connection.token)
        }
    }
}
