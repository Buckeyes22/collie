package com.lateapex.collie.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.lateapex.collie.network.isValidMachineId
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
enum class MachineRuleAttemptState { pending, unknown }

/** Encrypted metadata only. It deliberately has no desired rules or request-body field. */
@Serializable
data class MachineRuleAttempt(
    val attemptId: String,
    val ownerDigest: String,
    val connectionIncarnation: String,
    val machineId: String,
    val machineIncarnation: String,
    val rulesRevision: String,
    val state: MachineRuleAttemptState = MachineRuleAttemptState.pending,
    /** True when memory treats an inactive durable PENDING row as UNKNOWN after same-process contention. */
    @Transient val durablePendingWarning: Boolean = false,
) {
    override fun toString(): String =
        "MachineRuleAttempt(attemptId=$attemptId, machineId=$machineId, state=$state, metadata=<redacted>)"
}

sealed interface MachineRuleAttemptReservation {
    data object Reserved : MachineRuleAttemptReservation
    data object Unresolved : MachineRuleAttemptReservation
    data object Retired : MachineRuleAttemptReservation
    data object Unavailable : MachineRuleAttemptReservation
}

/** The admission wrapper runs [commitPrepared] only while the immutable original actor is current. */
typealias MachineRuleAttemptFinalCommit = (commitPrepared: () -> Boolean) -> Boolean

/** Bounded encrypted custody for one-shot conditional machine-rule writes. */
interface MachineRuleAttemptStore {
    /** Null means encrypted custody is unavailable; an empty list means no unresolved attempt. */
    fun unresolved(ownerDigest: String, connectionIncarnation: String): List<MachineRuleAttempt>?

    /** Null means encrypted custody is unavailable; an empty list means no unresolved attempt. */
    fun unresolved(ownerDigest: String, connectionIncarnation: String, machineId: String): List<MachineRuleAttempt>?

    /** Encrypts outside locks, then commits pending through original-actor final admission. */
    fun reserve(attempt: MachineRuleAttempt, finalCommit: MachineRuleAttemptFinalCommit): MachineRuleAttemptReservation

    /** Preserve uncertainty under this exact original owner and attempt, including after retirement. */
    fun markUnknown(ownerDigest: String, connectionIncarnation: String, attemptId: String): Boolean

    /** End the exact process-local dispatch before best-effort durable UNKNOWN publication. */
    fun finishLive(ownerDigest: String, connectionIncarnation: String, attemptId: String): Boolean

    /** Clear a known terminal response through original-actor final admission. */
    fun settleKnown(ownerDigest: String, connectionIncarnation: String, attemptId: String, finalCommit: MachineRuleAttemptFinalCommit): Boolean

    /** Clear an UNKNOWN only after the user reviewed a fresh authorized census and acknowledged risk. */
    fun acknowledgeReviewed(ownerDigest: String, connectionIncarnation: String, attemptId: String, finalCommit: MachineRuleAttemptFinalCommit): Boolean
}

/** AES/GCM custody using a dedicated Keystore alias supplied by the application container. */
class EncryptedMachineRuleAttemptStore(
    context: Context,
    private val cipher: SecretCipher,
    private val json: Json = Json,
    preferencesOverride: SharedPreferences? = null,
) : MachineRuleAttemptStore {
    private val preferences = preferencesOverride
        ?: context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val journalMonitor = Any()
    private val records = linkedMapOf<String, MachineRuleAttempt>()
    /** Exact live tuples exist only in memory and are bounded by the 64 persisted attempt rows. */
    private val live = linkedMapOf<String, AttemptTuple>()
    /** Keep process-local UUID tombstones so a retired callback cannot alias a same-process successor. */
    private val usedAttemptIds = linkedSetOf<String>()
    private var version = 0L
    private var unavailable = false

    init {
        // Construction is single-threaded. Decode and possible pending->unknown re-encryption happen
        // before publishing this instance, and never while holding the journal monitor.
        try {
            val encoded = preferences.getString(RECORD, null)
            val decoded = if (encoded == null) emptyList() else decode(encoded)
            val recovered = decoded.map { row ->
                if (row.state == MachineRuleAttemptState.pending) row.copy(state = MachineRuleAttemptState.unknown) else row
            }
            if (recovered != decoded) {
                when (val prepared = prepare(recovered.associateByTo(linkedMapOf()) { it.attemptId })) {
                    is Preparation.Ready -> {
                        if (!preferences.edit().putString(RECORD, prepared.encoded).commit()) unavailable = true
                        else version = 1L
                    }
                    Preparation.TooLarge, Preparation.Failed -> unavailable = true
                }
            }
            if (!unavailable) {
                records.putAll(recovered.associateBy { it.attemptId })
                usedAttemptIds.addAll(recovered.map { it.attemptId })
            }
        } catch (_: Exception) {
            unavailable = true
        }
    }

    override fun unresolved(ownerDigest: String, connectionIncarnation: String): List<MachineRuleAttempt>? = synchronized(journalMonitor) {
        if (!validOwner(ownerDigest, connectionIncarnation) || unavailable) return@synchronized null
        records.values.filter { it.ownerDigest == ownerDigest && it.connectionIncarnation == connectionIncarnation }.map(::project)
    }

    override fun unresolved(ownerDigest: String, connectionIncarnation: String, machineId: String): List<MachineRuleAttempt>? = synchronized(journalMonitor) {
        if (!validOwner(ownerDigest, connectionIncarnation) || !isValidMachineId(machineId) || unavailable) return@synchronized null
        records.values.filter {
            it.ownerDigest == ownerDigest && it.connectionIncarnation == connectionIncarnation && it.machineId == machineId
        }.map(::project)
    }

    override fun reserve(attempt: MachineRuleAttempt, finalCommit: MachineRuleAttemptFinalCommit): MachineRuleAttemptReservation {
        if (!valid(attempt)) return MachineRuleAttemptReservation.Unavailable
        val snapshot = snapshot() ?: return MachineRuleAttemptReservation.Unavailable
        if (snapshot.rows.values.any {
                it.ownerDigest == attempt.ownerDigest && it.connectionIncarnation == attempt.connectionIncarnation &&
                    it.machineId == attempt.machineId
            }) return MachineRuleAttemptReservation.Unresolved
        if (snapshot.rows.containsKey(attempt.attemptId) || snapshot.rows.size >= MAX_ROWS ||
            synchronized(journalMonitor) { attempt.attemptId in usedAttemptIds }) return MachineRuleAttemptReservation.Unavailable
        val candidate = LinkedHashMap(snapshot.rows).apply { put(attempt.attemptId, attempt.copy(state = MachineRuleAttemptState.pending)) }
        return when (finalCommit(snapshot, candidate, finalCommit, registerLive = tuple(attempt))) {
            CommitResult.Committed -> MachineRuleAttemptReservation.Reserved
            CommitResult.NotAdmitted -> MachineRuleAttemptReservation.Retired
            CommitResult.Stale, CommitResult.Refused, CommitResult.Unavailable -> MachineRuleAttemptReservation.Unavailable
        }
    }

    override fun markUnknown(ownerDigest: String, connectionIncarnation: String, attemptId: String): Boolean {
        if (!validOwner(ownerDigest, connectionIncarnation) || !ATTEMPT_ID.matches(attemptId)) return false
        // A concurrent mutation invalidates a prepared candidate. Re-snapshot and re-encrypt the
        // exact operation so a concurrent successor's rows are preserved, never overwritten.
        repeat(RETRY_LIMIT) {
            val snapshot = snapshot() ?: return false
            val row = snapshot.rows[attemptId]?.takeIf {
                it.ownerDigest == ownerDigest && it.connectionIncarnation == connectionIncarnation
            } ?: return false
            if (synchronized(journalMonitor) { live[attemptId] == tuple(row) }) return false
            if (row.state == MachineRuleAttemptState.unknown) return true
            val candidate = LinkedHashMap(snapshot.rows).apply {
                put(attemptId, row.copy(state = MachineRuleAttemptState.unknown))
            }
            when (commitUnadmitted(snapshot, candidate)) {
                CommitResult.Committed -> return true
                CommitResult.Stale -> Unit
                CommitResult.Refused, CommitResult.Unavailable, CommitResult.NotAdmitted -> return false
            }
        }
        return false
    }

    override fun finishLive(ownerDigest: String, connectionIncarnation: String, attemptId: String): Boolean = synchronized(journalMonitor) {
        if (!validOwner(ownerDigest, connectionIncarnation) || !ATTEMPT_ID.matches(attemptId) || unavailable) return@synchronized false
        val row = records[attemptId]?.takeIf { it.ownerDigest == ownerDigest && it.connectionIncarnation == connectionIncarnation }
            ?: return@synchronized false
        val expected = tuple(row)
        if (live[attemptId] != expected) return@synchronized false
        val nextVersion = try { Math.addExact(version, 1L) } catch (_: ArithmeticException) {
            unavailable = true
            return@synchronized false
        }
        live.remove(attemptId)
        version = nextVersion
        true
    }

    override fun settleKnown(
        ownerDigest: String,
        connectionIncarnation: String,
        attemptId: String,
        finalCommit: MachineRuleAttemptFinalCommit,
    ): Boolean = removeExact(ownerDigest, connectionIncarnation, attemptId, finalCommit, requireUnknown = false, requireInactive = false, clearLive = true)

    override fun acknowledgeReviewed(
        ownerDigest: String,
        connectionIncarnation: String,
        attemptId: String,
        finalCommit: MachineRuleAttemptFinalCommit,
    ): Boolean = removeExact(ownerDigest, connectionIncarnation, attemptId, finalCommit, requireUnknown = true, requireInactive = true, clearLive = false)

    private fun removeExact(
        ownerDigest: String,
        connectionIncarnation: String,
        attemptId: String,
        finalCommit: MachineRuleAttemptFinalCommit,
        requireUnknown: Boolean,
        requireInactive: Boolean,
        clearLive: Boolean,
    ): Boolean {
        if (!validOwner(ownerDigest, connectionIncarnation) || !ATTEMPT_ID.matches(attemptId)) return false
        val snapshot = snapshot() ?: return false
        val row = snapshot.rows[attemptId]?.takeIf {
            it.ownerDigest == ownerDigest && it.connectionIncarnation == connectionIncarnation &&
                (!requireUnknown || it.state == MachineRuleAttemptState.unknown ||
                    it.state == MachineRuleAttemptState.pending && !isLive(it)) &&
                (!requireInactive || !isLive(it))
        } ?: return false
        val candidate = LinkedHashMap(snapshot.rows).apply { remove(row.attemptId) }
        return finalCommit(snapshot, candidate, finalCommit, clearLive = if (clearLive) tuple(row) else null,
            requireInactive = if (requireInactive) tuple(row) else null) == CommitResult.Committed
    }

    /** Prepare encryption with no repository or journal lock held; only final persistence is guarded. */
    private fun finalCommit(
        snapshot: Snapshot,
        candidate: LinkedHashMap<String, MachineRuleAttempt>,
        admission: MachineRuleAttemptFinalCommit,
        registerLive: AttemptTuple? = null,
        clearLive: AttemptTuple? = null,
        requireInactive: AttemptTuple? = null,
    ): CommitResult {
        when (val preparation = prepare(candidate)) {
            Preparation.TooLarge -> return CommitResult.Unavailable
            Preparation.Failed -> {
                synchronized(journalMonitor) { if (version == snapshot.version && records == snapshot.rows) unavailable = true }
                return CommitResult.Unavailable
            }
            is Preparation.Ready -> {
                var outcome: CommitResult? = null
                val admitted = admission {
                    commitPrepared(snapshot, candidate, preparation.encoded, registerLive, clearLive, requireInactive)
                        .also { outcome = it } == CommitResult.Committed
                }
                return outcome ?: if (admitted) CommitResult.Refused else CommitResult.NotAdmitted
            }
        }
    }

    private fun commitUnadmitted(
        snapshot: Snapshot,
        candidate: LinkedHashMap<String, MachineRuleAttempt>,
    ): CommitResult = when (val preparation = prepare(candidate)) {
        Preparation.TooLarge -> CommitResult.Unavailable
        Preparation.Failed -> {
            synchronized(journalMonitor) { if (version == snapshot.version && records == snapshot.rows) unavailable = true }
            CommitResult.Unavailable
        }
        is Preparation.Ready -> commitPrepared(snapshot, candidate, preparation.encoded)
    }

    /** Caller enters through original authority first, then this method takes the journal monitor. */
    private fun commitPrepared(
        snapshot: Snapshot,
        candidate: LinkedHashMap<String, MachineRuleAttempt>,
        encoded: String,
        registerLive: AttemptTuple? = null,
        clearLive: AttemptTuple? = null,
        requireInactive: AttemptTuple? = null,
    ): CommitResult = synchronized(journalMonitor) {
        if (unavailable) return@synchronized CommitResult.Unavailable
        if (version != snapshot.version || records != snapshot.rows) return@synchronized CommitResult.Stale
        if (candidate.size > MAX_ROWS || candidate.values.any { !valid(it) }) return@synchronized CommitResult.Refused
        if (registerLive != null && (registerLive.attemptId in usedAttemptIds || live.size >= MAX_ROWS ||
                candidate[registerLive.attemptId]?.let(::tuple) != registerLive)) return@synchronized CommitResult.Refused
        if (clearLive != null && (records[clearLive.attemptId]?.let(::tuple) != clearLive || live[clearLive.attemptId] != clearLive)) {
            return@synchronized CommitResult.Refused
        }
        if (requireInactive != null && (records[requireInactive.attemptId]?.let(::tuple) != requireInactive ||
                live[requireInactive.attemptId] == requireInactive)) return@synchronized CommitResult.Refused
        val nextVersion = try { Math.addExact(version, 1L) } catch (_: ArithmeticException) {
            unavailable = true
            return@synchronized CommitResult.Unavailable
        }
        val committed = try { preferences.edit().putString(RECORD, encoded).commit() } catch (_: Exception) { false }
        if (!committed) {
            unavailable = true
            return@synchronized CommitResult.Unavailable
        }
        records.clear()
        records.putAll(candidate)
        registerLive?.let { live[it.attemptId] = it; usedAttemptIds.add(it.attemptId) }
        clearLive?.let { live.remove(it.attemptId) }
        version = nextVersion
        CommitResult.Committed
    }

    private fun snapshot(): Snapshot? = synchronized(journalMonitor) {
        if (unavailable) null else Snapshot(version, LinkedHashMap(records))
    }

    private fun isLive(row: MachineRuleAttempt): Boolean = synchronized(journalMonitor) { live[row.attemptId] == tuple(row) }

    private fun project(row: MachineRuleAttempt): MachineRuleAttempt {
        val isLive = live[row.attemptId] == tuple(row)
        return when {
            isLive -> row.copy(state = MachineRuleAttemptState.pending)
            row.state == MachineRuleAttemptState.pending -> row.copy(state = MachineRuleAttemptState.unknown, durablePendingWarning = true)
            else -> row.copy()
        }
    }

    private data class AttemptTuple(val attemptId: String, val ownerDigest: String, val connectionIncarnation: String)
    private fun tuple(row: MachineRuleAttempt) = AttemptTuple(row.attemptId, row.ownerDigest, row.connectionIncarnation)

    private fun decode(encoded: String): List<MachineRuleAttempt> {
        require(encoded.length <= MAX_ENVELOPE_BYTES)
        val envelope = json.decodeFromString(Envelope.serializer(), encoded)
        require(envelope.version == VERSION)
        val clear = cipher.decrypt(CipherEnvelope(
            Base64.decode(envelope.iv, Base64.NO_WRAP),
            Base64.decode(envelope.ciphertext, Base64.NO_WRAP),
        ))
        val decoded = json.decodeFromString(RecordList.serializer(), clear.decodeToString()).rows
        require(decoded.size <= MAX_ROWS && decoded.all(::valid))
        require(decoded.map { it.attemptId }.toSet().size == decoded.size)
        return decoded
    }

    private fun prepare(candidate: Map<String, MachineRuleAttempt>): Preparation {
        if (candidate.size > MAX_ROWS || candidate.values.any { !valid(it) }) return Preparation.TooLarge
        return try {
            val clear = json.encodeToString(RecordList.serializer(), RecordList(candidate.values.toList())).encodeToByteArray()
            val encrypted = cipher.encrypt(clear)
            val encoded = json.encodeToString(Envelope.serializer(), Envelope(
                VERSION,
                Base64.encodeToString(encrypted.iv, Base64.NO_WRAP),
                Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP),
            ))
            if (encoded.length <= MAX_ENVELOPE_BYTES) Preparation.Ready(encoded) else Preparation.TooLarge
        } catch (_: Exception) {
            Preparation.Failed
        }
    }

    private data class Snapshot(val version: Long, val rows: LinkedHashMap<String, MachineRuleAttempt>)
    private sealed interface Preparation {
        data class Ready(val encoded: String) : Preparation
        data object TooLarge : Preparation
        data object Failed : Preparation
    }
    private enum class CommitResult { Committed, Stale, NotAdmitted, Refused, Unavailable }

    @Serializable
    private data class RecordList(val rows: List<MachineRuleAttempt>)

    @Serializable
    private data class Envelope(val version: Int, val iv: String, val ciphertext: String)

    companion object {
        private const val PREFERENCES = "machine_rule_attempts_v1"
        private const val RECORD = "encrypted_attempts"
        private const val VERSION = 1
        private const val MAX_ROWS = 64
        private const val MAX_ENVELOPE_BYTES = 64 * 1024
        private const val RETRY_LIMIT = 16
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val CONNECTION_INCARNATION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val MACHINE_INCARNATION = Regex("[0-9a-f]{64}")
        private val RULE_REVISION = Regex("(?:0|[1-9][0-9]{0,19})")
        private val ATTEMPT_ID = Regex("[0-9a-f]{32}")

        private fun validOwner(ownerDigest: String, connectionIncarnation: String): Boolean =
            DIGEST.matches(ownerDigest) && CONNECTION_INCARNATION.matches(connectionIncarnation)

        private fun valid(attempt: MachineRuleAttempt): Boolean =
            ATTEMPT_ID.matches(attempt.attemptId) && validOwner(attempt.ownerDigest, attempt.connectionIncarnation) &&
                isValidMachineId(attempt.machineId) && MACHINE_INCARNATION.matches(attempt.machineIncarnation) &&
                RULE_REVISION.matches(attempt.rulesRevision) && runCatching { attempt.rulesRevision.toULong() }.isSuccess
    }
}
