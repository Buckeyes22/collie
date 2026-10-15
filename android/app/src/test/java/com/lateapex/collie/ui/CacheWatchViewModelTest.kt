package com.lateapex.collie.ui

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CacheRule
import com.lateapex.collie.network.PaneCacheReading
import com.lateapex.collie.network.CacheRulesResponse
import com.lateapex.collie.network.CacheWatchListResponse
import com.lateapex.collie.network.CacheWatchResponse
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.OriginValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CacheWatchViewModelTest {
    @Test
    fun activityRecreationRestoresExactPeerAddressAndRefreshUsesSameScope() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val address = PaneAddress(Scope(host = "peer-east", session = "work"), "w2:p7")
        val fake = FakeApi(app, address).apply {
            setResult = ApiResult.Failure(ApiFailure.Http(409, "no session", "cache.no_session"))
        }
        val repository = repository(fake)
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val original = app.container.repository
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(CacheWatchActivity::class.java, CacheWatchActivity.intent(app, address))
            .create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val firstActivity = controller.get()
            val first = ViewModelProvider(
                firstActivity,
                SavedStateViewModelFactory(app, firstActivity, firstActivity.intent.extras),
            )[CacheWatchViewModel::class.java]
            assertEquals(address, first.address)
            val watchSwitch = findWatchSwitch(firstActivity.window.decorView)!!
            assertFalse(watchSwitch.isChecked)
            assertTrue(watchSwitch.isEnabled)
            watchSwitch.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("Refused add must leave the server-controlled switch off", watchSwitch.isChecked)
            assertTrue(first.state.value.error == CacheWatchMessage.UNAVAILABLE)

            val configuration = Configuration(firstActivity.resources.configuration).apply {
                orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    Configuration.ORIENTATION_PORTRAIT
                } else Configuration.ORIENTATION_LANDSCAPE
            }
            controller.configurationChange(configuration)
            shadowOf(Looper.getMainLooper()).idle()
            val recreatedActivity = controller.get()
            assertNotSame(firstActivity, recreatedActivity)
            val restored = ViewModelProvider(
                recreatedActivity,
                SavedStateViewModelFactory(app, recreatedActivity, recreatedActivity.intent.extras),
            )[CacheWatchViewModel::class.java]
            assertEquals(address, restored.address)
            restored.refresh()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(address.scope, address.scope), fake.snapshotScopes)
            assertEquals(listOf(address, address), fake.watchReads)
        } finally {
            controller.pause().stop().destroy()
            field.set(app.container, original)
        }
    }

    @Test
    fun savedPeerAddressSurvivesRefreshAndViewModelRecreationWithoutLosingScope() = withMainDispatcher { dispatcher ->
        runTest(dispatcher) {
            val app = ApplicationProvider.getApplicationContext<CollieApplication>()
            val scope = Scope(host = "peer-east", session = "work")
            val address = PaneAddress(scope, "w2:p7")
            val fake = FakeApi(app, address)
            val repository = repository(fake)
            val args = savedArgs(address)

            val first = CacheWatchViewModel(app as Application, SavedStateHandle(args), repository)
            advanceUntilIdle()
            assertEquals(address, first.address)
            first.refresh()
            advanceUntilIdle()

            val recreated = CacheWatchViewModel(app, SavedStateHandle(args), repository)
            advanceUntilIdle()

            assertEquals(address, recreated.address)
            assertEquals(listOf(scope, scope, scope), fake.snapshotScopes)
            assertEquals(listOf(address, address, address), fake.watchReads)
            assertTrue("Peer provenance must never fetch the lead catalog", fake.catalogReads.isEmpty())
        }
    }

    @Test
    fun refusedAddStaysOffAndAuthorityRetirementClearsTheSwitch() = withMainDispatcher { dispatcher ->
        runTest(dispatcher) {
            val app = ApplicationProvider.getApplicationContext<CollieApplication>()
            val address = PaneAddress(Scope(host = "peer-east", session = "work"), "w2:p7")
            val fake = FakeApi(app, address).apply {
                setResult = ApiResult.Failure(ApiFailure.Http(409, "no session", "cache.no_session"))
            }
            val repository = repository(fake)
            val viewModel = CacheWatchViewModel(app as Application, SavedStateHandle(savedArgs(address)), repository)
            advanceUntilIdle()
            assertTrue(CacheWatchControlState.switchEnabled(viewModel.state.value))

            viewModel.setWatching(true)
            advanceUntilIdle()

            assertEquals(false, viewModel.state.value.watch?.on)
            assertFalse(viewModel.state.value.busy)
            assertEquals(CacheWatchMessage.UNAVAILABLE, viewModel.state.value.error)
            assertEquals(listOf(address to true), fake.watchWrites)

            repository.setLeadEpoch(2)
            assertNull(viewModel.state.value.watch)
            assertFalse(CacheWatchControlState.switchEnabled(viewModel.state.value))
        }
    }

    @Test
    fun staleAddResultCannotReenableTheSwitchAfterItsCapturedReadContextRetires() = withMainDispatcher { dispatcher ->
        runTest(dispatcher) {
            val app = ApplicationProvider.getApplicationContext<CollieApplication>()
            val address = PaneAddress(Scope(host = "peer-east", session = "work"), "w2:p7")
            val pending = CompletableDeferred<ApiResult<CacheWatchResponse>>()
            val fake = FakeApi(app, address).apply { pendingSetResult = pending }
            val repository = repository(fake)
            val viewModel = CacheWatchViewModel(app as Application, SavedStateHandle(savedArgs(address)), repository)
            advanceUntilIdle()

            viewModel.setWatching(true)
            runCurrent()
            assertEquals(listOf(address to true), fake.watchWrites)
            repository.setLeadEpoch(2)
            assertNull(viewModel.state.value.watch)
            assertFalse(CacheWatchControlState.switchEnabled(viewModel.state.value))

            pending.complete(ApiResult.Success(CacheWatchResponse(on = true, global = false, watchable = true, warnSeconds = 75), 200))
            advanceUntilIdle()
            assertNull(viewModel.state.value.watch)
            assertFalse(CacheWatchControlState.switchEnabled(viewModel.state.value))
        }
    }

    @Test
    fun existingUnwatchableWatchCanBeRemovedButCannotBeAdded() = withMainDispatcher { dispatcher ->
        runTest(dispatcher) {
            val app = ApplicationProvider.getApplicationContext<CollieApplication>()
            val address = PaneAddress(Scope(host = "peer-east", session = "work"), "w2:p7")
            val fake = FakeApi(app, address).apply {
                watch = CacheWatchResponse(on = true, global = false, watchable = false, warnSeconds = 75)
                setResult = ApiResult.Success(watch.copy(on = false), 200)
            }
            val viewModel = CacheWatchViewModel(app as Application, SavedStateHandle(savedArgs(address)), repository(fake))
            advanceUntilIdle()

            assertTrue(CacheWatchControlState.switchEnabled(viewModel.state.value))
            viewModel.setWatching(false)
            advanceUntilIdle()

            assertEquals(listOf(address to false), fake.watchWrites)
            assertEquals(false, viewModel.state.value.watch?.on)
            assertTrue(CacheWatchControlState.switchEnabled(viewModel.state.value).not())
        }
    }

    @Test
    fun retirementClearsCatalogValidatorsAndOldCapturedGestureCannotBorrowNewActor() = withMainDispatcher { dispatcher ->
        runTest(dispatcher) {
            val app = ApplicationProvider.getApplicationContext<CollieApplication>()
            val address = PaneAddress(Scope(), "w2:p7")
            val rule = CacheRule("rule", "old origin rule", 300, "documented", "source", "https://source/", "2026-10-01")
            val fake = FakeApi(app, address).apply {
                cache = PaneCacheReading(ruleId = "rule")
                catalogResult = ApiResult.Success(CacheRulesResponse(listOf(rule)), 200, etag = "old-origin-etag")
            }
            val repository = repository(fake)
            val vm = CacheWatchViewModel(app, SavedStateHandle(savedArgs(address)), repository)
            advanceUntilIdle()
            val displayed = vm.state.value
            assertEquals(rule, displayed.rule)
            vm.refresh(); advanceUntilIdle()
            assertEquals(listOf(null, "old-origin-etag"), fake.catalogEtags)
            repository.setLeadEpoch(2)
            assertNull(vm.state.value.rule)
            fake.catalogResult = ApiResult.NotModified(etag = "old-origin-etag")
            vm.refresh(); advanceUntilIdle()
            assertEquals(listOf(null, "old-origin-etag", null), fake.catalogEtags)
            assertNull(vm.state.value.rule)
            assertTrue(vm.state.value.catalogUnavailable)
            vm.setWatching(true, displayed)
            assertTrue(fake.watchWrites.isEmpty())
            val retiredActor = displayed.actor!!
            assertTrue(repository.cacheWatch(address, retiredActor) is ApiResult.Failure)
            assertTrue(repository.setCacheWatch(address, true, retiredActor) is ApiResult.Failure)
            assertTrue(repository.cacheRules(address.scope, address.paneId, "old-origin-etag", retiredActor) is ApiResult.Failure)
            assertEquals(3, fake.watchReads.size)
            assertEquals(3, fake.catalogReads.size)
        }
    }

    @Test
    fun deferredCatalogAndAtomicPublicationCannotRestoreRetiredOrNewerFrame() = withMainDispatcher { dispatcher ->
        runTest(dispatcher) {
            val app = ApplicationProvider.getApplicationContext<CollieApplication>()
            val address = PaneAddress(Scope(), "w2:p7")
            val pending = CompletableDeferred<ApiResult<CacheRulesResponse>>()
            val fake = FakeApi(app, address).apply { cache = PaneCacheReading(ruleId = "rule"); pendingCatalog = pending }
            val repository = repository(fake)
            val vm = CacheWatchViewModel(app, SavedStateHandle(savedArgs(address)), repository)
            runCurrent()
            val loading = vm.state.value
            assertTrue(loading.loading)
            assertNotNull(loading.actor)
            vm.stop()
            // Exercise the precise non-suspending commit boundary after real public retirement.
            val method = vm.javaClass.getDeclaredMethod("publishIfCurrent", CacheWatchScreenState::class.java,
                CacheWatchScreenState::class.java, Map::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            assertEquals(false, method.invoke(vm, loading, loading.copy(loading = false), emptyMap<String, CacheRule>(), "late", true))
            pending.complete(ApiResult.Success(CacheRulesResponse(), 200, etag = "late"))
            advanceUntilIdle()
            assertNull(vm.state.value.pane)
            fake.pendingCatalog = null
            vm.refresh(); advanceUntilIdle()
            val fresh = vm.state.value
            assertNotNull(fresh.pane)
            assertEquals(false, method.invoke(vm, loading, loading.copy(loading = false), emptyMap<String, CacheRule>(), "late", true))
            assertTrue(vm.state.value === fresh)
            assertEquals(listOf(null, null), fake.catalogEtags)
        }
    }

    @Test
    fun deferredOldCredentialCatalogCannotSupplyAReplacementConnections304() = withMainDispatcher { dispatcher ->
        runTest(dispatcher) {
            val app = ApplicationProvider.getApplicationContext<CollieApplication>()
            val address = PaneAddress(Scope(), "w2:p7")
            val pending = CompletableDeferred<ApiResult<CacheRulesResponse>>()
            val fake = FakeApi(app, address).apply { cache = PaneCacheReading(ruleId = "rule"); pendingCatalog = pending }
            val store = object : ConnectionStore {
                override val connection = MutableStateFlow<Connection?>(Connection(CollieOrigin("https://cache-watch.fixture/"), "same label", "old credential"))
                override fun save(connection: Connection) { this.connection.value = connection }
                override fun clear() { connection.value = null }
            }
            val repository = CollieRepository(fake, store, OriginValidator())
            val vm = CacheWatchViewModel(app, SavedStateHandle(savedArgs(address)), repository)
            runCurrent()
            assertTrue(vm.state.value.loading)
            store.save(Connection(CollieOrigin("https://cache-watch.fixture/"), "same label", "replacement credential"))
            runCurrent()
            assertNull(vm.state.value.actor)
            pending.complete(ApiResult.Success(CacheRulesResponse(listOf(CacheRule("rule", "retired credential rule", 300,
                "documented", "source", "https://source/", "2026-10-01"))), 200, etag = "retired-etag"))
            advanceUntilIdle()
            assertNull(vm.state.value.pane)
            fake.pendingCatalog = null
            fake.catalogResult = ApiResult.NotModified("retired-etag")
            vm.refresh(); advanceUntilIdle()
            assertNotNull(vm.state.value.pane)
            assertNull(vm.state.value.rule)
            assertTrue(vm.state.value.catalogUnavailable)
            assertEquals(listOf(null, null), fake.catalogEtags)
            assertTrue(fake.watchWrites.isEmpty())
        }
    }

    @Test
    fun delayedConnectionNotificationsCannotRetireFreshCustodyAndOldPublicationRefuses() = withMainDispatcher { dispatcher ->
        runTest(dispatcher) {
            val app = ApplicationProvider.getApplicationContext<CollieApplication>()
            val address = PaneAddress(Scope(), "w2:p7")
            val original = Connection(CollieOrigin("https://cache-watch.fixture/"), "same label", "old credential")
            val replacement = Connection(CollieOrigin("https://cache-watch.fixture/"), "same label", "replacement credential")
            val store = object : ConnectionStore {
                override val connection = MutableStateFlow<Connection?>(original)
                override fun save(connection: Connection) { this.connection.value = connection }
                override fun clear() { connection.value = null }
            }
            val fake = FakeApi(app, address).apply {
                cache = PaneCacheReading(ruleId = "rule")
                catalogResult = ApiResult.Success(CacheRulesResponse(listOf(CacheRule("rule", "current rule", 300,
                    "documented", "source", "https://source/", "2026-10-01"))), 200, etag = "catalog")
            }
            val repository = CollieRepository(fake, store, OriginValidator())
            val vm = CacheWatchViewModel(app, SavedStateHandle(savedArgs(address)), repository)
            advanceUntilIdle()
            val oldFrame = vm.state.value
            assertNotNull(oldFrame.rule)
            store.save(replacement)
            // Do not drain the observer yet: completion must reject the actual changed store
            // even before the queued retirement is delivered.
            val publish = vm.javaClass.getDeclaredMethod("publishIfCurrent", CacheWatchScreenState::class.java,
                CacheWatchScreenState::class.java, Map::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            assertEquals(false, publish.invoke(vm, oldFrame, oldFrame, emptyMap<String, CacheRule>(), "late", true))
            assertFalse(vm.isCurrentPresentation(oldFrame))
            // A real explicit Refresh may acquire the replacement actor before its queued
            // connection notification. Its own readContext CONNECTION_CHANGED callback must
            // retire old custody without cancelling this fresh admission.
            vm.refresh()
            advanceUntilIdle()
            val fresh = vm.state.value
            assertNotNull(fresh.pane)
            assertNotNull(fresh.rule)
            assertTrue(vm.isCurrentPresentation(fresh))
            assertEquals(listOf(null, null), fake.catalogEtags)
            val notify = vm.javaClass.getDeclaredMethod("retireChangedConnection", Connection::class.java).apply { isAccessible = true }
            notify.invoke(vm, original)
            notify.invoke(vm, replacement)
            assertTrue(vm.state.value === fresh)
            assertTrue(vm.isCurrentPresentation(fresh))
            assertEquals(false, publish.invoke(vm, oldFrame, oldFrame, emptyMap<String, CacheRule>(), "late", true))
            assertTrue(vm.state.value === fresh)
            vm.setWatching(true, oldFrame)
            assertTrue(fake.watchWrites.isEmpty())
        }
    }

    @Test
    fun activityStopWipesCopiedFactsAndOffMainRetirementAcknowledgesViewsBeforeReturning() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val address = PaneAddress(Scope(host = "peer-east", session = "work"), "w2:p7")
        val fake = FakeApi(app, address)
        val repository = repository(fake)
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val original = app.container.repository
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(CacheWatchActivity::class.java, CacheWatchActivity.intent(app, address))
            .create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val switch = findWatchSwitch(activity.window.decorView)!!
            assertTrue(switch.isEnabled)
            val vm = ViewModelProvider(activity, SavedStateViewModelFactory(app, activity, activity.intent.extras))[CacheWatchViewModel::class.java]
            val oldListenerField = android.widget.CompoundButton::class.java.getDeclaredField("mOnCheckedChangeListener").apply { isAccessible = true }
            val queuedOldGesture = oldListenerField.get(switch) as android.widget.CompoundButton.OnCheckedChangeListener
            val finished = java.util.concurrent.CountDownLatch(1)
            val worker = Thread { repository.setLeadEpoch(2); finished.countDown() }
            worker.start()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (worker.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, worker.state)
            assertEquals(1L, finished.count)
            assertNull(vm.state.value.pane)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            worker.join(5000)
            assertFalse(switch.isEnabled)
            assertFalse(switch.isChecked)
            val copied = activity.javaClass.getDeclaredField("rendered").apply { isAccessible = true }.get(activity) as CacheWatchScreenState
            assertNull(copied.pane)
            assertNull(copied.watch)
            assertNull(copied.rule)
            assertNull(vm.state.value.pane)
            vm.refresh(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(switch.isEnabled)
            queuedOldGesture.onCheckedChanged(switch, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("A queued old gesture cannot borrow the refreshed actor", fake.watchWrites.isEmpty())
            controller.pause().stop()
            assertFalse(switch.isEnabled)
            assertNull(vm.state.value.pane)
            val stoppedCopy = activity.javaClass.getDeclaredField("rendered").apply { isAccessible = true }.get(activity) as CacheWatchScreenState
            assertNull(stoppedCopy.pane)
            assertNull(stoppedCopy.watch)
            switch.performClick()
            assertTrue(fake.watchWrites.isEmpty())
        } finally {
            controller.destroy()
            field.set(app.container, original)
        }
    }

    private fun savedArgs(address: PaneAddress) = mapOf(
        CacheWatchViewModel.KEY_PANE_ID to address.paneId,
        CacheWatchViewModel.KEY_HOST to address.scope.host,
        CacheWatchViewModel.KEY_SESSION to address.scope.session,
    )

    private fun repository(api: CollieApi): CollieRepository {
        val initialConnection = Connection(CollieOrigin("https://cache-watch.fixture/"), "fixture", "paired")
        val store = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(initialConnection)
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        return CollieRepository(api, store, OriginValidator())
    }

    private fun findWatchSwitch(root: View): MaterialSwitch? {
        if (root is MaterialSwitch && root.text.toString() == "Watch this pane") return root
        if (root is ViewGroup) for (index in 0 until root.childCount) findWatchSwitch(root.getChildAt(index))?.let { return it }
        return null
    }

    private class FakeApi(app: CollieApplication, private val address: PaneAddress) : CollieApi by app.container.api {
        val snapshotScopes = mutableListOf<Scope>()
        val watchReads = mutableListOf<PaneAddress>()
        val watchWrites = mutableListOf<Pair<PaneAddress, Boolean>>()
        val catalogReads = mutableListOf<Scope>()
        val catalogEtags = mutableListOf<String?>()
        var cache: PaneCacheReading? = null
        var catalogResult: ApiResult<CacheRulesResponse> = ApiResult.Success(CacheRulesResponse(), 200)
        var pendingCatalog: CompletableDeferred<ApiResult<CacheRulesResponse>>? = null
        var watch = CacheWatchResponse(on = false, global = false, watchable = true, warnSeconds = 75)
        var setResult: ApiResult<CacheWatchResponse>? = null
        var pendingSetResult: CompletableDeferred<ApiResult<CacheWatchResponse>>? = null

        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> {
            snapshotScopes += scope
            return ApiResult.Success(
                SnapshotResponse(
                    bridge = "connected",
                    agents = listOf(PaneSummary(
                        paneId = address.paneId,
                        workspaceId = "w2",
                        workspaceLabel = "work",
                        workspaceNumber = 2,
                        tabId = "w2:t1",
                        agent = "claude",
                        status = AgentStatus.WORKING,
                        cwd = "/repo",
                        cache = cache,
                        focused = true,
                        host = address.scope.host,
                        session = address.scope.session,
                    )),
                    shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1_754_000_000_000,
                ),
                200,
            )
        }

        override suspend fun cacheWatch(connection: Connection, address: PaneAddress): ApiResult<CacheWatchResponse> {
            watchReads += address
            return ApiResult.Success(watch, 200)
        }

        override suspend fun setCacheWatch(connection: Connection, address: PaneAddress, on: Boolean): ApiResult<CacheWatchResponse> {
            watchWrites += address to on
            pendingSetResult?.let { return it.await() }
            return setResult ?: ApiResult.Success(watch.copy(on = on), 200)
        }

        override suspend fun cacheRules(connection: Connection, etag: String?): ApiResult<CacheRulesResponse> {
            catalogReads += Scope()
            catalogEtags += etag
            return pendingCatalog?.await() ?: catalogResult
        }

        override suspend fun cacheWatchList(connection: Connection) = ApiResult.Success(CacheWatchListResponse(emptyList()), 200)
    }

    private fun withMainDispatcher(block: (TestDispatcher) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            block(dispatcher)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
