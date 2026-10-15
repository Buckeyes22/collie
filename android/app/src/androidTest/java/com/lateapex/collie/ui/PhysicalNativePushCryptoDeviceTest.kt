package com.lateapex.collie.ui

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lateapex.collie.notifications.NativePushVerifier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** RFC 8032 section 7.1 TEST 1: public standard vector, no enrollment or producer side effects. */
@RunWith(AndroidJUnit4::class)
class PhysicalNativePushCryptoDeviceTest {
    @Test fun productionVerifierAcceptsExternalEd25519StandardVectorAndRejectsTampering() {
        val spki = hex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        var stage = "keyFactory"
        var provider = "unavailable"
        val platform = try {
            val factory = KeyFactory.getInstance("Ed25519")
            provider = factory.provider.name
            stage = "externalSpki"
            val publicKey = factory.generatePublic(X509EncodedKeySpec(spki))
            stage = "signature"
            val verifier = Signature.getInstance("Ed25519")
            provider = verifier.provider.name
            verifier.initVerify(publicKey)
            verifier.update(byteArrayOf())
            "supported=${verifier.verify(signature)} stage=$stage provider=$provider"
        } catch (failure: Exception) {
            "supported=false stage=$stage provider=$provider exception=${failure.javaClass.simpleName}"
        }
        // No vector bytes, keys, exception messages, pairing values, or provider URLs are logged.
        Log.i("ColliePushCryptoAcceptance", platform)
        val publicKey = encode(spki)
        fun envelope(payload: String) = buildJsonObject {
            put("payload", payload); put("signature", encode(signature))
        }.toString().encodeToByteArray()
        assertTrue("Production Ed25519 verifier must accept RFC8032 empty UTF8 payload; $platform",
            NativePushVerifier.verify(envelope(""), publicKey) == "")
        assertNull("A changed payload must fail the same production verifier", NativePushVerifier.verify(envelope("changed"), publicKey))
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
