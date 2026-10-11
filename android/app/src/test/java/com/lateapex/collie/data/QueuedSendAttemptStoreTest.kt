package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueuedSendAttemptStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val cipher = XorCipher()

    @Before
    fun clear() {
        context.getSharedPreferences("queued_send_attempts_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun uncertainAttemptSurvivesStoreRecreationWithoutPlaintextActionData() {
        val first = EncryptedQueuedSendAttemptStore(context, cipher, json)
        val pane = "1".repeat(64)
        val action = "2".repeat(64)
        assertTrue(first.observe(pane, action))
        assertTrue(first.recordBeforeDispatch(pane, action))

        val raw = context.getSharedPreferences("queued_send_attempts_v1", Context.MODE_PRIVATE)
            .getString("encrypted_attempts", "").orEmpty()
        assertFalse(raw.contains("queued text"))
        assertFalse(raw.contains("collie.example"))

        val restored = EncryptedQueuedSendAttemptStore(context, cipher, json)
        assertTrue(restored.wasAttempted(action))
        assertFalse(restored.recordBeforeDispatch(pane, action))
    }

    @Test
    fun changedAuthoritativeActionRetiresOnlyTheOldPaneLatch() {
        val store = EncryptedQueuedSendAttemptStore(context, cipher, json)
        val pane = "1".repeat(64)
        val old = "2".repeat(64)
        val next = "3".repeat(64)
        val otherPaneAction = "4".repeat(64)
        assertTrue(store.observe(pane, old))
        assertTrue(store.recordBeforeDispatch(pane, old))
        assertTrue(store.observe("5".repeat(64), otherPaneAction))
        assertTrue(store.recordBeforeDispatch("5".repeat(64), otherPaneAction))

        assertTrue(store.observe(pane, next))
        assertFalse(store.wasAttempted(old))
        assertTrue(store.wasAttempted(otherPaneAction))
        assertTrue(store.recordBeforeDispatch(pane, next))
    }

    private class XorCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(byteArrayOf(11), plaintext.map { (it.toInt() xor 0x63).toByte() }.toByteArray())

        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x63).toByte() }.toByteArray()
    }
}
