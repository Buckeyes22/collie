package com.lateapex.collie.network

import kotlinx.serialization.Serializable

enum class FailureSource { COLLIE, GATEWAY, UNKNOWN }
enum class FailureFactor { PAIRING, IDENTITY, DEVICE, SCOPE, CAPABILITY, OPERATION, UNKNOWN }

sealed interface CapabilityState {
    data object Unknown : CapabilityState
    data object Available : CapabilityState
    data class Unavailable(val reason: String?) : CapabilityState
    data object Forbidden : CapabilityState
}

@Serializable
data class ControlCapabilityResponse(val capabilities: Map<String, Boolean> = emptyMap()) {
    fun supportsNew(name: String): Boolean = capabilities[name] == true
    fun state(name: String): CapabilityState = when (capabilities[name]) {
        true -> CapabilityState.Available
        false -> CapabilityState.Unavailable(null)
        null -> CapabilityState.Unknown
    }
}
@Serializable
data class HostControlResponse(
    val provider: String = "none",
    val capabilities: Map<String, Boolean> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
) {
    fun supportsNew(name: String): Boolean = capabilities[name] == true
}

/** Open display codes, closed state/schema acceptance. Unknown states never authorize a repeat. */
@Serializable
data class ControlOperation(
    val schema: Int,
    val requestId: String,
    val state: String,
    val capability: String,
    val targetIdentity: String,
    val at: Long,
    val updatedAt: Long,
    val code: String? = null,
) {
    fun validFor(id: String): Boolean = schema == 1 && requestId == id &&
        state in setOf("pending", "succeeded", "refused", "unknown") &&
        capability.isNotBlank() && targetIdentity.isNotBlank() && updatedAt >= at
}
@Serializable
data class ControlOperationResponse(val ok: Boolean, val operation: ControlOperation)

/** Only a declared response source/factor/code can retire Collie pairing, never server prose. */
fun ApiFailure.Http.retiresPairing(): Boolean = status in setOf(401, 403) &&
    source == FailureSource.COLLIE && factor == FailureFactor.PAIRING &&
    code in setOf("pairing.expired", "pairing.revoked", "pairing.unpaired")
