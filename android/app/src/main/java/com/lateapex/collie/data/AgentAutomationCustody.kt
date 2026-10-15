package com.lateapex.collie.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.lateapex.collie.domain.PaneAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Serializable
data class StoredAgentAction(
    val id: String, val owner: ScopedCustodyVersion, val host: String?, val session: String?, val paneId: String,
    val terminalId: String, val runtimeIdentity: String, val witness: String, val bodyHash: String,
    val verb: String, val state: String,
) {
    override fun toString() = "StoredAgentAction(id=$id, verb=$verb, state=$state, privateCustody=<redacted>)"
}
@Serializable private data class AgentRetirementFence(val version: ScopedCustodyVersion, val wholeIncarnation: Boolean)
@Serializable private data class AgentCustodyJournal(val schema: Int = 1, val rows: List<StoredAgentAction>, val retired: List<AgentRetirementFence>)
@Serializable private data class AgentCustodyEnvelope(val schema: Int = 1, val iv: String, val ciphertext: String)

/** Opaque one-shot intent custody only. Prompt/argv/output/bearers never enter this journal or Bundle.
 * All callbacks and transport happen outside this monitor. Retirement records zero-row owners too. */
class AgentAutomationCustody(
    context: Context,
    private val cipher: SecretCipher = AndroidKeystoreCipher(alias = "com.lateapex.collie.agent-actions.v1", associatedData = "com.lateapex.collie.agent-actions.v1".toByteArray()),
    private val json: Json = Json,
    preferencesOverride: SharedPreferences? = null,
) {
    private val preferences = preferencesOverride ?: context.getSharedPreferences("agent-actions-v1", Context.MODE_PRIVATE)
    private val rows = linkedMapOf<String, StoredAgentAction>()
    private val retired = linkedSetOf<AgentRetirementFence>()
    private var unavailable = false
    init {
        try {
            preferences.getString("sealed", null)?.let { encoded ->
                require(encoded.length <= 100_000)
                val envelope = json.decodeFromString(AgentCustodyEnvelope.serializer(), encoded)
                require(envelope.schema == 1 && envelope.iv.length == 16 && envelope.ciphertext.length in 24..90_000)
                val iv = Base64.decode(envelope.iv, Base64.NO_WRAP); val ciphertext = Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
                require(iv.size == 12 && ciphertext.size >= 16 && Base64.encodeToString(iv, Base64.NO_WRAP) == envelope.iv && Base64.encodeToString(ciphertext, Base64.NO_WRAP) == envelope.ciphertext)
                val clear = cipher.decrypt(CipherEnvelope(iv, ciphertext)); require(clear.size <= 65_536)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(clear)).toString()
                val journal = json.decodeFromString(AgentCustodyJournal.serializer(), text)
                require(journal.schema == 1 && journal.rows.size <= 64 && journal.retired.size <= 128)
                for (record in journal.rows) { require(valid(record) && rows.put(record.id, if (record.state == "pending") record.copy(state = "unknown") else record) == null) }
                retired.addAll(journal.retired); require(retired.size == journal.retired.size)
                persist(rows.values.toList(), retired.toList())
            }
        } catch (_: Exception) { unavailable = true; rows.clear(); retired.clear() }
    }
    @Synchronized fun get(id: String): StoredAgentAction? { check(!unavailable) { "Agent action custody is unreadable; original encrypted bytes were preserved" }; return rows[id] }
    @Synchronized fun owned(context: ScopedReadContext, address: PaneAddress): List<StoredAgentAction> {
        check(!unavailable) { "Agent action custody is unreadable" }
        return rows.values.filter { matches(it, context, address) }
    }
    @Synchronized fun reserve(record: StoredAgentAction): Boolean {
        check(!unavailable) { "Agent action custody is unreadable" }; require(valid(record) && record.state == "pending")
        if (rows.containsKey(record.id) || retired.any { retiredOwner -> matchesVersion(retiredOwner, record.owner) } || rows.values.any { it.owner.owner == record.owner.owner && it.owner.connectionIncarnation == record.owner.connectionIncarnation && it.host == record.host && it.session == record.session && it.paneId == record.paneId && it.state in setOf("pending", "unknown") }) return false
        if (rows.size >= 64) return false
        val next = rows.values.toList() + record
        try { persist(next, retired.toList()); rows[record.id] = record; return true }
        catch (_: Exception) { unavailable = true; return false }
    }
    @Synchronized fun finish(original: StoredAgentAction, state: String): Boolean {
        check(!unavailable) { "Agent action custody is unreadable" }; require(state in setOf("succeeded", "refused", "unknown", "abandoned"))
        val held = rows[original.id] ?: return false
        if (held.owner != original.owner || held.witness != original.witness || held.bodyHash != original.bodyHash || held.state !in setOf("pending", "unknown")) return false
        val next = held.copy(state = state)
        try { persist(rows.values.map { if (it.id == held.id) next else it }, retired.toList()); rows[held.id] = next; return true }
        catch (_: Exception) { unavailable = true; return false }
    }
    @Synchronized fun retire(event: AuthorityRetirement) {
        if (unavailable || event.reason == AuthorityInvalidation.ACCESS_FROZEN && !event.retiresReadActor) return
        val affected = rows.values.filter { event.affectsCustody(it.owner) }
        // Authority retirement disarms the owner; it never proves that accepted bytes had no effect.
        val next = rows.values.map { if (it in affected && it.state in setOf("pending", "unknown")) it.copy(state = "unknown") else it }
        // Original read actors cannot reserve late even when their first row has not reached disk.
        val original = event.custodyVersion ?: return
        val wholeIncarnation = original.connectionIncarnation != null && event.reason in setOf(AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.DISCONNECTED)
        val fence = AgentRetirementFence(original, wholeIncarnation)
        val remaining = retired.filterNot { held -> held.version.owner == original.owner && held.version.connectionIncarnation == original.connectionIncarnation && (wholeIncarnation || !held.wholeIncarnation && held.version.processIdentity == original.processIdentity && held.version.connectionGeneration == original.connectionGeneration && held.version.navigationGeneration <= original.navigationGeneration) }
        val retiredNext = (remaining + fence).toSet()
        if (retiredNext.size > 128) { unavailable = true; return }
        try { persist(next, retiredNext.toList()); rows.clear(); next.forEach { rows[it.id] = it }; retired.clear(); retired.addAll(retiredNext) }
        catch (_: Exception) { unavailable = true }
        // The owner's next incarnation is deliberately not blocked by old version records.
    }
    /** Captured before reserve, registered independently of an Activity/modal lifetime. */
    @Synchronized fun track(context: ScopedReadContext): Boolean {
        if (unavailable || retired.any { matchesVersion(it, context.custodyVersion) }) return false
        return true
    }
    @Synchronized fun clearSettled(context: ScopedReadContext, address: PaneAddress): Boolean {
        if (unavailable) return false
        val next = rows.values.filterNot { matches(it, context, address) && it.state in setOf("succeeded", "refused", "abandoned", "retired") }
        return try { persist(next, retired.toList()); rows.clear(); next.forEach { rows[it.id] = it }; true } catch (_: Exception) { unavailable = true; false }
    }
    private fun matches(record: StoredAgentAction, context: ScopedReadContext, address: PaneAddress): Boolean = record.owner.owner == context.custodyOwner && record.owner.connectionIncarnation == context.custodyVersion.connectionIncarnation && (record.owner.connectionIncarnation != null || record.owner.processIdentity == context.custodyVersion.processIdentity) && record.host == address.scope.host && record.session == address.scope.session && record.paneId == address.paneId
    private fun matchesVersion(retired: AgentRetirementFence, proposed: ScopedCustodyVersion): Boolean = retired.version.owner == proposed.owner && retired.version.connectionIncarnation == proposed.connectionIncarnation && (retired.wholeIncarnation || retired.version.processIdentity == proposed.processIdentity && retired.version.connectionGeneration == proposed.connectionGeneration && proposed.navigationGeneration <= retired.version.navigationGeneration)
    private fun valid(record: StoredAgentAction): Boolean = record.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && record.paneId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && record.terminalId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && record.runtimeIdentity.length in 1..128 && listOf(record.witness, record.bodyHash).all { it.matches(Regex("[a-f0-9]{64}")) } && record.verb in setOf("start", "rename", "prompt", "focus", "keys", "input") && record.state in setOf("pending", "unknown", "succeeded", "refused", "abandoned", "retired") && listOf(record.host, record.session).all { it == null || it.length in 1..256 && it.none { char -> char.code < 32 } }
    private fun persist(next: List<StoredAgentAction>, retiredNext: List<AgentRetirementFence>) {
        val clear = json.encodeToString(AgentCustodyJournal.serializer(), AgentCustodyJournal(rows = next, retired = retiredNext)).toByteArray(Charsets.UTF_8)
        require(clear.size <= 65_536)
        val sealed = cipher.encrypt(clear); require(sealed.iv.size == 12 && sealed.ciphertext.size >= 16)
        val envelope = AgentCustodyEnvelope(iv = Base64.encodeToString(sealed.iv, Base64.NO_WRAP), ciphertext = Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP))
        val encoded = json.encodeToString(AgentCustodyEnvelope.serializer(), envelope); require(encoded.length <= 100_000)
        check(preferences.edit().putString("sealed", encoded).commit()) { "Agent action custody did not commit" }
    }
}
