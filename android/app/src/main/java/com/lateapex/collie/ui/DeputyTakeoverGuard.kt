package com.lateapex.collie.ui

import android.content.Context
import android.content.SharedPreferences
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.CrewStatusResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/** One persisted takeover attempt for this exact paired origin. It never retries or changes profiles. */
internal class DeputyTakeoverGuard(context: Context, private val prefs: SharedPreferences = context.getSharedPreferences("collie_takeover_v1", Context.MODE_PRIVATE)) {
    @Serializable internal data class Attempt(val owner: String, val id: String, val expectedDeputyId: String?) {
        override fun toString() = "DeputyTakeoverAttempt(<private>)"
    }
    private val json = Json
    private fun owner(connection: Connection): String = MessageDigest.getInstance("SHA-256")
        .digest((connection.origin.value + "\u0000" + connection.token.orEmpty()).encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun key(owner: String) = "attempt_" + owner
    private fun read(owner: String): Attempt? {
        val raw = prefs.getString(key(owner), null) ?: return null
        require(raw.length <= 1024)
        return json.decodeFromString<Attempt>(raw).also {
            require(it.owner == owner && it.owner.matches(Regex("[0-9a-f]{64}")) && UUID.fromString(it.id).toString() == it.id)
            require(it.expectedDeputyId == null || it.expectedDeputyId.length in 1..128 && it.expectedDeputyId.none(Char::isISOControl))
        }
    }
    fun pending(connection: Connection): Boolean = synchronized(LOCK) {
        if (!connection.isPaired) return true
        runCatching { read(owner(connection)) != null }.getOrDefault(true)
    }
    fun reserve(connection: Connection, deputyId: String?): Attempt? = synchronized(LOCK) {
        if (!connection.isPaired || pending(connection)) return null
        if (deputyId != null && (deputyId.length !in 1..128 || deputyId.any(Char::isISOControl))) return null
        val attempt = Attempt(owner(connection), UUID.randomUUID().toString(), deputyId)
        return runCatching {
            if (prefs.all.size >= 256) return null
            if (prefs.edit().putString(key(attempt.owner), json.encodeToString(Attempt.serializer(), attempt)).commit()) attempt else null
        }.getOrNull()
    }
    /** A late predecessor response cannot settle a replacement profile's attempt. */
    fun refused(attempt: Attempt): Boolean = synchronized(LOCK) {
        runCatching { if (read(attempt.owner) != attempt) false else prefs.edit().remove(key(attempt.owner)).commit() }.getOrDefault(false)
    }
    /** Only a live Crew response naming the expected deputy as its new lead settles an unknown. */
    fun observeNewLead(connection: Connection, crew: CrewStatusResponse): Boolean = synchronized(LOCK) {
        runCatching {
            val attempt = read(owner(connection)) ?: return false
            if (attempt.owner != owner(connection) || attempt.expectedDeputyId == null ||
                crew.self.id != attempt.expectedDeputyId || crew.members.singleOrNull { it.isLead }?.id != attempt.expectedDeputyId) return false
            prefs.edit().remove(key(attempt.owner)).commit()
        }.getOrDefault(false)
    }
    fun currentAttempt(connection: Connection): Attempt? = synchronized(LOCK) {
        if (!connection.isPaired) return null
        runCatching { read(owner(connection)) }.getOrNull()
    }
    /** Called only after explicit review of a fresh, current paired Crew and leading health response. */
    fun resolveReviewedLead(attempt: Attempt, connection: Connection, crew: CrewStatusResponse): Boolean = synchronized(LOCK) {
        if (!connection.isPaired || attempt.owner != owner(connection) ||
            crew.members.singleOrNull { it.isLead }?.id != crew.self.id) return false
        refused(attempt)
    }
    companion object { private val LOCK = Any() }
}
