package com.lateapex.collie.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A serializable snapshot of the authority and exact request body for one launch-like action. */
@Serializable
data class FrozenLaunchIntent(
    val requestId: String,
    val origin: String,
    val mount: String,
    /** Capture-time actor fence; process-local and deliberately not a durable replay identity. */
    val connectionGeneration: Long,
    val pairedDeviceIdentity: String,
    /** Capture-time actor fence; same-owner lead takeover does not change durable request ownership. */
    val leadEpoch: Long,
    val host: String?,
    val session: String?,
    val subjectIdentity: String?,
    val operation: String,
    /** Captured capability mode; legacy one-shot records can never be upgraded by later config. */
    val receiptMode: LaunchIntentReceiptMode = LaunchIntentReceiptMode.ownerBound,
    /** Stable route target (for example a workspace ID), when the HTTP path is target-specific. */
    val targetIdentity: String? = null,
    /** The user's explicit consent to skip prompts, frozen with this exact request. */
    val confirmedNoPrompts: Boolean,
    /** Canonical JSON bytes, represented as UTF-8 text and encrypted at rest. */
    val canonicalBody: String,
    val bodySha256: String = LaunchIntentStore.sha256(canonicalBody),
) {
    override fun toString(): String =
        "FrozenLaunchIntent(requestId=$requestId, operation=$operation, bodySha256=$bodySha256, canonicalBody=<redacted>)"
}

@Serializable
enum class LaunchIntentState { pending, unknown, succeeded, refused }

@Serializable
enum class LaunchIntentReceiptMode { ownerBound, legacyInitialOnly }

@Serializable
data class StoredLaunchIntent(
    val intent: FrozenLaunchIntent,
    val state: LaunchIntentState,
    /** Optional non-secret receipt reference; never store a bearer or response body here. */
    val receiptReference: String? = null,
)

sealed interface ReserveIntentResult {
    data class Reserved(val record: StoredLaunchIntent) : ReserveIntentResult
    data class Existing(val record: StoredLaunchIntent) : ReserveIntentResult
    data class Unresolved(val record: StoredLaunchIntent) : ReserveIntentResult
    data object Mismatch : ReserveIntentResult
}

/**
 * Encrypted local journal for non-replayable remote intents. A pending record found at process
 * startup becomes unknown: the server may have acted before the process died. The store never
 * retries or replaces an unknown intent automatically.
 *
 * The supplied cipher should use a dedicated Android Keystore alias for this store.
 */
class LaunchIntentStore(
    context: Context,
    private val cipher: SecretCipher = AndroidKeystoreCipher(alias = KEY_ALIAS),
    private val json: Json = Json,
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val records = linkedMapOf<String, StoredLaunchIntent>()
    private var unavailable = false

    init {
        read()
        if (!unavailable) recoverPendingAsUnknown()
    }

    @Synchronized
    fun all(): List<StoredLaunchIntent> {
        checkAvailable()
        return records.values.toList()
    }

    @Synchronized
    fun get(requestId: String): StoredLaunchIntent? {
        checkAvailable()
        return records[requestId]
    }

    /** Retire private request bodies when pairing is explicitly removed or authoritatively revoked. */
    @Synchronized
    fun clear() {
        try {
            check(preferences.edit().remove(RECORD).commit()) { "Unable to retire encrypted launch intents" }
            records.clear()
            unavailable = false
        } catch (error: Exception) {
            unavailable = true
            throw error
        }
    }

    /** Forget an uncertain request only after the user confirms the duplicate-risk disclosure. */
    @Synchronized
    fun abandonUnknown(requestId: String, expectedBodySha256: String): Boolean {
        checkAvailable()
        val current = records[requestId] ?: return false
        if (current.state != LaunchIntentState.unknown || current.intent.bodySha256 != expectedBodySha256) return false
        val next = records.values.filterNot { it.intent.requestId == requestId }
        try {
            persist(next)
        } catch (error: Exception) {
            unavailable = true
            throw error
        }
        records.remove(requestId)
        return true
    }

    /** Atomically reserve a request ID for one frozen authority and canonical body. */
    @Synchronized
    fun reserve(intent: FrozenLaunchIntent): ReserveIntentResult {
        checkAvailable()
        requireValidIntent(intent)
        require(intent.bodySha256 == sha256(intent.canonicalBody))
        val prior = records[intent.requestId]
        if (prior != null) {
            return if (prior.intent == intent) ReserveIntentResult.Existing(prior) else ReserveIntentResult.Mismatch
        }
        val unresolved = records.values.firstOrNull { record ->
            (record.state == LaunchIntentState.pending || record.state == LaunchIntentState.unknown) &&
                sameAdmissionScope(record.intent, intent)
        }
        if (unresolved != null) return ReserveIntentResult.Unresolved(unresolved)
        check(records.size < MAX_RECORDS) { "Launch intent journal is full; resolve or retain its existing intents" }
        val record = StoredLaunchIntent(intent, LaunchIntentState.pending)
        try {
            persist(records.values.toList() + record)
        } catch (error: Exception) {
            if (error !is JournalLimitException) unavailable = true
            throw error
        }
        records[intent.requestId] = record
        return ReserveIntentResult.Reserved(record)
    }

    /** Update only the state for a matching immutable intent; there is no transition back to pending. */
    @Synchronized
    fun transition(
        requestId: String,
        expectedBodySha256: String,
        state: LaunchIntentState,
        receiptReference: String? = null,
    ): StoredLaunchIntent? {
        checkAvailable()
        val current = records[requestId] ?: return null
        if (current.intent.bodySha256 != expectedBodySha256) return null
        if (current.state == LaunchIntentState.succeeded || current.state == LaunchIntentState.refused) {
            return current.takeIf { state == current.state && (receiptReference == null || receiptReference == current.receiptReference) }
        }
        if (receiptReference != null && !RECEIPT_REFERENCE.matches(receiptReference)) return null
        val allowed = when (current.state) {
            LaunchIntentState.pending -> state != LaunchIntentState.pending
            // A refusal of a deliberate replay says only that this attempt was rejected. It
            // cannot establish that the original attempt had no effect, so unknown custody may
            // resolve only when the owner returns an authoritative success receipt.
            LaunchIntentState.unknown -> state == LaunchIntentState.succeeded
            LaunchIntentState.succeeded, LaunchIntentState.refused -> state == current.state
        }
        if (!allowed) return null
        val updated = current.copy(state = state, receiptReference = receiptReference ?: current.receiptReference)
        try {
            persist(records.values.map { if (it.intent.requestId == requestId) updated else it })
        } catch (error: Exception) {
            unavailable = true
            throw error
        }
        records[requestId] = updated
        return updated
    }

    private fun recoverPendingAsUnknown() {
        val recovered = records.values.map { record ->
            if (record.state == LaunchIntentState.pending) record.copy(state = LaunchIntentState.unknown) else record
        }
        if (recovered != records.values.toList()) {
            try {
                persist(recovered)
                records.clear()
                recovered.forEach { record -> records[record.intent.requestId] = record }
            } catch (_: Exception) {
                unavailable = true
            }
        }
    }

    private fun read() {
        val encoded = preferences.getString(RECORD, null) ?: return
        try {
            require(encoded.length <= MAX_ENVELOPE_CHARS)
            val envelope = json.decodeFromString(Envelope.serializer(), encoded)
            require(envelope.version == VERSION)
            require(envelope.iv.length == IV_BASE64_CHARS)
            require(envelope.ciphertext.length in MIN_CIPHERTEXT_BASE64_CHARS..MAX_CIPHERTEXT_BASE64_CHARS)
            val clear = cipher.decrypt(
                CipherEnvelope(
                    Base64.decode(envelope.iv, Base64.NO_WRAP),
                    Base64.decode(envelope.ciphertext, Base64.NO_WRAP),
                ),
            )
            require(clear.size <= MAX_JOURNAL_BYTES)
            val stored = json.decodeFromString(RecordList.serializer(), clear.decodeToString())
            val validated = linkedMapOf<String, StoredLaunchIntent>()
            require(stored.records.size <= MAX_RECORDS)
            stored.records.forEach { record ->
                requireValidIntent(record.intent)
                require(record.intent.bodySha256 == sha256(record.intent.canonicalBody))
                require(record.receiptReference == null || RECEIPT_REFERENCE.matches(record.receiptReference))
                require(validated.put(record.intent.requestId, record) == null)
            }
            require(serializedSize(validated.values.toList()) <= MAX_JOURNAL_BYTES)
            records.putAll(validated)
        } catch (_: Exception) {
            // Keep the unreadable envelope and fail closed. Deleting it could permit a duplicate.
            unavailable = true
        }
    }

    private fun persist(next: List<StoredLaunchIntent>) {
        if (next.size > MAX_RECORDS) throw JournalLimitException("Launch intent journal is full; existing intents were retained")
        val clear = json.encodeToString(RecordList.serializer(), RecordList(next)).encodeToByteArray()
        if (clear.size > MAX_JOURNAL_BYTES || reservedResolutionHeadroom(next) > MAX_JOURNAL_BYTES - clear.size) {
            throw JournalLimitException("Launch intent journal size limit reached; existing intents were retained with resolution headroom")
        }
        val encrypted = cipher.encrypt(clear)
        require(encrypted.iv.size == IV_BYTES)
        require(encrypted.ciphertext.size <= MAX_CIPHERTEXT_BYTES)
        val encoded = json.encodeToString(
            Envelope.serializer(),
            Envelope(
                version = VERSION,
                iv = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP),
            ),
        )
        check(encoded.length <= MAX_ENVELOPE_CHARS)
        check(preferences.edit().putString(RECORD, encoded).commit()) {
            "Unable to persist encrypted launch intents"
        }
    }

    private fun requireValidIntent(intent: FrozenLaunchIntent) {
        require(REQUEST_ID.matches(intent.requestId))
        require(intent.origin.isNotBlank() && intent.origin.length <= MAX_IDENTITY_CHARS)
        require(intent.mount.isNotBlank() && intent.mount.length <= MAX_IDENTITY_CHARS)
        require(intent.pairedDeviceIdentity.isNotBlank() && intent.pairedDeviceIdentity.length <= MAX_IDENTITY_CHARS)
        require(intent.operation.isNotBlank() && intent.operation.length <= MAX_OPERATION_CHARS)
        require(intent.targetIdentity == null || (intent.targetIdentity.isNotBlank() && intent.targetIdentity.length <= MAX_IDENTITY_CHARS))
        require(intent.canonicalBody.isNotBlank() && intent.canonicalBody.length <= MAX_BODY_CHARS)
        require(intent.connectionGeneration >= 0L && intent.leadEpoch >= 0L)
        require(intent.host == null || (intent.host.isNotBlank() && intent.host.length <= MAX_IDENTITY_CHARS))
        require(intent.session == null || (intent.session.isNotBlank() && intent.session.length <= MAX_IDENTITY_CHARS))
        require(intent.subjectIdentity == null || (intent.subjectIdentity.isNotBlank() && intent.subjectIdentity.length <= MAX_IDENTITY_CHARS))
    }

    /** One uncertain action blocks every new effect for the same stable authority and subject. */
    private fun sameAdmissionScope(first: FrozenLaunchIntent, second: FrozenLaunchIntent): Boolean =
        first.origin == second.origin && first.mount == second.mount &&
            first.pairedDeviceIdentity == second.pairedDeviceIdentity && first.host == second.host &&
            first.session == second.session && first.subjectIdentity == second.subjectIdentity

    private fun serializedSize(next: List<StoredLaunchIntent>): Int =
        json.encodeToString(RecordList.serializer(), RecordList(next)).encodeToByteArray().size

    private fun reservedResolutionHeadroom(next: List<StoredLaunchIntent>): Int =
        // A null reference is omitted by the serializer. Resolving it can add the 256-byte value,
        // JSON key/separator/quotes (23 bytes), and the longest state growth (pending→succeeded,
        // 2 bytes). Keep this exact maximum reserved for every unresolved row.
        next.sumOf { if (it.receiptReference == null) RESOLUTION_HEADROOM_BYTES else 0 }

    private class JournalLimitException(message: String) : IllegalStateException(message)

    private fun checkAvailable() {
        check(!unavailable) { "Encrypted launch intent journal is unreadable; refusing new intents" }
    }

    @Serializable
    private data class RecordList(val records: List<StoredLaunchIntent>)

    @Serializable
    private data class Envelope(val version: Int, val iv: String, val ciphertext: String)

    companion object {
        private const val PREFERENCES = "collie_launch_intents"
        private const val RECORD = "encrypted_records"
        private const val VERSION = 1
        private const val KEY_ALIAS = "com.lateapex.collie.launch-intents.v1"
        private const val MAX_RECORDS = 128
        private const val MAX_BODY_CHARS = 8_192
        private const val MAX_JOURNAL_BYTES = 512 * 1_024
        private const val MAX_IDENTITY_CHARS = 2_048
        private const val MAX_OPERATION_CHARS = 128
        private const val MAX_RECEIPT_REFERENCE_CHARS = 256
        private const val RECEIPT_JSON_FIELD_OVERHEAD = 23
        private const val TERMINAL_STATE_GROWTH_BYTES = 2
        private const val RESOLUTION_HEADROOM_BYTES =
            MAX_RECEIPT_REFERENCE_CHARS + RECEIPT_JSON_FIELD_OVERHEAD + TERMINAL_STATE_GROWTH_BYTES
        private const val IV_BYTES = 12
        private const val IV_BASE64_CHARS = 16
        private const val MIN_CIPHERTEXT_BASE64_CHARS = 24
        private const val MAX_CIPHERTEXT_BYTES = MAX_JOURNAL_BYTES + 16
        private const val MAX_CIPHERTEXT_BASE64_CHARS = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4
        private const val MAX_ENVELOPE_CHARS = MAX_CIPHERTEXT_BASE64_CHARS + 128
        private val REQUEST_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val RECEIPT_REFERENCE = Regex("^[A-Za-z0-9._:-]{1,$MAX_RECEIPT_REFERENCE_CHARS}$")

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
