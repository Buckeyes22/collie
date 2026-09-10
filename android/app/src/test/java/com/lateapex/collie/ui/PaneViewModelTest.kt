package com.lateapex.collie.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.DeviceAuthorization
import com.lateapex.collie.network.HealthResponse
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneHistoryResponse
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import com.lateapex.collie.ui.terminal.AgentSemanticParser
import com.lateapex.collie.ui.terminal.TerminalDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaneViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun partialDeliveryWarningSurvivesTheAutomaticPaneRefresh() = runTest(dispatcher) {
        val fixture = fixture(
            ApiResult.Success(
                ActionResponse(ok = false, error = "submit failed", textDelivered = true),
                200,
            ),
        )
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.sendReply("hello")
        advanceUntilIdle()

        assertEquals(
            "Text was already typed, but submit did not complete. Check the pane; do not resend it blindly.",
            fixture.viewModel.state.value.mutationError,
        )
        assertFalse(fixture.viewModel.state.value.clearReplyDraft)
        assertEquals(1, fixture.api.replyCalls)
    }

    @Test
    fun revokedWriteDemotesTheConnectionAndDisarmsControls() = runTest(dispatcher) {
        val fixture = fixture(
            ApiResult.Failure(ApiFailure.Http(403, "device not paired", null)),
        )
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.sendReply("hello")
        advanceUntilIdle()

        assertFalse(fixture.viewModel.state.value.canWrite)
        assertFalse(fixture.store.connection.value?.isPaired == true)
        assertEquals(1, fixture.api.replyCalls)
    }

    @Test
    fun frontProxyDenialMakesAnOtherwisePairedPaneReadOnlyAndBlocksWrites() = runTest(dispatcher) {
        val fixture = fixture(
            ApiResult.Success(ActionResponse(ok = true), 200),
            deviceAuthorization = DeviceAuthorization(enforced = true, device = "phone", authorized = false),
        )

        assertFalse(fixture.viewModel.state.value.canWrite)
        assertEquals(text(com.lateapex.collie.R.string.pane_status_checking_device), fixture.viewModel.state.value.status)
        fixture.viewModel.refresh()
        advanceUntilIdle()

        assertFalse(fixture.viewModel.state.value.canWrite)
        assertEquals(text(com.lateapex.collie.R.string.pane_status_unauthorised), fixture.viewModel.state.value.status)
        fixture.viewModel.sendReply("hello")
        fixture.viewModel.sendKey("Enter")
        advanceUntilIdle()
        assertEquals(0, fixture.api.replyCalls)
        assertTrue(fixture.api.keysCalls.isEmpty())
    }

    @Test
    fun absentDisabledOrLaterAuthorizedProxyGateKeepsOrRestoresPairedWrites() = runTest(dispatcher) {
        val fixture = fixture(
            ApiResult.Success(ActionResponse(ok = true), 200),
            deviceAuthorization = null,
        )

        fixture.viewModel.refresh()
        advanceUntilIdle()
        assertTrue(fixture.viewModel.state.value.canWrite)

        fixture.api.deviceAuthorization = DeviceAuthorization(enforced = false, device = "phone", authorized = false)
        fixture.viewModel.refresh()
        advanceUntilIdle()
        assertTrue(fixture.viewModel.state.value.canWrite)

        fixture.api.deviceAuthorization = DeviceAuthorization(enforced = true, device = "phone", authorized = false)
        fixture.viewModel.refresh()
        advanceUntilIdle()
        assertFalse(fixture.viewModel.state.value.canWrite)

        fixture.api.deviceAuthorization = DeviceAuthorization(enforced = true, device = "phone", authorized = true)
        fixture.viewModel.refresh()
        advanceUntilIdle()
        assertTrue(fixture.viewModel.state.value.canWrite)
        assertNull(fixture.viewModel.state.value.status)
    }

    @Test
    fun staleAuthorizationSnapshotCannotEnableANewConnection() = runTest(dispatcher) {
        val fixture = fixture(
            ApiResult.Success(ActionResponse(ok = true), 200),
            deviceAuthorization = DeviceAuthorization(enforced = false, device = "phone", authorized = false),
        )
        fixture.api.snapshotHook = {
            fixture.store.save(
                Connection(CollieOrigin("https://other.example/"), "phone", "other-token"),
            )
        }

        fixture.viewModel.refresh()
        advanceUntilIdle()

        assertFalse(fixture.viewModel.state.value.canWrite)
        assertEquals(text(com.lateapex.collie.R.string.pane_status_checking_device), fixture.viewModel.state.value.status)

        fixture.api.snapshotHook = null
        fixture.viewModel.refresh()
        advanceUntilIdle()
        assertTrue(fixture.viewModel.state.value.canWrite)
    }

    @Test
    fun directTypingSendsExactlyTheCommittedKeysWithoutAddingEnter() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.beginDirectTyping()
        fixture.viewModel.sendDirectKeys(listOf("h", "i", "Space", "👋"))
        advanceUntilIdle()

        assertEquals(listOf("h", "i", "Space", "👋"), fixture.api.lastKeys)
        assertFalse(fixture.api.lastKeys.contains("Enter"))
    }

    @Test
    fun aConfirmationNoticeClearsItselfInsteadOfSittingOverTheMirror() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.beginDirectTyping()
        fixture.viewModel.sendDirectKeys(listOf("a"))
        advanceTimeBy(PaneViewModel.STATUS_NOTICE_MS / 2)
        runCurrent()
        assertEquals(text(com.lateapex.collie.R.string.pane_typed), fixture.viewModel.state.value.status)

        advanceTimeBy(PaneViewModel.STATUS_NOTICE_MS)
        runCurrent()
        assertNull(fixture.viewModel.state.value.status)
    }

    @Test
    fun directTypingIsUnboundSoAnEchoLandingBetweenKeystrokesDropsNothing() = runTest(dispatcher) {
        // Herdr echoes each key onto the prompt line after the post-send read: with a tail-region
        // binding every second key was refused as "screen changed" on the S25 Ultra (2026-09-10).
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.refresh()
        advanceUntilIdle()
        fixture.api.keysHook = { keys ->
            val typed = keys.joinToString("") { if (it == "Space") " " else it }
            fixture.api.pane = fixture.api.pane.copy(text = fixture.api.pane.text + typed)
        }

        fixture.viewModel.beginDirectTyping()
        fixture.viewModel.sendDirectKeys(listOf("x"))
        advanceUntilIdle()
        fixture.viewModel.sendDirectKeys(listOf("y"))
        advanceUntilIdle()
        fixture.viewModel.sendDirectKeys(listOf("Space"))
        advanceUntilIdle()

        assertEquals(listOf(listOf("x"), listOf("y"), listOf("Space")), fixture.api.keysCalls)
        assertNull(fixture.api.lastExpectedPrompt)
        assertNull(fixture.viewModel.state.value.mutationError)
    }

    @Test
    fun directTypingQueuesRapidCommitsAndPreservesOrder() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.beginDirectTyping()
        fixture.viewModel.sendDirectKeys(listOf("a"))
        fixture.viewModel.sendDirectKeys(listOf("b", "c"))
        advanceUntilIdle()

        assertEquals(listOf(listOf("a"), listOf("b", "c")), fixture.api.keysCalls)
    }

    @Test
    fun directTypingSplitsLargeCommitsIntoBoundedBatches() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.refresh()
        advanceUntilIdle()
        val keys = (0 until 70).map { ('a'.code + it % 26).toChar().toString() }

        fixture.viewModel.beginDirectTyping()
        fixture.viewModel.sendDirectKeys(keys)
        advanceUntilIdle()

        assertEquals(listOf(64, 6), fixture.api.keysCalls.map(List<String>::size))
        assertEquals(keys, fixture.api.keysCalls.flatten())
    }

    @Test
    fun directFailureDiscardsEverythingStillQueued() = runTest(dispatcher) {
        val fixture = fixture(
            ApiResult.Success(ActionResponse(ok = true), 200),
            listOf(ApiResult.Success(ActionResponse(ok = false, error = "refused"), 200)),
        )
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.beginDirectTyping()
        fixture.viewModel.sendDirectKeys(List(70) { "x" })
        advanceUntilIdle()

        assertEquals(1, fixture.api.keysCalls.size)
        assertEquals("refused", fixture.viewModel.state.value.mutationError)
    }

    @Test
    fun endingDirectTypingDropsQueuedButNotAlreadyCapturedKeys() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.beginDirectTyping()
        fixture.viewModel.sendDirectKeys(listOf("a"))
        fixture.viewModel.sendDirectKeys(listOf("b"))
        fixture.viewModel.endDirectTyping()
        advanceUntilIdle()

        assertEquals(listOf(listOf("a")), fixture.api.keysCalls)
    }

    @Test
    fun directTypingCannotRearmUntilAnAlreadyCapturedBatchFinishes() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.refresh()
        advanceUntilIdle()

        assertTrue(fixture.viewModel.beginDirectTyping())
        fixture.viewModel.sendDirectKeys(listOf("a"))
        fixture.viewModel.endDirectTyping()
        assertFalse(fixture.viewModel.beginDirectTyping())
        advanceUntilIdle()

        assertTrue(fixture.viewModel.beginDirectTyping())
        fixture.viewModel.sendDirectKeys(listOf("b"))
        advanceUntilIdle()
        assertEquals(listOf(listOf("a"), listOf("b")), fixture.api.keysCalls)
    }

    @Test
    fun directTypingOverflowFailsClosedBeforeSending() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.beginDirectTyping()
        fixture.viewModel.sendDirectKeys(List(PaneViewModel.MAX_DIRECT_KEYS_PENDING + 1) { "x" })
        advanceUntilIdle()

        assertTrue(fixture.api.keysCalls.isEmpty())
        assertTrue(fixture.viewModel.state.value.mutationError?.contains("too much input") == true)
    }

    @Test
    fun loadOlderGrowsThePolledPaneWindowToTheHerdrCap() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "shell")
        fixture.viewModel.refresh()
        advanceUntilIdle()

        assertEquals(600, fixture.viewModel.state.value.loadedLines)
        assertEquals(1_000, fixture.viewModel.loadOlderScrollback())
        assertTrue(fixture.viewModel.state.value.loadingOlder)
        advanceUntilIdle()

        assertEquals(listOf(600, 1_000), fixture.api.paneLines)
        assertEquals(1_000, fixture.viewModel.state.value.requestedLines)
        assertEquals(1_000, fixture.viewModel.state.value.loadedLines)
        assertFalse(fixture.viewModel.state.value.loadingOlder)
        assertNull(fixture.viewModel.loadOlderScrollback())
    }

    @Test
    fun semanticActionFreshReadsThenSendsExactlyOnceWithTheLiteralRegion() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        fixture.api.pane = semanticPane()
        fixture.viewModel.refresh()
        advanceUntilIdle()
        val surface = AgentSemanticParser.detect("claude", fixture.api.pane.text, fixture.api.pane.revision)!!

        fixture.viewModel.sendSemanticAction(surface, surface.actions.first())
        advanceUntilIdle()

        assertEquals(listOf(listOf("1", "Enter")), fixture.api.keysCalls)
        assertEquals(surface.regionSignature, fixture.api.lastExpectedPrompt)
    }

    @Test
    fun semanticActionSendsNothingWhenFreshDialogTextOrRevisionChanges() = runTest(dispatcher) {
        for (fresh in listOf(
            semanticPane().copy(text = semanticPane().text.replace("Blue", "Green")),
            semanticPane().copy(revision = 8),
        )) {
            val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
            fixture.api.pane = semanticPane()
            fixture.viewModel.refresh()
            advanceUntilIdle()
            val surface = AgentSemanticParser.detect("claude", fixture.api.pane.text, fixture.api.pane.revision)!!
            fixture.api.pane = fresh

            fixture.viewModel.sendSemanticAction(surface, surface.actions.first())
            advanceUntilIdle()

            assertTrue(fixture.api.keysCalls.isEmpty())
            assertTrue(fixture.viewModel.state.value.mutationError?.contains("changed") == true)
        }
    }

    @Test
    fun terminalWritesAreBlockedWhenPaneIsGoneHostIsUnavailableOrMuxCannotSend() = runTest(dispatcher) {
        val gone = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        gone.api.snapshotPanes = emptyList()
        gone.viewModel.refresh()
        advanceUntilIdle()
        gone.viewModel.sendReply("hello")
        gone.viewModel.sendKey("Enter")
        assertEquals(0, gone.api.replyCalls)
        assertTrue(gone.api.keysCalls.isEmpty())
        assertTrue(gone.viewModel.state.value.mutationError?.contains("no longer exists") == true)

        val remote = PaneAddress(Scope(host = "remote", session = "main"), "w1:p1")
        val unreachable = fixture(ApiResult.Success(ActionResponse(ok = true), 200), address = remote)
        unreachable.api.snapshotPanes = listOf(paneSummary(remote))
        unreachable.api.servers = listOf(server(reachable = false))
        unreachable.viewModel.refresh()
        advanceUntilIdle()
        assertFalse(unreachable.viewModel.beginDirectTyping())
        assertTrue(unreachable.viewModel.state.value.mutationError?.contains("unreachable") == true)

        val unsupported = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        unsupported.api.config = BridgeConfigResponse(
            mux = MuxConfigResponse(
                capabilities = mapOf("typeText" to true, "sendKeys" to false),
                notes = mapOf("sendKeys" to "This adapter cannot send named keys."),
            ),
        )
        unsupported.viewModel.refresh()
        advanceUntilIdle()
        unsupported.viewModel.sendReply("hello")
        assertEquals("This adapter cannot send named keys.", unsupported.viewModel.state.value.mutationError)
        assertEquals(0, unsupported.api.replyCalls)
    }

    @Test
    fun takenOverDraftIsBoundClearedAndReverifiedBeforeReply() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        val rule = "─".repeat(40)
        fixture.api.pane = PaneReadResponse(
            "w1:p1",
            "prior\n$rule\n❯ continue from host\n$rule\n  Opus · /repo",
            false,
            10,
        )
        fixture.api.keysHook = {
            fixture.api.pane = fixture.api.pane.copy(
                text = "prior\n$rule\n❯ \n$rule\n  Opus · /repo",
                revision = 11,
            )
        }
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.sendReply("continue from host", TerminalDraft("continue from host"))
        advanceUntilIdle()

        assertEquals("ctrl+k", fixture.api.keysCalls.single().first())
        assertEquals(
            "continue from host".codePointCount(0, "continue from host".length) +
                PaneViewModel.TAKEOVER_BACKSPACE_MARGIN,
            fixture.api.keysCalls.single().drop(1).count { it == "Backspace" },
        )
        assertEquals(1, fixture.api.replyCalls)
        assertTrue(fixture.api.lastReplyExpectedPrompt?.contains("❯") == true)
        assertTrue(fixture.viewModel.state.value.clearReplyDraft)
    }

    @Test
    fun changedTakenOverDraftRefusesTheClearAndReply() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        val rule = "─".repeat(40)
        fixture.api.pane = PaneReadResponse(
            "w1:p1",
            "$rule\n❯ host changed this\n$rule\n  Opus · /repo",
            false,
            10,
        )
        fixture.viewModel.refresh()
        advanceUntilIdle()

        fixture.viewModel.sendReply("old host draft", TerminalDraft("old host draft"))
        advanceUntilIdle()

        assertTrue(fixture.api.keysCalls.isEmpty())
        assertEquals(0, fixture.api.replyCalls)
        assertTrue(fixture.viewModel.state.value.mutationError?.contains("changed") == true)
    }

    @Test
    fun pollMergesTranscriptEntriesByUuid() = runTest(dispatcher) {
        val model = fixture(ApiResult.Success(ActionResponse(ok = true), 200)).let { f ->
            f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(entry("a"), entry("b")))
            f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(entry("b"), entry("c")))
            f.viewModel
        }
        model.refresh(); advanceUntilIdle()
        model.refresh(); advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), model.state.value.transcript.map { it.uuid })
        assertEquals(true, model.state.value.transcriptAvailable)
    }

    @Test
    fun unavailableHistoryIsRecordedOnceAndNotPolledAgain() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = false, reason = "no-session")
        f.viewModel.refresh(); advanceUntilIdle()
        f.viewModel.refresh(); advanceUntilIdle()
        assertEquals(false, f.viewModel.state.value.transcriptAvailable)
        assertEquals("no-session", f.viewModel.state.value.transcriptReason)
        assertEquals(1, f.api.historyCalls)
    }

    private fun entry(uuid: String) =
        TranscriptEntry(uuid, "2026-09-10T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = uuid)))

    private fun fixture(
        reply: ApiResult<ActionResponse>,
        keyResults: List<ApiResult<ActionResponse>> = emptyList(),
        deviceAuthorization: DeviceAuthorization? = null,
        agent: String = "codex",
        address: PaneAddress = PaneAddress(paneId = "w1:p1"),
    ): Fixture {
        val connection = Connection(CollieOrigin("https://collie.example/"), "phone", "token")
        val store = FakeStore(connection)
        val api = FakeApi(reply, keyResults, deviceAuthorization)
        val repository = CollieRepository(api, store, OriginValidator())
        val viewModel = PaneViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            address,
            agent,
            repository,
        )
        return Fixture(viewModel, api, store)
    }

    private fun text(resource: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resource)

    private data class Fixture(
        val viewModel: PaneViewModel,
        val api: FakeApi,
        val store: FakeStore,
    )

    private class FakeStore(initial: Connection) : ConnectionStore {
        private val mutable = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = mutable
        override fun save(connection: Connection) { mutable.value = connection }
        override fun clear() { mutable.value = null }
    }

    private class FakeApi(
        private val replyResult: ApiResult<ActionResponse>,
        keyResults: List<ApiResult<ActionResponse>>,
        var deviceAuthorization: DeviceAuthorization?,
    ) : CollieApi {
        var replyCalls = 0
        var lastKeys: List<String> = emptyList()
        val keysCalls = mutableListOf<List<String>>()
        var snapshotHook: (() -> Unit)? = null
        var keysHook: ((List<String>) -> Unit)? = null
        var config = BridgeConfigResponse()
        var snapshotPanes = listOf(paneSummary(PaneAddress(paneId = "w1:p1")))
        var servers: List<ServerSummary>? = null
        var lastReplyExpectedPrompt: String? = null
        private val keyResults = ArrayDeque(keyResults)
        var lastExpectedPrompt: String? = null
        val paneLines = mutableListOf<Int>()
        var pane = PaneReadResponse(
            paneId = "w1:p1",
            text = "transcript\n\n› \n\n  gpt-5 · /repo · Context 50% left",
            truncated = false,
            revision = 7,
        )
        val historyPages = ArrayDeque<PaneHistoryResponse>()
        var historyCalls = 0

        override suspend fun history(
            connection: Connection,
            address: PaneAddress,
            limit: Int,
            before: String?,
        ): ApiResult<PaneHistoryResponse> {
            historyCalls++
            val page = historyPages.removeFirstOrNull() ?: PaneHistoryResponse(address.paneId, available = true)
            return ApiResult.Success(page, 200)
        }

        override suspend fun pane(
            connection: Connection,
            address: PaneAddress,
            lines: Int,
            etag: String?,
            markSeen: Boolean,
        ): ApiResult<PaneReadResponse> {
            paneLines += lines
            return ApiResult.Success(pane, 200, etag = "\"7\"")
        }

        override suspend fun reply(
            connection: Connection,
            address: PaneAddress,
            text: String,
            submit: Boolean,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> {
            replyCalls++
            lastReplyExpectedPrompt = expectedPrompt
            return replyResult
        }

        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun config(connection: Connection) = ApiResult.Success(config, 200)
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
            snapshotHook?.invoke()
            return ApiResult.Success(
                SnapshotResponse(
                    bridge = "connected",
                    device = deviceAuthorization,
                    agents = snapshotPanes,
                    shellPanes = emptyList(),
                    workspaces = emptyList(),
                    tabs = emptyList(),
                    servers = servers,
                    ts = 1,
                ),
                200,
            )
        }
        override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> = error("unused")
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> = error("unused")
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = error("unused")
        override suspend fun keys(
            connection: Connection,
            address: PaneAddress,
            keys: List<String>,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> {
            lastKeys = keys
            keysCalls += keys
            lastExpectedPrompt = expectedPrompt
            keysHook?.invoke(keys)
            return if (keyResults.isEmpty()) {
                ApiResult.Success(ActionResponse(ok = true), 200)
            } else {
                keyResults.removeFirst()
            }
        }
    }

    private fun semanticPane() = PaneReadResponse(
        paneId = "w1:p1",
        text = "Which color?\n❯ 1. Red\n  2. Blue\nEnter to select · Esc to cancel",
        truncated = false,
        revision = 7,
    )

    private companion object {
        fun paneSummary(address: PaneAddress) = PaneSummary(
            paneId = address.paneId,
            workspaceId = "w1",
            workspaceLabel = "repo",
            workspaceNumber = 1,
            tabId = "w1:t1",
            agent = "codex",
            status = AgentStatus.IDLE,
            cwd = "/repo",
            focused = true,
            host = address.scope.host,
            session = address.scope.session,
        )

        fun server(reachable: Boolean) = ServerSummary(
            id = "remote",
            name = "Remote",
            isLead = false,
            reachable = reachable,
            protocol = "compatible",
            lastSeenAt = 1,
        )
    }
}
