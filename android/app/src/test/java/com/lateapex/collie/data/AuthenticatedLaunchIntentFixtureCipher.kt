package com.lateapex.collie.data

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Real JVM AES-GCM/tag semantics for the added retirement/recreation fixtures only.
 * Public, fixed test key survives fixture recreation; each encrypt uses a fresh provider IV.
 * This does not simulate hardware key custody or claim Android Keystore acceptance. */
internal class AuthenticatedLaunchIntentFixtureCipher : SecretCipher {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val aad = "b15.launch-intents.fixture-only.v1".encodeToByteArray()

    override fun encrypt(plaintext: ByteArray): CipherEnvelope {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        check(cipher.iv.size == 12 && encrypted.size == plaintext.size + 16)
        return CipherEnvelope(cipher.iv.copyOf(), encrypted)
    }

    override fun decrypt(envelope: CipherEnvelope): ByteArray {
        require(envelope.iv.size == 12 && envelope.ciphertext.size >= 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope.ciphertext)
    }
}
