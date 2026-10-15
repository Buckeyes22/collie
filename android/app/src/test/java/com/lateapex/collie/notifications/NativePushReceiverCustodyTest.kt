package com.lateapex.collie.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class NativePushReceiverCustodyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private lateinit var store: NativePushStore
    private var active = true
    private val instance = "installation_demo"
    @Before fun setup() {
        java.io.File(context.noBackupFilesDir, "native-push-v1").delete()
        store = NativePushStore(context, object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = CipherEnvelope(ByteArray(12), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
            override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        })
        store.write(PushEnrollment(instance, "https://collie.example/", "incarnation1", "digest", registrationId = "registration_demo", signingPublicKey = encode(key.public.encoded)))
    }
    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun signed(payload: String): ByteArray = buildJsonObject {
        put("payload", payload); put("signature", encode(Signature.getInstance("Ed25519").run { initSign(key.private); update(payload.encodeToByteArray()); sign() }))
    }.toString().encodeToByteArray()
    private fun payload(sequence: Int, kind: String = "blocked") = buildJsonObject {
        put("schemaVersion", 1); put("registrationId", "registration_demo"); put("slot", "slot1"); put("sequence", sequence); put("expiresAt", 3000); put("kind", kind)
        if (kind != "clear") { put("title", "Needs you"); put("body", "Work"); putJsonObject("target") { put("screen", "pane"); put("paneId", "p1") } }
    }.toString()
    @Test fun requiresServerSignatureDecryptionExactInstanceAndCurrentPairing() {
        val receiver = NativePushReceiverCustody(store, { active }, { 1000 })
        assertNull(receiver.accept(payload(1).encodeToByteArray(), true, instance))
        assertNull(receiver.accept(signed(payload(1)), false, instance))
        assertNull(receiver.accept(signed(payload(1)), true, "other_instance"))
        active = false; assertNull(receiver.accept(signed(payload(1)), true, instance))
        active = true; assertTrue(receiver.accept(signed(payload(1)), true, instance) is NativePushReceiverCustody.Accepted.Display)
    }
    @Test fun durableSlotWatermarksSurviveRestartAndClearRejectsOlderDisplays() {
        val receiver = NativePushReceiverCustody(store, { active }, { 1000 })
        assertNotNull(receiver.accept(signed(payload(2)), true, instance))
        assertNull(receiver.accept(signed(payload(2)), true, instance))
        assertNull(receiver.accept(signed(payload(1)), true, instance))
        assertNotNull(receiver.accept(signed(payload(3, "clear")), true, instance))
        assertEquals(3L, store.read()!!.sequences["slot1"])
        assertNull(NativePushReceiverCustody(store, { active }, { 1000 }).accept(signed(payload(2)), true, instance))
    }
    @Test fun signedRetirementCommitsTombstoneBeforeFurtherDisplayAndStorageIsEncrypted() {
        val receiver = NativePushReceiverCustody(store, { active }, { 1000 })
        val retired = """{"schemaVersion":1,"registrationId":"registration_demo","type":"retired"}"""
        assertTrue(receiver.accept(signed(retired), true, instance) is NativePushReceiverCustody.Accepted.Retired)
        assertTrue(store.read()!!.retired)
        assertNull(receiver.accept(signed(payload(4)), true, instance))
        val raw = java.io.File(context.noBackupFilesDir, "native-push-v1").readText()
        assertFalse(raw.contains("registration_demo")); assertFalse(raw.contains("collie.example"))
    }
}
