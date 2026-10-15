package com.lateapex.collie.notifications

import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37])
class NativePushVerifierTest {
    // RFC 8032 section 7.1 TEST 1, independent of the verifier and platform providers.
    private val spki = hex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun envelope(payload: String = "", sig: ByteArray = signature) = buildJsonObject {
        put("payload", payload)
        put("signature", encode(sig))
    }.toString().encodeToByteArray()

    @Test fun acceptsStandardVectorAndRejectsChangedPayloadSignatureAndKey() {
        assertEquals("", NativePushVerifier.verify(envelope(), encode(spki)))
        assertNull(NativePushVerifier.verify(envelope("changed"), encode(spki)))
        assertNull(NativePushVerifier.verify(envelope(sig = signature.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }), encode(spki)))
        assertNull(NativePushVerifier.verify(envelope(), encode(spki.clone().also { it[43] = (it[43].toInt() xor 1).toByte() })))
    }

    @Test fun rejectsWrongAlgorithmMalformedSpkiAndSignatureLengths() {
        val wrongAlgorithm = spki.clone().also { it[8] = 0x6e } // X25519, same key size.
        for (key in listOf(wrongAlgorithm, spki + byteArrayOf(0), spki.drop(1).toByteArray(),
            spki.copyOfRange(12, 44), spki.clone().also { it[11] = 1 })) {
            assertNull(NativePushVerifier.verify(envelope(), encode(key)))
        }
        assertNull(NativePushVerifier.verify(envelope(sig = signature.copyOf(63)), encode(spki)))
        assertNull(NativePushVerifier.verify(envelope(sig = signature + byteArrayOf(0)), encode(spki)))
        assertNull(NativePushVerifier.verify("{\"payload\":\"\",\"signature\":\"!!!\"}".encodeToByteArray(), encode(spki)))
        assertNull(NativePushVerifier.verify(envelope(), "!"))
    }
}
