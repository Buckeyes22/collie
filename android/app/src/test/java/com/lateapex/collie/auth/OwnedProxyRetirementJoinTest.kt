package com.lateapex.collie.auth

import android.content.Context
import android.content.SharedPreferences
import com.lateapex.collie.AppContainer
import com.lateapex.collie.data.*
import com.lateapex.collie.domain.*
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.*
import com.lateapex.collie.ui.ProxyCallbackHandoff
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The SAME actual container owns all calls/stores across committed A/B transitions. */
@RunWith(RobolectricTestRunner::class)
class OwnedProxyRetirementJoinTest {
    @Test fun actualContainerHeldOldACallbackCannotEraseFreshBTransactionNonceHandoffOrPinnedMutation() {
        Fixture().use { f ->
            val a = f.actor()
            f.ready(a)
            assertTrue(runBlocking { f.app.repository.snapshot(expectedContext = a) } is ApiResult.Success)
            // Force A's one-shot mutation to wait on its counted read-only nonce probe.
            f.app.proxyInterceptor.retire()
            val nonceEntered = CountDownLatch(1)
            val nonceRelease = CountDownLatch(1)
            f.holdHealth = nonceEntered to nonceRelease
            val oldResult = AtomicReference<ApiResult<ActionResponse>>()
            val oldDone = CountDownLatch(1)
            val oldThread = Thread {
                try { oldResult.set(runBlocking { f.app.repository.keys(PaneAddress(paneId = "pane"), listOf("Enter")) }) }
                finally { oldDone.countDown() }
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val captured = AtomicReference<AuthorityRetirement>()
            val hold = f.app.repository.onReadContextRetired { event ->
                if (event.affects(a)) {
                    captured.set(event)
                    try {
                        assertFalse(Thread.holdsLock(f.app.proxyIdentityStore))
                        assertFalse(Thread.holdsLock(f.app.connectionStore))
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    } catch (error: Throwable) { failure.set(error) }
                }
            }
            @Suppress("UNCHECKED_CAST")
            val callbacks = field(f.app.repository, "readContextInvalidations") as CopyOnWriteArrayList<(AuthorityRetirement) -> Unit>
            val last = callbacks.removeAt(callbacks.lastIndex)
            callbacks.add(0, last) // Hold BEFORE the actual preexisting container callback.
            val handoffA = ProxyCallbackHandoff.put("com.lateapex.collie:/oauth2redirect?state=old&code=old", a)
            try {
                oldThread.start()
                assertTrue(nonceEntered.await(5, TimeUnit.SECONDS))
                f.store.save(f.b)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val b = f.actor()
                assertTrue(f.app.repository.isActorRetired(a))
                assertTrue(f.app.repository.isCurrent(b))
                f.ready(b)
                assertTrue(runBlocking { f.app.repository.snapshot(expectedContext = b) } is ApiResult.Success)
                assertTrue(f.app.repository.writesAllowed())
                f.app.proxyCoordinator.begin(b)
                val record = f.app.proxyIdentityStore.record()!!
                val transaction = record.transaction!!
                val handoffB = ProxyCallbackHandoff.put("com.lateapex.collie:/oauth2redirect?state=fresh&code=fresh", b)
                val healthBefore = f.health.get()
                release.countDown()
                nonceRelease.countDown()
                assertTrue(oldDone.await(5, TimeUnit.SECONDS))
                f.awaitPublication()
                assertNull(failure.get())
                assertTrue(oldResult.get() is ApiResult.Failure)
                assertEquals(0, f.posts.get())
                val preserved = f.app.proxyIdentityStore.record()!!
                assertEquals(record.revision, preserved.revision)
                assertEquals(transaction.state, preserved.transaction!!.state)
                assertEquals(b.custodyVersion, preserved.custody)
                assertNull(ProxyCallbackHandoff.consume(handoffA))
                assertEquals(b, ProxyCallbackHandoff.consumeOwned(handoffB)?.actor)
                // The fresh nonce survives old retirement. The actual one-shot POST is sent once.
                assertTrue(runBlocking { f.app.repository.keys(PaneAddress(paneId = "pane"), listOf("Enter")) } is ApiResult.Success)
                assertEquals(1, f.posts.get())
                assertEquals(healthBefore, f.health.get())
                assertEquals(1, f.requests.count { it.method == "POST" })
                val post = f.requests.single { it.method == "POST" }
                assertNotNull(post.getHeader("DPoP"))
                assertEquals("Bearer paired-b", post.getHeader("Authorization"))
                assertTrue(post.getHeader("Collie-Proxy-Authorization")!!.startsWith("DPoP "))
                assertFalse(f.app.proxyIdentityStore.retireOwned(captured.get(), removeRecord = true))
                assertTrue(f.app.repository.isCurrent(b))
            } finally { release.countDown(); nonceRelease.countDown(); hold.close(); oldThread.join(5000) }
        }
    }

    @Test fun actualPreexistingCoordinatorCancelsHeldAIssuerCallWhileHeldBIssuerCallFinishesAndPublishes() {
        Fixture().use { f ->
            val a = f.actor()
            f.ready(a)
            val aEntered = CountDownLatch(1); val aRelease = CountDownLatch(1)
            val bEntered = CountDownLatch(1); val bRelease = CountDownLatch(1)
            val oldDone = CountDownLatch(1); val freshDone = CountDownLatch(1)
            val old = AtomicReference<Boolean>(); val fresh = AtomicReference<Boolean>()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val hold = f.app.repository.onReadContextRetired { event ->
                if (event.affects(a)) {
                    entered.countDown()
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)) } catch (error: Throwable) { failure.set(error) }
                }
            }
            @Suppress("UNCHECKED_CAST")
            val callbacks = field(f.app.repository, "readContextInvalidations") as CopyOnWriteArrayList<(AuthorityRetirement) -> Unit>
            callbacks.add(0, callbacks.removeAt(callbacks.lastIndex))
            val oldThread = Thread { try { old.set(runCatching { f.app.proxyCoordinator.begin(a) }.isSuccess) } finally { oldDone.countDown() } }
            var freshThread: Thread? = null
            try {
                f.holdDiscovery = aEntered to aRelease
                oldThread.start(); assertTrue(aEntered.await(5, TimeUnit.SECONDS))
                f.store.save(f.b); assertTrue(entered.await(5, TimeUnit.SECONDS))
                val b = f.actor(); f.ready(b)
                f.holdDiscovery = bEntered to bRelease
                freshThread = Thread { try { fresh.set(runCatching { f.app.proxyCoordinator.begin(b) }.isSuccess) } finally { freshDone.countDown() } }
                freshThread.start(); assertTrue(bEntered.await(5, TimeUnit.SECONDS))
                release.countDown(); f.awaitPublication()
                assertTrue(oldDone.await(5, TimeUnit.SECONDS)); assertFalse(old.get())
                assertEquals(1L, freshDone.count)
                assertEquals(1L, bRelease.count)
                bRelease.countDown(); assertTrue(freshDone.await(5, TimeUnit.SECONDS))
                assertTrue(fresh.get()); assertNull(failure.get())
                assertEquals(b.custodyVersion, f.app.proxyIdentityStore.record()!!.transaction!!.custody)
                assertTrue(f.app.repository.isCurrent(b))
                assertEquals(0, f.posts.get())
                assertTrue(f.requests.filter { it.path!!.contains("openid-configuration") || it.path == "/jwks" }
                    .all { it.getHeader("Authorization") == null && it.getHeader("Collie-Proxy-Authorization") == null && it.getHeader("Cookie") == null })
            } finally { release.countDown(); aRelease.countDown(); bRelease.countDown(); hold.close(); oldThread.join(5000); freshThread?.join(5000) }
        }
    }

    @Test fun sameOwnerFreshNavigationPromotedBeforeLateOldEventAndStaleCaptureCannotDowngradeIt() {
        Fixture().use { f ->
            val old = f.actor()
            f.ready(old)
            val event = AtomicReference<AuthorityRetirement>()
            val registration = f.app.repository.onAuthorityRetired { if (it.affects(old)) event.set(it) }
            try {
                f.app.repository.selectSubject(Scope(), "new-pane")
                val fresh = f.app.repository.readContext(Scope(), "new-pane")!!
                f.app.proxyCoordinator.credentials(fresh.connection, fresh)
                f.app.proxyCoordinator.begin(fresh)
                val record = f.app.proxyIdentityStore.record()!!
                assertFalse(event.get().affects(fresh))
                f.app.proxyCoordinator.retire(event.get())
                f.app.proxyInterceptor.retire(event.get())
                assertEquals(record.revision, f.app.proxyIdentityStore.record()!!.revision)
                assertNotNull(f.app.proxyIdentityStore.record()!!.transaction)
                try { f.app.proxyIdentityStore.captureOwned(old); fail("old navigation cannot demote owned record") }
                catch (_: IllegalStateException) { }
                assertEquals(record.revision, f.app.proxyIdentityStore.record()!!.revision)
                assertTrue(f.app.repository.isCurrent(fresh))
            } finally { registration.close() }
        }
    }

    @Test fun encryptedPriorProcessNavigation500ReopensAtZeroWithoutStaleKnownOrNullEventRetiringAdoptedRecord() {
        Fixture().use { f ->
            val original = f.actor()
            f.ready(original)
            val prior = f.app.proxyIdentityStore.record()!!
            val oldProcess = "controlled-prior-process"
            val oldStamp = original.custodyVersion.copy(connectionGeneration = 500, navigationGeneration = 500, processIdentity = oldProcess)
            f.app.proxyIdentityStore.write(prior.copyCustody(custody = oldStamp))
            val reopenedConnection = EncryptedConnectionStore(f.crypto.context, f.cipher, Json, f.app.originValidator)
            val freshRepository = CollieRepository(f.app.api, reopenedConnection, f.app.originValidator)
            val fresh = freshRepository.readContext()!!
            assertEquals(0L, fresh.key.connectionGeneration)
            assertEquals(0L, fresh.navigationGeneration)
            assertEquals(original.connectionIncarnation, fresh.connectionIncarnation)
            val reopened = ProxyIdentityStore(f.crypto.context, f.cipher, Json, f.proxyPreferences)
            val coordinator = ProxySignInCoordinator(reopened, f.crypto.key, { reopenedConnection.connection.value },
                { freshRepository.readContext() }, f.crypto.client, actorCurrent = freshRepository::isCurrent)
            assertNotNull(coordinator.credentials(fresh.connection, fresh))
            val adopted = reopened.record()!!
            assertEquals(fresh.custodyVersion, adopted.custody)
            assertEquals(prior.profile, adopted.profile)
            assertEquals(prior.tokens!!.accessToken, adopted.tokens!!.accessToken)
            val knownOld = AuthorityRetirement(AuthorityInvalidation.SCOPE_CHANGED, original.custodyOwner, true,
                original.connection, original.connectionIncarnation, 500, 500, processIdentity = oldProcess)
            val nullOld = AuthorityRetirement(AuthorityInvalidation.ACCESS_FROZEN, original.custodyOwner, true,
                original.connection, original.connectionIncarnation, 500, 500, processIdentity = null)
            assertFalse(reopened.retireOwned(knownOld, true))
            assertFalse(reopened.retireOwned(nullOld, true))
            assertEquals(adopted.revision, reopened.record()!!.revision)
            val witness = ProxyBindingWitness(f.crypto.issuer, "operator", f.crypto.key.thumbprint, "owned-binding-abcdefghijkl", 7)
            reopened.write(adopted.copyCustody(binding = witness))
            val permanent = AuthorityRetirement(AuthorityInvalidation.DISCONNECTED, original.custodyOwner, true,
                original.connection, original.connectionIncarnation, 500, 500, processIdentity = oldProcess)
            assertTrue(reopened.retireOwned(permanent, true))
            assertNull(reopened.record())
            assertEquals(listOf(ProxyBindingRetirement(witness.bindingId, witness.revision)), reopened.pendingRetirements())
            assertEquals(reopened.pendingRetirements(), ProxyIdentityStore(f.crypto.context, f.cipher, Json, f.proxyPreferences).pendingRetirements())
            assertTrue(freshRepository.isCurrent(fresh)) // Identity retirement grants no Collie admission.
        }
    }

    @Test fun sameActualProfileActivityFreshBEditorReviewAndBrowserTransactionSurviveLateAAndStillWipeOnStop() {
        Fixture().use { f ->
            val application = f.crypto.context as com.lateapex.collie.CollieApplication
            val originalContainer = application.container
            set(application, "container", f.app)
            val a = f.actor()
            f.ready(a)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failed = AtomicReference<Throwable?>()
            val hold = f.app.repository.onReadContextRetired { event ->
                if (event.affects(a)) {
                    entered.countDown()
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)) } catch (error: Throwable) { failed.set(error) }
                }
            }
            val controller = org.robolectric.Robolectric.buildActivity(com.lateapex.collie.ui.ProxySignInActivity::class.java).setup()
            try {
                val activity = controller.get()
                val editor = field(activity, "profile") as android.widget.EditText
                editor.setText("A-private-editor")
                f.store.save(f.b)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val b = f.actor()
                f.ready(b)
                f.app.proxyCoordinator.begin(b)
                val transaction = f.app.proxyIdentityStore.record()!!.transaction!!.state
                fun buttons(view: android.view.View): List<android.widget.Button> =
                    if (view is android.widget.Button) listOf(view)
                    else if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                    else emptyList()
                buttons(activity.window.decorView).single { it.text.toString() == activity.getString(com.lateapex.collie.R.string.proxy_identity_copy_key) }.performClick()
                assertEquals("", editor.text.toString())
                editor.setText(f.crypto.rawProfile)
                (field(activity, "import") as android.widget.Button).performClick()
                val dialog = field(activity, "review") as androidx.appcompat.app.AlertDialog
                assertTrue(dialog.isShowing)
                editor.setText("B-private-editor")
                val baseline = f.requests.size
                release.countDown()
                val done = CountDownLatch(1)
                val subscription = f.store.observeCommitted { done.countDown() }
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (done.count != 0L && System.nanoTime() < deadline) {
                        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield()
                    }
                    assertEquals(0L, done.count)
                } finally { subscription.close() }
                assertNull(failed.get())
                assertTrue(dialog.isShowing)
                assertEquals("B-private-editor", editor.text.toString())
                val presented = field(activity, "presentationActor") as ScopedReadContext
                assertSame(b.connection, presented.connection)
                assertEquals(b.connectionIncarnation, presented.connectionIncarnation)
                assertEquals(b.custodyOwner, presented.custodyOwner)
                assertEquals(b.custodyVersion, presented.custodyVersion)
                assertTrue(f.app.repository.isCurrent(presented))
                val reviewedActor = field(field(activity, "reviewed")!!, "actor") as ScopedReadContext
                assertSame(presented, reviewedActor)
                assertSame(b.connection, reviewedActor.connection)
                assertEquals(b.custodyVersion, reviewedActor.custodyVersion)
                assertTrue(f.app.repository.isCurrent(reviewedActor))
                assertEquals(transaction, f.app.proxyIdentityStore.record()!!.transaction!!.state)
                assertEquals(baseline, f.requests.size)
                controller.pause().stop()
                assertEquals("", editor.text.toString())
                assertFalse(dialog.isShowing)
                assertEquals(transaction, f.app.proxyIdentityStore.record()!!.transaction!!.state)
            } finally {
                release.countDown(); hold.close()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                controller.destroy(); set(application, "container", originalContainer)
            }
        }
    }

    @Test fun ownedRetirementFailedCommitRetainsDiskAndClosesOriginalLeaseWithoutRemovingRecreatedB() = failedPersistence(false)
    @Test fun ownedRetirementFailedEncryptionRetainsDiskAndClosesOriginalLeaseWithoutRemovingRecreatedB() = failedPersistence(true)

    private fun failedPersistence(encryption: Boolean) {
        Fixture().use { f ->
            val actor = f.actor()
            f.ready(actor)
            val (lease, record) = f.app.proxyIdentityStore.captureOwned(actor)
            val event = AtomicReference<AuthorityRetirement>()
            val registration = f.app.repository.onAuthorityRetired { if (it.affects(actor)) event.set(it) }
            f.app.repository.selectSubject(Scope(), "retired")
            registration.close()
            // Restore the captured original envelope for a genuine failed original-owner ACK.
            f.app.proxyIdentityStore.write(record!!)
            val raw = f.proxyPreferences.getString("record", null)
            val (failedLease, _) = f.app.proxyIdentityStore.capture()
            assertTrue(f.app.proxyIdentityStore.current(failedLease))
            if (encryption) f.cipher.fail = true else f.failCommit = true
            try { f.app.proxyIdentityStore.retireOwned(event.get(), removeRecord = !encryption); fail("persistence failure must propagate") }
            catch (_: IllegalStateException) { }
            assertEquals(raw, f.proxyPreferences.getString("record", null))
            assertFalse(f.app.proxyIdentityStore.current(lease))
            assertFalse(f.app.proxyIdentityStore.current(failedLease))
            try { f.app.proxyIdentityStore.record(); fail("unavailable custody must refuse") }
            catch (_: IllegalStateException) { }
            f.cipher.fail = false; f.failCommit = false
            val recreated = ProxyIdentityStore(f.crypto.context, f.cipher, Json, f.proxyPreferences)
            assertEquals(record.revision, recreated.record()!!.revision)
            val b = Connection(actor.connection.origin, "B", "paired-b")
            f.store.save(b)
            val fresh = f.actor()
            val coordinator = ProxySignInCoordinator(recreated, f.crypto.key, { f.store.connection.value }, { f.actor() }, f.client,
                actorCurrent = f.app.repository::isCurrent)
            coordinator.importProfile(f.crypto.rawProfile, fresh)
            val bRecord = recreated.record()!!
            assertFalse(recreated.retireOwned(event.get(), removeRecord = true))
            assertEquals(bRecord.revision, recreated.record()!!.revision)
            assertEquals(bRecord.revision, ProxyIdentityStore(f.crypto.context, f.cipher, Json, f.proxyPreferences).record()!!.revision)
        }
    }

    private class Cipher : SecretCipher {
        var fail = false
        override fun encrypt(plaintext: ByteArray): CipherEnvelope {
            check(!fail) { "controlled encryption failure" }
            return CipherEnvelope(byteArrayOf(1), plaintext.map { (it.toInt() xor 90).toByte() }.toByteArray())
        }
        override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 90).toByte() }.toByteArray()
    }
    private class Fixture : AutoCloseable {
        val crypto = ProxyHttpsFixture()
        val cipher = Cipher()
        private val exactAccess by lazy { crypto.access() }
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val posts = AtomicInteger()
        val health = AtomicInteger()
        @Volatile var holdHealth: Pair<CountDownLatch, CountDownLatch>? = null
        @Volatile var holdDiscovery: Pair<CountDownLatch, CountDownLatch>? = null
        var failCommit = false
        val proxyPreferences = crypto.context.getSharedPreferences("collie_proxy_identity_v1", Context.MODE_PRIVATE)
        val app: AppContainer
        val store: EncryptedConnectionStore
        val b: Connection
        val client: OkHttpClient
        init {
            crypto.server.enqueue(MockResponse().setBody("{}"))
            val certificate = crypto.client.newCall(Request.Builder().url(crypto.server.url("/fixture-certificate")).build()).execute().use {
                it.handshake!!.peerCertificates.first()
            }
            crypto.server.takeRequest(5, TimeUnit.SECONDS)
            crypto.context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
            app = AppContainer(crypto.context)
            store = app.connectionStore as EncryptedConnectionStore
            set(store, "cipher", cipher)
            set(app.proxyIdentityStore, "cipher", cipher)
            val guarded = object : SharedPreferences by proxyPreferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = proxyPreferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                        override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                        override fun commit() = if (failCommit) false else editor.commit()
                    }
                }
            }
            set(app.proxyIdentityStore, "prefs", guarded)
            set(app.proxyCoordinator, "key", crypto.key)
            client = crypto.client.newBuilder().certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(certificate)).build())
                .addInterceptor(app.proxyInterceptor).callTimeout(12, TimeUnit.SECONDS).build()
            set(app.api, "client", client)
            set(app.proxyCoordinator, "credentialClient", crypto.client)
            crypto.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    if (request.method == "POST") posts.incrementAndGet()
                    val path = request.requestUrl!!.encodedPath
                    if (path.endsWith("api/health")) {
                        health.incrementAndGet()
                        holdHealth?.let { hold -> holdHealth = null; hold.first.countDown(); assertTrue(hold.second.await(5, TimeUnit.SECONDS)) }
                        return MockResponse().setHeader("DPoP-Nonce", "fresh-fixture-nonce").setBody("{}")
                    }
                    val body = when {
                        path.endsWith("openid-configuration") -> {
                            holdDiscovery?.let { hold -> holdDiscovery = null; hold.first.countDown(); assertTrue(hold.second.await(5, TimeUnit.SECONDS)) }
                            crypto.discovery()
                        }
                        path == "/jwks" -> crypto.jwks()
                        path.endsWith("api/snapshot") -> """{"ts":1,"bridge":"connected","agents":[],"shellPanes":[],"workspaces":[],"tabs":[],"device":{"enforced":true,"device":"fixture","authorized":true}}"""
                        else -> """{"ok":true}"""
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            val a = Connection(crypto.connection.origin, "A", "paired-a")
            b = Connection(a.origin, "B", "paired-b")
            store.save(a); actor(); awaitPublication()
        }
        fun actor() = app.repository.readContext()!!
        fun ready(actor: ScopedReadContext) {
            app.proxyCoordinator.importProfile(crypto.rawProfile, actor)
            val record = app.proxyIdentityStore.record()!!
            app.proxyIdentityStore.write(record.copyCustody(tokens = ProxyTokens(exactAccess, "refresh", "operator", System.currentTimeMillis() + 120_000, crypto.key.thumbprint, "nonce")))
        }
        fun awaitPublication() {
            val done = CountDownLatch(1)
            val registration = store.observeCommitted { done.countDown() }
            try { assertTrue(done.await(5, TimeUnit.SECONDS)) } finally { registration.close() }
        }
        override fun close() { failCommit = false; cipher.fail = false; client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); crypto.close(); store.clear(); ProxyCallbackHandoff.retire() }
    }
    companion object {
        private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).run { isAccessible = true; get(owner) }
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
