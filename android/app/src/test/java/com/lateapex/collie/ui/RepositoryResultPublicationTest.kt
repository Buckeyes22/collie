package com.lateapex.collie.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ActionResponse
import com.lateapex.collie.network.AgentIdentityResponse
import com.lateapex.collie.network.AgentStateAuthority
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.ControlAuthority
import com.lateapex.collie.network.DeviceAuthorization
import com.lateapex.collie.network.FailureFactor
import com.lateapex.collie.network.FailureSource
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.SnapshotResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual public repository calls, paused after initial result admission and before final commit. */
@RunWith(RobolectricTestRunner::class)
class RepositoryResultPublicationTest {
    private val app: CollieApplication = ApplicationProvider.getApplicationContext()
    private val address = PaneAddress(Scope(), "w1:p1")

    @Test
    fun completedSavedCopyAccessFreezeAndWideNavigationAckWinAgainstOldSnapshotPublication() {
        for (boundary in listOf("saved", "access", "wide")) {
            val original = app.container.repository
            val f = Fixture()
            install(f.repository)
            val controller = Robolectric.buildActivity(AgentIdentityActivity::class.java,
                AgentIdentityActivity.intent(app, address)).create().start().resume().visible()
            val admitted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val result = AtomicReference<ApiResult<SnapshotResponse>?>()
            val workerFailure = AtomicReference<Throwable?>()
            val worker = Thread {
                try { result.set(runBlocking { f.repository.snapshot() }) }
                catch (error: Throwable) { workerFailure.set(error) }
            }
            val acknowledgements = AtomicInteger()
            val registration = f.repository.onAuthorityInvalidated { acknowledgements.incrementAndGet() }
            f.repository.beforeSnapshotPublicationForTesting = {
                if (Thread.currentThread() === worker) {
                    admitted.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
            }
            try {
                val activity = controller.get()
                shadowOf(activity.mainLooper).idle()
                assertTrue(text(activity.window.decorView).contains("private-old"))
                assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
                assertTrue(f.repository.writesAllowed())
                worker.start()
                assertTrue(admitted.await(2, TimeUnit.SECONDS))
                val accessRefusal = ApiFailure.Http(401, "gateway expired", "identity.expired",
                    source = FailureSource.GATEWAY, factor = FailureFactor.IDENTITY)
                when (boundary) {
                    "saved" -> f.repository.showSavedCopy(42)
                    "access" -> {
                        f.failure = accessRefusal
                        assertTrue(runBlocking { f.repository.config() } is ApiResult.Failure)
                    }
                    "wide" -> f.repository.selectScope(Scope(viewAll = true))
                }
                assertEquals("retirement and the actual Activity wipe ACK completed", 1, acknowledgements.get())
                assertFalse(text(activity.window.decorView).contains("private-old"))
                assertFalse(f.repository.writesAllowed())
                if (boundary == "saved") assertTrue(f.repository.readSource.value is ReadSourceState.SavedCopy)
                if (boundary == "access") assertTrue(f.repository.accessFailure === accessRefusal)
                release.countDown()
                worker.join(2_000)
                assertFalse(worker.isAlive)
                assertNull(workerFailure.get())
                assertTrue("an old successful response is discarded", result.get() is ApiResult.Failure)
                assertFalse(f.repository.readSource.value is ReadSourceState.Live)
                assertFalse(f.repository.writesAllowed())
                if (boundary == "saved") assertTrue(f.repository.readSource.value is ReadSourceState.SavedCopy)
                if (boundary == "access") assertTrue("old success cannot clear the current refusal", f.repository.accessFailure === accessRefusal)
                assertTrue(runBlocking { f.repository.keys(address, listOf("Enter")) } is ApiResult.Failure)
                assertEquals("retired authority permits zero POSTs", 0, f.posts.get())
                f.failure = null
                f.repository.selectSubject(address.scope, address.paneId)
                assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
                assertTrue(f.repository.readSource.value is ReadSourceState.Live)
                assertNull(f.repository.accessFailure)
                assertTrue(f.repository.writesAllowed())
                assertTrue(runBlocking { f.repository.keys(address, listOf("Enter")) } is ApiResult.Success)
                assertEquals(1, f.posts.get())
            } finally {
                release.countDown()
                if (worker.state != Thread.State.NEW) worker.join(2_000)
                f.repository.beforeSnapshotPublicationForTesting = {}
                registration.close()
                controller.destroy()
                install(original)
            }
        }
    }

    @Test
    fun oldFailureAndOldControlAuthorityCannotMutateAReplacementWithAFreshSnapshot() {
        for (oldFailure in listOf<ApiFailure?>(pairingRefusal(), ApiFailure.Http(403, "gateway denied", null), ApiFailure.Protocol("old malformed body"), null)) {
            val f = Fixture()
            f.repository.selectSubject(address.scope, address.paneId)
            assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
            f.failure = oldFailure
            val admitted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val result = AtomicReference<ApiResult<BridgeConfigResponse>?>()
            val error = AtomicReference<Throwable?>()
            val worker = Thread {
                try { result.set(runBlocking { f.repository.config() }) }
                catch (failure: Throwable) { error.set(failure) }
            }
            f.repository.beforeConnectionResultPublicationForTesting = {
                if (Thread.currentThread() === worker) {
                    admitted.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
            }
            try {
                worker.start()
                assertTrue(admitted.await(2, TimeUnit.SECONDS))
                val replacement = Connection(f.old.origin, "phone", "replacement-fixture-token")
                f.store.save(replacement) // Deliberately outside the repository lock.
                f.failure = null
                f.authority = ControlAuthority("replacement-lead", "2")
                assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
                val current = f.repository.readContext()!!
                assertTrue(f.repository.writesAllowed())
                release.countDown()
                worker.join(2_000)
                assertFalse(worker.isAlive)
                assertNull(error.get())
                assertTrue(result.get() is ApiResult.Failure)
                assertTrue(f.store.connection.value === replacement)
                assertNull(f.repository.accessFailure)
                assertTrue(f.repository.isCurrent(current))
                assertTrue(f.repository.writesAllowed())
                assertEquals(0, f.posts.get())
                assertTrue("old header cannot overwrite the replacement lead observation", runBlocking { f.repository.config() } is ApiResult.Success)
                assertTrue(f.repository.isCurrent(current))
                val identity = runBlocking { f.repository.paneAgentIdentity(address, current) }
                assertTrue(identity is ApiResult.Success)
                assertEquals("current-replacement", (identity as ApiResult.Success).value.agent)
            } finally {
                release.countDown()
                if (worker.state != Thread.State.NEW) worker.join(2_000)
                f.repository.beforeConnectionResultPublicationForTesting = {}
            }
        }
    }

    @Test
    fun replacementAtTheStoreCasBoundaryIsNotOverwrittenByAnAdmittedOldRefusal() {
        val f = Fixture()
        f.repository.selectSubject(address.scope, address.paneId)
        assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
        val replacement = Connection(f.old.origin, "phone", "replacement-fixture-token")
        f.failure = pairingRefusal()
        f.store.beforeCompare = { f.store.save(replacement) }
        assertTrue(runBlocking { f.repository.config() } is ApiResult.Failure)
        assertTrue(f.store.connection.value === replacement)
        assertNull("uncommitted old refusal does not publish its error", f.repository.accessFailure)
        f.store.beforeCompare = null
        f.failure = null
        assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
        assertTrue(f.repository.writesAllowed())
        assertTrue(runBlocking { f.repository.paneAgentIdentity(address) } is ApiResult.Success)
        assertEquals(0, f.posts.get())
    }

    @Test
    fun replacementDuringActualOffMainWipeAckRetainsItsCredentialAndRecoversIdentity() {
        val original = app.container.repository
        val f = Fixture()
        install(f.repository)
        val controller = Robolectric.buildActivity(AgentIdentityActivity::class.java,
            AgentIdentityActivity.intent(app, address)).create().start().resume().visible()
        val afterAck = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable?>()
        val workerFailure = AtomicReference<Throwable?>()
        val result = AtomicReference<ApiResult<BridgeConfigResponse>?>()
        val replacement = Connection(f.old.origin, "phone", "replacement-fixture-token")
        val admissionLock = f.repository.javaClass.getDeclaredField("readAdmissionLock").apply { isAccessible = true }.get(f.repository)
        // Registered after the real ViewModel/Activity wipe, before transition completion.
        val registration = f.repository.onReadContextInvalidated { reason ->
            if (reason == AuthorityInvalidation.PAIRING_RETIRED) {
                try {
                    assertFalse(Thread.holdsLock(admissionLock))
                    assertNull("original A is already demoted before callbacks", f.store.connection.value?.token)
                    f.store.save(replacement)
                    assertNull("ACK transition still admits no replacement actor", f.repository.readContext())
                    afterAck.countDown()
                    assertTrue(releaseListener.await(2, TimeUnit.SECONDS))
                } catch (error: Throwable) { callbackFailure.set(error); afterAck.countDown() }
            }
        }
        val worker = Thread {
            try { result.set(runBlocking { f.repository.config() }) }
            catch (error: Throwable) { workerFailure.set(error) }
        }
        try {
            val activity = controller.get()
            shadowOf(activity.mainLooper).idle()
            assertTrue(text(activity.window.decorView).contains("private-old"))
            assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
            f.failure = pairingRefusal()
            worker.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (afterAck.count != 0L && System.nanoTime() < deadline) {
                shadowOf(activity.mainLooper).idle()
                Thread.yield()
            }
            assertTrue(afterAck.await(0, TimeUnit.SECONDS))
            assertNull(callbackFailure.get())
            assertFalse(text(activity.window.decorView).contains("private-old"))
            assertTrue(worker.isAlive)
            assertTrue(f.store.connection.value === replacement)
            assertFalse(f.repository.writesAllowed())
            assertNull(f.repository.readContext())
            releaseListener.countDown()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertNull(workerFailure.get())
            assertTrue(result.get() is ApiResult.Failure)
            assertTrue("no late old demotion runs after the real ACK", f.store.connection.value === replacement)
            f.failure = null
            assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
            assertTrue(f.repository.writesAllowed())
            button(activity.window.decorView)!!.performClick()
            shadowOf(activity.mainLooper).idle()
            assertTrue(text(activity.window.decorView).contains("current-replacement"))
            assertFalse(text(activity.window.decorView).contains("private-old"))
            assertEquals(0, f.posts.get())
        } finally {
            releaseListener.countDown()
            if (worker.state != Thread.State.NEW) {
                shadowOf(Looper.getMainLooper()).idle()
                worker.join(2_000)
            }
            registration.close()
            controller.destroy()
            install(original)
        }
    }

    @Test
    fun currentAuthoritativeRefusalDemotesButUnknownAndGatewayRefusalsKeepCredentials() {
        for (failure in listOf(pairingRefusal(), ApiFailure.Http(403, "device expired", "pairing.expired"),
            ApiFailure.Http(403, "gateway expired", "pairing.expired", source = FailureSource.GATEWAY, factor = FailureFactor.IDENTITY))) {
            val f = Fixture()
            assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
            f.failure = failure
            assertTrue(runBlocking { f.repository.config() } is ApiResult.Failure)
            assertFalse(f.repository.writesAllowed())
            if (failure.source == FailureSource.COLLIE) assertNull(f.store.connection.value?.token)
            else assertTrue(f.store.connection.value === f.old)
            assertEquals(0, f.posts.get())
        }
    }

    private fun pairingRefusal() = ApiFailure.Http(403, "fixture pairing expired", "pairing.expired",
        source = FailureSource.COLLIE, factor = FailureFactor.PAIRING)

    private inner class Fixture {
        val old = Connection(CollieOrigin("https://identity.fixture/"), "phone", "old-fixture-token")
        val store = Store(old)
        val posts = AtomicInteger()
        @Volatile var failure: ApiFailure? = null
        @Volatile var authority = ControlAuthority("original-lead", "1")
        val api = object : CollieApi by app.container.api {
            override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Success(
                SnapshotResponse("fixture", controlAuthority = authority,
                    device = DeviceAuthorization(true, "phone", true), agents = emptyList(), shellPanes = emptyList(),
                    workspaces = emptyList(), tabs = emptyList(), ts = 1), 200,
            )
            override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> =
                failure?.let { ApiResult.Failure(it) } ?: ApiResult.Success(
                    BridgeConfigResponse(mux = MuxConfigResponse(capabilities = mapOf("agentIdentity" to true))),
                    200, controlAuthority = authority,
                )
            override suspend fun paneAgentIdentity(connection: Connection, address: PaneAddress) = ApiResult.Success(
                AgentIdentityResponse(true, address.paneId, if (connection === old) "private-old" else "current-replacement",
                    AgentStatus.WORKING, null, AgentStateAuthority(false, null), null, null, null, null), 200,
            )
            override suspend fun keys(connection: Connection, address: PaneAddress, keys: List<String>, expectedPrompt: String?): ApiResult<ActionResponse> {
                posts.incrementAndGet()
                return ApiResult.Success(ActionResponse(true), 200)
            }
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
    }

    private class Store(initial: Connection) : ConnectionStore {
        private val mutable = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = mutable
        var beforeCompare: (() -> Unit)? = null
        override fun save(connection: Connection) { mutable.value = connection }
        override fun clear() { mutable.value = null }
        override fun compareAndSet(expected: Connection, replacement: Connection): Boolean {
            beforeCompare?.invoke()
            return mutable.compareAndSet(expected, replacement)
        }
    }

    private fun install(repository: CollieRepository) {
        app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
    }

    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { text(view.getChildAt(it)) }
        else -> ""
    }

    private fun button(view: View): Button? = when (view) {
        is Button -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { button(view.getChildAt(it)) }
        else -> null
    }
}
