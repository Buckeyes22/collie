package com.lateapex.collie.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** RFC8291 decryption alone does not authenticate the app server: require its enrolled signer. */
internal object NativePushVerifier {
    fun verify(content: ByteArray, publicKey: String): String? = runCatching {
        if (content.size > 3600) return null
        val envelope = Json.parseToJsonElement(content.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: return null
        if (envelope.keys != setOf("payload", "signature")) return null
        val payload = (envelope["payload"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val signature = (envelope["signature"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!Regex("[A-Za-z0-9_-]{86}").matches(signature) || publicKey.length > 128) return null
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(NativePushStore.decode64(publicKey)))
        if (!Signature.getInstance("Ed25519").run { initVerify(key); update(payload.encodeToByteArray()); verify(NativePushStore.decode64(signature)) }) return null
        payload
    }.getOrNull()
    fun isRetirement(payload: String, registrationId: String): Boolean = runCatching {
        val p = Json.parseToJsonElement(payload) as? JsonObject ?: return false
        p.keys == setOf("schemaVersion", "registrationId", "type") && p["schemaVersion"] == JsonPrimitive(1) &&
            p["registrationId"] == JsonPrimitive(registrationId) && p["type"] == JsonPrimitive("retired")
    }.getOrDefault(false)
}
