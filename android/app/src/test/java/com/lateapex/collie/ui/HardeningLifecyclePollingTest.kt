package com.lateapex.collie.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.HealthResponse
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.WorkspaceSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun hardeningSnapshot(label: String) = SnapshotResponse(
    bridge = "connected",
    agents = emptyList(),
    shellPanes = emptyList(),
    workspaces = listOf(
        WorkspaceSummary("w1", 1, label, true, "w1:t1", tabCount = 0, paneCount = 0),
    ),
    tabs = emptyList(),
    ts = 1,
)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HardeningLifecyclePollingTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun paneKeepsLastReadDuringOutageThenRecoversAfterBackoff() = runTest(dispatcher) {
        val fixture = paneFixture()
        fixture.api.paneResult = ApiResult.Success(PaneReadResponse("w1:p1", "fresh", false, 1), 200)
        fixture.viewModel.startPolling()
        runCurrent()
        assertEquals("fresh", fixture.viewModel.state.value.pane?.text)

        fixture.api.paneResult = ApiResult.Failure(ApiFailure.Network("offline"))
        advanceTimeBy(PaneViewModel.POLL_MS)
        runCurrent()
        assertEquals(2, fixture.api.paneCalls)
        assertEquals("fresh", fixture.viewModel.state.value.pane?.text)
        assertNotNull(fixture.viewModel.state.value.error)

        advanceTimeBy(PaneViewModel.POLL_MS)
        runCurrent()
        assertEquals("the first failure doubles the delay", 2, fixture.api.paneCalls)
        advanceTimeBy(PaneViewModel.POLL_MS)
        runCurrent()
        assertEquals("the next retry occurs after the doubled interval", 3, fixture.api.paneCalls)

        fixture.api.paneResult = ApiResult.Success(PaneReadResponse("w1:p1", "recovered", false, 2), 200)
        advanceTimeBy(4 * PaneViewModel.POLL_MS)
        runCurrent()
        assertEquals(4, fixture.api.paneCalls)
        assertEquals("recovered", fixture.viewModel.state.value.pane?.text)
        assertNull(fixture.viewModel.state.value.error)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun stoppingForegroundPollingCancelsInflightReadAndResumeStartsFreshRead() = runTest(dispatcher) {
        val fixture = paneFixture()
        fixture.api.blockPaneReads = true
        fixture.viewModel.startPolling()
        runCurrent()
        assertEquals(1, fixture.api.paneCalls)
        assertEquals(0, fixture.api.cancelledPaneCalls)

        fixture.viewModel.stopPolling()
        runCurrent()
        assertEquals(1, fixture.api.cancelledPaneCalls)
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals("a stopped view does not keep polling", 1, fixture.api.paneCalls)

        fixture.api.blockPaneReads = false
        fixture.api.paneResult = ApiResult.Success(PaneReadResponse("w1:p1", "resumed", false, 1), 200)
        fixture.viewModel.startPolling()
        runCurrent()
        assertEquals(2, fixture.api.paneCalls)
        assertEquals("resumed", fixture.viewModel.state.value.pane?.text)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun spacePreservesStaleSnapshotAndBacksOffUntilServerReturns() = runTest(dispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = FakeStore(Connection(CollieOrigin("https://collie.example/"), "phone", "token"))
        val api = FakeApi().apply {
            snapshotResult = ApiResult.Success(hardeningSnapshot("before outage"), 200)
        }
        val repository = CollieRepository(api, store, OriginValidator())
        val viewModel = SpaceViewModel(app, "w1", Scope(), repository)
        viewModel.startPolling()
        runCurrent()
        assertEquals("before outage", viewModel.state.value.content?.workspace?.label)
        assertEquals(1, api.snapshotCalls)

        api.snapshotResult = ApiResult.Failure(ApiFailure.Network("offline"))
        advanceTimeBy(SpaceViewModel.POLL_MS)
        runCurrent()
        assertEquals(2, api.snapshotCalls)
        assertEquals("before outage", viewModel.state.value.content?.workspace?.label)
        assertNotNull(viewModel.state.value.error)

        advanceTimeBy(SpaceViewModel.POLL_MS)
        runCurrent()
        assertEquals("space polling waits out the first backoff interval", 2, api.snapshotCalls)
        advanceTimeBy(SpaceViewModel.POLL_MS)
        runCurrent()
        assertEquals(3, api.snapshotCalls)

        api.snapshotResult = ApiResult.Success(hardeningSnapshot("after recovery"), 200)
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(4, api.snapshotCalls)
        assertEquals("after recovery", viewModel.state.value.content?.workspace?.label)
        assertNull(viewModel.state.value.error)
        viewModel.stopPolling()
    }

    private fun paneFixture(): Fixture {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = FakeStore(Connection(CollieOrigin("https://collie.example/"), "phone", "token"))
        val api = FakeApi()
        val repository = CollieRepository(api, store, OriginValidator())
        val viewModel = PaneViewModel(
            app,
            PaneAddress(paneId = "w1:p1"),
            "shell",
            repository,
            RecordingDiagnosticsRecorder(mutableListOf()),
        )
        return Fixture(viewModel, api)
    }

    private data class Fixture(val viewModel: PaneViewModel, val api: FakeApi)

    private class FakeStore(initial: Connection) : ConnectionStore {
        private val value = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = value
        override fun save(connection: Connection) { value.value = connection }
        override fun clear() { value.value = null }
    }

    private class FakeApi : CollieApi {
        var snapshotResult: ApiResult<SnapshotResponse> = ApiResult.Success(hardeningSnapshot("fixture"), 200)
        var snapshotCalls = 0
        var paneResult: ApiResult<PaneReadResponse> =
            ApiResult.Success(PaneReadResponse("w1:p1", "initial", false, 0), 200)
        var paneCalls = 0
        var cancelledPaneCalls = 0
        var blockPaneReads = false

        override suspend fun pane(
            connection: Connection,
            address: PaneAddress,
            lines: Int,
            etag: String?,
            markSeen: Boolean,
        ): ApiResult<PaneReadResponse> {
            paneCalls++
            if (blockPaneReads) {
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    cancelledPaneCalls++
                    throw cancelled
                }
            }
            return paneResult
        }

        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
            snapshotCalls++
            return snapshotResult
        }
        override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> = error("unused")
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> = error("unused")
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = error("unused")
        override suspend fun reply(
            connection: Connection,
            address: PaneAddress,
            text: String,
            submit: Boolean,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> = error("unused")
        override suspend fun keys(
            connection: Connection,
            address: PaneAddress,
            keys: List<String>,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> = error("unused")
    }
}
