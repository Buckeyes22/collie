package com.lateapex.collie.ui

import android.content.Context
import android.content.SharedPreferences
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Non-secret, profile-scoped identity used only to resume owner receipt GETs. */
internal class PluginReceiptOwner private constructor(
    val pairedIdentity: String,
    val scope: Scope,
) {
    fun matches(context: ScopedReadContext, requireCommitted: Boolean): Boolean =
        (!requireCommitted || context.committedStore) &&
            context.key.pairedDeviceIdentity == pairedIdentity &&
            context.key.host == scope.host && context.key.session == scope.session

    val storageKey: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val fields = listOf(pairedIdentity, scope.host.orEmpty(), scope.session.orEmpty())
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    override fun toString(): String = "PluginReceiptOwner(<redacted>)"

    companion object {
        fun create(pairedIdentity: String, scope: Scope): PluginReceiptOwner? =
            pairedIdentity.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { PluginReceiptOwner(it, scope) }
    }
}

internal enum class PluginReceiptKind(val wireValue: String) {
    OWNER_OPERATION("owner"),
    CONTROL_OPERATION("control"),
    LEGACY_UNKNOWN("legacy");

    companion object {
        fun fromWire(value: String): PluginReceiptKind? = values().firstOrNull { it.wireValue == value }
    }
}

internal data class PendingPluginReceipt(val requestId: String, val kind: PluginReceiptKind) {
    fun encode(): String = "${kind.wireValue}:$requestId"

    companion object {
        fun parse(value: String): PendingPluginReceipt? {
            val separator = value.indexOf(':')
            if (separator < 0) {
                return value.takeIf(REQUEST_ID::matches)?.let { PendingPluginReceipt(it, PluginReceiptKind.LEGACY_UNKNOWN) }
            }
            val kind = PluginReceiptKind.fromWire(value.substring(0, separator)) ?: return null
            val id = value.substring(separator + 1).takeIf(REQUEST_ID::matches) ?: return null
            return PendingPluginReceipt(id, kind)
        }

        private val REQUEST_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

/** Bounded private preferences contain opaque request IDs and finite receipt kinds under a hashed owner key. */
internal class PluginReceiptStore(context: Context, preferencesName: String = PREFERENCES) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun pending(owner: PluginReceiptOwner): LinkedHashMap<String, PluginReceiptKind> = synchronized(LOCK) {
        read(owner).associateTo(linkedMapOf<String, PluginReceiptKind>()) { it.requestId to it.kind }
    }

    fun add(owner: PluginReceiptOwner, requestId: String, kind: PluginReceiptKind): Boolean = synchronized(LOCK) {
        if (!REQUEST_ID.matches(requestId) || kind == PluginReceiptKind.LEGACY_UNKNOWN) return false
        val current = read(owner)
        val existing = current.firstOrNull { it.requestId == requestId }
        if (existing != null) return@synchronized existing.kind == kind
        if (current.size >= MAX_PENDING) return@synchronized false
        val owners = readOwners()
        val ownerKey = owner.storageKey
        if (ownerKey !in owners && owners.size >= MAX_OWNERS) return@synchronized false
        preferences.edit()
            .putStringSet(storageKey(owner), (current + PendingPluginReceipt(requestId, kind)).mapTo(linkedSetOf<String>()) { it.encode() })
            .putStringSet(OWNERS, owners + ownerKey)
            .commit()
    }

    fun remove(owner: PluginReceiptOwner, requestId: String): Boolean = synchronized(LOCK) {
        val current = read(owner)
        val remaining = current.filterNot { it.requestId == requestId }
        if (remaining.size == current.size) return@synchronized true
        val edit = preferences.edit()
        if (remaining.isEmpty()) {
            edit.remove(storageKey(owner)).putStringSet(OWNERS, readOwners() - owner.storageKey)
        } else {
            edit.putStringSet(storageKey(owner), remaining.mapTo(linkedSetOf<String>()) { it.encode() })
        }
        edit.commit()
    }

    private fun read(owner: PluginReceiptOwner): List<PendingPluginReceipt> = try {
        preferences.getStringSet(storageKey(owner), emptySet()).orEmpty()
            .asSequence().mapNotNull { PendingPluginReceipt.parse(it) }.take(MAX_PENDING).toList()
    } catch (_: ClassCastException) {
        emptyList()
    }

    private fun storageKey(owner: PluginReceiptOwner) = "${PREFIX}_${owner.storageKey}"

    private fun readOwners(): Set<String> = try {
        preferences.getStringSet(OWNERS, emptySet()).orEmpty()
            .asSequence().filter { it.matches(Regex("[a-f0-9]{64}")) }.take(MAX_OWNERS).toCollection(linkedSetOf())
    } catch (_: ClassCastException) {
        emptySet()
    }

    private companion object {
        const val PREFERENCES = "host_plugin_receipts_v1"
        const val PREFIX = "profile"
        const val MAX_PENDING = 32
        const val MAX_OWNERS = 16
        const val OWNERS = "owners"
        val REQUEST_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val LOCK = Any()
    }
}
