package com.lateapex.collie.notifications

/** The receiver admission core; durable commits precede every Android notification side effect. */
internal class NativePushReceiverCustody(
    private val store: NativePushStore,
    private val ownerCurrent: (PushEnrollment) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Accepted {
        data class Display(val enrollment: PushEnrollment, val payload: NativePushPayload, val rawPayload: String) : Accepted
        data class Retired(val enrollment: PushEnrollment) : Accepted
    }
    fun accept(content: ByteArray, decrypted: Boolean, instance: String): Accepted? {
        val state = store.read()?.takeIf { it.instance == instance && !it.retired && ownerCurrent(it) } ?: return null
        if (!decrypted) return null
        val registration = state.registrationId ?: return null
        val payload = NativePushVerifier.verify(content, state.signingPublicKey ?: return null) ?: return null
        if (NativePushVerifier.isRetirement(payload, registration)) {
            store.write(state.copy(retired = true, endpoint = null, p256dh = null, auth = null, sequences = emptyMap()))
            return Accepted.Retired(state)
        }
        val preliminary = NativePushPayload.decode(payload, registration, now()) ?: return null
        val decoded = NativePushPayload.decode(payload, registration, now(), state.sequences[preliminary.slot] ?: 0) ?: return null
        if (!(decoded.slot in state.sequences) && state.sequences.size >= 128) return null
        store.write(state.copy(sequences = state.sequences + (decoded.slot to decoded.sequence)))
        return Accepted.Display(state, decoded, payload)
    }
}
