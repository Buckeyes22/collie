package com.lateapex.collie.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class CommittedConnectionStoreTest {
    @Test fun sameObjectABACannotReuseOriginalSnapshotCASButLiveRecommitKeepsItsDurableId() {
        for (replace in listOf(false, true)) Fixture().use { f ->
            f.store.save(f.a)
            val original = f.store.captureCommitted()
            f.store.save(f.a)
            val recommit = f.store.captureCommitted()
            assertEquals(original.incarnationId, recommit.incarnationId)
            assertEquals(original.revision + 1, recommit.revision)
            assertFalse(f.store.compareAndSet(original, null))
            if (replace) f.store.save(f.b) else f.store.clear()
            f.store.save(f.a)
            val aba = f.store.captureCommitted()
            assertSame(f.a, aba.connection)
            assertNotEquals(original.incarnationId, aba.incarnationId)
            assertFalse(f.store.compareAndSet(recommit, null))
            assertTrue(f.store.compareAndSet(aba, f.a))
            assertEquals(aba.incarnationId, f.store.captureCommitted().incarnationId)
            assertTrue(f.store.compareAndSet(f.store.captureCommitted(), null))
            assertNull(f.store.captureCommitted().connection)
            assertNull(f.store.captureCommitted().incarnationId)
        }
    }

    @Test fun durableIdRestoresWithoutDisclosureAndDistinctIdenticalFieldsAreNewIncarnations() {
        Fixture().use { f ->
            f.store.save(f.a)
            val original = f.store.captureCommitted()
            val raw = f.preferences.getString(EncryptedConnectionStore.RECORD, null)!!
            assertFalse(raw.contains(original.incarnationId!!))
            assertFalse(raw.contains(f.a.token!!))
            val restored = EncryptedConnectionStore(f.context, f.cipher, Json, f.validator, f.preferences).captureCommitted()
            assertEquals(original.incarnationId, restored.incarnationId)
            assertNotSame(original.connection, restored.connection)
            for (privateValue in listOf(original.incarnationId!!, f.a.token!!, f.a.origin.value, f.a.label))
                assertFalse(original.toString().contains(privateValue))
            f.store.save(Connection(f.a.origin, f.a.label, f.a.token))
            assertNotEquals(original.incarnationId, f.store.captureCommitted().incarnationId)
            assertFalse(f.store.compareAndSet(original, null))
        }
    }

    @Test fun failedEncryptionSaveAndClearPersistenceNeverAdvanceCommittedWitness() {
        Fixture().use { f ->
            f.store.save(f.a)
            val original = f.store.captureCommitted()
            val raw = f.preferences.getString(EncryptedConnectionStore.RECORD, null)
            f.cipher.fail = true
            try { f.store.save(f.b); fail("encryption failure must propagate") } catch (_: IllegalStateException) { }
            assertSame(original, f.store.captureCommitted())
            f.cipher.fail = false
            f.failCommit = true
            try { f.store.save(f.b); fail("save failure must propagate") } catch (_: IllegalStateException) { }
            try { f.store.clear(); fail("clear failure must propagate") } catch (_: IllegalStateException) { }
            assertSame(original, f.store.captureCommitted())
            assertEquals(raw, f.preferences.getString(EncryptedConnectionStore.RECORD, null))
            f.failCommit = false
            assertTrue(f.store.compareAndSet(original, f.b))
        }
    }

    @Test fun legacyRecordDoesNotMintReadAuthorityUntilDeliberateReconnect() {
        Fixture().use { f ->
            val plaintext = """{"origin":"https://incarnation.fixture/","label":"legacy","token":"legacy-fixture"}""".encodeToByteArray()
            val encrypted = f.cipher.encrypt(plaintext)
            val iv = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP)
            val bytes = Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)
            f.preferences.edit().putString(EncryptedConnectionStore.RECORD, """{"version":1,"iv":"$iv","ciphertext":"$bytes"}""").commit()
            val legacy = EncryptedConnectionStore(f.context, f.cipher, Json, f.validator, f.preferences)
            val snapshot = legacy.captureCommitted()
            assertNotNull(snapshot.connection)
            assertNull(snapshot.incarnationId)
            val repository = CollieRepository(emptyApi(), legacy, f.validator)
            assertNull(repository.readContext())
            assertFalse(repository.writesAllowed())
            val refused = runBlocking { repository.config() }
            assertTrue(refused is ApiResult.Failure && refused.error is ApiFailure.Protocol)
            assertTrue((refused as ApiResult.Failure).error.toString().contains("Reconnect"))
            legacy.save(snapshot.connection!!)
            assertNotNull(legacy.captureCommitted().incarnationId)
            assertNotNull(repository.readContext())
        }
    }

    @Test fun pausedPublicationStillNotifiesABAOutsideStoreLockAndHarmlessRecommitStaysCurrent() {
        Fixture().use { f ->
            f.store.save(f.a)
            val repo = CollieRepository(emptyApi(), f.store, f.validator)
            val actor = repo.readContext()!!
            val initial = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val registration = f.store.observeCommitted {
                try { assertFalse(Thread.holdsLock(field(f.store, "persistenceLock"))); initial.countDown() }
                catch (e: Throwable) { failure.set(e); initial.countDown() }
            }
            assertTrue(initial.await(2, TimeUnit.SECONDS))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val retired = CountDownLatch(1)
            val observer = repo.onFilesActorRetired(actor) { retired.countDown() }
            try {
                f.store.beforePublicationForTesting = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
                f.store.clear()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.store.save(f.a)
                assertTrue(repo.isActorRetired(actor))
                assertFalse(repo.isCurrent(actor))
                assertFalse(f.store.compareAndSet(actor.committedSnapshot!!, f.b))
                f.store.beforePublicationForTesting = {}
                release.countDown()
                assertTrue(retired.await(2, TimeUnit.SECONDS))
                assertNull(failure.get())
                val fresh = repo.readContext()!!
                f.store.save(f.a)
                assertTrue(repo.isCurrent(fresh))
                assertFalse(repo.isActorRetired(fresh))
            } finally { release.countDown(); f.store.beforePublicationForTesting = {}; registration.close(); observer.close() }
        }
    }

    @Test fun sameConnectionIncarnationRecommitKeepsOriginalActorAndRuleCustodyCurrent() {
        Fixture().use { f ->
            f.store.save(f.a)
            val repository = CollieRepository(emptyApi(), f.store, f.validator)
            val original = repository.readContext()!!
            val prefs = f.context.getSharedPreferences("rule-harmless-${System.nanoTime()}", Context.MODE_PRIVATE)
            val attempts = EncryptedMachineRuleAttemptStore(f.context, f.cipher, Json, prefs)
            val attempt = MachineRuleAttempt(
                attemptId = java.util.UUID.randomUUID().toString().replace("-", ""),
                ownerDigest = original.custodyOwner.storageIdentity,
                connectionIncarnation = original.connectionIncarnation!!,
                machineId = "peer-east", machineIncarnation = "a".repeat(64), rulesRevision = "4",
            )
            assertEquals(MachineRuleAttemptReservation.Reserved, attempts.reserve(attempt) { commit ->
                repository.acknowledgeLocalCustody(original, commit)
            })
            assertTrue(attempts.finishLive(attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId))
            assertTrue(attempts.markUnknown(attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId))

            val before = original.committedSnapshot!!
            f.store.save(f.a)
            val after = f.store.captureCommitted()
            assertSame(f.a, after.connection)
            assertEquals(before.incarnationId, after.incarnationId)
            assertEquals(before.revision + 1, after.revision)
            assertTrue("A harmless same-incarnation save keeps the original scoped actor current", repository.isCurrent(original))
            assertTrue("Original rule custody can be explicitly settled after same-owner recommit", attempts.acknowledgeReviewed(
                attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId,
            ) { commit -> repository.acknowledgeLocalCustody(original, commit) })
            assertTrue(attempts.unresolved(attempt.ownerDigest, attempt.connectionIncarnation)!!.isEmpty())
        }
    }

    @Test fun realEncryptedStoreHeldPublicationAbaMintsNewIncarnationAndFencesOldRuleActor() {
        Fixture().use { f ->
            f.store.save(f.a)
            val repository = CollieRepository(emptyApi(), f.store, f.validator)
            val original = repository.readContext()!!
            val prefs = f.context.getSharedPreferences("rule-aba-${System.nanoTime()}", Context.MODE_PRIVATE)
            val attempts = EncryptedMachineRuleAttemptStore(f.context, f.cipher, Json, prefs)
            val attempt = MachineRuleAttempt(
                attemptId = java.util.UUID.randomUUID().toString().replace("-", ""),
                ownerDigest = original.custodyOwner.storageIdentity,
                connectionIncarnation = original.connectionIncarnation!!,
                machineId = "peer-east", machineIncarnation = "b".repeat(64), rulesRevision = "7",
            )
            assertEquals(MachineRuleAttemptReservation.Reserved, attempts.reserve(attempt) { commit ->
                repository.acknowledgeLocalCustody(original, commit)
            })
            assertTrue(attempts.finishLive(attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId))
            assertTrue(attempts.markUnknown(attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId))

            val baselinePublished = CountDownLatch(1)
            val baselineObserver = f.store.observeCommitted {
                if (it.revision >= original.committedSnapshot!!.revision) baselinePublished.countDown()
            }
            assertTrue("Initial durable A witness has completed publication", baselinePublished.await(2, TimeUnit.SECONDS))
            baselineObserver.close()

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holdOnce = AtomicBoolean(true)
            val publisherFailure = AtomicReference<Throwable?>()
            val publisherFinished = CountDownLatch(1)
            f.store.beforePublicationForTesting = {
                if (holdOnce.compareAndSet(true, false)) {
                    try {
                        entered.countDown()
                        check(release.await(2, TimeUnit.SECONDS)) { "Held ABA publication was not released" }
                    } catch (failure: Throwable) {
                        publisherFailure.set(failure)
                    } finally {
                        publisherFinished.countDown()
                    }
                }
            }
            try {
                repository.beforeReadContextCaptureForTesting = {
                    f.store.clear()
                    assertTrue("Clear publication is held after durable commit", entered.await(2, TimeUnit.SECONDS))
                    f.store.save(f.a)
                }
                assertNull("Capture hook cannot mint an actor across a real A→clear→same-A transition", repository.readContext())
                repository.beforeReadContextCaptureForTesting = {}
                val returned = f.store.captureCommitted()
                assertSame(f.a, returned.connection)
                assertNotEquals("Production clear and re-add mints a new durable incarnation", original.connectionIncarnation, returned.incarnationId)

                assertFalse("Original actor is retired from the durable witness before observers run", repository.isCurrent(original))
                assertFalse("Old original-owner journal deletion is fenced before publication", attempts.acknowledgeReviewed(
                    attempt.ownerDigest, attempt.connectionIncarnation, attempt.attemptId,
                ) { commit -> repository.acknowledgeLocalCustody(original, commit) })
                val fresh = repository.readContext()
                assertNotNull("Fresh direct capture can adopt the newly committed incarnation while publication is held", fresh)
                assertNotEquals(original.connectionIncarnation, fresh!!.connectionIncarnation)
                assertTrue(repository.isCurrent(fresh))
                assertEquals(listOf(attempt.copy(state = MachineRuleAttemptState.unknown)),
                    attempts.unresolved(attempt.ownerDigest, attempt.connectionIncarnation, attempt.machineId))
            } finally {
                repository.beforeReadContextCaptureForTesting = {}
                release.countDown()
                f.store.beforePublicationForTesting = {}
            }
            assertTrue("Held publisher callback completed after controlled release", publisherFinished.await(2, TimeUnit.SECONDS))
            assertNull("A daemon publisher failure must fail this test", publisherFailure.get())
        }
    }

    @Test fun writesAllowedRejectsCommittedSameObjectABAImmediatelyWithoutAReadOrPublication() {
        Fixture().use { f ->
            f.store.save(f.a)
            val api = object : CollieApi by emptyApi() {
                override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> = ApiResult.Success(
                    SnapshotResponse("connected", agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(),
                        device = DeviceAuthorization(true, "fixture", true), ts = 1), 200)
            }
            val repository = CollieRepository(api, f.store, f.validator)
            repository.selectSubject(Scope(), "pane")
            assertTrue(runBlocking { repository.snapshot() } is ApiResult.Success)
            val original = repository.readContext()!!
            assertTrue(repository.writesAllowed(Scope(), "pane"))
            f.store.save(f.a)
            assertTrue(repository.writesAllowed(Scope(), "pane"))
            val initial = CountDownLatch(1)
            val observation = f.store.observeCommitted { initial.countDown() }
            assertTrue(initial.await(2, TimeUnit.SECONDS))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                f.store.beforePublicationForTesting = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
                f.store.clear()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.store.save(f.a)
                assertSame(f.a, f.store.connection.value)
                assertFalse(repository.writesAllowed(Scope(), "pane"))
                assertFalse(repository.isCurrent(original))
                assertTrue(repository.isActorRetired(original))
            } finally { release.countDown(); f.store.beforePublicationForTesting = {}; observation.close() }
        }
    }

    @Test fun heldAuthoritativeRefusalCannotDemoteAReappearingAsANewIncarnation() {
        Fixture().use { f ->
            f.store.save(f.a)
            val api = object : CollieApi by emptyApi() {
                override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> = ApiResult.Failure(
                    ApiFailure.Http(401, "fixture revoked", "device.expired", source = FailureSource.COLLIE, factor = FailureFactor.PAIRING))
            }
            val repository = CollieRepository(api, f.store, f.validator)
            val actor = repository.readContext()!!
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val result = AtomicReference<ApiResult<BridgeConfigResponse>?>()
            val failure = AtomicReference<Throwable?>()
            repository.beforeConnectionResultPublicationForTesting = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
            try {
                Thread { try { result.set(runBlocking { repository.config(Scope(), actor) }) } catch (e: Throwable) { failure.set(e) } finally { finished.countDown() } }.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.store.clear(); f.store.save(f.a)
                val replacement = f.store.captureCommitted()
                assertNotEquals(actor.connectionIncarnation, replacement.incarnationId)
                release.countDown()
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertTrue(result.get() is ApiResult.Failure)
                assertSame(replacement, f.store.captureCommitted())
                assertSame(f.a, f.store.connection.value)
                assertTrue(f.a.isPaired)
                assertNull(repository.credentialRetirementFailure)
                assertFalse(f.store.compareAndSet(actor.committedSnapshot!!, null))
            } finally { release.countDown(); repository.beforeConnectionResultPublicationForTesting = {} }
        }
    }

    @Test fun realHttpsFinalInterceptorRefusesOriginalActorAfterHeldABAAndFreshReadSendsOnce() {
        Fixture().use { f ->
            val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val hold = AtomicBoolean(true)
            val result = AtomicReference<ApiResult<BridgeConfigResponse>?>()
            val failure = AtomicReference<Throwable?>()
            val server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory()); start() }
            val client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                .protocols(listOf(Protocol.HTTP_1_1)).retryOnConnectionFailure(false).addInterceptor { chain ->
                    if (hold.compareAndSet(true, false)) { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
                    chain.proceed(chain.request())
                }.build()
            try {
                val original = Connection(com.lateapex.collie.domain.CollieOrigin(server.url("/mount/").toString()), "tls", "tls-fixture")
                f.store.save(original)
                val repository = CollieRepository(CollieApiClient(client, Json), f.store, f.validator)
                val actor = repository.readContext()!!
                Thread { try { result.set(runBlocking { repository.config(Scope(), actor) }) } catch (e: Throwable) { failure.set(e) } finally { finished.countDown() } }.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.store.clear(); f.store.save(original)
                assertTrue(repository.isActorRetired(actor))
                release.countDown()
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertTrue(result.get() is ApiResult.Failure)
                assertEquals(0, server.requestCount)
                server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body("{}").build())
                assertTrue(runBlocking { repository.config() } is ApiResult.Success)
                assertEquals(1, server.requestCount)
                assertEquals("/mount/api/config", server.takeRequest(2, TimeUnit.SECONDS)!!.target)
            } finally { release.countDown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.close() }
        }
    }

    private class Fixture : AutoCloseable {
        val context: Context = ApplicationProvider.getApplicationContext()
        val validator = OriginValidator()
        val a = Connection(validator.requireValid("https://incarnation.fixture/"), "owner-a", "fixture-a")
        val b = Connection(a.origin, "owner-b", "fixture-b")
        val preferences = context.getSharedPreferences("incarnation-${System.nanoTime()}", Context.MODE_PRIVATE)
        var failCommit = false
        val cipher = TestCipher()
        private val guardedPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean = if (failCommit) false else editor.commit()
                }
            }
        }
        val store = EncryptedConnectionStore(context, cipher, Json, validator, guardedPreferences)
        override fun close() { store.beforePublicationForTesting = {}; preferences.edit().clear().commit() }
    }
    private class TestCipher : SecretCipher {
        var fail = false
        override fun encrypt(plaintext: ByteArray): CipherEnvelope {
            check(!fail) { "fixture encryption failure" }
            return CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
        }
        override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun emptyApi(): CollieApi = object : CollieApi by ApplicationProvider.getApplicationContext<com.lateapex.collie.CollieApplication>().container.api { }
    }
}
