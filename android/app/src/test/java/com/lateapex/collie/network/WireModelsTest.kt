package com.lateapex.collie.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

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
    fun packAndUpdateModelsDecodeCurrentBridgeContract() {
        val pack = json.decodeFromString(
            PackStatusResponse.serializer(),
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
