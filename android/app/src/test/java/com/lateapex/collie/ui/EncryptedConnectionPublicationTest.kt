package com.lateapex.collie.ui

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.data.SecretCipher
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class EncryptedConnectionPublicationTest {
    private val app = ApplicationProvider.getApplicationContext<CollieApplication>()

    @Test
    fun actualMainImmediateConsumerAndConcurrentOriginalRefusalFinishWithoutStoreAdmissionInversion() {
        val mainThread = Thread.currentThread()
        val finalAdmission = CountDownLatch(1)
        val enterCas = CountDownLatch(1)
        val releaseCipher = CountDownLatch(1)
        val coordinatorFailure = AtomicReference<Throwable?>()
        val workerFailure = AtomicReference<Throwable?>()
        val result = AtomicReference<ApiResult<BridgeConfigResponse>?>()
        var blockMainCipher = false
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): CipherEnvelope {
                if (blockMainCipher && Thread.currentThread() === mainThread) {
                    enterCas.countDown()
                    assertTrue(releaseCipher.await(2, TimeUnit.SECONDS))
                }
                return encodeFixture(plaintext)
            }
            override fun decrypt(envelope: CipherEnvelope) = decryptBytes(envelope)
        }
        val store = freshStore(cipher)
        val old = Connection(CollieOrigin("https://identity.fixture/"), "old-owner", "old-fixture-token")
        val replacement = Connection(old.origin, "replacement-owner", "replacement-fixture-token")
        store.save(old)
        val api = object : CollieApi by app.container.api {
            override suspend fun snapshot(connection: Connection, scope: Scope) = snapshotValue()
            override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> =
                if (connection === old) ApiResult.Failure(ApiFailure.Http(403, "fixture pairing expired", "pairing.expired",
                    source = FailureSource.COLLIE, factor = FailureFactor.PAIRING))
                else ApiResult.Success(BridgeConfigResponse(), 200)
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        val viewModels = ViewModelStore()
        val main = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app, repository) as T
        })[MainViewModel::class.java]
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val collected = mutableListOf<Connection?>()
        val callbackFailure = AtomicReference<Throwable?>()
        val storeLock = store.javaClass.getDeclaredField("persistenceLock").apply { isAccessible = true }.get(store)
        val admissionLock = repository.javaClass.getDeclaredField("readAdmissionLock").apply { isAccessible = true }.get(repository)
        collectorScope.launch {
            store.connection.collect { current ->
                try {
                    assertFalse(Thread.holdsLock(storeLock))
                    assertFalse(Thread.holdsLock(admissionLock))
                    repository.writesAllowed() // The same lock acquisition as actual MainViewModel.
                    collected.add(current)
                } catch (error: Throwable) { callbackFailure.set(error) }
            }
        }
        val worker = Thread {
            try { result.set(runBlocking { repository.config() }) }
            catch (error: Throwable) { workerFailure.set(error) }
        }
        repository.beforeConnectionResultPublicationForTesting = {
            if (Thread.currentThread() === worker) {
                finalAdmission.countDown()
                assertTrue(enterCas.await(2, TimeUnit.SECONDS))
            }
        }
        val coordinator = Thread {
            try {
                assertTrue(enterCas.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                var observed = worker.state
                while (observed != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.yield()
                    observed = worker.state
                }
                assertEquals("off-main old refusal has entered the actual storage CAS", Thread.State.BLOCKED, observed)
            } catch (error: Throwable) { coordinatorFailure.set(error) }
            finally { releaseCipher.countDown() }
        }
        try {
            idleUntil { collected.any { it === old } }
            assertTrue(runBlocking { repository.snapshot() } is ApiResult.Success)
            worker.start()
            assertTrue(finalAdmission.await(2, TimeUnit.SECONDS))
            coordinator.start()
            blockMainCipher = true
            store.save(replacement) // Actual main save holds storage while old refusal waits for CAS.
            blockMainCipher = false
            assertTrue("value projects the durable replacement before notification drainage", store.connection.value === replacement)
            worker.join(2_000)
            coordinator.join(2_000)
            assertFalse(worker.isAlive)
            assertFalse(coordinator.isAlive)
            assertNull(workerFailure.get())
            assertNull(coordinatorFailure.get())
            assertTrue(result.get() is ApiResult.Failure)
            idleUntil { main.state.value.label == "replacement-owner" && collected.any { it === replacement } }
            assertNull(callbackFailure.get())
            assertTrue(main.state.value.paired)
            assertFalse(main.state.value.writeAuthorized)
            assertTrue(store.connection.value === replacement)
            assertNull(repository.accessFailure)
            assertTrue(runBlocking { repository.snapshot() } is ApiResult.Success)
            assertTrue(repository.writesAllowed())
            assertEquals(replacement.token, EncryptedConnectionStore(app, cipher, Json, app.container.originValidator).connection.value?.token)
        } finally {
            enterCas.countDown()
            releaseCipher.countDown()
            if (worker.state != Thread.State.NEW) worker.join(2_000)
            if (coordinator.state != Thread.State.NEW) coordinator.join(2_000)
            repository.beforeConnectionResultPublicationForTesting = {}
            collectorScope.cancel()
            viewModels.clear()
        }
    }

    @Test
    fun queuedSaveClearAndCasNotificationsCannotRepublishAnObsoleteDurableOwner() {
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = encodeFixture(plaintext)
            override fun decrypt(envelope: CipherEnvelope) = decryptBytes(envelope)
        }
        val store = freshStore(cipher)
        val origin = CollieOrigin("https://identity.fixture/")
        val old = Connection(origin, "phone", "old-fixture-token")
        val second = Connection(origin, "phone", "second-fixture-token")
        val third = Connection(origin, "phone", "third-fixture-token")
        val readOnly = Connection(origin, "phone", null)
        val collecting = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val emitted = Collections.synchronizedList(mutableListOf<Connection?>())
        collecting.launch { store.connection.collect { emitted.add(it) } }
        val queued = CountDownLatch(1)
        val release = CountDownLatch(1)
        val publicationFailure = AtomicReference<Throwable?>()
        store.beforePublicationForTesting = {
            queued.countDown()
            try { assertTrue(release.await(2, TimeUnit.SECONDS)) }
            catch (error: Throwable) { publicationFailure.set(error) }
        }
        try {
            store.save(old)
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            store.save(second)
            assertTrue(store.connection.value === second)
            store.clear()
            assertNull(store.connection.value)
            store.save(third)
            assertTrue(store.connection.value === third)
            assertFalse(store.compareAndSet(second, readOnly))
            assertTrue(store.compareAndSet(third, readOnly))
            assertTrue(store.connection.value === readOnly)
            assertTrue(store.connection.replayCache.single() === readOnly)
            assertNull(EncryptedConnectionStore(app, cipher, Json, app.container.originValidator).connection.value?.token)
            release.countDown()
            idleUntil { synchronized(emitted) { emitted.any { it === readOnly } } }
            assertNull(publicationFailure.get())
            assertFalse("coalesced older queued notifications must not emit old owners", synchronized(emitted) { emitted.any { it === old || it === second || it === third } })
            assertTrue(store.connection.value === readOnly)
        } finally {
            release.countDown()
            store.beforePublicationForTesting = {}
            collecting.cancel()
        }
    }

    private fun freshStore(cipher: SecretCipher): EncryptedConnectionStore {
        app.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        return EncryptedConnectionStore(app, cipher, Json, app.container.originValidator)
    }
    private fun snapshotValue() = ApiResult.Success(SnapshotResponse("fixture", device = DeviceAuthorization(true, "phone", true),
        agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
    private fun encodeFixture(bytes: ByteArray) = CipherEnvelope(byteArrayOf(7), bytes.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
    private fun decryptBytes(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    private fun idleUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        assertTrue("bounded current notification/consumer completion", predicate())
    }
}
