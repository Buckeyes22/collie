package com.lateapex.collie.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.google.crypto.tink.subtle.Ed25519Verify

/** RFC8291 decryption alone does not authenticate the app server: require its enrolled signer. */
internal object NativePushVerifier {
    // RFC 8410: Ed25519 SubjectPublicKeyInfo has absent parameters and a 32-byte key.
    private val ed25519SpkiPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    fun verify(content: ByteArray, publicKey: String): String? = runCatching {
        if (content.size > 3600) return null
        val envelope = Json.parseToJsonElement(content.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: return null
        if (envelope.keys != setOf("payload", "signature")) return null
        val payload = (envelope["payload"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val signature = (envelope["signature"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!Regex("[A-Za-z0-9_-]{86}").matches(signature) || publicKey.length > 128) return null
        val spki = NativePushStore.decode64(publicKey)
        if (spki.size != ed25519SpkiPrefix.size + 32 ||
            !spki.copyOfRange(0, ed25519SpkiPrefix.size).contentEquals(ed25519SpkiPrefix)) return null
        // Android 16's default Ed25519 KeyFactory may only accept AndroidKeyStore keys.
        // Tink verifies external keys without relying on that device-specific provider.
        Ed25519Verify(spki.copyOfRange(ed25519SpkiPrefix.size, spki.size))
            .verify(NativePushStore.decode64(signature), payload.encodeToByteArray())
        payload
    }.getOrNull()
    fun isRetirement(payload: String, registrationId: String): Boolean = runCatching {
        val p = Json.parseToJsonElement(payload) as? JsonObject ?: return false
        p.keys == setOf("schemaVersion", "registrationId", "type") && p["schemaVersion"] == JsonPrimitive(1) &&
            p["registrationId"] == JsonPrimitive(registrationId) && p["type"] == JsonPrimitive("retired")
    }.getOrDefault(false)
}
