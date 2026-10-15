package com.lateapex.collie.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

@Serializable
data class PaneTransferOwner(
    val origin: String, val deviceIdentity: String, val host: String?, val session: String?, val subject: String?,
    val sourceWorkspaceId: String, val sourceTabId: String,
)
@Serializable
data class PaneTransferMember(val originalPaneId: String, val witness: String, val attemptIds: List<String> = emptyList())
@Serializable
data class PaneTransferMapping(val originalPaneId: String, val currentPaneId: String, val workspaceId: String, val tabId: String, val witness: String)
@Serializable
data class PaneTransferRecipeStep(val direction: String, val ratio: Double)
@Serializable
data class PaneTransferLayoutCell(val paneId: String, val focused: Boolean, val x: Int, val y: Int, val width: Int, val height: Int)
@Serializable
data class PaneTransferLayoutSplit(val direction: String, val ratio: Double, val x: Int, val y: Int, val width: Int, val height: Int)
@Serializable
data class PaneTransferLayout(val x: Int, val y: Int, val width: Int, val height: Int, val zoomed: Boolean, val focusedPaneId: String,
    val cells: List<PaneTransferLayoutCell>, val splits: List<PaneTransferLayoutSplit>)
@Serializable
data class PaneTransferIntent(
    val owner: PaneTransferOwner, val connectionGeneration: Long, val leadEpoch: Long, val transferId: String,
    val destinationType: String, val destinationWorkspaceId: String? = null, val destinationLabel: String? = null, val destinationTabLabel: String? = null,
    val recipe: List<PaneTransferRecipeStep>, val sourceMembers: List<PaneTransferMember>, val mappings: List<PaneTransferMapping> = emptyList(),
    val activeChild: PaneLayoutIntent? = null, val state: String = "ready",
    val sourceLayout: PaneTransferLayout? = null,
)
@Serializable
data class PaneTransferStepResult(
    val changed: Boolean, val currentPaneId: String, val targetWorkspaceId: String, val targetTabId: String, val processWitness: String,
    val createdTabId: String? = null, val createdWorkspaceId: String? = null, val closedTabId: String? = null, val closedWorkspaceId: String? = null,
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
    private val transfers = linkedMapOf<String, PaneTransferIntent>()

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
    fun transfer(owner: PaneTransferOwner): PaneTransferIntent? {
        if (!load() && !loaded) return null
        return transfers[transferOwnerKey(owner)]?.takeIf { it.owner == owner }
    }

    /** Create the parent in the same encrypted journal as pane operations; no child has run yet. */
    @Synchronized
    fun beginTransfer(intent: PaneTransferIntent): Boolean {
        if (unavailable || recoveryRequired || (!load() && !loaded) || !validTransfer(intent) || intent.state != "ready" || intent.activeChild != null || intent.mappings.isNotEmpty()) return false
        val key = transferOwnerKey(intent.owner)
        if (transfers[key] != null || transfers.size >= MAX_TRANSFER_ROWS) return false
        transfers[key] = intent
        if (!persist()) { transfers.remove(key); recoveryRequired = true; return false }
        return true
    }

    /** Durably latch the one exact child before its POST. A restarted pending child becomes unknown. */
    @Synchronized
    fun reserveTransferChild(owner: PaneTransferOwner, transferId: String, child: PaneLayoutIntent): Boolean {
        if (unavailable || recoveryRequired || !load()) return false
        val key = transferOwnerKey(owner)
        val parent = transfers[key]?.takeIf { it.owner == owner && it.transferId == transferId && it.state == "ready" && it.activeChild == null } ?: return false
        if (!valid(child) || child.owner.origin != owner.origin || child.owner.deviceIdentity != owner.deviceIdentity || child.owner.host != owner.host ||
            child.owner.session != owner.session || child.owner.subject != owner.subject || parent.sourceMembers.sumOf { it.attemptIds.size } >= MAX_TRANSFER_ATTEMPTS) return false
        val member = parent.sourceMembers.firstOrNull { row -> (parent.mappings.find { it.originalPaneId == row.originalPaneId }?.currentPaneId ?: row.originalPaneId) == child.owner.paneId } ?: return false
        if (parent.mappings.any { it.originalPaneId == member.originalPaneId }) return false
        if (child.requestId in member.attemptIds) return false
        val updated = parent.copy(sourceMembers = parent.sourceMembers.map { if (it.originalPaneId == member.originalPaneId) it.copy(attemptIds = it.attemptIds + child.requestId) else it },
            activeChild = child, state = "pending")
        if (!validTransfer(updated)) return false
        transfers[key] = updated
        if (!persist()) { transfers[key] = updated.copy(activeChild = child.copy(state = "unknown"), state = "unknown"); recoveryRequired = true; return false }
        return true
    }

    /** Commit the returned ID mapping and child resolution as one journal replacement. */
    @Synchronized
    fun completeTransferChild(owner: PaneTransferOwner, transferId: String, requestId: String, result: PaneTransferStepResult): Boolean {
        if (unavailable || recoveryRequired || !load() || !result.changed) return false
        val key = transferOwnerKey(owner)
        val parent = transfers[key]?.takeIf { it.owner == owner && it.transferId == transferId } ?: return false
        val child = parent.activeChild?.takeIf { it.requestId == requestId && it.state in setOf("pending", "unknown") } ?: return false
        val originalId = child.owner.paneId
        val member = parent.sourceMembers.find { it.originalPaneId == originalId } ?: return false
        val first = parent.mappings.firstOrNull()
        val isFirst = first == null
        val isLast = parent.mappings.size + 1 == parent.sourceMembers.size
        val destinationMatches = if (isFirst) {
            when (parent.destinationType) {
                "new_tab" -> result.targetWorkspaceId == parent.destinationWorkspaceId && result.createdTabId == result.targetTabId && result.createdWorkspaceId == null
                "new_workspace" -> result.createdWorkspaceId == result.targetWorkspaceId && result.createdTabId == result.targetTabId && result.targetWorkspaceId != owner.sourceWorkspaceId
                else -> false
            }
        } else {
            result.targetWorkspaceId == first.workspaceId && result.targetTabId == first.tabId &&
                result.createdTabId == null && result.createdWorkspaceId == null
        }
        val closeAliasesMatch = result.closedWorkspaceId == null &&
            (result.closedTabId == null || (isLast && result.closedTabId == owner.sourceTabId))
        if (member.witness != result.processWitness || parent.mappings.any { it.originalPaneId == originalId || it.currentPaneId == result.currentPaneId } ||
            !validId(result.currentPaneId) || !validId(result.targetTabId) || !validId(result.targetWorkspaceId) ||
            !destinationMatches || !closeAliasesMatch) return false
        val updated = parent.copy(mappings = parent.mappings + PaneTransferMapping(originalId, result.currentPaneId, result.targetWorkspaceId, result.targetTabId, result.processWitness),
            destinationWorkspaceId = parent.destinationWorkspaceId ?: result.targetWorkspaceId,
            activeChild = null, state = if (parent.mappings.size + 1 == parent.sourceMembers.size) "complete" else "ready")
        if (!validTransfer(updated)) return false
        transfers[key] = updated
        if (!persist()) {
            transfers[key] = parent.copy(activeChild = child.copy(state = "unknown"), state = "unknown")
            recoveryRequired = true
            persist()
            return false
        }
        return true
    }

    @Synchronized
    fun markTransferUnknown(owner: PaneTransferOwner, transferId: String, requestId: String): Boolean {
        if (!load() && !loaded) return false
        val key = transferOwnerKey(owner)
        val parent = transfers[key]?.takeIf { it.owner == owner && it.transferId == transferId && it.activeChild?.requestId == requestId } ?: return false
        transfers[key] = parent.copy(activeChild = parent.activeChild?.copy(state = "unknown"), state = "unknown")
        if (!persist()) { recoveryRequired = true; return false }
        return true
    }

    @Synchronized
    fun finishRefusedTransferChild(owner: PaneTransferOwner, transferId: String, requestId: String): Boolean {
        if (unavailable || recoveryRequired || !load()) return false
        val key = transferOwnerKey(owner)
        val parent = transfers[key]?.takeIf { it.owner == owner && it.transferId == transferId && it.state in setOf("pending", "unknown") && it.activeChild?.requestId == requestId } ?: return false
        transfers[key] = parent.copy(activeChild = null, state = "ready")
        if (!persist()) {
            transfers[key] = parent.copy(activeChild = parent.activeChild?.copy(state = "unknown"), state = "unknown")
            recoveryRequired = true
            persist()
            return false
        }
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
            val decoded = json.decodeFromString(RecordList.serializer(), clear.decodeToString())
            val records = decoded.rows
            require(records.size <= MAX_ROWS && records.all(::valid) && decoded.transfers.size <= MAX_TRANSFER_ROWS && decoded.transfers.all(::validTransfer))
            require(records.map { ownerKey(it.owner) }.distinct().size == records.size)
            require(decoded.transfers.map { transferOwnerKey(it.owner) }.distinct().size == decoded.transfers.size)
            records.forEach { rows[ownerKey(it.owner)] = it.copy(state = "unknown") }
            decoded.transfers.forEach { row ->
                val restored = if (row.activeChild != null) row.copy(activeChild = row.activeChild.copy(state = "unknown"), state = "unknown") else row
                transfers[transferOwnerKey(row.owner)] = restored
            }
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
            val clear = json.encodeToString(RecordList.serializer(), RecordList(rows.values.toList(), transfers.values.toList())).encodeToByteArray()
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

    private fun validTransfer(row: PaneTransferIntent): Boolean {
        val owner = row.owner
        val layout = row.sourceLayout ?: return false
        if (owner.origin.isBlank() || owner.origin.length > 2048 || !owner.deviceIdentity.matches(Regex("[a-f0-9]{64}")) ||
            (owner.host != null && owner.host.length !in 1..256) || (owner.session != null && owner.session.length !in 1..256) || (owner.subject != null && owner.subject.length !in 1..256) ||
            !validId(owner.sourceWorkspaceId) || !validId(owner.sourceTabId) || row.connectionGeneration < 0 || row.leadEpoch < 0 || !row.transferId.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
            layout.x < 0 || layout.y < 0 || layout.width !in 1..100_000 || layout.height !in 1..100_000 ||
            layout.x.toLong() + layout.width > 100_000 || layout.y.toLong() + layout.height > 100_000 || !validId(layout.focusedPaneId) || layout.cells.size !in 1..64 ||
            layout.cells.map { it.paneId } != row.sourceMembers.map { it.originalPaneId } || layout.cells.map { it.paneId }.distinct().size != layout.cells.size ||
            layout.cells.count { it.focused } != 1 || layout.cells.none { it.paneId == layout.focusedPaneId && it.focused } || layout.cells.any { cell ->
                !validId(cell.paneId) || cell.x < layout.x || cell.y < layout.y || cell.width < 1 || cell.height < 1 ||
                    cell.x.toLong() + cell.width > layout.x.toLong() + layout.width || cell.y.toLong() + cell.height > layout.y.toLong() + layout.height
            } || layout.splits.size > 63 || layout.splits.any { split ->
                split.direction !in setOf("right", "down") || !split.ratio.isFinite() || split.ratio <= 0.0 || split.ratio >= 1.0 ||
                    split.x < layout.x || split.y < layout.y || split.width < 1 || split.height < 1 ||
                    split.x.toLong() + split.width > layout.x.toLong() + layout.width || split.y.toLong() + split.height > layout.y.toLong() + layout.height
            } ||
            row.destinationType !in setOf("new_tab", "new_workspace") || (row.destinationWorkspaceId != null && !validId(row.destinationWorkspaceId)) ||
            (row.destinationType == "new_tab" && (row.destinationWorkspaceId == null || row.destinationWorkspaceId == owner.sourceWorkspaceId)) ||
            (row.destinationType == "new_tab" && row.destinationTabLabel != null) ||
            (row.destinationType == "new_workspace" && row.mappings.isEmpty() && row.destinationWorkspaceId != null) ||
            (row.destinationType == "new_workspace" && (row.destinationLabel.isNullOrBlank() || row.destinationTabLabel.isNullOrBlank())) ||
            (row.destinationLabel != null && (row.destinationLabel.length > 128 || row.destinationLabel.any(Char::isISOControl))) ||
            (row.destinationTabLabel != null && (row.destinationTabLabel.length > 128 || row.destinationTabLabel.any(Char::isISOControl))) ||
            row.recipe.size != row.sourceMembers.size - 1 || row.recipe.any { it.direction !in setOf("right", "down") || !it.ratio.isFinite() || it.ratio <= 0.0 || it.ratio >= 1.0 } ||
            row.sourceMembers.size !in 1..64 || row.sourceMembers.map { it.originalPaneId }.distinct().size != row.sourceMembers.size ||
            row.sourceMembers.any { !validId(it.originalPaneId) || !it.witness.matches(Regex("[a-f0-9]{64}")) || it.attemptIds.size > MAX_TRANSFER_ATTEMPTS || it.attemptIds.distinct().size != it.attemptIds.size || it.attemptIds.any { id -> !id.matches(Regex("[A-Za-z0-9_-]{1,128}")) } } ||
            row.sourceMembers.sumOf { it.attemptIds.size } > MAX_TRANSFER_ATTEMPTS ||
            row.mappings.size > row.sourceMembers.size || row.mappings.map { it.originalPaneId }.distinct().size != row.mappings.size || row.mappings.map { it.currentPaneId }.distinct().size != row.mappings.size ||
            row.mappings.indices.any { row.mappings[it].originalPaneId != row.sourceMembers[it].originalPaneId } ||
            row.mappings.any { mapping -> !validId(mapping.originalPaneId) || !validId(mapping.currentPaneId) || mapping.currentPaneId == mapping.originalPaneId || !validId(mapping.workspaceId) || !validId(mapping.tabId) || !mapping.witness.matches(Regex("[a-f0-9]{64}")) || row.sourceMembers.none { it.originalPaneId == mapping.originalPaneId && it.witness == mapping.witness } } ||
            row.mappings.any { it.workspaceId != row.mappings.firstOrNull()?.workspaceId || it.tabId != row.mappings.firstOrNull()?.tabId } ||
            (row.activeChild != null && !valid(row.activeChild)) || row.state !in setOf("ready", "pending", "unknown", "complete", "abandoned") ||
            ((row.state in setOf("pending", "unknown")) != (row.activeChild != null)) ||
            (row.state == "complete" && row.mappings.size != row.sourceMembers.size) ||
            (row.state == "ready" && row.mappings.size >= row.sourceMembers.size) ||
            (row.mappings.isNotEmpty() && row.destinationWorkspaceId != row.mappings.first().workspaceId) ||
            (row.activeChild != null && !validTransferChild(row, row.activeChild)) ||
            row.mappings.size + (if (row.activeChild == null) 0 else 1) > row.sourceMembers.size) return false
        return true
    }

    private fun validTransferChild(parent: PaneTransferIntent, child: PaneLayoutIntent): Boolean {
        val owner = parent.owner
        if (child.owner.origin != owner.origin || child.owner.deviceIdentity != owner.deviceIdentity || child.owner.host != owner.host ||
            child.owner.session != owner.session || child.owner.subject != owner.subject || child.sourceTabId != owner.sourceTabId ||
            child.sourceWorkspaceId != owner.sourceWorkspaceId || parent.sourceMembers.none { member ->
                member.originalPaneId == child.owner.paneId && parent.mappings.none { it.originalPaneId == member.originalPaneId }
            }) return false
        val action = runCatching { json.parseToJsonElement(child.actionJson) as? JsonObject }.getOrNull() ?: return false
        if (action.keys != setOf("kind", "destination", "transfer") || (action["kind"] as? JsonPrimitive)?.contentOrNull != "move") return false
        val guard = action["transfer"] as? JsonObject ?: return false
        if (guard.keys != setOf("schema", "transferId", "sourceWorkspaceId", "sourceTabId", "remainingSource", "destination") ||
            (guard["schema"] as? JsonPrimitive)?.contentOrNull != "1" ||
            (guard["transferId"] as? JsonPrimitive)?.contentOrNull != parent.transferId ||
            (guard["sourceWorkspaceId"] as? JsonPrimitive)?.contentOrNull != owner.sourceWorkspaceId ||
            (guard["sourceTabId"] as? JsonPrimitive)?.contentOrNull != owner.sourceTabId) return false
        val remaining = guard["remainingSource"] as? JsonArray ?: return false
        val expectedRemaining = parent.sourceMembers.filter { member -> parent.mappings.none { it.originalPaneId == member.originalPaneId } }
        if (remaining.size != expectedRemaining.size || remaining.mapNotNull { value ->
                val row = value as? JsonObject ?: return@mapNotNull null
                val paneId = (row["paneId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val witness = (row["witness"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                paneId to witness
            }.toSet() != expectedRemaining.map { it.originalPaneId to it.witness }.toSet() || remaining.any { value ->
                val row = value as? JsonObject ?: return@any true
                row.keys != setOf("paneId", "witness") || expectedRemaining.none {
                    it.originalPaneId == (row["paneId"] as? JsonPrimitive)?.contentOrNull && it.witness == (row["witness"] as? JsonPrimitive)?.contentOrNull
                }
            }) return false
        val destination = action["destination"] as? JsonObject ?: return false
        val first = parent.mappings.firstOrNull()
        if (first == null) {
            if (guard["destination"] != kotlinx.serialization.json.JsonNull) return false
            val type = (destination["type"] as? JsonPrimitive)?.contentOrNull
            if (type != parent.destinationType) return false
            if (type == "new_tab" && (destination.keys != setOf("type", "workspaceId", "label") ||
                    (destination["workspaceId"] as? JsonPrimitive)?.contentOrNull != parent.destinationWorkspaceId ||
                    (destination["label"] as? JsonPrimitive)?.contentOrNull != parent.destinationLabel)) return false
            if (type == "new_workspace" && (destination.keys != setOf("type", "label", "tabLabel") ||
                    (destination["label"] as? JsonPrimitive)?.contentOrNull != parent.destinationLabel ||
                    (destination["tabLabel"] as? JsonPrimitive)?.contentOrNull != parent.destinationTabLabel)) return false
        } else {
            val expectedDestination = guard["destination"] as? JsonObject ?: return false
            if (expectedDestination.keys != setOf("workspaceId", "tabId", "members") ||
                (expectedDestination["workspaceId"] as? JsonPrimitive)?.contentOrNull != first.workspaceId ||
                (expectedDestination["tabId"] as? JsonPrimitive)?.contentOrNull != first.tabId ||
                (destination["type"] as? JsonPrimitive)?.contentOrNull != "tab" ||
                (destination["tabId"] as? JsonPrimitive)?.contentOrNull != first.tabId ||
                (destination["targetPaneId"] as? JsonPrimitive)?.contentOrNull !in parent.mappings.map { it.currentPaneId }) return false
            // The first move creates the destination container and consumes no split.
            // Recipe element 0 places the second pane, after one mapping exists.
            val step = parent.recipe.getOrNull(parent.mappings.size - 1) ?: return false
            if ((destination["split"] as? JsonPrimitive)?.contentOrNull != step.direction ||
                (destination["ratio"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() != step.ratio) return false
            val members = expectedDestination["members"] as? JsonArray ?: return false
            if (members.size != parent.mappings.size || members.mapNotNull { value ->
                    val row = value as? JsonObject ?: return@mapNotNull null
                    val paneId = (row["paneId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val witness = (row["witness"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    paneId to witness
                }.toSet() != parent.mappings.map { it.currentPaneId to it.witness }.toSet() || members.any { value ->
                    val row = value as? JsonObject ?: return@any true
                    row.keys != setOf("paneId", "witness") || parent.mappings.none {
                        it.currentPaneId == (row["paneId"] as? JsonPrimitive)?.contentOrNull && it.witness == (row["witness"] as? JsonPrimitive)?.contentOrNull
                    }
                }) return false
        }
        return true
    }

    private fun validId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))

    private fun ownerKey(owner: PaneLayoutIntentOwner): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(PaneLayoutIntentOwner.serializer(), owner).encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun transferOwnerKey(owner: PaneTransferOwner): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(PaneTransferOwner.serializer(), owner).encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Serializable private data class RecordList(val rows: List<PaneLayoutIntent>, val transfers: List<PaneTransferIntent> = emptyList())
    @Serializable private data class Envelope(val version: Int, val iv: String, val ciphertext: String)

    companion object {
        private const val PREFERENCES = "collie_layout_intents"
        private const val RECORD = "encrypted_journal"
        private const val VERSION = 1
        private const val MAX_ROWS = 64
        private const val MAX_TRANSFER_ROWS = 16
        private const val MAX_TRANSFER_ATTEMPTS = 256
        private const val MAX_ACTION_BYTES = 16 * 1024
        private const val MAX_CLEAR_BYTES = 1024 * 1024
        private const val IV_BYTES = 12
        private const val IV_BASE64_CHARS = 16
        private const val MAX_CIPHERTEXT_BYTES = MAX_CLEAR_BYTES + 16
        private const val MAX_CIPHERTEXT_BASE64_CHARS = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4
        private const val MAX_ENVELOPE_CHARS = MAX_CIPHERTEXT_BASE64_CHARS + 128
    }
}
