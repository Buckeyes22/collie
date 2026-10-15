package com.lateapex.collie.ui

import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.data.SecretCipher
import com.lateapex.collie.domain.Connection

/** Robolectric lacks Android Keystore: replace only crypto, then use the real durable public save. */
internal fun saveFixtureConnection(application: CollieApplication, connection: Connection) {
    val store = application.container.connectionStore as EncryptedConnectionStore
    EncryptedConnectionStore::class.java.getDeclaredField("cipher").apply {
        isAccessible = true
        set(store, FixtureConnectionCipher)
    }
    store.save(connection)
}

private object FixtureConnectionCipher : SecretCipher {
    override fun encrypt(plaintext: ByteArray) = CipherEnvelope(
        ByteArray(12) { 7 },
        plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray(),
    )
    override fun decrypt(envelope: CipherEnvelope) =
        envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
}
