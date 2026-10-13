package com.lateapex.collie.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Protocol
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class OwnedRetirementBoundaryTest {
    @Test fun actualEncryptedHeldDisconnectAckRetiresOnlyAAndPreservesBUnknownBarrierAndPinnedZeroPost() {
        Fixture().use { f ->
            val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            val server = MockWebServer()
            server.useHttps(serverCertificates.sslSocketFactory())
            server.protocols = listOf(Protocol.HTTP_1_1)
            val posts = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") posts.incrementAndGet()
                    return MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"ts":1,"bridge":"connected","agents":[],"shellPanes":[],"workspaces":[],"tabs":[],"device":{"enforced":true,"device":"fixture","authorized":true}}""").build()
                }
            }
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(certificate.certificate)).build())
                .protocols(listOf(Protocol.HTTP_1_1)).retryOnConnectionFailure(false).build()
            val origin = CollieOrigin(server.url("/mounted/").toString())
            val a = Connection(origin, "A", "paired-a")
            val b = Connection(origin, "B", "paired-b")
            f.store.save(a)
            val repository = CollieRepository(CollieApiClient(client, f.json), f.store, f.app.container.originValidator, launchIntentStore = f.journal)
            val actorA = repository.readContext()!!
            f.awaitPublication()
            val intentA = intent(actorA, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "a-private")
            assertTrue(f.journal.reserve(intentA) is ReserveIntentResult.Reserved)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val failed = AtomicReference<Throwable?>()
            val hold = repository.onReadContextRetired { event ->
                if (event.reason == AuthorityInvalidation.DISCONNECTED) {
                    try {
                        assertTrue(event.affects(actorA))
                        assertFalse(Thread.holdsLock(repository))
                        assertFalse(Thread.holdsLock(field(repository, "readAdmissionLock")))
                        assertFalse(Thread.holdsLock(f.journal))
                        entered.countDown()
                        assertTrue(release.await(2, TimeUnit.SECONDS))
                    } catch (error: Throwable) { failed.set(error); entered.countDown() }
                }
            }
            try {
                Thread {
                    try { runBlocking { repository.disconnect() } }
                    catch (error: Throwable) { failed.set(error) }
                    finally { finished.countDown() }
                }.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.store.save(b)
                val actorB = repository.readContext()!!
                assertSame(b, actorB.connection)
                assertTrue(repository.isCurrent(actorB))
                assertTrue(runBlocking { repository.snapshot(expectedContext = actorB) } is ApiResult.Success)
                assertTrue(repository.writesAllowed())
                val intentB = intent(actorB, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "b-private")
                assertTrue(f.journal.reserve(intentB) is ReserveIntentResult.Reserved)
                assertNotNull(f.journal.transition(intentB.requestId, intentB.bodySha256, LaunchIntentState.unknown))
                release.countDown()
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                f.awaitPublication()
                assertNull(failed.get())
                assertNull(f.journal.get(intentA.requestId))
                assertEquals(intentB, f.journal.get(intentB.requestId)?.intent)
                assertEquals(LaunchIntentState.unknown, f.journal.get(intentB.requestId)?.state)
                assertEquals(intentB, f.recreateJournal().get(intentB.requestId)?.intent)
                assertEquals(LaunchIntentState.unknown, f.recreateJournal().get(intentB.requestId)?.state)
                val barrier = f.journal.reserve(intentB.copy(requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"))
                assertTrue(barrier is ReserveIntentResult.Unresolved)
                assertEquals(intentB.requestId, (barrier as ReserveIntentResult.Unresolved).record.intent.requestId)
                val result = runBlocking { repository.startLaunchIntent(Scope(), StartTarget(command = "another"), "dddddddd-dddd-4ddd-8ddd-dddddddddddd", null, false) }
                assertTrue(result is ApiResult.Failure)
                assertEquals(0, posts.get())
                assertTrue(repository.isCurrent(actorB))
                assertEquals(1, server.requestCount)
            } finally { release.countDown(); hold.close(); server.close(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test fun selectiveJournalEncryptionFailureRetainsExactDiskAndFailsClosedWithoutLosingBOnRecreation() = failedRetirement(false)
    @Test fun selectiveJournalCommitFailureRetainsExactDiskAndFailsClosedWithoutLosingBOnRecreation() = failedRetirement(true)

    private fun failedRetirement(commit: Boolean) {
        Fixture().use { f ->
            val a = ScopedCustodyOwner("https://owned.fixture/", "https://owned.fixture/", "owner-a")
            val b = a.copy(pairedDeviceIdentity = "owner-b")
            val ai = FrozenLaunchIntent("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", a.origin, a.mount, 1, a.pairedDeviceIdentity, 0, null, null, null, "launch.start", confirmedNoPrompts = false, canonicalBody = "{\"command\":\"a\"}")
            val bi = ai.copy(requestId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", pairedDeviceIdentity = b.pairedDeviceIdentity, canonicalBody = "{\"command\":\"b\"}", bodySha256 = LaunchIntentStore.sha256("{\"command\":\"b\"}"))
            assertTrue(f.journal.reserve(ai) is ReserveIntentResult.Reserved)
            assertTrue(f.journal.reserve(bi) is ReserveIntentResult.Reserved)
            assertNotNull(f.journal.transition(bi.requestId, bi.bodySha256, LaunchIntentState.unknown))
            val raw = f.journalPreferences.getString("encrypted_records", null)
            if (commit) f.failCommit = true else f.cipher.failEncrypt = true
            try { f.journal.retireOwned(a); fail("failed persistence must propagate") } catch (_: IllegalStateException) { }
            assertEquals(raw, f.journalPreferences.getString("encrypted_records", null))
            try { f.journal.all(); fail("unavailable store cannot admit") } catch (_: IllegalStateException) { }
            f.failCommit = false; f.cipher.failEncrypt = false
            val recreated = f.recreateJournal()
            assertEquals(ai, recreated.get(ai.requestId)?.intent)
            assertEquals(bi, recreated.get(bi.requestId)?.intent)
            assertEquals(LaunchIntentState.unknown, recreated.get(bi.requestId)?.state)
            assertEquals(1, recreated.retireOwned(a))
            assertNull(recreated.get(ai.requestId))
            assertEquals(bi, f.recreateJournal().get(bi.requestId)?.intent)
            assertTrue(recreated.reserve(bi.copy(requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc")) is ReserveIntentResult.Unresolved)
        }
    }

    @Test fun eventKeepsOriginalOwnerAcrossSameObjectAbaAndHarmlessCurrentRecommit() {
        Fixture().use { f ->
            val a = Connection(CollieOrigin("https://owned.fixture/"), "A", "paired")
            f.store.save(a)
            val repository = CollieRepository(f.app.container.api, f.store, f.app.container.originValidator)
            val old = repository.readContext()!!
            f.awaitPublication()
            val events = java.util.concurrent.CopyOnWriteArrayList<AuthorityRetirement>()
            val listener = repository.onAuthorityRetired(events::add)
            try {
                f.store.clear(); f.store.save(a)
                val fresh = repository.readContext()!!
                f.awaitPublication()
                val event = events.first { it.affects(old) }
                assertEquals(old.custodyOwner, event.owner)
                assertFalse(event.affects(fresh))
                assertFalse(event.owns(fresh))
                assertTrue(repository.isActorRetired(old))
                assertFalse(f.store.compareAndSet(old.committedSnapshot!!, null))
                f.store.save(a)
                assertTrue(repository.isCurrent(fresh))
                assertFalse(event.affects(fresh))
                assertEquals(fresh.connectionIncarnation, f.store.captureCommitted()?.incarnationId)
            } finally { listener.close() }
        }
    }

    private class Fixture : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        val cipher = Cipher()
        private val connectionPreferences = app.getSharedPreferences("owned-connection-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val journalPreferences = app.getSharedPreferences("owned-journal-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        var failCommit = false
        private val guardedPreferences = object : SharedPreferences by journalPreferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = journalPreferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean = if (failCommit) false else editor.commit()
                }
            }
        }
        val store = EncryptedConnectionStore(app, cipher, json, app.container.originValidator, preferencesOverride = connectionPreferences)
        val journal = recreateJournal()
        fun recreateJournal() = LaunchIntentStore(app, cipher, json, preferencesOverride = guardedPreferences)
        fun awaitPublication() {
            val done = CountDownLatch(1)
            val observation = store.observeCommitted { done.countDown() }
            try { assertTrue(done.await(2, TimeUnit.SECONDS)) } finally { observation.close() }
        }
        override fun close() { connectionPreferences.edit().clear().commit(); journalPreferences.edit().clear().commit() }
    }
    private class Cipher : SecretCipher {
        var failEncrypt = false
        override fun encrypt(cleartext: ByteArray): CipherEnvelope {
            check(!failEncrypt) { "fixture encryption refused" }
            return CipherEnvelope(ByteArray(12), cleartext.map { (it.toInt() xor 0x6a).toByte() }.toByteArray())
        }
        override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x6a).toByte() }.toByteArray()
    }
    private fun intent(actor: ScopedReadContext, request: String, content: String) = FrozenLaunchIntent(
        request, actor.key.origin, actor.key.origin, actor.key.connectionGeneration, actor.key.pairedDeviceIdentity,
        actor.key.leadEpoch, actor.key.host, actor.key.session, actor.key.subjectIdentity, "launch.start",
        confirmedNoPrompts = false, canonicalBody = "{\"command\":\"$content\"}",
    )
    private fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).run { isAccessible = true; get(target) }
}
