package com.lateapex.collie.data

import android.content.Context
import android.util.Base64
import com.lateapex.collie.auth.ProxyProfile
import com.lateapex.collie.auth.ProxyBindingRetirement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
class ProxyTokens(val accessToken: String, val refreshToken: String?, val subject: String, val expiresAt: Long,
    val thumbprint: String, val nonce: String, val bindingId: String? = null, val bindingRevision: Long? = null) {
    override fun toString() = "ProxyTokens(<redacted>)"
}
@Serializable
class ProxyTransaction(val state: String, val nonce: String, val verifier: String, val expiresAt: Long,
    val connectionIdentity: String, val connectionGeneration: Long, val processIdentity: String, val custody: ScopedCustodyVersion? = null) {
    override fun toString() = "ProxyTransaction(<redacted>)"
}
/** The enrollment witness survives access-token expiry and same-identity refresh. */
@Serializable
internal data class ProxyBindingWitness(val issuer: String, val subject: String, val thumbprint: String,
    val bindingId: String, val revision: Long)
internal class ProxyCustodyLease internal constructor(internal val epoch: Long, internal val revision: String?)
@Serializable
internal class ProxyRecord(val schema: Int = 1, val origin: String, val connectionIdentity: String, val profile: String,
    val tokens: ProxyTokens? = null, val transaction: ProxyTransaction? = null, val revision: String = java.util.UUID.randomUUID().toString(), val binding: ProxyBindingWitness? = null, val custody: ScopedCustodyVersion? = null) {
    override fun toString() = "ProxyRecord(<redacted>)"
}
@Serializable
private data class ProxyEnvelope(val version: Int = 1, val iv: String, val ciphertext: String)

/** Identity credentials never share pairing ciphertext or the pairing key. Synchronous commit is
 * the consume-before-exchange boundary; no callbacks/network/view work occur under this monitor. */
class ProxyIdentityStore(context: Context, private val cipher: SecretCipher, private val json: Json,
    preferencesOverride: android.content.SharedPreferences? = null) {
    private val prefs = preferencesOverride ?: context.applicationContext.getSharedPreferences("collie_proxy_identity_v1", Context.MODE_PRIVATE)
    private var epoch = 0L
    private var unavailable = false
    private fun checkAvailable() { check(!unavailable) { "Identity custody unavailable" } }
    @Synchronized internal fun capture(): Pair<ProxyCustodyLease, ProxyRecord?> {
        val record = record(); return ProxyCustodyLease(epoch, record?.revision) to record
    }
    @Synchronized internal fun current(lease: ProxyCustodyLease): Boolean = !unavailable && epoch == lease.epoch && record()?.revision == lease.revision
    /** Called after outside-lock original actor admission. Version adoption changes only a record
     * whose origin/incarnation-bound identity already matches; no foreign profile/token is borrowed. */
    @Synchronized internal fun captureOwned(actor: ScopedReadContext): Pair<ProxyCustodyLease, ProxyRecord?> {
        checkAvailable()
        var record = record()
        if (record != null && record.origin == actor.key.origin && record.connectionIdentity == actor.key.pairedDeviceIdentity && record.custody != actor.custodyVersion) {
            val previous = record.custody
            val proposed = actor.custodyVersion
            check(previous == null || (previous.connectionIncarnation == proposed.connectionIncarnation &&
                (previous.processIdentity != proposed.processIdentity || proposed.connectionGeneration > previous.connectionGeneration ||
                    (proposed.connectionGeneration == previous.connectionGeneration && proposed.navigationGeneration >= previous.navigationGeneration)))) {
                "Identity actor retired"
            }
            val next = record.copyCustody(custody = proposed)
            write(next)
            record = next
        }
        return ProxyCustodyLease(epoch, record?.revision) to record
    }
    /** Pure copied-owner comparison under this journal's monitor. Commit precedes lease retirement.
     * Legacy unstamped records can prove only their durable owner, never a newer navigation. */
    @Synchronized internal fun retireOwned(event: AuthorityRetirement, removeRecord: Boolean = false): Boolean {
        checkAvailable()
        val record = record() ?: return false
        val matches = if (record.custody != null) event.affectsCustody(record.custody)
            else event.owner?.let { it.origin == record.origin && it.pairedDeviceIdentity == record.connectionIdentity } == true
        if (!matches) return false
        try {
            if (removeRecord) {
                val editor = prefs.edit().remove("record")
                val bindingId = record.binding?.bindingId ?: record.tokens?.bindingId
                val bindingRevision = record.binding?.revision ?: record.tokens?.bindingRevision
                if (bindingId != null && bindingRevision != null) {
                    require(bindingId.matches(Regex("[A-Za-z0-9_-]{16,64}")) && bindingRevision > 0)
                    val pending = pendingRetirements().toMutableList()
                    val retirement = ProxyBindingRetirement(bindingId, bindingRevision)
                    if (retirement !in pending) pending.add(retirement)
                    require(pending.size <= 256) { "Binding retirement acknowledgement required" }
                    editor.putString("retirements", seal(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProxyBindingRetirement.serializer()), pending)))
                }
                check(editor.commit()) { "Identity retirement persistence refused" }
            } else write(record.copyCustody(transaction = null))
        } catch (failure: Exception) { unavailable = true; throw failure }
        epoch++
        return true
    }
    @Synchronized internal fun suspendPresentation(actor: ScopedReadContext) {
        checkAvailable()
        val record = record() ?: return
        val matches = if (record.custody != null) record.custody == actor.custodyVersion
            else record.origin == actor.key.origin && record.connectionIdentity == actor.key.pairedDeviceIdentity
        if (!matches) return
        try { write(record.copyCustody(custody = actor.custodyVersion)) }
        catch (failure: Exception) { unavailable = true; throw failure }
        epoch++
    }
    @Synchronized internal fun suspendPresentation() { epoch++; record()?.let { write(it.copyCustody()) } }
    /** Retirement changes the publication revision even after a callback consumed its transaction. */
    @Synchronized internal fun retire() {
        epoch++
        record()?.let { write(it.copyCustody(transaction = null)) }
    }
    @Synchronized internal fun commit(lease: ProxyCustodyLease, next: ProxyRecord, retirement: ProxyBindingRetirement? = null): Boolean {
        if (!current(lease)) return false
        val editor = prefs.edit().putString("record", seal(json.encodeToString(ProxyRecord.serializer(), next)))
        if (retirement != null) {
            val pending = pendingRetirements().toMutableList()
            if (pending.none { it.bindingId == retirement.bindingId && it.revision == retirement.revision }) pending.add(retirement)
            require(pending.size <= 256) { "Binding retirement acknowledgement required" }
            editor.putString("retirements", seal(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProxyBindingRetirement.serializer()), pending)))
        }
        check(editor.commit()); return true
    }
    @Synchronized internal fun pendingRetirements(): List<ProxyBindingRetirement> = prefs.getString("retirements", null)?.let { encoded ->
        // Corrupt encrypted retirement custody fails closed; never silently acknowledge it.
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ProxyBindingRetirement.serializer()), open(encoded))
            .also { require(it.size <= 256) }
    } ?: emptyList()
    @Synchronized internal fun acknowledgeRetirement(event: ProxyBindingRetirement) {
        val pending = pendingRetirements().filterNot { it == event }
        check(prefs.edit().putString("retirements", seal(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProxyBindingRetirement.serializer()), pending))).commit())
    }
    private fun seal(text: String): String {
        val clear = text.encodeToByteArray(); require(clear.size <= 128_000)
        val encrypted = try { cipher.encrypt(clear) } finally { clear.fill(0) }
        return json.encodeToString(ProxyEnvelope.serializer(), ProxyEnvelope(iv = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP), ciphertext = Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)))
    }
    private fun open(encoded: String): String {
        require(encoded.length <= 192_000)
        val envelope = json.decodeFromString(ProxyEnvelope.serializer(), encoded); require(envelope.version == 1)
        val clear = cipher.decrypt(CipherEnvelope(Base64.decode(envelope.iv, Base64.NO_WRAP), Base64.decode(envelope.ciphertext, Base64.NO_WRAP)))
        return try { require(clear.size <= 128_000); clear.toString(Charsets.UTF_8) } finally { clear.fill(0) }
    }
    @Synchronized fun hasCustody(): Boolean = prefs.contains("record")
    @Synchronized internal fun record(): ProxyRecord? {
        checkAvailable()
        val encoded = prefs.getString("record", null) ?: return null
        if (encoded.length > 192_000) return null
        return runCatching {
            val e = json.decodeFromString(ProxyEnvelope.serializer(), encoded); require(e.version == 1)
            val clear = cipher.decrypt(CipherEnvelope(Base64.decode(e.iv, Base64.NO_WRAP), Base64.decode(e.ciphertext, Base64.NO_WRAP)))
            try { require(clear.size <= 128_000); json.decodeFromString(ProxyRecord.serializer(), clear.toString(Charsets.UTF_8)).also { require(it.schema == 1); ProxyProfile.parse(it.profile) } }
            finally { clear.fill(0) }
        }.getOrNull()
    }
    @Synchronized internal fun write(record: ProxyRecord) {
        checkAvailable()
        check(prefs.edit().putString("record", seal(json.encodeToString(ProxyRecord.serializer(), record))).commit())
    }
    @Synchronized internal fun replace(expected: ProxyRecord, next: ProxyRecord): Boolean {
        if (record()?.revision != expected.revision) return false
        write(next); return true
    }
    @Synchronized internal fun clearIdentity(expected: ProxyRecord): Boolean = replace(expected, expected.copyCustody(tokens = null, transaction = null))
    @Synchronized internal fun remove(expected: ProxyRecord) { if (record()?.revision == expected.revision) clear() }
    @Synchronized fun clear() { epoch++; check(prefs.edit().remove("record").commit()) }
    @Synchronized fun clearIdentity() { record()?.let { write(it.copyCustody(tokens = null, transaction = null)) } }
    @Synchronized internal fun consume(state: String, identity: String, generation: Long, process: String, now: Long, custody: ScopedCustodyVersion? = null): Pair<ProxyRecord, ProxyTransaction>? {
        val r = record() ?: return null; val t = r.transaction ?: return null
        if (t.state != state || t.connectionIdentity != identity || t.connectionGeneration != generation || t.processIdentity != process || t.expiresAt <= now || t.expiresAt - now > 5 * 60_000L) return null
        if (custody != null && t.custody != null && t.custody != custody) return null
        val consumed = r.copyCustody(transaction = null)
        write(consumed)
        return consumed to t
    }
}

internal fun ProxyRecord.copyCustody(tokens: ProxyTokens? = this.tokens, transaction: ProxyTransaction? = this.transaction,
    binding: ProxyBindingWitness? = this.binding, custody: ScopedCustodyVersion? = this.custody) = ProxyRecord(origin = origin, connectionIdentity = connectionIdentity,
        profile = profile, tokens = tokens, transaction = transaction, binding = binding, custody = custody)
