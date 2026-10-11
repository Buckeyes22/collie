package com.lateapex.collie.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Decode the declared string status, tolerating bounded display-only {state,label} compatibility. */
object AgentStatusSerializer : KSerializer<AgentStatus> {
    override val descriptor = PrimitiveSerialDescriptor("AgentStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AgentStatus {
        val state = if (decoder is JsonDecoder) {
            val value = decoder.decodeJsonElement()
            val primitive = when (value) {
                is JsonObject -> value["state"] as? JsonPrimitive
                is JsonPrimitive -> value
                else -> null
            }
            if (primitive?.isString != true) throw SerializationException("Agent status requires a string state")
            primitive.content
        } else decoder.decodeString()
        if (!state.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) {
            throw SerializationException("Malformed agent status state")
        }
        return AgentStatus.entries.firstOrNull { it.name.lowercase() == state } ?: AgentStatus.UNKNOWN
    }

    override fun serialize(encoder: Encoder, value: AgentStatus) = encoder.encodeString(value.name.lowercase())
}
