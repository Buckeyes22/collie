package com.lateapex.collie.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Opaque action receipt persisted before a queued-send POST can leave the phone. */
interface QueuedSendAttemptStore {
    fun wasAttempted(actionIdentity: String): Boolean
    /** Records a new authoritative action identity and retires an older one for this pane. */
    fun observe(paneIdentity: String, actionIdentity: String): Boolean
    /** Durable, synchronous latch. False means storage is full or failed closed. */
    fun recordBeforeDispatch(paneIdentity: String, actionIdentity: String): Boolean
    fun recordOutcome(actionIdentity: String, outcome: String)
}

/** Test/partial-host fallback that never authorizes a write without encrypted application custody. */
object UnavailableQueuedSendAttemptStore : QueuedSendAttemptStore {
    override fun wasAttempted(actionIdentity: String): Boolean = true
    override fun observe(paneIdentity: String, actionIdentity: String): Boolean = false
    override fun recordBeforeDispatch(paneIdentity: String, actionIdentity: String): Boolean = false
    override fun recordOutcome(actionIdentity: String, outcome: String) = Unit
}

/**
 * Encrypted bounded custody for queued-send identity/outcome metadata only.
 * No transcript text, keys, credentials, hostnames, or session refs are stored.
 */
class EncryptedQueuedSendAttemptStore(
    context: Context,
    private val cipher: SecretCipher,
    private val json: Json,
) : QueuedSendAttemptStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun wasAttempted(actionIdentity: String): Boolean = read().any { it.action == actionIdentity }

    @Synchronized
    override fun observe(paneIdentity: String, actionIdentity: String): Boolean {
        if (!isDigest(paneIdentity) || !isDigest(actionIdentity)) return false
        val current = read().toMutableList()
        val same = current.any { it.pane == paneIdentity && it.action == actionIdentity }
        if (!same) {
            current.removeAll { it.pane == paneIdentity }
            if (!write(current)) return false
        }
        return true
    }

    @Synchronized
    override fun recordBeforeDispatch(paneIdentity: String, actionIdentity: String): Boolean {
        if (!isDigest(paneIdentity) || !isDigest(actionIdentity)) return false
        val current = read().toMutableList()
        if (current.any { it.action == actionIdentity }) return false
        current.removeAll { it.pane == paneIdentity }
        // Uncertain attempts are never evicted to make room for a newer request.
        if (current.size >= MAX_RECORDS) return false
        current += Attempt(paneIdentity, actionIdentity, OUTCOME_UNKNOWN)
        return write(current)
    }

    @Synchronized
    override fun recordOutcome(actionIdentity: String, outcome: String) {
        if (!isDigest(actionIdentity) || outcome !in OUTCOMES) return
        val current = read().map { record ->
            if (record.action == actionIdentity) record.copy(outcome = outcome) else record
        }
        write(current)
    }

    private fun read(): List<Attempt> {
        val encoded = preferences.getString(RECORD, null) ?: return emptyList()
        return try {
            val envelope = json.decodeFromString(StoredEnvelope.serializer(), encoded)
            require(envelope.version == VERSION)
            val clear = cipher.decrypt(
                CipherEnvelope(
                    Base64.decode(envelope.iv, Base64.NO_WRAP),
                    Base64.decode(envelope.ciphertext, Base64.NO_WRAP),
                ),
            )
            val records = json.decodeFromString(StoredAttempts.serializer(), clear.decodeToString()).records
            require(records.size <= MAX_RECORDS && records.all {
                isDigest(it.pane) && isDigest(it.action) && it.outcome in OUTCOMES
            })
            records
        } catch (_: Exception) {
            // A damaged latch must never make an old queued action eligible again.
            throw IllegalStateException("Unable to read encrypted queued-send action custody")
        }
    }

    private fun write(records: List<Attempt>): Boolean = try {
        require(records.size <= MAX_RECORDS)
        val encrypted = cipher.encrypt(json.encodeToString(StoredAttempts.serializer(), StoredAttempts(records)).encodeToByteArray())
        val stored = json.encodeToString(
            StoredEnvelope.serializer(),
            StoredEnvelope(VERSION, Base64.encodeToString(encrypted.iv, Base64.NO_WRAP), Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)),
        )
        preferences.edit().putString(RECORD, stored).commit()
    } catch (_: Exception) {
        false
    }

    @Serializable
    private data class Attempt(val pane: String, val action: String, val outcome: String)

    @Serializable
    private data class StoredAttempts(val records: List<Attempt>)

    @Serializable
    private data class StoredEnvelope(val version: Int, val iv: String, val ciphertext: String)

    companion object {
        private const val PREFERENCES = "queued_send_attempts_v1"
        private const val RECORD = "encrypted_attempts"
        private const val VERSION = 1
        private const val MAX_RECORDS = 64
        const val OUTCOME_UNKNOWN = "outcome_unknown"
        const val OUTCOME_ACCEPTED = "accepted"
        const val OUTCOME_REFUSED = "refused_after_dispatch"
        const val OUTCOME_PRE_DISPATCH_REFUSED = "pre_dispatch_refused"
        private val OUTCOMES = setOf(OUTCOME_UNKNOWN, OUTCOME_ACCEPTED, OUTCOME_REFUSED, OUTCOME_PRE_DISPATCH_REFUSED)
        private val DIGEST = Regex("[a-f0-9]{64}")
        private fun isDigest(value: String): Boolean = DIGEST.matches(value)
    }
}
