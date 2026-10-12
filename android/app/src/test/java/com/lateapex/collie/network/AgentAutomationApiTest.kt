package com.lateapex.collie.network

import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.CertificatePinner
import mockwebserver3.MockWebServer
import mockwebserver3.MockResponse
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AgentAutomationApiTest {
    private val info = """{"paneId":"w1:p1","terminalId":"terminal-A","workspaceId":"w1","tabId":"w1:t1","runtimeIdentity":"runtime-A","agent":"codex","sessionIdentity":null,"processIdentity":null,"name":"named","status":"idle","focused":true,"interactiveReady":true,"launchPending":false,"stateChangeSeq":"0","completionSeq":"0"}"""
    @Test fun actualPinnedHttpsMountedNamedReadsAndClosedWrongPrimitiveResponsesNeverCoerce() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { protocols = listOf(Protocol.HTTP_1_1); useHttps(serverCertificates.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(certificate.certificate)).build()).protocols(listOf(Protocol.HTTP_1_1)).build()
        try {
            val api = CollieApiClient(client, Json { ignoreUnknownKeys = true }); val connection = Connection(CollieOrigin(server.url("/nested/collie/").toString()), "fixture", "original-fixture-token"); val address = PaneAddress(Scope("peer", "session"), "w1:p1")
            fun response(body: String) = MockResponse.Builder().setHeader("Content-Type", "application/json").chunkedBody(body, 1).build()
            val positive = """{"ok":true,"schema":1,"info":$info,"witness":"${"a".repeat(64)}"}"""
            server.enqueue(response(positive)); assertTrue(api.namedAgentControl(connection, address) is ApiResult.Success)
            val first = server.takeRequest(5, TimeUnit.SECONDS)!!; assertEquals("/nested/collie/api/pane/w1%3Ap1/agent/control?host=peer&session=session", first.target); assertEquals("Bearer original-fixture-token", first.headers["Authorization"])
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/plain").body(positive).build()); assertTrue(api.namedAgentControl(connection, address) is ApiResult.Failure); server.takeRequest(5, TimeUnit.SECONDS)!!
            val original = Json.parseToJsonElement(positive) as JsonObject; val originalInfo = original["info"] as JsonObject
            for (field in originalInfo.keys) for (bad in listOf(JsonObject(emptyMap()), JsonArray(emptyList()), JsonNull)) {
                val alteredInfo = JsonObject(originalInfo.toMutableMap().apply { put(field, bad) }); val body = JsonObject(original.toMutableMap().apply { put("info", alteredInfo) })
                // Nullable identities legitimately accept JSON null; objects and arrays never do.
                if (bad == JsonNull && field in setOf("agent", "sessionIdentity", "processIdentity", "name", "stateChangeSeq", "completionSeq")) continue
                server.enqueue(response(body.toString())); assertTrue("wrong primitive $field", api.namedAgentControl(connection, address) is ApiResult.Failure); server.takeRequest(5, TimeUnit.SECONDS)!!
            }
            server.enqueue(response(positive.replace("\"schema\":1", "\"schema\":2,\"schema\":1"))); assertTrue(api.namedAgentControl(connection, address) is ApiResult.Failure); server.takeRequest(5, TimeUnit.SECONDS)!!
            server.enqueue(response(positive.replace("\"focused\":true", "\"focused\":\"true\""))); assertTrue(api.namedAgentControl(connection, address) is ApiResult.Failure); server.takeRequest(5, TimeUnit.SECONDS)!!
            server.enqueue(response("""{"ok":true,"schema":1,"agents":[$info]}""")); assertTrue(api.namedAgentList(connection, address.scope) is ApiResult.Success); assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.target!!.startsWith("/nested/collie/api/agents/named?"))
            server.enqueue(response("""{"ok":true,"schema":1,"paneId":"w1:p1","terminalId":"terminal-A","text":"bounded owned output","truncated":false}""")); assertTrue(api.namedAgentRead(connection, address, "recent", 200) is ApiResult.Success); assertEquals("/nested/collie/api/pane/w1%3Ap1/agent/read?host=peer&session=session&source=recent&lines=200", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
            val before = server.requestCount; assertTrue(api.namedAgentRead(connection, address, "arbitrary", 200) is ApiResult.Failure); assertTrue(api.namedAgentAction(connection, address, "id", "a".repeat(64), NamedAgentAction.Prompt(""), null) is ApiResult.Failure); assertEquals(before, server.requestCount)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.close() }
    }
    @Test fun nullableSequenceAndClosedProgressFieldsRetainExactTypes() {
        val row = Json.parseToJsonElement("""{"ok":true,"schema":1,"info":$info,"witness":"${"a".repeat(64)}"}""") as JsonObject
        assertNotNull(ClosedAgentJson.parse(row.toString())); assertNull(ClosedAgentJson.parse("{\"a\":1,\"a\":2}")); assertNull(ClosedAgentJson.parse("{\"a\":\"\\uD800\"}")); assertNull(ClosedAgentJson.parse("{\"a\":1e999}"))
        assertTrue(agentControlJson(row, "w1:p1")); assertFalse(agentControlJson(JsonObject(row.toMutableMap().apply { put("schema", JsonPrimitive("1")) }), "w1:p1"))
        assertFalse(NamedAgentAction.Prompt("\uD800").valid()); assertFalse(NamedAgentAction.Start("bad name", "codex").valid()); assertFalse(NamedAgentWait.Output("recent", "x".repeat(257), regex = true).valid())
    }
}
