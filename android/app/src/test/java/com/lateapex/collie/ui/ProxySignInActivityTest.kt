package com.lateapex.collie.ui

import android.content.Intent
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.auth.*
import com.lateapex.collie.data.*
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import mockwebserver3.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ProxySignInActivityTest {
    private class Store(initial: Connection) : ConnectionStore {
        private val state = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = state
        override fun save(connection: Connection) { state.value = connection }
        override fun clear() { state.value = null }
    }
    private class Fixture : java.io.Closeable {
        val https = ProxyHttpsFixture()
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        private val repositoryField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        private val coordinatorField = app.container.javaClass.getDeclaredField("proxyCoordinator").apply { isAccessible = true }
        private val priorRepository = app.container.repository
        private val priorCoordinator = app.container.proxyCoordinator
        val store = Store(https.connection)
        val repository = CollieRepository(CollieApiClient(https.client, Json { ignoreUnknownKeys = true }), store, OriginValidator())
        val coordinator = ProxySignInCoordinator(https.store, https.key, { store.connection.value }, { repository.readContext() }, https.client,
            actorCurrent = { repository.isCurrent(it) })
        private val retirement: AutoCloseable
        init {
            https.store.clear(); coordinator.importProfile(https.rawProfile, repository.readContext()!!)
            retirement = repository.onReadContextInvalidated { coordinator.retire() }
            repositoryField.set(app.container, repository); coordinatorField.set(app.container, coordinator)
        }
        override fun close() { retirement.close(); repositoryField.set(app.container, priorRepository); coordinatorField.set(app.container, priorCoordinator); https.close() }
    }
    private inline fun <reified T> field(target: Any, name: String): T {
        val value = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        return value as T // Reified, checked test-only inspection of exact private custody.
    }
    private fun awaitMain(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check() && System.nanoTime() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Actual main-thread continuation must settle within the fixture deadline", check())
    }
    @Test fun stoppedActualReviewWipesCopiedJsonAndOldPositiveCannotImportThenCurrentReviewWorks() {
        Fixture().use { f ->
            val controller = Robolectric.buildActivity(ProxySignInActivity::class.java).setup()
            try {
                val activity = controller.get(); val editor = field<EditText>(activity, "profile")
                val import = field<Button>(activity, "import")
                editor.setText(f.https.rawProfile); assertTrue(import.performClick())
                val dialog = field<AlertDialog>(activity, "review"); val oldPositive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val copied = field<Any>(activity, "reviewed"); val characters = field<CharArray>(copied, "raw")
                assertTrue(dialog.isShowing); assertTrue(characters.any { it != '\u0000' })
                controller.pause().stop()
                val stoppedRevision = f.https.store.record()!!.revision
                assertFalse(dialog.isShowing); assertEquals("", editor.text.toString()); assertTrue(characters.all { it == '\u0000' })
                oldPositive.performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(stoppedRevision, f.https.store.record()!!.revision); assertNull(shadowOf(activity).nextStartedActivity)
                controller.start().resume(); editor.setText(f.https.rawProfile); import.performClick()
                field<AlertDialog>(activity, "review").getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertNotEquals(stoppedRevision, f.https.store.record()!!.revision); assertEquals(f.https.rawProfile, f.https.store.record()!!.profile)
            } finally { controller.pause().stop().destroy() }
        }
    }
    @Test fun offMainRetirementWaitsForActualPrivateViewAndModalWipeWithoutHoldingCustodyLock() {
        Fixture().use { f ->
            val controller = Robolectric.buildActivity(ProxySignInActivity::class.java).setup()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val activity = controller.get(); val editor = field<EditText>(activity, "profile")
                editor.setText(f.https.rawProfile); field<Button>(activity, "import").performClick()
                val dialog = field<AlertDialog>(activity, "review"); editor.setText("private-unreviewed-copy")
                val version = field<java.util.concurrent.atomic.AtomicLong>(activity, "actorVersion"); val before = version.get()
                val laterListener = java.util.concurrent.atomic.AtomicBoolean(false)
                f.repository.onReadContextInvalidated { laterListener.set(true) }
                val old = executor.submit { f.repository.selectSubject(Scope(host = "new-host"), "new-pane") }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (version.get() == before && System.nanoTime() < deadline) Thread.yield()
                assertTrue(version.get() > before); assertFalse(old.isDone); assertFalse(laterListener.get())
                assertEquals("private-unreviewed-copy", editor.text.toString())
                // actorVersion advances before the worker posts its main-thread wipe. Keep
                // draining within the original deadline until that actual ACK settles.
                while (!old.isDone && System.nanoTime() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield() }
                assertTrue("Actual Activity ACK must complete within the original deadline", old.isDone)
                old.get(5, TimeUnit.SECONDS)
                assertTrue(laterListener.get()); assertEquals("", editor.text.toString()); assertFalse(dialog.isShowing)
            } finally { shadowOf(android.os.Looper.getMainLooper()).idle(); executor.shutdownNow(); controller.pause().stop().destroy() }
        }
    }
    @Test fun oldPublicationReleasedAfterActualRepositoryAndActivityAckCannotReplaceCustody() {
        Fixture().use { f ->
            val controller = Robolectric.buildActivity(ProxySignInActivity::class.java).setup()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val original = f.repository.readContext()!!
                f.coordinator.beforePublicationForTesting = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                val publication = executor.submit<Boolean> { runCatching { f.coordinator.importProfile(f.https.rawProfile, original) }.isSuccess }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val retirement = executor.submit { f.repository.selectSubject(Scope(host = "replacement-host"), "replacement-pane") }
                val version = field<java.util.concurrent.atomic.AtomicLong>(controller.get(), "actorVersion")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (version.get() == 0L && System.nanoTime() < deadline) Thread.yield()
                assertTrue(version.get() > 0L); assertFalse(retirement.isDone)
                while (!retirement.isDone && System.nanoTime() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield() }
                assertTrue("Actual Activity ACK must complete while publication remains held", retirement.isDone)
                assertEquals(1L, release.count)
                assertFalse(publication.isDone)
                retirement.get(5, TimeUnit.SECONDS)
                val acknowledged = f.https.store.record()!!.revision
                release.countDown(); assertFalse(publication.get(5, TimeUnit.SECONDS))
                assertEquals(acknowledged, f.https.store.record()!!.revision)
                assertEquals("", field<EditText>(controller.get(), "profile").text.toString())
                f.coordinator.beforePublicationForTesting = {}
                f.coordinator.importProfile(f.https.rawProfile, f.repository.readContext()!!)
                assertNotEquals(acknowledged, f.https.store.record()!!.revision)
            } finally { release.countDown(); shadowOf(android.os.Looper.getMainLooper()).idle(); executor.shutdownNow(); controller.pause().stop().destroy() }
        }
    }
    @Test fun stoppedHeldDiscoveryCannotLaunchBrowserButFreshExternalBrowserStopAndCallbackStillComplete() {
        Fixture().use { f ->
            val controller = Robolectric.buildActivity(ProxySignInActivity::class.java).setup()
            var returned: org.robolectric.android.controller.ActivityController<ProxySignInActivity>? = null
            val release = CountDownLatch(1)
            try {
                f.https.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse { check(release.await(5, TimeUnit.SECONDS)); return MockResponse.Builder().body(f.https.discovery()).build() }
                }
                field<Button>(controller.get(), "signIn").performClick()
                assertNotNull(f.https.server.takeRequest(5, TimeUnit.SECONDS)); controller.pause().stop(); release.countDown()
                val calls = field<MutableSet<okhttp3.Call>>(f.coordinator, "credentialCalls")
                awaitMain { calls.isEmpty() }
                assertEquals(1, f.https.server.requestCount); assertNull(shadowOf(controller.get()).nextStartedActivity); assertNull(f.https.store.record()!!.transaction)
                f.https.server.dispatcher = QueueDispatcher()
                f.https.server.enqueue(MockResponse.Builder().body(f.https.discovery()).build()); f.https.server.enqueue(MockResponse.Builder().body(f.https.jwks()).build())
                controller.start().resume(); field<Button>(controller.get(), "signIn").performClick()
                assertNotNull(f.https.server.takeRequest(5, TimeUnit.SECONDS)); assertNotNull(f.https.server.takeRequest(5, TimeUnit.SECONDS))
                var browser: Intent? = null
                awaitMain { if (browser == null) browser = shadowOf(controller.get()).nextStartedActivity; browser != null }
                assertEquals(Intent.ACTION_VIEW, browser!!.action)
                val transaction = f.https.store.record()!!.transaction!!
                controller.pause().stop(); assertEquals(transaction.state, f.https.store.record()!!.transaction!!.state)
                val raw = ProxyProfile.REDIRECT + "?state=" + transaction.state + "&code=owned-code&iss=" + java.net.URLEncoder.encode(f.https.server.url("/").toString(), "UTF-8")
                f.https.server.enqueue(MockResponse.Builder().body(f.https.tokens(transaction.nonce)).build())
                returned = Robolectric.buildActivity(ProxySignInActivity::class.java, Intent().putExtra(ProxySignInActivity.EXTRA_CALLBACK_ID, ProxyCallbackHandoff.put(raw))).setup()
                assertNotNull(f.https.server.takeRequest(5, TimeUnit.SECONDS))
                awaitMain { f.https.store.record()?.tokens != null }
                assertNull(f.https.store.record()!!.transaction); assertEquals("pairing-secret", f.store.connection.value!!.token)
                assertNull(shadowOf(returned!!.get()).nextStartedActivity); assertFalse(f.coordinator.consumeCallback(raw))
            } finally { release.countDown(); returned?.pause()?.stop()?.destroy(); controller.destroy() }
        }
    }
}
