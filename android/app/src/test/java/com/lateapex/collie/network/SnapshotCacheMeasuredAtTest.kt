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
import org.junit.Assert.*
import org.junit.Test

class SnapshotCacheMeasuredAtTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test fun actualCodexFractionalMtimeSnapshotDecodesThroughMountedHttpsWithoutDroppingOtherPanes() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory())
            server.start()
            val client = CollieApiClient.defaultHttpClient().newBuilder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            val api = CollieApiClient(client, json)
            val connection = Connection(CollieOrigin(server.url("/completion/").toString()), "fixture phone", "fixture-token")
            // Minimized actual owned stock HTTPS response: its genuine Codex cache mtime had
            // this fraction and blocked the entire dashboard, including the unaffected shell.
            server.enqueue(MockResponse.Builder().body(SNAPSHOT).build())
            val result = api.snapshot(connection, Scope())
            assertTrue("A legitimate fractional cache observation cannot invalidate the live snapshot", result is ApiResult.Success)
            val snapshot = (result as ApiResult.Success).value
            val codex = snapshot.agents.single()
            assertEquals("w7:p1", codex.paneId)
            assertEquals("gpt-6.1-sol", codex.model)
            val cache = requireNotNull(codex.cache)
            assertEquals(1_791_950_656_872.6829, requireNotNull(cache.measuredAt), 0.0)
            assertEquals(1_791_950_700_000L, cache.expiresAt)
            assertEquals(1_791_950_600_000L, cache.lastRequestAt)
            assertEquals("w1:p1", snapshot.shellPanes.single().paneId)
            assertEquals(AgentStatus.IDLE, snapshot.shellPanes.single().status)
            assertEquals("/completion/api/snapshot", server.takeRequest().target)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun measuredTimePreservesFiniteFractionAndKeepsNullableAndIntegerCompatibility() {
        for (value in listOf("1791950656872", "1791950656872.6829", "1.7919506568726829e12")) {
            val cache = json.decodeFromString(PaneCacheReading.serializer(), "{\"measuredAt\":$value}")
            assertEquals(value.toDouble(), requireNotNull(cache.measuredAt), 0.0)
            assertEquals(cache, json.decodeFromString(PaneCacheReading.serializer(), json.encodeToString(PaneCacheReading.serializer(), cache)))
        }
        assertNull(json.decodeFromString(PaneCacheReading.serializer(), "{}").measuredAt)
        assertNull(json.decodeFromString(PaneCacheReading.serializer(), "{\"measuredAt\":null}").measuredAt)
        assertEquals(0.9, requireNotNull(json.decodeFromString(PaneCacheReading.serializer(), "{\"measuredAt\":0.9}").measuredAt), 0.0)
    }

    private companion object {
        const val SNAPSHOT = """{"bridge":"herdr","agents":[{"paneId":"w7:p1","workspaceId":"w7","workspaceLabel":"Acceptance Codex","workspaceNumber":7,"tabId":"t1","agent":"codex","status":"idle","cwd":"/home/operator/collie-completion-20261013","focused":true,"model":"gpt-6.1-sol","cache":{"state":"warm","expiresAt":1791950700000,"ttlSeconds":300,"ruleId":"codex","confidence":"reported","lastRequestAt":1791950600000,"measuredAt":1791950656872.6829}}],"shellPanes":[{"paneId":"w1:p1","workspaceId":"w1","workspaceLabel":"Acceptance shell","workspaceNumber":1,"tabId":"t1","agent":"shell","status":"idle","cwd":"/home/operator/collie-completion-20261013","focused":false}],"workspaces":[],"tabs":[],"sessions":[],"notifications":{"snoozedUntil":null},"ts":1791950660000}"""
    }
}
