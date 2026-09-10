package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.OriginValidator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    private class XorCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): CipherEnvelope =
            CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())

        override fun decrypt(envelope: CipherEnvelope): ByteArray =
            envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
