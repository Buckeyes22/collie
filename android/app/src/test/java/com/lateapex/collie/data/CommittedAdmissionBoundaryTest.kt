package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class CommittedAdmissionBoundaryTest {
    @Test fun predecessorEntryAckCannotFenceFreshSuccessorOrEraseItsConditionalPaneCache() {
        StoreFixture().use { f ->
            f.store.save(f.a)
            val paneReads = AtomicInteger()
            val api = object : CollieApi by f.app.container.api {
                override suspend fun snapshot(connection: Connection, scope: Scope) = snapshotValue(true)
                override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean): ApiResult<PaneReadResponse> {
                    paneReads.incrementAndGet()
                    return if (etag == "fresh-b") ApiResult.NotModified(etag)
                    else ApiResult.Success(PaneReadResponse(address.paneId, "fresh-b-content", false, 1), 200, "fresh-b")
                }
            }
            val repository = CollieRepository(api, f.store, f.app.container.originValidator)
            repository.selectSubject(Scope(), "pane-a")
            val a = repository.readContext()!!
            f.awaitPublication()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbackFailure = AtomicReference<Throwable?>()
            val oldRegistration = repository.onReadContextInvalidated { reason ->
                if (reason == AuthorityInvalidation.CONNECTION_CHANGED) {
                    try {
                        assertTrue(repository.isActorRetired(a))
                        assertFalse(Thread.holdsLock(field(repository, "readAdmissionLock")))
                        assertFalse(Thread.holdsLock(field(f.store, "persistenceLock")))
                        entered.countDown()
                        assertTrue(release.await(2, TimeUnit.SECONDS))
                    } catch (error: Throwable) { callbackFailure.set(error); entered.countDown() }
                }
            }
            var freshRegistration: AutoCloseable? = null
            try {
                f.store.save(f.b)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                repository.selectSubject(Scope(), "pane-b")
                val b = repository.readContext()!!
                assertSame(f.b, b.connection)
                assertTrue(repository.isCurrent(b))
                assertFalse(repository.isActorRetired(b))
                assertTrue(runBlocking { repository.snapshot(expectedContext = b) } is ApiResult.Success)
                assertTrue(repository.writesAllowed(Scope(), "pane-b"))
                val address = PaneAddress(Scope(), "pane-b")
                assertTrue(runBlocking { repository.readPane(address) } is ApiResult.Success)
                val obsoleteEvents = AtomicInteger()
                freshRegistration = repository.onReadContextInvalidated { obsoleteEvents.incrementAndGet() }
                f.store.save(f.b)
                assertTrue(repository.isCurrent(b))
                release.countDown()
                f.awaitPublication()
                assertNull(callbackFailure.get())
                assertEquals(0, obsoleteEvents.get())
                assertTrue(repository.isCurrent(b))
                assertTrue(repository.writesAllowed(Scope(), "pane-b"))
                val cached = runBlocking { repository.readPane(address) }
                assertTrue(cached is ApiResult.Success && cached.status == 304)
                assertEquals("fresh-b-content", (cached as ApiResult.Success).value.pane.text)
                assertEquals(2, paneReads.get())
                assertTrue(repository.isActorRetired(a))
                assertFalse(f.store.compareAndSet(a.committedSnapshot!!, null))
            } finally { release.countDown(); oldRegistration.close(); freshRegistration?.close() }
        }
    }

    @Test fun genuineSameIncarnationPendingAckRefusesNewActorButPreservesHeldOriginal() {
        StoreFixture().use { f ->
            f.store.save(f.a)
            val configCalls = AtomicInteger()
            val api = object : CollieApi by f.app.container.api {
                override suspend fun snapshot(connection: Connection, scope: Scope) = snapshotValue(true)
                override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> {
                    configCalls.incrementAndGet()
                    return ApiResult.Failure(ApiFailure.Protocol("optional fixture read failed"))
                }
            }
            val repository = CollieRepository(api, f.store, f.app.container.originValidator)
            repository.selectSubject(Scope(), "pane")
            assertTrue(runBlocking { repository.snapshot() } is ApiResult.Success)
            val original = repository.readContext()!!
            f.awaitPublication()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val registration = repository.onAuthorityInvalidated { reason ->
                if (reason == AuthorityInvalidation.ACCESS_FROZEN) {
                    try {
                        assertFalse(Thread.holdsLock(field(repository, "readAdmissionLock")))
                        entered.countDown()
                        assertTrue(release.await(2, TimeUnit.SECONDS))
                    } catch (error: Throwable) { failure.set(error); entered.countDown() }
                }
            }
            try {
                Thread {
                    try { assertTrue(runBlocking { repository.config(expectedContext = original) } is ApiResult.Failure) }
                    catch (error: Throwable) { failure.set(error) }
                    finally { finished.countDown() }
                }.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertNull(repository.readContext())
                val pending = original
                assertSame(original.connection, pending.connection)
                assertEquals(original.key, pending.key)
                assertFalse(repository.isCurrent(original))
                assertFalse(repository.isCurrent(pending))
                assertFalse(repository.isActorRetired(original))
                assertFalse(repository.isActorRetired(pending))
                assertFalse(repository.writesAllowed())
                val refused = runBlocking { repository.config(expectedContext = pending) }
                assertTrue(refused is ApiResult.Failure)
                assertFalse((refused as ApiResult.Failure).error.toString().contains("No Collie connection"))
                assertEquals(1, configCalls.get())
                release.countDown()
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertTrue(repository.isCurrent(original))
                assertFalse(repository.isActorRetired(original))
                assertTrue(runBlocking { repository.snapshot(expectedContext = original) } is ApiResult.Success)
                f.store.save(f.a)
                assertTrue(repository.isCurrent(original))
                assertTrue(repository.writesAllowed())
            } finally { release.countDown(); registration.close() }
        }
    }

    @Test fun actualPinnedHttpsHeldMutationRechecksOriginalWriteAdmissionAfterSameActorDowngrade() {
        HttpsFixture("/mount/api/pane/pane/rename").use { f ->
            assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
            val actor = f.repository.readContext()!!
            assertTrue(f.repository.writesAllowed(Scope(), "pane"))
            val result = AtomicReference<ApiResult<ActionResponse>?>()
            val failure = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            Thread {
                try { result.set(runBlocking { f.repository.renamePane(f.address, "original-one-shot") }) }
                catch (error: Throwable) { failure.set(error) }
                finally { finished.countDown() }
            }.start()
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            f.authorized.set(false)
            assertTrue(runBlocking { f.repository.snapshot(expectedContext = actor) } is ApiResult.Success)
            assertTrue(f.repository.isCurrent(actor))
            assertFalse(f.repository.isActorRetired(actor))
            assertFalse(f.repository.writesAllowed(Scope(), "pane"))
            f.release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue(result.get() is ApiResult.Failure)
            assertEquals(0, f.posts.size)
            f.authorized.set(true)
            assertTrue(runBlocking { f.repository.snapshot(expectedContext = actor) } is ApiResult.Success)
            assertTrue(runBlocking { f.repository.renamePane(f.address, "fresh-one-shot") } is ApiResult.Success)
            assertEquals(1, f.posts.size)
            assertEquals("/mount/api/pane/pane/rename", f.posts.single().path)
            assertEquals("{\"label\":\"fresh-one-shot\"}", f.posts.single().body.readUtf8())
            assertEquals("Bearer pinned-fixture", f.posts.single().getHeader("Authorization"))
            assertEquals(f.connection.origin.headerValue, f.posts.single().getHeader("Origin"))
        }
    }

    @Test fun actualPinnedHttpsHeldDeviceOwnedPreferenceKeepsPairedReadAdmissionAfterDowngrade() {
        HttpsFixture("/mount/api/notifications/prefs").use { f ->
            assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
            val actor = f.repository.readContext()!!
            val result = AtomicReference<ApiResult<NotifyPreferences>?>()
            val failure = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            Thread {
                try { result.set(runBlocking { f.repository.setNotificationPreferences(NotifyPreferencesPatch(blocked = false)) }) }
                catch (error: Throwable) { failure.set(error) }
                finally { finished.countDown() }
            }.start()
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            f.authorized.set(false)
            assertTrue(runBlocking { f.repository.snapshot(expectedContext = actor) } is ApiResult.Success)
            assertTrue(f.repository.isCurrent(actor))
            assertFalse(f.repository.writesAllowed())
            f.release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue(result.get() is ApiResult.Success)
            assertEquals(1, f.posts.size)
            assertEquals("/mount/api/notifications/prefs", f.posts.single().path)
            assertEquals("Bearer pinned-fixture", f.posts.single().getHeader("Authorization"))
            assertEquals("{\"blocked\":false}", f.posts.single().body.readUtf8())
        }
    }

    @Test fun actualPinnedHttpsPendingSameIncarnationAckBlocksHeldSendWithoutRetiringActor() {
        HttpsFixture("/mount/api/pane/pane/rename").use { f ->
            assertTrue(runBlocking { f.repository.snapshot() } is ApiResult.Success)
            val actor = f.repository.readContext()!!
            val enteredAck = CountDownLatch(1)
            val releaseAck = CountDownLatch(1)
            val ackFinished = CountDownLatch(1)
            val mutationFinished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val result = AtomicReference<ApiResult<ActionResponse>?>()
            val registration = f.repository.onAuthorityInvalidated { reason ->
                if (reason == AuthorityInvalidation.ACCESS_FROZEN) {
                    try {
                        enteredAck.countDown()
                        assertTrue(releaseAck.await(2, TimeUnit.SECONDS))
                    } catch (error: Throwable) { failure.set(error); enteredAck.countDown() }
                }
            }
            try {
                Thread {
                    try { result.set(runBlocking { f.repository.renamePane(f.address, "held-one-shot") }) }
                    catch (error: Throwable) { failure.set(error) }
                    finally { mutationFinished.countDown() }
                }.start()
                assertTrue(f.entered.await(2, TimeUnit.SECONDS))
                Thread {
                    try { assertTrue(runBlocking { f.repository.config(expectedContext = actor) } is ApiResult.Failure) }
                    catch (error: Throwable) { failure.set(error) }
                    finally { ackFinished.countDown() }
                }.start()
                assertTrue(enteredAck.await(2, TimeUnit.SECONDS))
                assertNull(f.repository.readContext())
                val pending = actor
                assertEquals(actor.key, pending.key)
                assertFalse(f.repository.isCurrent(pending))
                assertFalse(f.repository.isActorRetired(actor))
                assertFalse(f.repository.isActorRetired(pending))
                f.release.countDown()
                assertTrue(mutationFinished.await(2, TimeUnit.SECONDS))
                assertTrue(result.get() is ApiResult.Failure)
                assertEquals(0, f.posts.size)
                releaseAck.countDown()
                assertTrue(ackFinished.await(2, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertTrue(f.repository.isCurrent(actor))
                assertTrue(runBlocking { f.repository.snapshot(expectedContext = actor) } is ApiResult.Success)
                assertTrue(runBlocking { f.repository.renamePane(f.address, "fresh-after-ack") } is ApiResult.Success)
                assertEquals(1, f.posts.size)
            } finally { f.release.countDown(); releaseAck.countDown(); registration.close() }
        }
    }

    private class StoreFixture : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val preferences = app.getSharedPreferences("admission-${System.nanoTime()}", Context.MODE_PRIVATE)
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = CipherEnvelope(byteArrayOf(9), plaintext.map { (it.toInt() xor 0x61).toByte() }.toByteArray())
            override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x61).toByte() }.toByteArray()
        }
        val store = EncryptedConnectionStore(app, cipher, Json, app.container.originValidator, preferences)
        val a = Connection(CollieOrigin("https://admission.fixture/"), "a", "fixture-a")
        val b = Connection(a.origin, "b", "fixture-b")
        fun awaitPublication() {
            val delivered = CountDownLatch(1)
            val observer = store.observeCommitted { delivered.countDown() }
            try { assertTrue(delivered.await(2, TimeUnit.SECONDS)) } finally { observer.close() }
        }
        override fun close() { preferences.edit().clear().commit() }
    }

    private class HttpsFixture(heldPath: String) : AutoCloseable {
        private val ownedStore = StoreFixture()
        val authorized = AtomicBoolean(true)
        val posts = CopyOnWriteArrayList<RecordedRequest>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val hold = AtomicBoolean(true)
        private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") posts.add(request)
                    val body = when (request.requestUrl!!.encodedPath) {
                        "/mount/api/snapshot" -> """{"bridge":"connected","agents":[],"shellPanes":[],"workspaces":[],"tabs":[],"device":{"enforced":true,"device":"fixture","authorized":${authorized.get()}},"ts":1}"""
                        "/mount/api/notifications/prefs" -> """{"blocked":false,"done":false,"updates":true}"""
                        "/mount/api/pane/pane/rename" -> """{"ok":true}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        val connection = Connection(CollieOrigin(server.url("/mount/").toString()), "tls", "pinned-fixture")
        private val client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(certificate.certificate)).build())
            .protocols(listOf(Protocol.HTTP_1_1)).retryOnConnectionFailure(false).addInterceptor { chain ->
                if (chain.request().method == "POST" && chain.request().url.encodedPath == heldPath && hold.compareAndSet(true, false)) {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
                chain.proceed(chain.request())
            }.build()
        val repository: CollieRepository
        val address = PaneAddress(Scope(), "pane")
        init {
            ownedStore.store.save(connection)
            repository = CollieRepository(CollieApiClient(client, Json), ownedStore.store, ownedStore.app.container.originValidator)
            repository.selectSubject(Scope(), "pane")
        }
        override fun close() {
            release.countDown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
            ownedStore.close()
        }
    }

    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun snapshotValue(authorized: Boolean) = ApiResult.Success(
            SnapshotResponse("connected", agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(),
                device = DeviceAuthorization(true, "fixture", authorized), ts = 1), 200)
    }
}
