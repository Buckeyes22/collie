package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.OriginValidator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class EncryptedConnectionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val validator = OriginValidator()
    private val cipher = XorCipher()

    @Before
    fun reset() {
        context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun savesAndRestoresEncryptedConnectionWithoutPlaintext() {
        val origin = validator.requireValid("https://collie.example")
        val store = EncryptedConnectionStore(context, cipher, json, validator)
        store.save(Connection(origin, "S25 Ultra", "secret-bearer"))

        val raw = context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
            .getString(EncryptedConnectionStore.RECORD, null).orEmpty()
        assertFalse(raw.contains("secret-bearer"))
        assertFalse(raw.contains("collie.example"))

        val restored = EncryptedConnectionStore(context, cipher, json, validator).connection.value!!
        assertEquals("https://collie.example/", restored.origin.value)
        assertEquals("S25 Ultra", restored.label)
        assertEquals("secret-bearer", restored.token)
        assertFalse(restored.toString().contains("secret-bearer"))
    }

    @Test
    fun corruptEnvelopeIsDeletedAndTreatedAsUnpaired() {
        val prefs = context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString(EncryptedConnectionStore.RECORD, "not-json").commit()

        val store = EncryptedConnectionStore(context, cipher, json, validator)

        assertNull(store.connection.value)
        assertFalse(prefs.contains(EncryptedConnectionStore.RECORD))
    }

    @Test
    fun conditionalDemotionPersistsOnlyTheExactCurrentIncarnation() {
        val origin = validator.requireValid("https://collie.example")
        val store = EncryptedConnectionStore(context, cipher, json, validator)
        val old = Connection(origin, "phone", "old-fixture-token")
        val replacement = Connection(origin, "phone", "new-fixture-token")
        store.save(old)
        store.save(replacement)
        assertFalse(store.compareAndSet(old, Connection(origin, "phone", null)))
        assertTrue(store.connection.value === replacement)
        assertEquals("new-fixture-token", EncryptedConnectionStore(context, cipher, json, validator).connection.value?.token)
        assertTrue(store.compareAndSet(replacement, Connection(origin, "phone", null)))
        val restored = EncryptedConnectionStore(context, cipher, json, validator).connection.value!!
        assertEquals(origin, restored.origin)
        assertEquals("phone", restored.label)
        assertNull(restored.token)
    }

    @Test
    fun externalSaveAndConditionalDemotionShareTheDurableSerializationBoundary() {
        val encrypting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saveFailure = AtomicReference<Throwable?>()
        val casFailure = AtomicReference<Throwable?>()
        val casResult = AtomicReference<Boolean?>()
        val casStarted = CountDownLatch(1)
        val casReturned = CountDownLatch(1)
        lateinit var saving: Thread
        val boundedCipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): CipherEnvelope {
                if (Thread.currentThread() === saving) {
                    encrypting.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
                return cipher.encrypt(plaintext)
            }
            override fun decrypt(envelope: CipherEnvelope) = cipher.decrypt(envelope)
        }
        val origin = validator.requireValid("https://collie.example")
        val old = Connection(origin, "phone", "old-fixture-token")
        val replacement = Connection(origin, "phone", "new-fixture-token")
        // The initial write must not consult an uninitialized thread reference.
        saving = Thread()
        val store = EncryptedConnectionStore(context, boundedCipher, json, validator)
        store.save(old)
        saving = Thread {
            try { store.save(replacement) } catch (error: Throwable) { saveFailure.set(error) }
        }
        val demoting = Thread {
            casStarted.countDown()
            try { casResult.set(store.compareAndSet(old, Connection(origin, "phone", null))) }
            catch (error: Throwable) { casFailure.set(error) }
            finally { casReturned.countDown() }
        }
        try {
            saving.start()
            assertTrue(encrypting.await(2, TimeUnit.SECONDS))
            demoting.start()
            assertTrue(casStarted.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (demoting.state != Thread.State.BLOCKED && casReturned.count != 0L && System.nanoTime() < deadline) Thread.yield()
            assertEquals("the conditional mutation waits for the in-progress external save", Thread.State.BLOCKED, demoting.state)
            assertEquals(1L, casReturned.count)
            release.countDown()
            saving.join(2_000)
            demoting.join(2_000)
            assertFalse(saving.isAlive)
            assertFalse(demoting.isAlive)
            assertNull(saveFailure.get())
            assertNull(casFailure.get())
            assertEquals(false, casResult.get())
            assertTrue(store.connection.value === replacement)
            assertEquals("new-fixture-token", EncryptedConnectionStore(context, cipher, json, validator).connection.value?.token)
        } finally {
            release.countDown()
            saving.join(2_000)
            if (demoting.state != Thread.State.NEW) demoting.join(2_000)
        }
    }

    private class XorCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())

        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
