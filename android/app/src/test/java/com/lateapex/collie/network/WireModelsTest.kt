package com.lateapex.collie.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun boundedStatusObjectCompatibilityDecodesItsState() {
        val body = """{"bridge":"connected","agents":[{"paneId":"fixture:pane","workspaceId":"w1","workspaceLabel":"Owned HTTPS acceptance","workspaceNumber":1,"tabId":"t1","agent":"claude","status":{"state":"idle","label":"idle"},"cwd":"/tmp","focused":true}],"shellPanes":[],"workspaces":[],"tabs":[],"ts":42}"""
        assertEquals(AgentStatus.IDLE, json.decodeFromString(SnapshotResponse.serializer(), body).agents.single().status)
        assertEquals(AgentStatus.WORKING, json.decodeFromString(AgentStatus.serializer(), "\"working\""))
        assertEquals(AgentStatus.UNKNOWN, json.decodeFromString(AgentStatus.serializer(), """{"state":"future-state","label":"future"}"""))
    }

    @Test
    fun malformedStatusObjectsCannotInventAState() {
        for (body in listOf("null", "[]", "42", "{}", """{"state":false}""", """{"label":"working"}""")) {
            assertThrows(SerializationException::class.java) { json.decodeFromString(AgentStatus.serializer(), body) }
        }
    }

    @Test
    fun snapshotToleratesAdditiveFieldsButKeepsRequiredIdentityStrict() {
        val body = """
            {
              "bridge":"connected",
              "agents":[{"paneId":"w1:p1","workspaceId":"w1","workspaceLabel":"collie","workspaceNumber":1,"tabId":"w1:t1","agent":"codex","status":"working","cwd":"/repo","focused":true,"future":"ok"}],
              "shellPanes":[],"workspaces":[],"tabs":[],"sessions":[],"ts":42,"futureTop":true
            }
        """.trimIndent()
        val snapshot = json.decodeFromString(SnapshotResponse.serializer(), body)
        assertEquals("w1:p1", snapshot.agents.single().paneId)
        assertEquals(42L, snapshot.ts)
    }

    @Test
    fun malformedSuccessfulPaneCannotInventRequiredText() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(
                PaneReadResponse.serializer(),
                """{"paneId":"w1:p1","truncated":false,"revision":1}""",
            )
        }
    }

    @Test
    fun transcriptUnionAndWorktreeAnswerRemainInspectable() {
        val history = json.decodeFromString(
            PaneHistoryResponse.serializer(),
            """{"paneId":"w1:p1","available":true,"entries":[{"uuid":"u1","ts":"2026-01-01","role":"assistant","parts":[{"kind":"tool","name":"read","summary":"file","result":{"text":"ok"}}]}],"hasMore":true,"total":2,"fileTruncated":false}""",
        )
        val worktree = json.decodeFromString(
            WorktreeOpenResponse.serializer(),
            """{"ok":true,"alreadyOpen":true,"pane":{"paneId":"p2","workspaceId":"w2","workspaceLabel":"feature","tabId":"t2","cwd":"/repo-feature"}}""",
        )

        assertEquals("read", history.entries.single().parts.single().name)
        assertEquals("ok", history.entries.single().parts.single().result?.text)
        assertEquals(true, worktree.alreadyOpen)
        assertEquals("w2", worktree.pane?.workspaceId)
    }

    @Test
    fun transcriptWireDecodeKeepsStructuredQuestionAndIndependentResultFlags() {
        val entry = json.decodeFromString(
            TranscriptEntry.serializer(),
            """{"uuid":"q1","ts":"2026-01-01","role":"assistant","parts":[{"kind":"tool","name":"question","summary":"Pick","id":"call-1","call":{"kind":"question","name":"question","summary":"Pick","questions":[{"header":"Color","question":"Which?","multiple":true,"options":[{"label":"Blue","description":"Cool"}]}],"answers":[["Blue"]]},"result":{"text":"Blue","denied":false,"isError":false,"truncated":true,"imageUrl":"https://example.test/result.png"}}]}""",
        )
        val part = entry.parts.single()

        assertEquals("call-1", part.id)
        assertEquals("question", part.call?.get("kind")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(true, part.result?.truncated)
        assertEquals(false, part.result?.denied)
        assertEquals(false, part.result?.isError)
        assertEquals("https://example.test/result.png", part.result?.imageUrl)
    }

    @Test
    fun packAndUpdateModelsDecodeCurrentBridgeContract() {
        val pack = json.decodeFromString(
            CrewStatusResponse.serializer(),
            """{"pack":{"id":"pack-1","name":"lab","secretGeneration":2,"rotatedAt":10},"self":{"id":"lead","name":"Lead","version":"1.5.0"},"deputy":null,"members":[{"id":"lead","name":"Lead","isLead":true,"health":"reachable","lastSeenAt":11,"secretBehind":false,"provisional":false}],"ts":12}""",
        )
        val update = json.decodeFromString(
            UpdateCheckResponse.serializer(),
            """{"current":"1.5.0","latest":"1.6.0","latestUrl":"https://example.invalid/release","releaseAvailable":true,"majorAvailable":null,"majorUrl":null,"installKind":"binary","bridgeStale":false,"checkedAt":12,"preflight":{"schema":1,"verdict":"green","checks":[]}}""",
        )

        assertEquals("lead", pack.members.single().id)
        assertEquals("green", update.preflight?.verdict)
    }

    @Test
    fun configCarriesSpeechCapabilityAndOperatorCommonFlag() {
        val config = json.decodeFromString(
            BridgeConfigResponse.serializer(),
            """{"operatorCommands":[{"command":"/deploy","common":false}],"operatorKeys":[{"label":"Interrupt","keys":["ctrl+c"]}],"stt":{"provider":"openai-compatible","available":false,"reason":"missing model"}}""",
        )
        val older = json.decodeFromString(
            BridgeConfigResponse.serializer(),
            """{"operatorCommands":[{"command":"/local"}]}""",
        )

        assertEquals(false, config.operatorCommands.single().common)
        assertEquals(listOf("ctrl+c"), config.operatorKeys.single().keys)
        assertEquals("missing model", config.stt?.reason)
        assertEquals(true, older.operatorCommands.single().common)
        assertEquals(null, older.stt)
    }

    @Test
    fun muxCapabilityAnswersAreFailOpenOnlyWhenAbsent() {
        val mux = json.decodeFromString(
            MuxConfigResponse.serializer(),
            """{"name":"limited","capabilities":{"createSpace":false,"createTab":true}}""",
        )

        assertEquals(false, mux.supports("createSpace"))
        assertEquals(true, mux.supports("createTab"))
        assertEquals(true, mux.supports("futureCapability"))
    }

    @Test
    fun muxFreshnessAndReadLimitsAreOptionalAndUnknownKindsStayHarmless() {
        val declared = json.decodeFromString(
            MuxConfigResponse.serializer(),
            """{"name":"zellij","topologyLatency":{"kind":"bounded","ms":12000},"readLimits":{"maxRequestedLines":10000}}""",
        )
        val unknown = json.decodeFromString(
            MuxConfigResponse.serializer(),
            """{"topologyLatency":{"kind":"future","ms":-1},"readLimits":{"maxRequestedLines":10001}}""",
        )
        val pane = json.decodeFromString(
            PaneReadResponse.serializer(),
            """{"paneId":"p","text":"a\\nb","truncated":true,"revision":1,"requestedLines":1000,"servedLines":2,"readableLines":1500,"limitReason":"adapter-ceiling"}""",
        )

        assertEquals(12_000L, declared.topologyLatency?.displayBoundMs())
        assertEquals(10_000, declared.readLimits?.verifiedMaximum())
        assertEquals(null, unknown.topologyLatency?.displayBoundMs())
        assertEquals(null, unknown.readLimits?.verifiedMaximum())
        assertEquals(2, pane.servedLines)
        assertEquals("adapter-ceiling", pane.limitReason)
    }

    @Test
    fun audioUploadEnforcesTheBridgeContainerAndSizeContract() {
        assertEquals("audio/mp4; codecs=aac", AudioUpload("audio/mp4; codecs=aac", byteArrayOf(1)).contentType)
        assertThrows(IllegalArgumentException::class.java) {
            AudioUpload("application/octet-stream", byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioUpload("audio/mp4", ByteArray(AudioUpload.MAX_BYTES + 1))
        }
    }
}
