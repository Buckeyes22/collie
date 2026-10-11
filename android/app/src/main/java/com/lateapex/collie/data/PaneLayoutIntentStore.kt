package com.lateapex.collie.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Stable encrypted custody scope. The identity is a local credential digest, never a bearer. */
@Serializable
data class PaneLayoutIntentOwner(
    val origin: String,
    val deviceIdentity: String,
    val host: String?,
    val session: String?,
    val subject: String?,
    val paneId: String,
) {
    companion object {
        fun from(context: ScopedReadContext, paneId: String): PaneLayoutIntentOwner =
            PaneLayoutIntentOwner(context.key.origin, context.key.pairedDeviceIdentity, context.key.host,
                context.key.session, context.key.subjectIdentity, paneId)
    }
}

@Serializable
data class PaneLayoutIntent(
    val owner: PaneLayoutIntentOwner,
    val connectionGeneration: Long,
    val leadEpoch: Long,
    val requestId: String,
    val planToken: String,
    val actionJson: String,
    val sourceTabId: String? = null,
    val sourceWorkspaceId: String? = null,
    val state: String = "pending",
)

sealed interface PaneLayoutReservation {
    data object Reserved : PaneLayoutReservation
    data class Existing(val intent: PaneLayoutIntent) : PaneLayoutReservation
    data object UnresolvedIntent : PaneLayoutReservation
    data object Unavailable : PaneLayoutReservation
}

/**
 * One encrypted, synchronous journal for uncertain layout operations. Pending is already durable
 * before POST; loading pending converts it to an in-memory unknown, never to an automatic replay.
 * Rows for retired credentials stay encrypted and owner-bound until their owner can reconcile them.
 */
class PaneLayoutIntentStore(
    context: Context,
    private val cipher: SecretCipher,
    private val json: Json,
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
) {
    private var loaded = false
    private var unavailable = false
    // A failed commit may change SharedPreferences' memory without proving what reached disk.
    // Retain our complete original journal until an explicit UNKNOWN re-commit is acknowledged.
    private var recoveryRequired = false
    private val rows = linkedMapOf<String, PaneLayoutIntent>()

    @Synchronized
    fun unresolved(owner: PaneLayoutIntentOwner): PaneLayoutIntent? {
        if (!load() && !loaded) return null
        return rows[ownerKey(owner)]?.takeIf { it.owner == owner }
    }

    @Synchronized
    fun reserve(intent: PaneLayoutIntent): PaneLayoutReservation {
        if (!valid(intent) || (!load() && !loaded)) return PaneLayoutReservation.Unavailable
        if (unavailable || recoveryRequired) return PaneLayoutReservation.Unavailable
        val key = ownerKey(intent.owner)
        val old = rows[key]
        if (old != null) {
            if (old.requestId == intent.requestId && old.planToken == intent.planToken && old.actionJson == intent.actionJson &&
                old.sourceTabId == intent.sourceTabId && old.sourceWorkspaceId == intent.sourceWorkspaceId && old.owner == intent.owner) {
                return PaneLayoutReservation.Existing(old.copy(state = "unknown"))
            }
            return PaneLayoutReservation.UnresolvedIntent
        }
        if (rows.size >= MAX_ROWS) return PaneLayoutReservation.Unavailable
        rows[key] = intent
        if (!persist()) {
            rows[key] = intent.copy(state = "unknown")
            recoveryRequired = true
            return PaneLayoutReservation.Unavailable
        }
        return PaneLayoutReservation.Reserved
    }

    @Synchronized
    fun markUnknown(owner: PaneLayoutIntentOwner, requestId: String): Boolean {
        if (!load() && !loaded) return false
        val key = ownerKey(owner)
        val row = rows[key]?.takeIf { it.owner == owner && it.requestId == requestId } ?: return false
        if (row.state == "unknown") return true
        rows[key] = row.copy(state = "unknown")
        if (!persist()) {
            rows[key] = row.copy(state = "unknown")
            recoveryRequired = true
            // commit(false) can already have changed cache/disk. Rescue the complete retained
            // UNKNOWN journal once; even an ACK here does not authorize UI cleanup or replay.
            persist()
            return false
        }
        return true
    }

    /** Remove only after a correlated terminal receipt; a failed sync leaves the old unknown row. */
    @Synchronized
    fun resolve(owner: PaneLayoutIntentOwner, requestId: String): Boolean {
        if (!load() || recoveryRequired) return false
        val key = ownerKey(owner)
        val row = rows[key]?.takeIf { it.owner == owner && it.requestId == requestId } ?: return false
        rows.remove(key)
        if (!persist()) {
            rows[key] = row.copy(state = "unknown")
            recoveryRequired = true
            // commit(false) can already have changed cache/disk. Rescue the complete retained
            // UNKNOWN journal once; even an ACK here does not authorize UI cleanup or replay.
            persist()
            return false
        }
        return true
    }

    /** Explicit operator abandonment only; never inferred from a failed lookup or receipt. */
    @Synchronized
    fun abandon(owner: PaneLayoutIntentOwner, requestId: String): Boolean = resolve(owner, requestId)

    @Synchronized
    fun needsRecovery(owner: PaneLayoutIntentOwner, requestId: String): Boolean =
        recoveryRequired && rows[ownerKey(owner)]?.let { it.owner == owner && it.requestId == requestId } == true

    /** A separate deliberate ACK re-establishes the whole retained journal; it deletes no row. */
    @Synchronized
    fun recoverCustody(expected: PaneLayoutIntent): Boolean {
        if (unavailable || !loaded || !recoveryRequired) return false
        val key = ownerKey(expected.owner)
        val row = rows[key] ?: return false
        if (row.copy(state = "unknown") != expected.copy(state = "unknown") || !valid(row) || !rows.values.all(::valid)) return false
        rows[key] = row.copy(state = "unknown")
        if (!persist()) return false
        recoveryRequired = false
        return true
    }

    @Synchronized
    private fun load(): Boolean {
        if (unavailable) return false
        if (loaded) return true
        val encoded = preferences.getString(RECORD, null)
        if (encoded == null) { loaded = true; return true }
        try {
            require(encoded.length <= MAX_ENVELOPE_CHARS)
            val envelope = json.decodeFromString(Envelope.serializer(), encoded)
            require(envelope.version == VERSION && envelope.iv.length == IV_BASE64_CHARS)
            require(envelope.ciphertext.length in 24..MAX_CIPHERTEXT_BASE64_CHARS)
            val clear = cipher.decrypt(CipherEnvelope(Base64.decode(envelope.iv, Base64.NO_WRAP), Base64.decode(envelope.ciphertext, Base64.NO_WRAP)))
            require(clear.size <= MAX_CLEAR_BYTES)
            val records = json.decodeFromString(RecordList.serializer(), clear.decodeToString()).rows
            require(records.size <= MAX_ROWS && records.all(::valid))
            require(records.map { ownerKey(it.owner) }.distinct().size == records.size)
            records.forEach { rows[ownerKey(it.owner)] = it.copy(state = "unknown") }
            loaded = true
            return true
        } catch (_: Exception) {
            // Preserve the unreadable ciphertext for diagnosis/recovery; never overwrite it.
            unavailable = true
            return false
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun persist(): Boolean {
        return try {
            val clear = json.encodeToString(RecordList.serializer(), RecordList(rows.values.toList())).encodeToByteArray()
            if (clear.size > MAX_CLEAR_BYTES) return false
            val encrypted = cipher.encrypt(clear)
            if (encrypted.iv.size != IV_BYTES || encrypted.ciphertext.size > MAX_CIPHERTEXT_BYTES) return false
            val encoded = json.encodeToString(Envelope.serializer(), Envelope(VERSION,
                Base64.encodeToString(encrypted.iv, Base64.NO_WRAP), Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)))
            if (encoded.length > MAX_ENVELOPE_CHARS) return false
            preferences.edit().putString(RECORD, encoded).commit()
        } catch (_: Exception) { false }
    }

    private fun valid(row: PaneLayoutIntent): Boolean =
        row.owner.origin.isNotBlank() && row.owner.origin.length <= 2048 && row.owner.deviceIdentity.matches(Regex("[a-f0-9]{64}")) &&
            (row.owner.host == null || row.owner.host.length in 1..256) && (row.owner.session == null || row.owner.session.length in 1..256) &&
            (row.owner.subject == null || row.owner.subject.length in 1..256) && row.owner.paneId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) &&
            row.connectionGeneration >= 0 && row.leadEpoch >= 0 && row.requestId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            row.planToken.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && row.actionJson.toByteArray(Charsets.UTF_8).size <= MAX_ACTION_BYTES &&
            (row.sourceTabId == null || row.sourceTabId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) &&
            (row.sourceWorkspaceId == null || row.sourceWorkspaceId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) &&
            (row.state == "pending" || row.state == "unknown")

    private fun ownerKey(owner: PaneLayoutIntentOwner): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(PaneLayoutIntentOwner.serializer(), owner).encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Serializable private data class RecordList(val rows: List<PaneLayoutIntent>)
    @Serializable private data class Envelope(val version: Int, val iv: String, val ciphertext: String)

    companion object {
        private const val PREFERENCES = "collie_layout_intents"
        private const val RECORD = "encrypted_journal"
        private const val VERSION = 1
        private const val MAX_ROWS = 64
        private const val MAX_ACTION_BYTES = 16 * 1024
        private const val MAX_CLEAR_BYTES = 1024 * 1024
        private const val IV_BYTES = 12
        private const val IV_BASE64_CHARS = 16
        private const val MAX_CIPHERTEXT_BYTES = MAX_CLEAR_BYTES + 16
        private const val MAX_CIPHERTEXT_BASE64_CHARS = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4
        private const val MAX_ENVELOPE_CHARS = MAX_CIPHERTEXT_BASE64_CHARS + 128
    }
}
