package com.lateapex.collie.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.HealthResponse
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PairResult
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.WorkspaceSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class LookNowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = Scope(host = "desk", session = "work")
    private val stopPollingCallbacks = mutableListOf<() -> Unit>()
    private val viewModelStores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        stopPollingCallbacks.forEach { it() }
        stopPollingCallbacks.clear()
        viewModelStores.forEach(ViewModelStore::clear)
        viewModelStores.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun dashboardLookNowRefreshesTheSelectedHostAndSessionBeforeItsSnapshot() = runTest(dispatcher) {
        val fixture = mainFixture()
        fixture.viewModel.startPolling(refreshBridge = false)
        runCurrent()
        fixture.viewModel.stopPolling()
        fixture.api.events.clear()

        fixture.viewModel.refresh()
        runCurrent()

        assertEquals(listOf("refresh:desk:work", "snapshot:desk:work"), fixture.api.events)
        assertNotNull(fixture.viewModel.state.value.snapshot)
        assertFalse(fixture.viewModel.state.value.refreshing)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun dashboardRefreshFailureKeepsItsSnapshotAndSettlesTheSpinner() = runTest(dispatcher) {
        val fixture = mainFixture()
        fixture.viewModel.startPolling(refreshBridge = false)
        runCurrent()
        fixture.viewModel.stopPolling()
        val before = fixture.viewModel.state.value.snapshot
        fixture.api.events.clear()
        fixture.api.refreshResult = ApiResult.Failure(ApiFailure.Network("offline"))

        fixture.viewModel.refresh()
        runCurrent()

        assertEquals(listOf("refresh:desk:work", "snapshot:desk:work"), fixture.api.events)
        assertEquals(before?.ts, fixture.viewModel.state.value.snapshot?.ts)
        assertNotNull(fixture.viewModel.state.value.refreshNotice)
        assertFalse(fixture.viewModel.state.value.refreshing)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun dashboardDiscardsTheInFlightLookNowSnapshotAfterScopeChanges() = runTest(dispatcher) {
        val fixture = mainFixture()
        fixture.viewModel.startPolling(refreshBridge = false)
        runCurrent()
        fixture.viewModel.stopPolling()
        fixture.api.snapshotScopes.clear()
        fixture.api.events.clear()
        val blockedSnapshot = CompletableDeferred<ApiResult<SnapshotResponse>>()
        fixture.api.nextSnapshotGate = blockedSnapshot

        fixture.viewModel.refresh()
        runCurrent()
        assertTrue(fixture.viewModel.state.value.refreshing)
        assertEquals("snapshot:desk:work", fixture.api.events.last())

        fixture.viewModel.selectScope(Scope(host = "laptop", session = "personal"))
        fixture.viewModel.stopPolling()
        blockedSnapshot.complete(ApiResult.Success(snapshot("stale scope"), 200))
        runCurrent()

        assertNull(fixture.viewModel.state.value.snapshot)
        assertFalse(fixture.viewModel.state.value.refreshing)
        assertEquals(listOf(scope), fixture.api.snapshotScopes)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun dashboardCoalescesConcurrentLookNowRequests() = runTest(dispatcher) {
        val fixture = mainFixture()
        fixture.viewModel.startPolling(refreshBridge = false)
        runCurrent()
        fixture.viewModel.stopPolling()
        fixture.api.events.clear()
        fixture.api.snapshotTokens.clear()
        fixture.api.refreshTokens.clear()
        val blockedRefresh = CompletableDeferred<ApiResult<ActionResponse>>()
        fixture.api.nextRefreshGate = blockedRefresh

        fixture.viewModel.refresh()
        fixture.viewModel.refresh()
        runCurrent()

        assertEquals(listOf("refresh:desk:work"), fixture.api.events)
        blockedRefresh.complete(ApiResult.Success(ActionResponse(ok = true), 200))
        runCurrent()
        assertEquals(listOf("refresh:desk:work", "snapshot:desk:work"), fixture.api.events)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun dashboardDiscardsDelayedRefreshFailureAfterScopeChanges() = runTest(dispatcher) {
        val fixture = mainFixture()
        fixture.viewModel.startPolling(refreshBridge = false)
        runCurrent()
        fixture.viewModel.stopPolling()
        fixture.api.events.clear()
        fixture.api.snapshotTokens.clear()
        fixture.api.refreshTokens.clear()
        val blockedRefresh = CompletableDeferred<ApiResult<ActionResponse>>()
        fixture.api.nextRefreshGate = blockedRefresh

        fixture.viewModel.refresh()
        runCurrent()
        assertEquals(listOf("refresh:desk:work"), fixture.api.events)
        fixture.viewModel.selectScope(Scope(host = "laptop", session = "personal"))
        fixture.viewModel.stopPolling()
        blockedRefresh.complete(ApiResult.Failure(ApiFailure.Network("old scope failed")))
        runCurrent()

        assertNull(fixture.viewModel.state.value.refreshNotice)
        assertEquals("laptop", fixture.viewModel.currentScope.host)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun dashboardDiscardsDelayedRefreshAfterSameScopeConnectionReplacement() = runTest(dispatcher) {
        val fixture = mainFixture()
        fixture.viewModel.startPolling(refreshBridge = false)
        runCurrent()
        fixture.viewModel.stopPolling()
        fixture.api.events.clear()
        fixture.api.snapshotTokens.clear()
        fixture.api.refreshTokens.clear()
        val blockedRefresh = CompletableDeferred<ApiResult<ActionResponse>>()
        fixture.api.nextRefreshGate = blockedRefresh

        fixture.viewModel.refresh()
        runCurrent()
        fixture.store.save(Connection(CollieOrigin("https://collie.example/"), "replacement", "replacement-token"))
        runCurrent()
        blockedRefresh.complete(ApiResult.Success(ActionResponse(ok = true), 200))
        runCurrent()

        assertEquals(listOf("refresh:desk:work"), fixture.api.events)
        assertTrue(fixture.api.snapshotTokens.isEmpty())
        assertNull(fixture.viewModel.state.value.refreshNotice)
        assertFalse(fixture.viewModel.state.value.refreshing)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun repositoryRefusesToDispatchLookNowWithAReplacedCapturedConnection() = runTest(dispatcher) {
        val fixture = mainFixture()
        val captured = requireNotNull(fixture.repository.readContext(scope, null))
        fixture.store.save(Connection(CollieOrigin("https://collie.example/"), "replacement", "replacement-token"))
        runCurrent()

        val result = fixture.repository.refresh(scope, captured)

        assertFalse(result is ApiResult.Success)
        assertTrue(fixture.api.refreshTokens.isEmpty())
        fixture.viewModel.stopPolling()
    }

    @Test
    fun repositorySnapshotAndPaneSnapshotRefuseARetiredLeadContext() = runTest(dispatcher) {
        val fixture = mainFixture()
        val captured = requireNotNull(fixture.repository.readContext(scope, null))
        fixture.repository.setLeadEpoch(captured.key.leadEpoch + 1)

        assertFalse(fixture.repository.snapshot(scope, captured) is ApiResult.Success)
        assertFalse(fixture.repository.paneSnapshot(scope, captured) is ApiResult.Success)
        assertTrue(fixture.api.snapshotTokens.isEmpty())
    }

    @Test
    fun dashboardLeadChangeSettlesLookNowSpinnerAndDiscardsItsSnapshot() = runTest(dispatcher) {
        val fixture = mainFixture()
        fixture.viewModel.startPolling(refreshBridge = false)
        runCurrent()
        fixture.viewModel.stopPolling()
        val gate = CompletableDeferred<ApiResult<SnapshotResponse>>()
        fixture.api.nextSnapshotGate = gate

        fixture.viewModel.refresh()
        runCurrent()
        assertTrue(fixture.viewModel.state.value.refreshing)

        fixture.repository.setLeadEpoch(1)
        runCurrent()
        assertFalse(fixture.viewModel.state.value.refreshing)
        gate.complete(ApiResult.Success(snapshot("retired lead"), 200))
        runCurrent()
        assertFalse(fixture.viewModel.state.value.refreshing)
        assertEquals("desk", fixture.viewModel.state.value.snapshot?.servers?.firstOrNull()?.id)
    }

    @Test
    fun spaceLeadChangeSettlesLookNowSpinnerAndDiscardsItsSnapshot() = runTest(dispatcher) {
        val fixture = spaceFixture()
        fixture.viewModel.startPolling()
        runCurrent()
        fixture.viewModel.stopPolling()
        val before = fixture.viewModel.state.value.content
        val gate = CompletableDeferred<ApiResult<SnapshotResponse>>()
        fixture.api.nextSnapshotGate = gate

        fixture.viewModel.refresh()
        runCurrent()
        assertTrue(fixture.viewModel.state.value.refreshing)

        fixture.repository.setLeadEpoch(1)
        runCurrent()
        assertFalse(fixture.viewModel.state.value.refreshing)
        gate.complete(ApiResult.Success(snapshot("retired lead"), 200))
        runCurrent()
        assertFalse(fixture.viewModel.state.value.refreshing)
        assertEquals(before?.workspace?.label, fixture.viewModel.state.value.content?.workspace?.label)
    }

    @Test
    fun dashboardColdSnapshotSettlesLoadingWhenLeadChanges() = runTest(dispatcher) {
        val fixture = mainFixture()
        val gate = CompletableDeferred<ApiResult<SnapshotResponse>>()
        fixture.api.nextSnapshotGate = gate
        try {
            fixture.viewModel.startPolling(refreshBridge = false)
            runCurrent()
            assertTrue(fixture.viewModel.state.value.loading)

            fixture.repository.setLeadEpoch(1)
            runCurrent()

            assertFalse(fixture.viewModel.state.value.loading)
            assertFalse(fixture.viewModel.state.value.refreshing)
            assertNull(fixture.viewModel.state.value.snapshot)
        } finally {
            gate.complete(ApiResult.Success(snapshot("cleanup"), 200))
            fixture.viewModel.stopPolling()
            viewModelStores.forEach(ViewModelStore::clear)
        }
    }

    @Test
    fun spaceColdSnapshotSettlesLoadingWhenLeadChanges() = runTest(dispatcher) {
        val fixture = spaceFixture()
        val gate = CompletableDeferred<ApiResult<SnapshotResponse>>()
        fixture.api.nextSnapshotGate = gate
        try {
            fixture.viewModel.startPolling()
            runCurrent()
            assertTrue(fixture.viewModel.state.value.loading)

            fixture.repository.setLeadEpoch(1)
            runCurrent()

            assertFalse(fixture.viewModel.state.value.loading)
            assertFalse(fixture.viewModel.state.value.refreshing)
            assertNull(fixture.viewModel.state.value.content)
        } finally {
            gate.complete(ApiResult.Success(snapshot("cleanup"), 200))
            fixture.viewModel.stopPolling()
            viewModelStores.forEach(ViewModelStore::clear)
        }
    }

    @Test
    fun retiredContextAtAtomicReadAdmissionNeverDispatchesSnapshot() = runTest(dispatcher) {
        val fixture = mainFixture()
        val captured = requireNotNull(fixture.repository.readContext(scope, null))
        val admissionReached = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        fixture.repository.beforeReadAdmissionForTesting = {
            admissionReached.complete(Unit)
            releaseAdmission.await()
        }
        val pending = async { fixture.repository.snapshot(scope, captured) }
        runCurrent()
        admissionReached.await()

        fixture.repository.setLeadEpoch(captured.key.leadEpoch + 1)
        releaseAdmission.complete(Unit)
        runCurrent()

        assertFalse(pending.await() is ApiResult.Success)
        assertTrue(fixture.api.snapshotTokens.isEmpty())
        fixture.repository.beforeReadAdmissionForTesting = {}
    }

    @Test
    fun repositoryCompletesCoalescedLookNowWaitersWhenConnectionRetires() = runTest(dispatcher) {
        val fixture = mainFixture()
        val captured = requireNotNull(fixture.repository.readContext(scope, null))
        val gate = CompletableDeferred<ApiResult<ActionResponse>>()
        fixture.api.nextRefreshGate = gate
        val owner = async { fixture.repository.refresh(scope, captured) }
        runCurrent()
        val waiter = async { fixture.repository.refresh(scope, captured) }
        runCurrent()

        fixture.store.save(Connection(CollieOrigin("https://collie.example/"), "replacement", "replacement-token"))
        fixture.repository.readContext(scope, null)
        runCurrent()

        assertFalse(withTimeout(1_000) { owner.await() } is ApiResult.Success)
        assertFalse(withTimeout(1_000) { waiter.await() } is ApiResult.Success)
        assertEquals(listOf("token"), fixture.api.refreshTokens)
        gate.complete(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.stopPolling()
    }

    @Test
    fun repositoryCompletesCoalescedLookNowWaitersWhenScopeRetires() = runTest(dispatcher) {
        val fixture = mainFixture()
        val captured = requireNotNull(fixture.repository.readContext(scope, null))
        val gate = CompletableDeferred<ApiResult<ActionResponse>>()
        fixture.api.nextRefreshGate = gate
        val owner = async { fixture.repository.refresh(scope, captured) }
        runCurrent()
        val waiter = async { fixture.repository.refresh(scope, captured) }
        runCurrent()

        fixture.repository.selectScope(Scope(host = "laptop", session = "personal"))
        runCurrent()

        assertFalse(withTimeout(1_000) { owner.await() } is ApiResult.Success)
        assertFalse(withTimeout(1_000) { waiter.await() } is ApiResult.Success)
        assertEquals(listOf("token"), fixture.api.refreshTokens)
        gate.complete(ApiResult.Success(ActionResponse(ok = true), 200))
        fixture.viewModel.stopPolling()
    }

    @Test
    fun spaceLookNowUsesItsScopeAndSnapshotsOnlyAfterRefresh() = runTest(dispatcher) {
        val fixture = spaceFixture()
        fixture.viewModel.startPolling()
        runCurrent()
        fixture.viewModel.stopPolling()
        fixture.api.events.clear()

        fixture.viewModel.refresh()
        runCurrent()

        assertEquals(listOf("refresh:desk:work", "snapshot:desk:work"), fixture.api.events)
        assertNotNull(fixture.viewModel.state.value.content)
        assertFalse(fixture.viewModel.state.value.refreshing)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun spaceOldPeerRefreshFallbackStillReloadsAndNamesTheOrdinaryRead() = runTest(dispatcher) {
        val fixture = spaceFixture()
        fixture.viewModel.startPolling()
        runCurrent()
        fixture.viewModel.stopPolling()
        fixture.api.events.clear()
        fixture.api.refreshResult = ApiResult.Failure(ApiFailure.Http(404, "route missing", null))

        fixture.viewModel.refresh()
        runCurrent()

        assertEquals(listOf("refresh:desk:work", "snapshot:desk:work"), fixture.api.events)
        assertNotNull(fixture.viewModel.state.value.refreshNotice)
        assertFalse(fixture.viewModel.state.value.refreshing)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun spaceRefreshFailureRetainsTheSnapshotAndSettlesWithoutClaimingRefresh() = runTest(dispatcher) {
        val fixture = spaceFixture()
        fixture.viewModel.startPolling()
        runCurrent()
        fixture.viewModel.stopPolling()
        val before = fixture.viewModel.state.value.content
        val previousReadSource = fixture.viewModel.state.value.readSource
        val previousTimestamp = requireNotNull(previousReadSource.capturedAt)
        fixture.api.events.clear()
        fixture.api.refreshResult = ApiResult.Failure(ApiFailure.Network("offline"))

        fixture.viewModel.refresh()
        runCurrent()

        assertEquals(listOf("refresh:desk:work"), fixture.api.events)
        assertEquals(before?.workspace?.label, fixture.viewModel.state.value.content?.workspace?.label)
        assertEquals(previousTimestamp, fixture.viewModel.state.value.readSource.capturedAt)
        assertTrue(fixture.viewModel.state.value.readSource is ReadSourceState.SavedCopy)
        assertFalse(fixture.viewModel.state.value.writeAuthorized)
        assertFalse(fixture.repository.writesAllowed(scope, "w1"))
        assertNotNull(fixture.viewModel.state.value.error)
        assertFalse(fixture.viewModel.state.value.refreshing)

        val recoveredTimestamp = previousTimestamp + 120_000
        fixture.api.nextSnapshotGate = CompletableDeferred(ApiResult.Success(
            snapshot("fresh recovery").copy(ts = recoveredTimestamp), 200,
        ))
        fixture.api.refreshResult = ApiResult.Success(ActionResponse(ok = true), 200)
        fixture.api.events.clear()
        fixture.viewModel.refresh()
        runCurrent()

        assertEquals(listOf("refresh:desk:work", "snapshot:desk:work"), fixture.api.events)
        assertEquals("fresh recovery", fixture.viewModel.state.value.content?.workspace?.label)
        assertEquals(recoveredTimestamp, fixture.viewModel.state.value.readSource.capturedAt)
        assertTrue(fixture.viewModel.state.value.readSource is ReadSourceState.Live)
        assertTrue(fixture.viewModel.state.value.writeAuthorized)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun coldSpaceRefreshFailureDoesNotInventASnapshotTimestamp() = runTest(dispatcher) {
        val fixture = spaceFixture()
        fixture.api.refreshResult = ApiResult.Failure(ApiFailure.Network("offline"))

        fixture.viewModel.refresh()
        runCurrent()

        assertEquals(listOf("refresh:desk:work"), fixture.api.events)
        assertNull(fixture.viewModel.state.value.content)
        assertNull(fixture.viewModel.state.value.readSource.capturedAt)
        assertTrue(fixture.viewModel.state.value.readSource is ReadSourceState.Unavailable)
        assertNotNull(fixture.viewModel.state.value.error)
        assertFalse(fixture.viewModel.state.value.refreshing)
        assertFalse(fixture.viewModel.state.value.writeAuthorized)
        assertFalse(fixture.repository.writesAllowed(scope, "w1"))
    }

    @Test
    fun spaceLookNowWaitsForAnInFlightPollBeforeRefreshing() = runTest(dispatcher) {
        val fixture = spaceFixture()
        fixture.viewModel.startPolling()
        runCurrent()
        fixture.viewModel.stopPolling()
        fixture.api.events.clear()
        val blockedSnapshot = CompletableDeferred<ApiResult<SnapshotResponse>>()
        fixture.api.nextSnapshotGate = blockedSnapshot

        fixture.viewModel.startPolling()
        runCurrent()
        fixture.viewModel.refresh()
        runCurrent()
        assertEquals(listOf("snapshot:desk:work"), fixture.api.events)
        assertTrue(fixture.viewModel.state.value.refreshing)

        blockedSnapshot.complete(ApiResult.Success(snapshot("polled"), 200))
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertEquals(
            listOf("snapshot:desk:work", "refresh:desk:work", "snapshot:desk:work"),
            fixture.api.events,
        )
        assertFalse(fixture.viewModel.state.value.refreshing)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun spaceDiscardsDelayedRefreshAfterItsReadScopeIsReplaced() = runTest(dispatcher) {
        val fixture = spaceFixture()
        fixture.viewModel.startPolling()
        runCurrent()
        fixture.viewModel.stopPolling()
        fixture.api.events.clear()
        fixture.api.snapshotTokens.clear()
        fixture.api.refreshTokens.clear()
        val blockedRefresh = CompletableDeferred<ApiResult<ActionResponse>>()
        fixture.api.nextRefreshGate = blockedRefresh

        fixture.viewModel.refresh()
        runCurrent()
        assertTrue(fixture.viewModel.state.value.refreshing)
        assertEquals(listOf("refresh:desk:work"), fixture.api.events)

        fixture.repository.selectSubject(Scope(host = "laptop", session = "personal"), "w2")
        runCurrent()
        blockedRefresh.complete(ApiResult.Success(ActionResponse(ok = true), 200))
        runCurrent()

        assertEquals(listOf("refresh:desk:work"), fixture.api.events)
        assertNull(fixture.viewModel.state.value.content)
        assertFalse(fixture.viewModel.state.value.refreshing)
        fixture.viewModel.stopPolling()
    }

    @Test
    fun spaceQueuedLookNowDoesNotDispatchOnAReplacementConnection() = runTest(dispatcher) {
        val fixture = spaceFixture()
        val blockedSnapshot = CompletableDeferred<ApiResult<SnapshotResponse>>()
        fixture.api.nextSnapshotGate = blockedSnapshot
        fixture.viewModel.startPolling()
        runCurrent()
        assertEquals(listOf("snapshot:desk:work"), fixture.api.events)

        fixture.viewModel.refresh()
        runCurrent()
        fixture.store.save(Connection(CollieOrigin("https://collie.example/"), "replacement", "replacement-token"))
        runCurrent()
        blockedSnapshot.complete(ApiResult.Success(snapshot("old connection"), 200))
        runCurrent()

        assertTrue(fixture.api.refreshTokens.isEmpty())
        assertTrue(fixture.api.snapshotTokens.all { it == "token" })
        assertFalse(fixture.viewModel.state.value.refreshing)
        fixture.viewModel.stopPolling()
    }

    private fun mainFixture(): MainFixture {
        val app = ApplicationProvider.getApplicationContext<Application>()
        NativePreferences(app).dashboardScope = scope
        val store = FakeStore(Connection(CollieOrigin("https://collie.example/"), "phone", "token"))
        val api = LookNowApi()
        val repository = CollieRepository(api, store, OriginValidator())
        val viewModel = MainViewModel(app, repository)
        register(viewModel, viewModel::stopPolling)
        return MainFixture(viewModel, api, repository, store)
    }

    private fun spaceFixture(): SpaceFixture {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = FakeStore(Connection(CollieOrigin("https://collie.example/"), "phone", "token"))
        val api = LookNowApi()
        val repository = CollieRepository(api, store, OriginValidator())
        val viewModel = SpaceViewModel(app, "w1", scope, repository)
        register(viewModel, viewModel::stopPolling)
        return SpaceFixture(viewModel, api, repository, store)
    }

    private data class MainFixture(val viewModel: MainViewModel, val api: LookNowApi, val repository: CollieRepository, val store: FakeStore)
    private data class SpaceFixture(val viewModel: SpaceViewModel, val api: LookNowApi, val repository: CollieRepository, val store: FakeStore)

    private fun register(viewModel: ViewModel, stopPolling: () -> Unit) {
        val store = ViewModelStore()
        store.put("look-now", viewModel)
        viewModelStores += store
        stopPollingCallbacks += stopPolling
    }

    private class FakeStore(initial: Connection) : ConnectionStore {
        private val value = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = value
        override fun save(connection: Connection) { value.value = connection }
        override fun clear() { value.value = null }
    }

    private class LookNowApi : CollieApi {
        val events = mutableListOf<String>()
        val snapshotScopes = mutableListOf<Scope>()
        val snapshotTokens = mutableListOf<String>()
        val refreshTokens = mutableListOf<String>()
        var refreshResult: ApiResult<ActionResponse> = ApiResult.Success(ActionResponse(ok = true), 200)
        var nextSnapshotGate: CompletableDeferred<ApiResult<SnapshotResponse>>? = null
        var nextRefreshGate: CompletableDeferred<ApiResult<ActionResponse>>? = null

        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
            events += "snapshot:${scope.host}:${scope.session}"
            snapshotTokens += connection.token.orEmpty()
            snapshotScopes += scope
            val gate = nextSnapshotGate.also { nextSnapshotGate = null }
            return gate?.await() ?: ApiResult.Success(snapshot(scope.host ?: "fixture"), 200)
        }

        override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> {
            events += "refresh:${scope.host}:${scope.session}"
            refreshTokens += connection.token.orEmpty()
            val gate = nextRefreshGate.also { nextRefreshGate = null }
            return gate?.await() ?: refreshResult
        }

        override suspend fun health(connection: Connection): ApiResult<HealthResponse> = error("unused")
        override suspend fun pane(
            connection: Connection,
            address: com.lateapex.collie.domain.PaneAddress,
            lines: Int,
            etag: String?,
            markSeen: Boolean,
        ): ApiResult<com.lateapex.collie.network.PaneReadResponse> = error("unused")
        override suspend fun pair(origin: CollieOrigin, code: String, label: String): ApiResult<PairResult> = error("unused")
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = error("unused")
        override suspend fun reply(
            connection: Connection,
            address: com.lateapex.collie.domain.PaneAddress,
            text: String,
            submit: Boolean,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> = error("unused")
        override suspend fun keys(
            connection: Connection,
            address: com.lateapex.collie.domain.PaneAddress,
            keys: List<String>,
            expectedPrompt: String?,
        ): ApiResult<ActionResponse> = error("unused")
    }

    private companion object {
        fun snapshot(label: String) = SnapshotResponse(
            bridge = "connected",
            agents = emptyList(),
            shellPanes = emptyList(),
            workspaces = listOf(WorkspaceSummary("w1", 1, label, true, "w1:t1", 0, 0, host = "desk")),
            tabs = emptyList(),
            sessions = listOf(com.lateapex.collie.network.SessionSummary("work", true, true, 0, 0, 0, "desk")),
            servers = listOf(com.lateapex.collie.network.ServerSummary("desk", "desk", true, true, "local", lastSeenAt = 1)),
            ts = label.hashCode().toLong(),
        )
    }
}
