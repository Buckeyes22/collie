package com.lateapex.collie.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.OriginValidator
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface ConnectionStore {
    val connection: StateFlow<Connection?>
    fun save(connection: Connection)
    fun clear()
}

data class CipherEnvelope(val iv: ByteArray, val ciphertext: ByteArray)

interface SecretCipher {
    fun encrypt(plaintext: ByteArray): CipherEnvelope
    fun decrypt(envelope: CipherEnvelope): ByteArray
}

/** AES/GCM whose non-exportable key lives in Android Keystore. */
class AndroidKeystoreCipher(
    private val alias: String = KEY_ALIAS,
) : SecretCipher {
    override fun encrypt(plaintext: ByteArray): CipherEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(ASSOCIATED_DATA)
        return CipherEnvelope(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(envelope: CipherEnvelope): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, envelope.iv))
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(envelope.ciphertext)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "com.lateapex.collie.connection.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private val ASSOCIATED_DATA = "com.lateapex.collie.connection.v1".encodeToByteArray()
    }
}

/**
 * Persists one encrypted connection envelope. Origin, label, and token are encrypted together so a
 * filesystem copy reveals neither credentials nor private-network metadata.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
class EncryptedConnectionStore(
    context: Context,
    private val cipher: SecretCipher,
    private val json: Json,
    private val originValidator: OriginValidator,
) : ConnectionStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableConnection = MutableStateFlow(read())

    override val connection: StateFlow<Connection?> = mutableConnection.asStateFlow()

    override fun save(connection: Connection) {
        require(connection.label.isNotBlank() && connection.label.length <= MAX_LABEL_CHARS)
        val clear = json.encodeToString(
            StoredConnection.serializer(),
            StoredConnection(connection.origin.value, connection.label, connection.token),
        ).encodeToByteArray()
        val envelope = cipher.encrypt(clear)
        val encoded = json.encodeToString(
            StoredEnvelope.serializer(),
            StoredEnvelope(
                version = ENVELOPE_VERSION,
                iv = Base64.encodeToString(envelope.iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
            ),
        )
        check(preferences.edit().putString(RECORD, encoded).commit()) {
            "Unable to persist encrypted Collie connection"
        }
        mutableConnection.value = connection
    }

    override fun clear() {
        check(preferences.edit().remove(RECORD).commit()) {
            "Unable to clear encrypted Collie connection"
        }
        mutableConnection.value = null
    }

    private fun read(): Connection? {
        val encoded = preferences.getString(RECORD, null) ?: return null
        return try {
            val stored = json.decodeFromString(StoredEnvelope.serializer(), encoded)
            require(stored.version == ENVELOPE_VERSION)
            val plaintext = cipher.decrypt(
                CipherEnvelope(
                    Base64.decode(stored.iv, Base64.NO_WRAP),
                    Base64.decode(stored.ciphertext, Base64.NO_WRAP),
                ),
            )
            val record = json.decodeFromString(StoredConnection.serializer(), plaintext.decodeToString())
            require(record.label.isNotBlank() && record.label.length <= MAX_LABEL_CHARS)
            Connection(originValidator.requireValid(record.origin), record.label, record.token)
        } catch (_: Exception) {
            // Synchronous removal prevents a repeatedly crashing envelope from surviving process death.
            preferences.edit().remove(RECORD).commit()
            null
        }
    }

    @Serializable
    private data class StoredEnvelope(
        val version: Int,
        val iv: String,
        val ciphertext: String,
    )

    @Serializable
    private data class StoredConnection(
        val origin: String,
        val label: String,
        val token: String? = null,
    )

    companion object {
        internal const val PREFERENCES = "collie_connection"
        internal const val RECORD = "encrypted_record"
        private const val ENVELOPE_VERSION = 1
        private const val MAX_LABEL_CHARS = 80
    }
}
