package com.lateapex.collie.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.ScopedImageLoader
import com.lateapex.collie.data.ScopedImageResult
import com.lateapex.collie.data.QueuedSendAttemptStore
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
import com.lateapex.collie.network.ControlAuthority
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneHistoryResponse
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.QueuedSendGuard
import com.lateapex.collie.network.ChatAfter
import com.lateapex.collie.network.ChatBefore
import com.lateapex.collie.ui.terminal.AgentSemanticParser
import com.lateapex.collie.ui.terminal.TerminalDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
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
            ApiResult.Failure(ApiFailure.Http(403, "device not paired", "pairing.unpaired", source = com.lateapex.collie.network.FailureSource.COLLIE, factor = com.lateapex.collie.network.FailureFactor.PAIRING)),
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
    fun queuedSendUsesOnlyDeclaredKeysAndLatchesAnImmediateDuplicate() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        fixture.api.config = BridgeConfigResponse(
            mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)),
        )
        fixture.api.snapshotPanes = listOf(
            paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING),
        )
        val page = queuedPage()
        fixture.api.chatPage = page
        fixture.viewModel.refresh()
        advanceUntilIdle()

        val action = fixture.viewModel.state.value.transcriptSendNow
        assertNotNull(action)
        fixture.viewModel.sendQueuedNow(action!!)
        // A second tap before the launched action settles is ignored by the one-flight gate.
        fixture.viewModel.sendQueuedNow(action)
        advanceUntilIdle()

        assertEquals(listOf(listOf("ctrl+Enter")), fixture.api.queuedKeysCalls)
        assertEquals(page.queuedDigest, fixture.api.lastQueuedGuard?.queueDigest)
        assertTrue(fixture.api.replyCalls == 0)
        assertTrue(fixture.api.keysCalls.isEmpty())
        fixture.viewModel.sendQueuedNow(action)
        advanceUntilIdle()
        assertEquals(1, fixture.api.queuedKeysCalls.size)
        assertEquals(action, fixture.viewModel.state.value.transcriptSendAttempt)

        val recreated = fixture(
            ApiResult.Success(ActionResponse(ok = true), 200),
            agent = "claude",
            queuedAttempts = fixture.queuedAttempts,
        )
        recreated.api.config = fixture.api.config
        recreated.api.snapshotPanes = fixture.api.snapshotPanes
        recreated.api.chatPage = page
        recreated.viewModel.refresh(); advanceUntilIdle()
        assertEquals(action, recreated.viewModel.state.value.transcriptSendAttempt)
        recreated.viewModel.sendQueuedNow(recreated.viewModel.state.value.transcriptSendNow!!)
        advanceUntilIdle()
        assertTrue(recreated.api.queuedKeysCalls.isEmpty())
    }

    @Test
    fun queuedVerificationCannotRestoreImagesOrReserveAWriteAfterActorRetirement() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        f.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
        f.api.snapshotPanes = listOf(paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING))
        f.api.chatPage = queuedPage(turns = listOf(entry("queued-image").copy(parts = listOf(
            TranscriptPart("image", url = "data:image/png;base64,AA==", mimeType = "image/png"),
        ))))
        f.viewModel.refresh(); advanceUntilIdle()
        val admitted = requireNotNull(f.viewModel.state.value.transcriptReadContext)
        val action = requireNotNull(f.viewModel.state.value.transcriptSendNow)
        f.api.chatHook = { f.repository.showSavedCopy(456L) }
        f.viewModel.sendQueuedNow(action); advanceUntilIdle()
        assertFalse(f.repository.isCurrent(admitted))
        assertNull(f.viewModel.state.value.transcriptReadContext)
        assertNull(f.viewModel.state.value.transcriptSendAttempt)
        assertTrue(f.api.queuedKeysCalls.isEmpty())
        assertEquals(0, f.api.imageBlobCalls)
        assertFalse(f.viewModel.state.value.transcriptSendPending)
    }

    @Test
    fun queuedSendRechecksModalAndStaleQueueBeforeItsOnlyWrite() = runTest(dispatcher) {
        val modal = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        modal.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
        modal.api.snapshotPanes = listOf(paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING))
        modal.api.chatPage = queuedPage()
        modal.viewModel.refresh(); advanceUntilIdle()
        val displayed = modal.viewModel.state.value.transcriptSendNow!!
        modal.api.paneHook = {
            modal.api.pane = PaneReadResponse(
                "w1:p1", "Which color?\n❯ 1. Red\n  2. Blue\nEnter to select · Esc to cancel", false, 8,
            )
        }
        modal.viewModel.sendQueuedNow(displayed); advanceUntilIdle()
        assertTrue(modal.api.queuedKeysCalls.isEmpty())

        val stale = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        stale.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
        stale.api.snapshotPanes = listOf(paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING))
        stale.api.chatPage = queuedPage()
        stale.viewModel.refresh(); advanceUntilIdle()
        val staleAction = stale.viewModel.state.value.transcriptSendNow!!
        stale.api.chatHook = { stale.api.chatPage = queuedPage(queued = listOf("replacement"), revision = 4) }
        stale.viewModel.sendQueuedNow(staleAction); advanceUntilIdle()
        assertTrue(stale.api.queuedKeysCalls.isEmpty())
    }

    @Test
    fun malformedLiveQueueIdentityDisarmsTheVisibleActionWithoutThrowingOrPosting() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        fixture.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
        fixture.api.snapshotPanes = listOf(
            paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING),
        )
        fixture.api.chatPage = queuedPage()
        fixture.viewModel.refresh(); advanceUntilIdle()
        val displayed = fixture.viewModel.state.value.transcriptSendNow!!
        fixture.api.chatHook = {
            fixture.api.chatPage = queuedPage(digest = "malformed")
        }

        fixture.viewModel.sendQueuedNow(displayed)
        advanceUntilIdle()

        assertTrue(fixture.api.queuedKeysCalls.isEmpty())
        assertNull(fixture.viewModel.state.value.transcriptSendNow)
        assertNotNull(fixture.viewModel.state.value.mutationError)
    }

    @Test
    fun negativeLiveQueueCountersDisarmVisibleActionWithoutReplacingTranscriptCursorOrEtag() = runTest(dispatcher) {
        val malformedPages = listOf(
            queuedPage(generation = -1, revision = 13, turns = listOf(entry("rejected-generation"))),
            queuedPage(revision = -1, turns = listOf(entry("rejected-revision"))),
        )
        for (malformed in malformedPages) {
            val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
            fixture.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
            fixture.api.snapshotPanes = listOf(
                paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING),
            )
            fixture.api.chatResponseEtag = "\"accepted-page\""
            fixture.api.chatPage = queuedPage(revision = 12, turns = listOf(entry("retained")))
            fixture.viewModel.refresh(); advanceUntilIdle()
            val displayed = fixture.viewModel.state.value.transcriptSendNow
            assertNotNull("initial valid queue action", displayed)
            assertEquals(listOf("retained"), fixture.viewModel.state.value.transcript.map { it.uuid })

            fixture.api.chatResponseEtag = "\"rejected-page\""
            fixture.api.chatPage = malformed
            fixture.viewModel.refresh(); advanceUntilIdle()

            val afterReject = fixture.viewModel.state.value
            assertNull("malformed counters disarm PaneUiState", afterReject.transcriptSendNow)
            assertEquals(listOf("retained"), afterReject.transcript.map { it.uuid })
            assertEquals(listOf("queued message"), afterReject.transcriptQueued)
            assertEquals(0, fixture.api.queuedKeysCalls.size)

            // A stale view callback cannot bypass the disarmed visible state or post once.
            fixture.viewModel.sendQueuedNow(displayed!!)
            advanceUntilIdle()
            assertEquals(0, fixture.api.queuedKeysCalls.size)

            fixture.api.chatResponseEtag = "\"next-page\""
            fixture.api.chatPage = queuedPage(revision = 13, turns = listOf(entry("retained"), entry("next")))
            fixture.viewModel.refresh(); advanceUntilIdle()
            assertEquals(ChatAfter(7, 12), fixture.api.chatAfterRequests.last())
            assertEquals("\"accepted-page\"", fixture.api.chatEtagRequests.last())
        }
    }

    @Test
    fun aLegacyLivePageWithoutRevisionUpdatesDisplayButCannotEnableQueuedSend() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        fixture.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
        fixture.api.snapshotPanes = listOf(
            paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING),
        )
        fixture.api.chatPage = queuedPage(revision = 12, turns = listOf(entry("current")))
        fixture.viewModel.refresh(); advanceUntilIdle()
        assertNotNull(fixture.viewModel.state.value.transcriptSendNow)

        fixture.api.chatPage = queuedPage(revision = null, turns = listOf(entry("legacy")))
        fixture.viewModel.refresh(); advanceUntilIdle()

        assertEquals(listOf("current", "legacy"), fixture.viewModel.state.value.transcript.map { it.uuid })
        assertNull(fixture.viewModel.state.value.transcriptSendNow)
        assertEquals(0, fixture.api.queuedKeysCalls.size)

        fixture.api.chatPage = queuedPage(revision = 14, turns = listOf(entry("after-legacy")))
        fixture.viewModel.refresh(); advanceUntilIdle()
        assertNull("legacy response must not seed an invalid revision cursor", fixture.api.chatAfterRequests.last())
    }

    @Test
    fun actualHttpsFailedChatReadDisarmsQueueAcrossSupportingRefreshAndValidator304(): Unit = runTest(dispatcher) {
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverCertificates = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        try {
            val client = com.lateapex.collie.network.CollieApiClient.defaultHttpClient().newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val actual = com.lateapex.collie.network.CollieApiClient(client, kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            fun page(gen: Long = 7, rev: Long = 12) = """{"paneId":"w1:p1","available":true,"page":"live","gen":$gen,"rev":$rev,"head":1,"oldest":1,"hasOlder":false,"upserts":[{"uuid":"retained","ts":"","role":"assistant","seq":1,"parts":[]}],"queued":["queued message"],"sendQueuedNow":["ctrl+Enter"],"queuedDigest":"${"a".repeat(64)}","queuedSendOccupant":"${"b".repeat(64)}"}"""
            fun response(body: String) = okhttp3.mockwebserver.MockResponse()
                .setHeader("Content-Type", "application/json").setHeader("ETag", "\"accepted-page\"").setBody(body)
            for (failure in listOf(response(page(gen = -1)), response(page(rev = -1)),
                okhttp3.mockwebserver.MockResponse().setResponseCode(503),
                okhttp3.mockwebserver.MockResponse().setResponseCode(401),
                okhttp3.mockwebserver.MockResponse().setResponseCode(403))) {
                val requestCountBefore = server.requestCount
                val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
                f.store.save(Connection(CollieOrigin(server.url("/mount/").toString()), "phone", "token"))
                f.api.chatTransport = actual
                f.api.queuedSendTransport = actual
                f.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
                f.api.snapshotPanes = listOf(paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING))
                server.enqueue(response(page()))
                f.viewModel.refresh(); advanceUntilIdle()
                val action = f.viewModel.state.value.transcriptSendNow!!
                val first = server.takeRequest()
                assertEquals("GET", first.method)
                assertEquals("Bearer token", first.getHeader("Authorization"))

                server.enqueue(failure)
                f.viewModel.refresh(); advanceUntilIdle()
                assertNull(f.viewModel.state.value.transcriptSendNow)
                assertEquals(listOf("retained"), f.viewModel.state.value.transcript.map { it.uuid })
                assertEquals(listOf("queued message"), f.viewModel.state.value.transcriptQueued)
                val failed = server.takeRequest()
                assertTrue(failed.path!!.contains("after="))
                assertEquals("\"accepted-page\"", failed.getHeader("If-None-Match"))

                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(304))
                f.viewModel.refresh(); advanceUntilIdle()
                assertNull("supporting snapshot and 304 must not restore retired queue authority", f.viewModel.state.value.transcriptSendNow)
                val unchanged = server.takeRequest()
                assertEquals(failed.path, unchanged.path)
                if (failure.status.contains(" 401 ") || failure.status.contains(" 403 ")) {
                    // Access refusal retires the B04 image actor and its validator. Supporting
                    // config recovery may read again, but a 304 still cannot re-admit old pixels.
                    assertNull(unchanged.getHeader("If-None-Match"))
                    assertNull(f.viewModel.state.value.transcriptReadContext)
                } else {
                    assertEquals("\"accepted-page\"", unchanged.getHeader("If-None-Match"))
                }
                f.viewModel.sendQueuedNow(action); advanceUntilIdle()
                assertTrue(f.api.queuedKeysCalls.isEmpty())
                assertEquals(requestCountBefore + 3, server.requestCount)

                server.enqueue(response(page(rev = 13)))
                f.viewModel.refresh(); advanceUntilIdle()
                assertNotNull("only a newly accepted live response restores queue authority", f.viewModel.state.value.transcriptSendNow)
                assertEquals("GET", server.takeRequest().method)

                val restoredAction = f.viewModel.state.value.transcriptSendNow!!
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(304))
                f.viewModel.sendQueuedNow(restoredAction); advanceUntilIdle()
                assertNull("a no-validator fresh verification cannot authorize a 304", f.viewModel.state.value.transcriptSendNow)
                assertNull("no reservation or dispatch occurred during failed preflight", f.viewModel.state.value.transcriptSendAttempt)
                val verification = server.takeRequest()
                assertEquals("GET", verification.method)
                assertNull(verification.getHeader("If-None-Match"))
                assertTrue(f.api.queuedKeysCalls.isEmpty())
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(304))
                f.viewModel.refresh(); advanceUntilIdle()
                assertNull(f.viewModel.state.value.transcriptSendNow)
                assertEquals("GET", server.takeRequest().method)

                server.enqueue(response(page(rev = 14)))
                f.viewModel.refresh(); advanceUntilIdle()
                assertNotNull(f.viewModel.state.value.transcriptSendNow)
                assertEquals("GET", server.takeRequest().method)
                val recovered = f.viewModel.state.value.transcriptSendNow!!
                assertNull(f.viewModel.state.value.transcriptSendAttempt)
                server.enqueue(response(page(rev = 14)))
                server.enqueue(response(page(rev = 14)))
                server.enqueue(response("{\"ok\":true}"))
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(304))
                f.viewModel.sendQueuedNow(recovered); advanceUntilIdle()
                assertEquals("GET", server.takeRequest().method)
                assertEquals("GET", server.takeRequest().method)
                val dispatch = server.takeRequest()
                assertEquals("POST", dispatch.method)
                assertTrue(dispatch.path!!.contains("/api/pane/w1%3Ap1/keys"))
                assertEquals("Bearer token", dispatch.getHeader("Authorization"))
                assertTrue(dispatch.body.readUtf8().contains("\"queued_send\""))
                assertEquals("GET", server.takeRequest().method)
                assertEquals(listOf(recovered.keys), f.api.queuedKeysCalls)
                assertEquals(recovered, f.viewModel.state.value.transcriptSendAttempt)
                val countAfterDispatch = server.requestCount
                f.viewModel.sendQueuedNow(recovered); advanceUntilIdle()
                assertEquals("a dispatched queue never repeats automatically or on a second tap", countAfterDispatch, server.requestCount)

                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
                f.viewModel.loadOlderTranscript(); advanceUntilIdle()
                assertNull("a failed current older-page read also retires queue authority", f.viewModel.state.value.transcriptSendNow)
                val older = server.takeRequest()
                assertEquals("GET", older.method)
                assertTrue(older.path!!.contains("before="))
                assertEquals(listOf("retained"), f.viewModel.state.value.transcript.map { it.uuid })
                assertEquals(listOf("queued message"), f.viewModel.state.value.transcriptQueued)
                assertEquals(listOf(recovered.keys), f.api.queuedKeysCalls)
                assertEquals("durable attempted-send latch survives read disarm", recovered, f.viewModel.state.value.transcriptSendAttempt)
            }
        } finally { server.shutdown() }
        Unit
    }

    @Test
    fun olderNonnegativeLiveRevisionKeepsVisibleActionTranscriptAndCursor() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        fixture.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
        fixture.api.snapshotPanes = listOf(
            paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING),
        )
        fixture.api.chatResponseEtag = "\"accepted-page\""
        fixture.api.chatPage = queuedPage(revision = 12, turns = listOf(entry("retained")))
        fixture.viewModel.refresh(); advanceUntilIdle()
        val action = fixture.viewModel.state.value.transcriptSendNow
        assertNotNull(action)

        fixture.api.chatResponseEtag = "\"older-page\""
        fixture.api.chatPage = queuedPage(revision = 11, turns = listOf(entry("rejected-older")))
        fixture.viewModel.refresh(); advanceUntilIdle()

        val state = fixture.viewModel.state.value
        assertEquals(action, state.transcriptSendNow)
        assertEquals(listOf("retained"), state.transcript.map { it.uuid })
        assertEquals(0, fixture.api.queuedKeysCalls.size)

        fixture.api.chatPage = queuedPage(revision = 13, turns = listOf(entry("retained"), entry("next")))
        fixture.viewModel.refresh(); advanceUntilIdle()
        assertEquals(ChatAfter(7, 12), fixture.api.chatAfterRequests.last())
        assertEquals("\"accepted-page\"", fixture.api.chatEtagRequests.last())
    }

    @Test
    fun queuedSendTimeoutRemainsUnknownAndLatchedAcrossRecreation() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        fixture.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
        fixture.api.snapshotPanes = listOf(
            paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING),
        )
        fixture.api.chatPage = queuedPage()
        fixture.api.queuedSendResult = ApiResult.Failure(ApiFailure.Timeout)
        fixture.viewModel.refresh(); advanceUntilIdle()
        val action = fixture.viewModel.state.value.transcriptSendNow!!
        fixture.viewModel.sendQueuedNow(action); advanceUntilIdle()
        assertEquals(1, fixture.api.queuedKeysCalls.size)
        assertEquals(action, fixture.viewModel.state.value.transcriptSendAttempt)
        assertNotNull(fixture.viewModel.state.value.mutationError)

        val recreated = fixture(
            ApiResult.Success(ActionResponse(ok = true), 200),
            agent = "claude",
            queuedAttempts = fixture.queuedAttempts,
        )
        recreated.api.config = fixture.api.config
        recreated.api.snapshotPanes = fixture.api.snapshotPanes
        recreated.api.chatPage = fixture.api.chatPage
        recreated.viewModel.refresh(); advanceUntilIdle()
        recreated.viewModel.sendQueuedNow(recreated.viewModel.state.value.transcriptSendNow!!)
        advanceUntilIdle()
        assertTrue(recreated.api.queuedKeysCalls.isEmpty())
    }

    @Test
    fun queuedSendPairingRevocationDisarmsWritesWithoutRetry() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        fixture.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
        fixture.api.snapshotPanes = listOf(
            paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING),
        )
        fixture.api.chatPage = queuedPage()
        fixture.viewModel.refresh(); advanceUntilIdle()
        val action = fixture.viewModel.state.value.transcriptSendNow!!
        fixture.api.queuedSendResult = ApiResult.Failure(
            ApiFailure.Http(
                403, "device not paired", "pairing.unpaired",
                source = com.lateapex.collie.network.FailureSource.COLLIE,
                factor = com.lateapex.collie.network.FailureFactor.PAIRING,
            ),
        )
        fixture.viewModel.sendQueuedNow(action); advanceUntilIdle()
        assertEquals(1, fixture.api.queuedKeysCalls.size)
        assertFalse(fixture.viewModel.state.value.canWrite)
        fixture.viewModel.sendQueuedNow(action); advanceUntilIdle()
        assertEquals(1, fixture.api.queuedKeysCalls.size)
    }

    @Test
    fun missingGuardedCapabilityNeverFallsBackToGenericKeys() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude")
        fixture.api.snapshotPanes = listOf(
            paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING),
        )
        fixture.api.chatPage = queuedPage()
        fixture.viewModel.refresh(); advanceUntilIdle()
        val action = fixture.viewModel.state.value.transcriptSendNow!!
        fixture.viewModel.sendQueuedNow(action); advanceUntilIdle()
        assertTrue(fixture.api.queuedKeysCalls.isEmpty())
        assertTrue(fixture.api.keysCalls.isEmpty())
    }

    @Test
    fun delayedGuardNegotiationCannotPostAfterCapturedActorAuthorityRetires() = runTest(dispatcher) {
        suspend fun readyFixture() = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "claude").also { f ->
            f.api.config = BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("guardedQueuedSend" to true)))
            f.api.snapshotControlAuthority = ControlAuthority("lead", "1")
            f.api.snapshotPanes = listOf(paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude", status = AgentStatus.WORKING))
            f.api.chatPage = queuedPage()
            f.viewModel.refresh()
            advanceUntilIdle()
        }

        suspend fun attemptAfter(mutate: (Fixture) -> Unit, configAuthority: ControlAuthority? = null) {
            val f = readyFixture()
            val action = f.viewModel.state.value.transcriptSendNow!!
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.api.configAuthority = configAuthority
            f.api.configHook = {
                entered.complete(Unit)
                release.await()
            }
            f.viewModel.sendQueuedNow(action)
            runCurrent()
            assertTrue("guarded config negotiation did not suspend", entered.isCompleted)
            mutate(f)
            release.complete(Unit)
            advanceUntilIdle()
            assertTrue("retired actor must not reach the queued-send POST", f.api.queuedKeysCalls.isEmpty())
        }

        attemptAfter({ it.repository.selectScope(Scope(host = "other", session = "other")) })
        attemptAfter({ it.store.save(Connection(CollieOrigin("https://replacement.example/"), "phone", "replacement")) })
        attemptAfter({ it.repository.setLeadEpoch(1) })
        attemptAfter({}, configAuthority = ControlAuthority("lead", "2"))
        attemptAfter({ it.repository.showSavedCopy(1234) })
    }

    @Test
    fun unknownProvenanceWriteRefusalFreezesControlsWithoutErasingPairing() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Failure(ApiFailure.Http(403, "device expired", "pairing.expired")))
        fixture.viewModel.refresh(); advanceUntilIdle()
        fixture.viewModel.sendReply("hello"); advanceUntilIdle()
        assertFalse(fixture.viewModel.state.value.canWrite)
        assertTrue(fixture.store.connection.value?.isPaired == true)
        assertEquals(1, fixture.api.replyCalls)
        assertNotNull(fixture.viewModel.state.value.error)
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
    fun loadOlderClampsItsEffectiveRequestToTheDeclaredAdapterMaximum() = runTest(dispatcher) {
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "shell")
        fixture.api.config = BridgeConfigResponse(
            mux = MuxConfigResponse(
                name = "herdr",
                readLimits = com.lateapex.collie.network.MuxReadLimitsResponse(
                    kotlinx.serialization.json.JsonPrimitive(1_000),
                ),
            ),
        )
        fixture.api.pane = PaneReadResponse(
            paneId = "w1:p1",
            text = (1..600).joinToString("\n") { "row $it" },
            truncated = true,
            revision = 7,
            servedLines = 600,
        )
        fixture.viewModel.refresh()
        advanceUntilIdle()

        assertEquals(600, fixture.viewModel.state.value.loadedLines)
        assertEquals(1_000, fixture.viewModel.loadOlderScrollback())
        assertTrue(fixture.viewModel.state.value.loadingOlder)
        advanceUntilIdle()

        assertEquals(listOf(600, 1_000), fixture.api.paneLines)
        assertEquals(1_000, fixture.viewModel.state.value.requestedLines)
        assertEquals(1_200, fixture.viewModel.state.value.desiredLines)
        assertEquals(600, fixture.viewModel.state.value.loadedLines)
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
    fun semanticActionFollowsTheAgentTheSnapshotReportsNotTheLaunchIntent() = runTest(dispatcher) {
        // A shell pane that starts claude while open: the activity re-labels it from the snapshot,
        // so the pre-flight must verify with the same grammar (S25 Ultra, 2026-09-10).
        val fixture = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "shell")
        fixture.api.snapshotPanes = listOf(paneSummary(PaneAddress(paneId = "w1:p1")).copy(agent = "claude"))
        fixture.api.pane = semanticPane()
        fixture.viewModel.refresh()
        advanceUntilIdle()
        val surface = AgentSemanticParser.detect("claude", fixture.api.pane.text, fixture.api.pane.revision)!!

        fixture.viewModel.sendSemanticAction(surface, surface.actions.first())
        advanceUntilIdle()

        assertEquals(listOf(listOf("1", "Enter")), fixture.api.keysCalls)
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
    fun theFirstTranscriptLoadReachesFurtherBackThanEachPoll() = runTest(dispatcher) {
        // 60 entries covered about 15 minutes of a busy agent, so "Load older" came almost at once
        // (S25 Ultra, 2026-09-18). The first load reaches back further; polls only top it up.
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(entry("a"), entry("b")), hasMore = true)
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(entry("b"), entry("c")), hasMore = true)
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(entry("c")), hasMore = false)
        f.viewModel.refresh(); advanceUntilIdle()
        f.viewModel.refresh(); advanceUntilIdle()
        f.viewModel.refresh(); advanceUntilIdle()

        assertEquals(PaneViewModel.TRANSCRIPT_PAGE, f.api.historyLimits.first())
        assertTrue(f.api.historyLimits.drop(1).all { it == PaneViewModel.TRANSCRIPT_POLL })
        assertTrue(PaneViewModel.TRANSCRIPT_PAGE > PaneViewModel.TRANSCRIPT_POLL)
        assertEquals(listOf("a", "b", "c"), f.viewModel.state.value.transcript.map { it.uuid })
        // A poll's short page says nothing about what lies before the first load's oldest entry.
        assertEquals(true, f.viewModel.state.value.transcriptHasMore)
    }

    @Test
    fun anUnchangedTranscriptPollIsANotModifiedNotAWholePage() = runTest(dispatcher) {
        // S25 Ultra, 2026-09-18: an open pane re-downloaded the same ~36 KB page every 2 seconds.
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(entry("a")), hasMore = true)
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(entry("a"), entry("b")), hasMore = true)
        f.viewModel.refresh(); advanceUntilIdle() // first load: the long page, no validator
        f.viewModel.refresh(); advanceUntilIdle() // first poll: the short page, whose ETag it keeps
        f.api.historyUnchanged = true
        f.viewModel.refresh(); advanceUntilIdle()
        f.viewModel.refresh(); advanceUntilIdle()

        assertEquals(listOf(null, null, "\"page-2\"", "\"page-2\""), f.api.historyEtags)
        assertEquals(listOf("a", "b"), f.viewModel.state.value.transcript.map { it.uuid })
        assertEquals(true, f.viewModel.state.value.transcriptHasMore)
    }

    @Test
    fun retainedTranscriptOnlyReauthorizesImagesAfterCurrentSuccessfulPage() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        val imageEntry = entry("stable-image-turn").copy(parts = listOf(
            TranscriptPart("image", url = "data:image/png;base64,AA==", mimeType = "image/png"),
        ))
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(imageEntry))
        f.viewModel.refresh(); advanceUntilIdle()
        val originalContext = requireNotNull(f.viewModel.state.value.transcriptReadContext)
        assertTrue(f.repository.isCurrent(originalContext))

        // The text stays available as a saved/stale copy, but a redraw cannot mint a fresh actor.
        f.repository.showSavedCopy(123L)
        assertEquals(listOf("stable-image-turn"), f.viewModel.state.value.transcript.map { it.uuid })
        assertNull(f.viewModel.state.value.transcriptReadContext)
        assertFalse(f.repository.isCurrent(originalContext))
        val chatCallsBeforeStaleRead = f.api.chatCalls
        val scope = Scope()
        val staleChat = f.repository.chat(PaneAddress(scope, "w1:p1"), expectedContext = originalContext)
        assertFalse("a retired transcript actor cannot dispatch", staleChat is ApiResult.Success)
        assertEquals(chatCallsBeforeStaleRead, f.api.chatCalls)

        // Even a 304 that arrives after retirement must not promote those retained entries.
        f.api.historyAlwaysNotModified = true
        f.viewModel.refresh(); advanceUntilIdle()
        assertNull(f.viewModel.state.value.transcriptReadContext)

        // A fresh 200 page under the new context can recreate cards for unchanged UUIDs.
        f.api.historyAlwaysNotModified = false
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(imageEntry))
        f.viewModel.refresh(); advanceUntilIdle()
        val refreshed = requireNotNull(f.viewModel.state.value.transcriptReadContext)
        assertTrue(f.repository.isCurrent(refreshed))
        assertEquals(originalContext.key, refreshed.key)
        assertTrue(refreshed.navigationGeneration != originalContext.navigationGeneration)
        assertEquals(listOf("stable-image-turn"), f.viewModel.state.value.transcript.map { it.uuid })
    }

    @Test
    fun cachedPane304CannotRestoreRetiredFilesLinkActor() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.viewModel.refresh(); advanceUntilIdle()
        val original = requireNotNull(f.viewModel.state.value.paneReadContext)
        assertTrue(f.repository.isCurrent(original))
        assertEquals("the actor follows the ViewModel's actual fresh pane read", 1, f.api.paneFreshReads)
        assertTrue("ordinary unavailable live-chat fallback must not retire the accepted pane actor", f.api.chatCalls > 0)

        // A current consumer retains its original actor on a genuine conditional304.
        f.api.paneAlwaysNotModified = true
        f.viewModel.refresh(); advanceUntilIdle()
        assertTrue(original === f.viewModel.state.value.paneReadContext)
        f.repository.showSavedCopy(1L)
        assertNull(f.viewModel.state.value.paneReadContext)
        f.api.paneAlwaysNotModified = true
        f.api.paneUnsolicitedNotModified = true
        f.viewModel.refresh(); advanceUntilIdle()
        assertNull(f.api.paneEtags.last())
        assertEquals(1, f.api.paneUnsolicitedReads)
        assertNull("304 without a fresh body cannot re-admit retained terminal text", f.viewModel.state.value.paneReadContext)
        assertFalse(f.repository.isCurrent(original))
        assertTrue("the fixture dispatched the actual conditional pane read", f.api.paneConditionalReads > 0)
    }

    @Test
    fun actualHttpsNewPaneConsumerRequiresFresh200AndNeverMintsAnActorFromSharedCache304(): Unit = runTest(dispatcher) {
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val certificates = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start()
        try {
            val client = com.lateapex.collie.network.CollieApiClient.defaultHttpClient().newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val actual = com.lateapex.collie.network.CollieApiClient(client, kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            val address = PaneAddress(paneId = "w1:p1")
            val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "shell", address = address)
            f.store.save(Connection(CollieOrigin(server.url("/mount/").toString()), "phone", "token"))
            runCurrent()
            f.api.paneTransport = actual
            f.api.config = BridgeConfigResponse(control = com.lateapex.collie.network.ControlCapabilityResponse(mapOf("filesDiscovery" to true)))
            fun body() = okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setHeader("ETag", "\"fixture7\"")
                .setBody("""{"paneId":"w1:p1","text":"See src/source.txt:3\n","truncated":false,"revision":7,"filesReadReady":true}""")
            fun request(): okhttp3.mockwebserver.RecordedRequest {
                val request = requireNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("GET", request.method)
                assertEquals("/mount/api/pane/w1%3Ap1", request.requestUrl?.encodedPath)
                assertEquals("Bearer token", request.getHeader("Authorization"))
                return request
            }
            server.enqueue(body())
            assertTrue(f.repository.readPane(address) is ApiResult.Success)
            assertNull(request().getHeader("If-None-Match"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(304))
            val cached = f.repository.readPane(address) as ApiResult.Success
            assertEquals(304, cached.status)
            assertTrue(cached.value.notModified)
            assertEquals("\"fixture7\"", request().getHeader("If-None-Match"))

            // Same repository/cache as the previous consumer; this new ViewModel owns no body.
            val fresh = PaneViewModel(ApplicationProvider.getApplicationContext<Application>(), address, "shell", f.repository,
                com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(mutableListOf()), f.queuedAttempts)
            assertNull(fresh.state.value.paneReadContext)
            var completedPaneLoads = 0
            // Supporting config runs only after load() has processed the real pane response.
            // Its next invocation distinguishes a completed 304 from an already-present actor.
            f.api.configHook = { completedPaneLoads++ }
            fun refreshAndAwaitResponse() {
                val priorLoads = completedPaneLoads
                fresh.refresh()
                val responseDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                while (completedPaneLoads == priorLoads && System.nanoTime() < responseDeadline) {
                    runCurrent()
                    if (completedPaneLoads == priorLoads) Thread.sleep(10)
                }
                runCurrent()
                assertEquals("Actual HTTPS pane response is processed before supporting reads", priorLoads + 1, completedPaneLoads)
                assertFalse("The completed pane phase settles loading", fresh.state.value.loading)
            }
            server.enqueue(body())
            refreshAndAwaitResponse()
            assertNull("new consumer must request its own fresh body", request().getHeader("If-None-Match"))
            val accepted = requireNotNull(fresh.state.value.paneReadContext)
            assertTrue(f.repository.isCurrent(accepted))
            assertTrue(fresh.state.value.pane?.filesReadReady == true)
            assertTrue(fresh.state.value.pane?.text?.contains("src/source.txt:3") == true)
            assertNotNull(fresh.state.value.filesConfigContext)

            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(304))
            refreshAndAwaitResponse()
            assertEquals("\"fixture7\"", request().getHeader("If-None-Match"))
            assertTrue("same current consumer retains its original accepted actor", accepted === fresh.state.value.paneReadContext)

            f.repository.showSavedCopy(1L)
            assertNull(fresh.state.value.paneReadContext)
            assertFalse(f.repository.isCurrent(accepted))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(304))
            refreshAndAwaitResponse()
            assertNull(request().getHeader("If-None-Match"))
            assertNull("unsolicited304 must never admit a new or retired body actor", fresh.state.value.paneReadContext)
            assertNotNull(fresh.state.value.error)

            server.enqueue(body())
            refreshAndAwaitResponse()
            assertNull(request().getHeader("If-None-Match"))
            val recovered = requireNotNull(fresh.state.value.paneReadContext)
            assertTrue(f.repository.isCurrent(recovered))
            assertTrue(recovered.navigationGeneration != accepted.navigationGeneration)
            assertEquals(cached.value.pane.text, fresh.state.value.pane?.text)
            assertEquals(6, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aFreshPaneReadCannotPublishWithAnActorReplacedDuringItsTransportWait() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        val original = requireNotNull(f.repository.readContext(Scope(), "w1:p1"))
        val replacement = Connection(CollieOrigin("https://collie.example/"), "replacement", "replacement-token")
        f.api.paneHook = { f.store.save(replacement) }
        f.viewModel.refresh(); advanceUntilIdle()
        assertFalse(f.repository.isCurrent(original))
        assertNull(f.viewModel.state.value.paneReadContext)
        assertEquals(1, f.api.paneLines.size)
        f.api.paneHook = null
        f.viewModel.refresh(); advanceUntilIdle()
        val recovered = requireNotNull(f.viewModel.state.value.paneReadContext)
        assertTrue(f.repository.isCurrent(recovered))
        assertTrue(recovered.connection === replacement)
    }

    @Test
    fun acceptedOlderLiveChatPageRestoresItsImageActorAndRejectsAQueuedRetiredActor() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        val newest = entry("newest").copy(seq = 2)
        f.api.chatPage = PaneChatResponse(
            paneId = "w1:p1", available = true, page = "live", gen = 7, rev = 1,
            head = 2, oldest = 2, hasOlder = true, upserts = listOf(newest),
        )
        f.viewModel.refresh(); advanceUntilIdle()
        val original = requireNotNull(f.viewModel.state.value.transcriptReadContext)
        assertEquals(listOf("newest"), f.viewModel.state.value.transcript.map { it.uuid })

        f.repository.showSavedCopy(200L)
        f.viewModel.loadOlderTranscript()
        f.repository.showSavedCopy(201L) // retire the actor captured before its coroutine dispatches
        advanceUntilIdle()
        assertEquals("a stale queued chat actor must not reach transport", 1, f.api.chatCalls)
        assertNull(f.viewModel.state.value.transcriptReadContext)

        val imageOlder = TranscriptEntry(
            "older-image", "2026-09-09T00:00:00Z", "assistant",
            listOf(TranscriptPart("image", url = "data:image/png;base64,AA==", mimeType = "image/png")),
            seq = 1,
        )
        f.api.chatOlderPages += PaneChatResponse(
            paneId = "w1:p1", available = true, page = "older", gen = 7,
            oldest = 1, hasOlder = false, upserts = listOf(imageOlder),
        )
        f.viewModel.loadOlderTranscript(); advanceUntilIdle()

        val admitted = requireNotNull(f.viewModel.state.value.transcriptReadContext)
        assertTrue(f.repository.isCurrent(admitted))
        assertEquals(original.key, admitted.key)
        assertTrue(admitted.navigationGeneration != original.navigationGeneration)
        assertEquals(listOf("older-image", "newest"), f.viewModel.state.value.transcript.map { it.uuid })
        assertEquals(listOf("newest"), f.api.chatBeforeRequests.drop(1).map { it?.uuid })
    }

    @Test
    fun rejectedLivePageCannotReauthorizeRetainedImagesAfterSavedCopy() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        val imageReference = "/api/blobs/${"ab".repeat(32)}"
        val imageEntry = TranscriptEntry(
            "stable-live-image", "2026-09-09T00:00:00Z", "assistant",
            listOf(TranscriptPart("image", url = imageReference, mimeType = "image/png")),
            seq = 10,
        )
        f.api.chatPage = PaneChatResponse(
            paneId = "w1:p1", available = true, page = "live", gen = 7, rev = 10,
            head = 10, oldest = 10, hasOlder = false, upserts = listOf(imageEntry),
        )
        f.viewModel.refresh(); advanceUntilIdle()
        val originalActor = requireNotNull(f.viewModel.state.value.transcriptReadContext)
        assertTrue(f.repository.isCurrent(originalActor))
        assertEquals(listOf("stable-live-image"), f.viewModel.state.value.transcript.map { it.uuid })

        // Authority retirement preserves the saved text but removes the actor that admitted it.
        f.repository.showSavedCopy(400L)
        assertNull(f.viewModel.state.value.transcriptReadContext)
        assertEquals(listOf("stable-live-image"), f.viewModel.state.value.transcript.map { it.uuid })

        // This is a current, successful HTTP response, but its same-generation revision is older
        // than the retained window, so merge rejects it. It must not adopt the new read actor.
        val rejected = entry("rejected-replacement").copy(seq = 11)
        f.api.chatPage = PaneChatResponse(
            paneId = "w1:p1", available = true, page = "live", gen = 7, rev = 9,
            head = 9, oldest = 9, hasOlder = false, upserts = listOf(rejected),
        )
        f.viewModel.refresh(); advanceUntilIdle()
        assertEquals(listOf("stable-live-image"), f.viewModel.state.value.transcript.map { it.uuid })
        assertNull("a rejected merge cannot promote the retired image actor", f.viewModel.state.value.transcriptReadContext)

        // The renderer passes its nullable state-owned actor to the loader. With no admitted actor,
        // the ordinary load path refuses before scheduling transport or parsing the reference.
        val loader = ScopedImageLoader(f.repository)
        try {
            var imageResult: ScopedImageResult? = null
            loader.load(
                context = f.viewModel.state.value.transcriptReadContext,
                reference = imageReference,
                viewportWidth = 320,
                viewportHeight = 240,
                callback = { imageResult = it },
                expectedMimeType = "image/png",
            )
            assertTrue(imageResult is ScopedImageResult.Refused)
            assertEquals(0, f.api.imageBlobCalls)
        } finally {
            loader.close()
        }

        // A later page that the window accepts is the event that restores image authority.
        f.api.chatPage = PaneChatResponse(
            paneId = "w1:p1", available = true, page = "live", gen = 7, rev = 11,
            head = 11, oldest = 10, hasOlder = false, upserts = listOf(imageEntry.copy(seq = 10)),
        )
        f.viewModel.refresh(); advanceUntilIdle()
        val recoveredActor = requireNotNull(f.viewModel.state.value.transcriptReadContext)
        assertTrue(f.repository.isCurrent(recoveredActor))
        assertEquals(originalActor.key, recoveredActor.key)
        assertTrue(recoveredActor.navigationGeneration != originalActor.navigationGeneration)
        assertEquals(listOf("stable-live-image"), f.viewModel.state.value.transcript.map { it.uuid })
    }

    @Test
    fun acceptedOlderLegacyHistoryPageRestoresItsImageActorAndRejectsAQueuedRetiredActor() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(entry("newest")), hasMore = true)
        f.viewModel.refresh(); advanceUntilIdle() // live chat is unavailable; legacy history is admitted
        val original = requireNotNull(f.viewModel.state.value.transcriptReadContext)
        assertEquals(listOf("newest"), f.viewModel.state.value.transcript.map { it.uuid })

        f.repository.showSavedCopy(300L)
        f.viewModel.loadOlderTranscript()
        f.repository.showSavedCopy(301L)
        advanceUntilIdle()
        assertEquals("a stale queued history actor must not reach transport", 1, f.api.historyCalls)
        assertNull(f.viewModel.state.value.transcriptReadContext)

        val imageOlder = TranscriptEntry(
            "older-image", "2026-09-09T00:00:00Z", "assistant",
            listOf(TranscriptPart("image", url = "data:image/png;base64,AA==", mimeType = "image/png")),
        )
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(imageOlder), hasMore = false)
        f.viewModel.loadOlderTranscript(); advanceUntilIdle()

        val admitted = requireNotNull(f.viewModel.state.value.transcriptReadContext)
        assertTrue(f.repository.isCurrent(admitted))
        assertEquals(original.key, admitted.key)
        assertTrue(admitted.navigationGeneration != original.navigationGeneration)
        assertEquals(listOf("older-image", "newest"), f.viewModel.state.value.transcript.map { it.uuid })
        assertEquals(listOf("newest"), f.api.historyBeforeRequests.drop(1))
    }

    @Test
    fun unavailableHistoryIsRetriedAfterAPauseAndPicksUpANewJournal() = runTest(dispatcher) {
        // A Claude started in an open pane has no session log until its first turn. Giving up
        // after one "no-session" left the pane on the mirror for good (S25 Ultra, 2026-09-11).
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = false, reason = "no-session")
        f.viewModel.refresh(); advanceUntilIdle()
        assertEquals(false, f.viewModel.state.value.transcriptAvailable)
        assertEquals("no-session", f.viewModel.state.value.transcriptReason)
        repeat(PaneViewModel.TRANSCRIPT_RETRY_POLLS) { f.viewModel.refresh(); advanceUntilIdle() }
        assertEquals("the pause skips history", 1, f.api.historyCalls)
        f.api.historyPages += PaneHistoryResponse("w1:p1", available = true, entries = listOf(entry("a")))
        f.viewModel.refresh(); advanceUntilIdle()
        assertEquals(2, f.api.historyCalls)
        assertEquals(true, f.viewModel.state.value.transcriptAvailable)
        assertEquals(listOf("a"), f.viewModel.state.value.transcript.map { it.uuid })
    }

    @Test
    fun sendingAReplyRecordsTheActionBeforeTheNetworkCallCompletes() {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
        val diagnostics = com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(lines)
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200), diagnostics = diagnostics)
        f.viewModel.sendReply("hello")

        assertTrue(lines.any { it.first == "action" && it.second["name"] == "pane.send_reply" })
    }

    @Test
    fun sendingAKeySequenceRecordsTheAction() {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
        val diagnostics = com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(lines)
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200), diagnostics = diagnostics)
        f.viewModel.sendKeySequence(listOf("ctrl+c"))

        assertTrue(lines.any { it.first == "action" && it.second["name"] == "pane.send_key_sequence" })
    }

    @Test
    fun codexAcknowledgementWithoutConsumptionKeepsTheDraftAndWarns() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.viewModel.refresh()
        advanceUntilIdle()
        f.api.replyHook = { message, submit ->
            if (!submit) f.api.pane = f.api.pane.copy(text = "› $message\n\n  model · /repo · Context 50% left")
        }
        f.viewModel.sendReply("hello")
        advanceUntilIdle()

        assertEquals(2, f.api.replyCalls)
        assertFalse(f.viewModel.state.value.clearReplyDraft)
        assertFalse(f.viewModel.state.value.sending)
        assertEquals(text(com.lateapex.collie.R.string.pane_error_codex_unconfirmed), f.viewModel.state.value.mutationError)
        assertTrue(f.viewModel.state.value.codexAcceptedReplies.isEmpty())
    }

    @Test
    fun acceptedCodexInputSurvivesUnrelatedOutputAndIsIsolatedByOriginWithoutReplay() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        val original = f.store.connection.value!!
        f.viewModel.refresh()
        advanceUntilIdle()
        f.api.replyHook = { message, submit ->
            f.api.pane = f.api.pane.copy(text = "› ${if (submit) "" else message}\n\n  model · /repo · Context 50% left")
        }
        f.viewModel.sendReply("hello")
        advanceUntilIdle()
        assertEquals(listOf("hello"), f.viewModel.state.value.codexAcceptedReplies)
        assertTrue(f.viewModel.state.value.clearReplyDraft)
        f.viewModel.onReplyDraftCleared()
        f.api.pane = f.api.pane.copy(text = "unrelated output\n${f.api.pane.text}", revision = 99)
        f.viewModel.refresh()
        advanceUntilIdle()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("hello"), f.viewModel.state.value.codexAcceptedReplies)
        assertFalse(f.viewModel.state.value.clearReplyDraft)
        f.store.save(Connection(original.origin, "re-paired", "different-token"))
        runCurrent()
        assertTrue(f.viewModel.state.value.codexAcceptedReplies.isEmpty())
        f.store.save(Connection(original.origin, "renamed same pairing", original.token))
        runCurrent()
        assertEquals(listOf("hello"), f.viewModel.state.value.codexAcceptedReplies)
        f.store.save(Connection(CollieOrigin("https://other.example/"), "other", "other-token"))
        runCurrent()
        assertTrue(f.viewModel.state.value.codexAcceptedReplies.isEmpty())
        f.store.save(original)
        runCurrent()
        assertEquals(listOf("hello"), f.viewModel.state.value.codexAcceptedReplies)
        f.viewModel.dismissAcceptedCodexReplies()
        assertTrue(f.viewModel.state.value.codexAcceptedReplies.isEmpty())
        assertEquals(2, f.api.replyCalls)
    }

    @Test
    fun aTurnPolledDuringSubmitVerificationReconcilesAfterAcceptanceWithoutAnotherUpsert() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.api.chatPage = PaneChatResponse("w1:p1", true, page = "live", gen = 2, rev = 4, head = 10)
        f.viewModel.refresh()
        advanceUntilIdle()
        assertNotNull("accepted live chat page owns image render authority", f.viewModel.state.value.transcriptReadContext)
        val guardRead = CompletableDeferred<Unit>()
        var holdConsumptionRead = false
        f.api.paneHook = {
            if (holdConsumptionRead) {
                holdConsumptionRead = false
                guardRead.await()
            }
        }
        f.api.replyHook = { message, submit ->
            f.api.pane = f.api.pane.copy(text = "› ${if (submit) "" else message}\n\n  model · /repo · Context 50% left")
            if (submit) {
                holdConsumptionRead = true
                f.api.chatPage = f.api.chatPage!!.copy(rev = 5, head = 11, upserts = listOf(
                    TranscriptEntry("new-user", "", "user", listOf(TranscriptPart("text", "hello")), seq = 11),
                ))
            }
        }
        f.viewModel.sendReply("hello")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(2, f.api.replyCalls)
        assertFalse(f.viewModel.state.value.clearReplyDraft)
        f.viewModel.refresh()
        runCurrent()
        assertEquals(listOf("new-user"), f.viewModel.state.value.transcript.map { it.uuid })
        guardRead.complete(Unit)
        advanceUntilIdle()
        assertTrue(f.viewModel.state.value.clearReplyDraft)
        assertTrue(f.viewModel.state.value.codexAcceptedReplies.isEmpty())
        assertEquals(2, f.api.replyCalls)
    }

    @Test
    fun outputPollDuringComposerVerificationDoesNotRefuseAStableEmptyComposer() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.viewModel.refresh()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        var holdNextRead = true
        f.api.paneHook = {
            if (holdNextRead) {
                holdNextRead = false
                gate.await()
            }
        }
        f.viewModel.sendReply("hello")
        runCurrent()
        f.api.pane = f.api.pane.copy(text = "more output\n${f.api.pane.text}", revision = 8)
        f.viewModel.refresh()
        runCurrent()
        f.api.replyHook = { message, submit ->
            f.api.pane = f.api.pane.copy(text = "› ${if (submit) "" else message}\n\n  model · /repo · Context 50% left")
        }
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, f.api.replyCalls)
        assertTrue(f.viewModel.state.value.clearReplyDraft)
        assertNull(f.viewModel.state.value.mutationError)
    }

    @Test
    fun modalPollDuringComposerVerificationStillRefusesTheWrite() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.viewModel.refresh()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        var holdNextRead = true
        f.api.paneHook = {
            if (holdNextRead) {
                holdNextRead = false
                gate.await()
            }
        }
        f.viewModel.sendReply("hello")
        runCurrent()
        f.api.pane = f.api.pane.copy(text = "› 1. Yes\n  2. No\nPress enter to confirm or esc to cancel", revision = 8)
        f.viewModel.refresh()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, f.api.replyCalls)
        assertFalse(f.viewModel.state.value.clearReplyDraft)
        assertNotNull(f.viewModel.state.value.mutationError)
    }


    @Test
    fun unsupportedChordsAreBlockedWithoutDisablingThePlainKey() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200), agent = "shell")
        f.api.config = BridgeConfigResponse(mux = MuxConfigResponse(unsupportedKeys = listOf("ctrl+Enter", "Delete")))
        f.viewModel.refresh()
        advanceUntilIdle()
        f.viewModel.sendKey("ctrl+Enter")
        f.viewModel.sendKey("shift+Delete")
        advanceUntilIdle()
        assertTrue(f.api.keysCalls.isEmpty())
        f.viewModel.sendKey("Enter")
        advanceUntilIdle()
        assertEquals(listOf(listOf("Enter")), f.api.keysCalls)
    }

    @Test
    fun connectionChangeCancelsACodexSubmissionAndRetainsTheDraft() = runTest(dispatcher) {
        val f = fixture(ApiResult.Success(ActionResponse(ok = true), 200))
        f.viewModel.refresh()
        advanceUntilIdle()
        f.api.replyHook = { _, submit ->
            if (!submit) f.store.save(Connection(CollieOrigin("https://other.example/"), "other", "other-token"))
        }
        f.viewModel.sendReply("hello")
        advanceUntilIdle()
        assertEquals(1, f.api.replyCalls)
        assertFalse(f.viewModel.state.value.clearReplyDraft)
        assertFalse(f.viewModel.state.value.sending)
        assertNull(f.viewModel.state.value.pane)
        assertFalse(f.viewModel.state.value.canWrite)
    }

    private fun entry(uuid: String) =
        TranscriptEntry(uuid, "2026-09-10T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = uuid)))

    private fun fixture(
        reply: ApiResult<ActionResponse>,
        keyResults: List<ApiResult<ActionResponse>> = emptyList(),
        deviceAuthorization: DeviceAuthorization? = null,
        agent: String = "codex",
        address: PaneAddress = PaneAddress(paneId = "w1:p1"),
        diagnostics: com.lateapex.collie.diagnostics.DiagnosticsRecorder =
            com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(mutableListOf()),
        queuedAttempts: QueuedSendAttemptStore = MemoryQueuedAttempts(),
    ): Fixture {
        val connection = Connection(CollieOrigin("https://collie.example/"), "phone", "token")
        val store = FakeStore(connection)
        val api = FakeApi(reply, keyResults, deviceAuthorization)
        // The snapshot names the pane's agent and the view model follows it, so the fake
        // snapshot must agree with the agent the fixture launches with.
        api.snapshotPanes = api.snapshotPanes.map { if (it.paneId == address.paneId) it.copy(agent = agent) else it }
        val repository = CollieRepository(api, store, OriginValidator())
        val viewModel = PaneViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            address,
            agent,
            repository,
            diagnostics,
            queuedAttempts,
        )
        return Fixture(viewModel, api, store, repository, queuedAttempts)
    }

    private fun text(resource: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resource)

    private fun queuedPage(
        queued: List<String> = listOf("queued message"),
        revision: Long? = 3,
        generation: Long? = 7,
        digest: String? = if (queued == listOf("queued message")) "a".repeat(64) else "c".repeat(64),
        occupant: String? = "b".repeat(64),
        turns: List<TranscriptEntry> = emptyList(),
    ) = PaneChatResponse(
        paneId = "w1:p1", available = true, page = "live", gen = generation, rev = revision,
        head = 1, oldest = 1, hasOlder = false, upserts = turns, queued = queued,
        sendQueuedNow = listOf("ctrl+Enter"),
        queuedDigest = digest,
        queuedSendOccupant = occupant,
    )

    private data class Fixture(
        val viewModel: PaneViewModel,
        val api: FakeApi,
        val store: FakeStore,
        val repository: CollieRepository,
        val queuedAttempts: QueuedSendAttemptStore,
    )

    private class FakeStore(initial: Connection) : ConnectionStore {
        private val mutable = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = mutable
        override fun compareAndSet(expected: Connection, replacement: Connection): Boolean =
            mutable.compareAndSet(expected, replacement)

        override fun save(connection: Connection) { mutable.value = connection }
        override fun clear() { mutable.value = null }
    }

    private class MemoryQueuedAttempts : QueuedSendAttemptStore {
        private val current = mutableMapOf<String, String>()
        private val attempts = mutableSetOf<String>()
        override fun wasAttempted(actionIdentity: String): Boolean = actionIdentity in attempts
        override fun observe(paneIdentity: String, actionIdentity: String): Boolean {
            val prior = current.put(paneIdentity, actionIdentity)
            if (prior != null && prior != actionIdentity) attempts.remove(prior)
            return true
        }
        override fun recordBeforeDispatch(paneIdentity: String, actionIdentity: String): Boolean {
            if (actionIdentity in attempts || current[paneIdentity] != actionIdentity) return false
            attempts += actionIdentity
            return true
        }
        override fun recordOutcome(actionIdentity: String, outcome: String) = Unit
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
        var replyHook: ((String, Boolean) -> Unit)? = null
        var paneHook: (suspend () -> Unit)? = null
        var queuedSendTransport: CollieApi? = null
        var chatTransport: CollieApi? = null
        var chatHook: (() -> Unit)? = null
        var config = BridgeConfigResponse()
        var configAuthority: ControlAuthority? = null
        var configHook: (suspend () -> Unit)? = null
        var snapshotControlAuthority: ControlAuthority? = null
        var snapshotPanes = listOf(paneSummary(PaneAddress(paneId = "w1:p1")))
        var servers: List<ServerSummary>? = null
        var lastReplyExpectedPrompt: String? = null
        private val keyResults = ArrayDeque(keyResults)
        var lastExpectedPrompt: String? = null
        var queuedSendResult: ApiResult<ActionResponse> = ApiResult.Success(ActionResponse(ok = true), 200)
        val paneLines = mutableListOf<Int>()
        var pane = PaneReadResponse(
            paneId = "w1:p1",
            text = "transcript\n\n› \n\n  gpt-5 · /repo · Context 50% left",
            truncated = false,
            revision = 7,
        )
        var paneAlwaysNotModified = false
        var paneUnsolicitedNotModified = false
        var paneUnsolicitedReads = 0
        var paneTransport: CollieApi? = null
        val paneEtags = mutableListOf<String?>()
        var paneConditionalReads = 0
        var paneFreshReads = 0
        val historyPages = ArrayDeque<PaneHistoryResponse>()
        var historyCalls = 0
        var chatPage: PaneChatResponse? = null
        var chatCalls = 0
        var imageBlobCalls = 0
        val chatBeforeRequests = mutableListOf<ChatBefore?>()
        val chatOlderPages = ArrayDeque<PaneChatResponse>()
        var chatResponseEtag: String? = "\"chat-page\""
        val chatAfterRequests = mutableListOf<ChatAfter?>()
        val chatEtagRequests = mutableListOf<String?>()
        val queuedKeysCalls = mutableListOf<List<String>>()
        var lastQueuedGuard: QueuedSendGuard? = null
        override suspend fun chat(connection: Connection, address: PaneAddress, limit: Int,
            after: ChatAfter?, before: ChatBefore?, etag: String?): ApiResult<PaneChatResponse> {
            chatCalls++
            chatBeforeRequests += before
            chatAfterRequests += after
            chatEtagRequests += etag
            chatHook?.invoke()
            chatTransport?.let { transport ->
                return kotlinx.coroutines.runBlocking { transport.chat(connection, address, limit, after, before, etag) }
            }
            if (before != null) {
                val older = chatOlderPages.removeFirstOrNull()
                if (older != null) return ApiResult.Success(older, 200)
            }
            val page = chatPage ?: return ApiResult.Failure(ApiFailure.Protocol("Live chat is unavailable"))
            if (after?.gen == page.gen && after?.rev == page.rev) return ApiResult.NotModified(etag)
            return ApiResult.Success(page, 200, etag = chatResponseEtag)
        }
        override suspend fun imageBlob(connection: Connection, scope: Scope, sha256: String): ApiResult<com.lateapex.collie.network.ImageBlob> {
            imageBlobCalls++
            return ApiResult.Failure(ApiFailure.Http(404, null, null))
        }
        val historyLimits = mutableListOf<Int>()
        val historyBeforeRequests = mutableListOf<String?>()

        val historyEtags = mutableListOf<String?>()
        var historyUnchanged = false
        var historyAlwaysNotModified = false

        override suspend fun history(
            connection: Connection,
            address: PaneAddress,
            limit: Int,
            before: String?,
            etag: String?,
        ): ApiResult<PaneHistoryResponse> {
            historyCalls++
            historyBeforeRequests += before
            historyLimits += limit
            historyEtags += etag
            if (historyAlwaysNotModified || historyUnchanged && etag != null) return ApiResult.NotModified(etag)
            val page = historyPages.removeFirstOrNull() ?: PaneHistoryResponse(address.paneId, available = true)
            return ApiResult.Success(page, 200, etag = "\"page-$historyCalls\"")
        }

        override suspend fun pane(
            connection: Connection,
            address: PaneAddress,
            lines: Int,
            etag: String?,
            markSeen: Boolean,
        ): ApiResult<PaneReadResponse> {
            paneLines += lines
            paneEtags += etag
            paneHook?.invoke()
            paneTransport?.let { return it.pane(connection, address, lines, etag, markSeen) }
            if (paneUnsolicitedNotModified && etag == null) {
                paneUnsolicitedReads++
                return ApiResult.NotModified(null)
            }
            if (paneAlwaysNotModified) {
                paneConditionalReads++
                assertNotNull("cached304 test must send the cache's actual ETag", etag)
                return ApiResult.NotModified(etag)
            }
            paneFreshReads++
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
            replyHook?.invoke(text, submit)
            lastReplyExpectedPrompt = expectedPrompt
            return replyResult
        }

        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun config(connection: Connection): ApiResult<BridgeConfigResponse> {
            configHook?.invoke()
            return ApiResult.Success(config, 200, controlAuthority = configAuthority)
        }
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
            snapshotHook?.invoke()
            return ApiResult.Success(
                SnapshotResponse(
                    bridge = "connected",
                    controlAuthority = snapshotControlAuthority,
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

        override suspend fun queuedSendNow(
            connection: Connection,
            address: PaneAddress,
            keys: List<String>,
            expectedPrompt: String,
            guard: QueuedSendGuard,
        ): ApiResult<ActionResponse> {
            queuedKeysCalls += keys
            lastExpectedPrompt = expectedPrompt
            lastQueuedGuard = guard
            queuedSendTransport?.let { transport ->
                return kotlinx.coroutines.runBlocking { transport.queuedSendNow(connection, address, keys, expectedPrompt, guard) }
            }
            return queuedSendResult
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
