package com.lateapex.collie.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.*
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class OwnedRetirementConsumersTest {
    @Test fun sameExistingMainViewModelFreshBStateSurvivesLateAReadAndAuthorityCallbacks() = existingViewModel("main")
    @Test fun sameExistingPaneViewModelFreshBBodyAndComposerSurviveLateACallbacks() = existingViewModel("pane")
    @Test fun sameExistingChangesViewModelFreshBListSurvivesLateACallbacks() = existingViewModel("changes")
    @Test fun sameExistingSpaceViewModelFreshBWorkspaceSurvivesLateACallbacks() = existingViewModel("space")
    @Test fun sameExistingIdentityViewModelFreshBIdentitySurvivesLateACallbacks() = existingViewModel("identity")
    @Test fun sameExistingCacheWatchViewModelFreshBPaneAndRuleCustodySurviveLateACallbacks() = existingViewModel("cache")

    private fun existingViewModel(kind: String) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                Fixture().use { f ->
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val failure = AtomicReference<Throwable?>()
                    val hold = f.repository.onReadContextRetired { event ->
                        if (event.reason == AuthorityInvalidation.CONNECTION_CHANGED && event.owner == f.actorA.custodyOwner) {
                            try {
                                assertTrue(event.affects(f.actorA))
                                assertFalse(Thread.holdsLock(f.repository))
                                entered.countDown()
                                assertTrue(release.await(2, TimeUnit.SECONDS))
                            } catch (error: Throwable) { failure.set(error); entered.countDown() }
                        }
                    }
                    val address = PaneAddress(Scope(), "pane")
                    val main = if (kind == "main") MainViewModel(f.app, f.repository) else null
                    val pane = if (kind == "pane") PaneViewModel(f.app, address, "shell", f.repository) else null
                    val changes = if (kind == "changes") ChangesViewModel(f.app, f.repository, ChangesTarget.Workspace("workspace"), Scope()) else null
                    val space = if (kind == "space") SpaceViewModel(f.app, "workspace", Scope(), f.repository) else null
                    val identity = if (kind == "identity") AgentIdentityViewModel(f.app, address, f.repository) else null
                    val cache = if (kind == "cache") CacheWatchViewModel(f.app, androidx.lifecycle.SavedStateHandle(mapOf(CacheWatchViewModel.KEY_PANE_ID to "pane")), f.repository) else null
                    fun refresh() { main?.refresh(); pane?.refresh(); changes?.refresh(); space?.refresh(); identity?.refresh(); cache?.refresh(); dispatcher.scheduler.runCurrent() }
                    fun body(): String = when (kind) {
                        "main" -> main!!.state.value.snapshot!!.workspaces.single().label
                        "pane" -> pane!!.state.value.pane!!.text
                        "changes" -> changes!!.state.value.list!!.workspaceLabel!!
                        "space" -> space!!.state.value.content!!.workspace.label
                        "identity" -> identity!!.state.value.identity!!.agent!!
                        else -> cache!!.state.value.pane!!.cwd
                    }
                    try {
                        refresh()
                        assertEquals("A-private", body())
                        f.store.save(f.b)
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        refresh()
                        assertEquals("B-private", body())
                        // These read-only screens do not request snapshot authority themselves.
                        // Deliberately authorize this fixture's current B before the no-extra-read
                        // baseline; pairing or an identity/list response alone grants no writes.
                        if (kind == "identity" || kind == "changes") {
                            assertTrue(f.repository.snapshot() is ApiResult.Success)
                        }
                        val b = f.repository.readContext()!!
                        assertTrue(f.repository.isCurrent(b))
                        val reads = f.reads.get()
                        f.store.save(f.b)
                        assertTrue(f.repository.isCurrent(b))
                        release.countDown()
                        f.finishPublication { dispatcher.scheduler.runCurrent() }
                        assertNull(failure.get())
                        assertEquals("B-private", body())
                        assertEquals(reads, f.reads.get())
                        assertTrue(f.repository.isCurrent(b))
                        assertTrue(f.repository.writesAllowed())
                        assertTrue(f.repository.isActorRetired(f.actorA))
                    } finally { release.countDown(); hold.close(); main?.stopPolling(); pane?.stopPolling(); space?.stopPolling(); identity?.screenStopped(); cache?.stop() }
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun sameActualIdentityActivityFreshBTextSurvivesOldAReadCallbackAndQueuedMainAck() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            Fixture().use { f ->
                val repositoryField = f.app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
                val previous = repositoryField.get(f.app.container)
                repositoryField.set(f.app.container, f.repository)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val hold = f.repository.onReadContextRetired { event ->
                    if (event.reason == AuthorityInvalidation.CONNECTION_CHANGED && event.owner == f.actorA.custodyOwner) {
                        try { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
                        catch (error: Throwable) { failure.set(error) }
                    }
                }
                val controller = org.robolectric.Robolectric.buildActivity(AgentIdentityActivity::class.java,
                    AgentIdentityActivity.intent(f.app, PaneAddress(Scope(), "pane"))).setup()
                val activity = controller.get()
                try {
                    dispatcher.scheduler.runCurrent()
                    assertTrue(texts(activity.window.decorView).contains("A-private"))
                    f.store.save(f.b)
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val vm = field(activity, "viewModel") as AgentIdentityViewModel
                    vm.refresh()
                    dispatcher.scheduler.runCurrent()
                    assertTrue(texts(activity.window.decorView).contains("B-private"))
                    assertFalse(texts(activity.window.decorView).contains("A-private"))
                    val reads = f.reads.get()
                    release.countDown()
                    f.finishPublication { dispatcher.scheduler.runCurrent() }
                    assertNull(failure.get())
                    assertTrue(texts(activity.window.decorView).contains("B-private"))
                    assertFalse(texts(activity.window.decorView).contains("A-private"))
                    assertEquals(reads, f.reads.get())
                } finally { release.countDown(); hold.close(); controller.pause().stop().destroy(); repositoryField.set(f.app.container, previous) }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun actualAppContainerCallbackRetiresOnlyOriginalPersistedAndVolatileDraftOwner() {
        Fixture().use { f ->
            val preferences = f.app.container.nativePreferences
            val old = f.actorA.custodyOwner
            val captured = AtomicReference<AuthorityRetirement>()
            val listener = f.repository.onAuthorityRetired { event -> if (event.owner == old) captured.compareAndSet(null, event) }
            val keyA = "a-${UUID.randomUUID()}"
            val keyB = "b-${UUID.randomUUID()}"
            val keyLargeA = "$keyA-large"
            val keyLargeB = "$keyB-large"
            try {
                preferences.savePaneDraft(keyA, "A-private", old)
                preferences.savePaneDraft(keyLargeA, "A".repeat(NativePreferences.MAX_PERSISTED_DRAFT_CHARS + 1), old)
                f.store.save(f.b)
                val actorB = f.repository.readContext()!!
                f.finishPublication { }
                val ownerB = actorB.custodyOwner
                preferences.savePaneDraft(keyB, "B-private", ownerB)
                val largeB = "B".repeat(NativePreferences.MAX_PERSISTED_DRAFT_CHARS + 1)
                preferences.savePaneDraft(keyLargeB, largeB, ownerB)
                // This is the ACTUAL AppContainer listener captured from its initialized repository,
                // exercised with the immutable event published by a real encrypted store transition.
                @Suppress("UNCHECKED_CAST")
                val actual = field(f.app.container.repository, "authorityInvalidations") as List<(AuthorityRetirement) -> Unit>
                assertTrue(actual.isNotEmpty())
                actual.first().invoke(captured.get())
                assertEquals("", NativePreferences(f.app).paneDraft(keyA))
                assertEquals("", NativePreferences(f.app).paneDraft(keyLargeA))
                assertEquals("B-private", NativePreferences(f.app).paneDraft(keyB))
                assertEquals(largeB, NativePreferences(f.app).paneDraft(keyLargeB))
                assertTrue(f.repository.isCurrent(actorB))
                assertEquals(0, f.reads.get())
            } finally {
                listener.close()
                listOf(keyA, keyB, keyLargeA, keyLargeB).forEach { preferences.savePaneDraft(it, "") }
            }
        }
    }

    @Test fun queuedMainCleanupRechecksCopiedBAtExecutionAndStillWipesRetainedAWithAckOutsideLocks() {
        Fixture().use { f ->
            val old = f.actorA
            val captured = AtomicReference<AuthorityRetirement>()
            val subscription = f.repository.onAuthorityRetired { event -> if (event.affects(old)) captured.compareAndSet(null, event) }
            try {
                f.store.save(f.b)
                val b = f.repository.readContext()!!
                f.finishPublication { }
                val event = captured.get()
                val copiedActor = AtomicReference(old)
                val text = AtomicReference("A-private")
                val started = CountDownLatch(1)
                val done = CountDownLatch(1)
                val error = AtomicReference<Throwable?>()
                Thread {
                    try {
                        started.countDown()
                        withRetirementOnMain(event, { copiedActor.get() }) {
                            assertFalse(Thread.holdsLock(f.repository))
                            text.set("")
                        }
                    } catch (failure: Throwable) { error.set(failure) }
                    finally { done.countDown() }
                }.start()
                assertTrue(started.await(2, TimeUnit.SECONDS))
                // Main has not executed the queued retirement. Replace the displayed COPY owner,
                // just as a successful fresh read does; the old event must compare again on Main.
                copiedActor.set(b); text.set("B-private")
                pump(done) { }
                assertNull(error.get())
                assertEquals("B-private", text.get())
                copiedActor.set(old); text.set("A-private")
                withRetirementOnMain(event, { copiedActor.get() }) { text.set("") }
                assertEquals("", text.get())
                assertEquals(0, f.reads.get())
                assertTrue(f.repository.isCurrent(b))
            } finally { subscription.close() }
        }
    }

    private class Fixture : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        private val preferences = app.getSharedPreferences("consumer-owned-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = CipherEnvelope(ByteArray(12), plaintext.map { (it.toInt() xor 0x6a).toByte() }.toByteArray())
            override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x6a).toByte() }.toByteArray()
        }
        val store = EncryptedConnectionStore(app, cipher, Json, app.container.originValidator, preferences)
        val a = Connection(CollieOrigin("https://owned-consumers.fixture/"), "A-private", "paired-a")
        val b = Connection(a.origin, "B-private", "paired-b")
        val reads = AtomicInteger()
        val api = object : CollieApi by app.container.api {
            override suspend fun refresh(connection: Connection, scope: Scope) = ApiResult.Success(ActionResponse(true), 200).also { reads.incrementAndGet() }
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))), 200).also { reads.incrementAndGet() }
            override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean) =
                ApiResult.Success(PaneReadResponse(address.paneId, connection.label, false, if (connection === a) 1 else 2), 200).also { reads.incrementAndGet() }
            override suspend fun chat(connection: Connection, address: PaneAddress, limit: Int, after: ChatAfter?, before: ChatBefore?, etag: String?) =
                ApiResult.Success(PaneChatResponse(address.paneId, available = false), 200).also { reads.incrementAndGet() }
            override suspend fun launchers(connection: Connection, scope: Scope) = ApiResult.Success(LaunchersResponse(emptyList(), "/listed/home"), 200).also { reads.incrementAndGet() }
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(SnapshotResponse(
                "connected", agents = listOf(PaneSummary("pane", "workspace", connection.label, 1, "tab", "shell", AgentStatus.IDLE, connection.label, true)), shellPanes = emptyList(),
                workspaces = listOf(WorkspaceSummary("workspace", 1, connection.label, true, "tab", 1, 0)), tabs = emptyList(),
                device = DeviceAuthorization(true, "fixture", true), ts = 1,
            ), 200).also { reads.incrementAndGet() }
            override suspend fun paneAgentIdentity(connection: Connection, address: PaneAddress) = ApiResult.Success(
                AgentIdentityResponse(true, address.paneId, connection.label, AgentStatus.IDLE, stateAuthority = AgentStateAuthority(false)), 200,
            ).also { reads.incrementAndGet() }
            override suspend fun cacheWatch(connection: Connection, address: PaneAddress) = ApiResult.Success(CacheWatchResponse(true, false, true, 60), 200).also { reads.incrementAndGet() }
            override suspend fun changes(connection: Connection, target: ChangesTarget, scope: Scope, lookup: ChangesLookup) = ApiResult.Success(
                ChangesListAnswer(target, connection.label, null, ChangesListState.Available("/listed/root", emptyList(), false, false, emptyList())), 200,
            ).also { reads.incrementAndGet() }
        }
        val repository: CollieRepository
        val actorA: ScopedReadContext
        init {
            store.save(a)
            repository = CollieRepository(api, store, app.container.originValidator)
            actorA = repository.readContext()!!
            val done = CountDownLatch(1)
            val subscription = store.observeCommitted { done.countDown() }
            try { assertTrue(done.await(2, TimeUnit.SECONDS)) } finally { subscription.close() }
        }
        fun finishPublication(mainTasks: () -> Unit) {
            val done = CountDownLatch(1)
            val subscription = store.observeCommitted { done.countDown() }
            try { pump(done, mainTasks) } finally { subscription.close() }
        }
        override fun close() { preferences.edit().clear().commit() }
    }
    companion object {
        private fun texts(view: android.view.View): String = buildString {
            if (view is android.widget.TextView) append(view.text)
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) append(texts(view.getChildAt(index)))
        }
        private fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).run { isAccessible = true; get(target) }
        private fun pump(done: CountDownLatch, mainTasks: () -> Unit) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (done.count != 0L && System.nanoTime() < deadline) {
                // Drive only the real queued Main ACK and deliberate fixture jobs, without sleeps
                // or read/admission retries. Production refusal is asserted before ACK completion.
                shadowOf(Looper.getMainLooper()).idle()
                mainTasks()
                Thread.yield()
            }
            assertEquals(0L, done.count)
        }
    }
}
