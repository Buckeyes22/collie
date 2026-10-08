package com.lateapex.collie.notifications

import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** A prepared contract, with no provider or Android notification side effects. */
data class NativePushPayload(
    val registrationId: String,
    val slot: String,
    val sequence: Long,
    val expiresAt: Long,
    val kind: String,
    val title: String?,
    val body: String?,
    val renotify: Boolean,
    val target: Target?,
) {
    // Display text can be sensitive; incidental logs should contain only lifecycle metadata.
    override fun toString(): String = "NativePushPayload(kind=$kind, sequence=$sequence, expiresAt=$expiresAt)"

    sealed interface Target {
        data class Home(val scope: Scope) : Target
        data class Pane(val address: PaneAddress) : Target
        data object Updates : Target
    }

    companion object {
        private const val MAX_SAFE_INTEGER = 9007199254740991L
        private const val MAX_TTL_MS = 24 * 60 * 60 * 1000L
        private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
        private val controlPattern = Regex("[\\u0000-\\u001f\\u007f]")
        private val keys = setOf("schemaVersion", "registrationId", "slot", "sequence", "expiresAt", "kind", "title", "body", "renotify", "target")

        /** Call only after the provider authenticates delivery. Persist a per-registration, per-slot
         * high-water sequence even for clears; a tap is navigation and never a terminal write. */
        fun decode(raw: String, registrationId: String, nowMs: Long, lastSequence: Long = 0): NativePushPayload? {
            // Bound the allocation before counting UTF-8 bytes.
            if (raw.length > 4096 || raw.toByteArray(Charsets.UTF_8).size > 4096 || !idPattern.matches(registrationId) ||
                nowMs !in 0..MAX_SAFE_INTEGER || lastSequence !in 0..MAX_SAFE_INTEGER) return null
            return runCatching {
                val p = Json.parseToJsonElement(raw) as? JsonObject ?: return null
                if (!keys.containsAll(p.keys) || p.number("schemaVersion") != 1L || p.string("registrationId") != registrationId) return null
                val slot = p.string("slot")?.takeIf(idPattern::matches) ?: return null
                val sequence = p.number("sequence")?.takeIf { it > lastSequence } ?: return null
                val expiresAt = p.number("expiresAt")?.takeIf { it > nowMs && it - nowMs <= MAX_TTL_MS } ?: return null
                val kind = p.string("kind") ?: return null
                if (kind == "clear") {
                    if (listOf("title", "body", "renotify", "target").any(p::containsKey)) return null
                    return NativePushPayload(registrationId, slot, sequence, expiresAt, kind, null, null, false, null)
                }
                if (kind !in setOf("blocked", "done", "update")) return null
                val title = p.text("title", 160) ?: return null
                val body = p.text("body", 512) ?: return null
                val renotify = if (p.containsKey("renotify")) {
                    (p["renotify"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
                } else false
                val t = p["target"] as? JsonObject ?: return null
                if (!setOf("screen", "paneId", "host", "session").containsAll(t.keys)) return null
                val target = if (kind == "update") {
                    if (t.string("screen") != "updates" || t.size != 1) return null
                    Target.Updates
                } else {
                    if ((t.containsKey("host") && t.text("host", 256) == null) ||
                        (t.containsKey("session") && t.text("session", 256) == null)) return null
                    val scope = Scope(host = t.string("host"), session = t.string("session"))
                    when (t.string("screen")) {
                        "home" -> { if (t.containsKey("paneId")) return null; Target.Home(scope) }
                        "pane" -> Target.Pane(PaneAddress(scope, t.text("paneId", 256) ?: return null))
                        else -> return null
                    }
                }
                NativePushPayload(registrationId, slot, sequence, expiresAt, kind, title, body, renotify, target)
            }.getOrNull()
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }
            ?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 && it <= MAX_SAFE_INTEGER.toDouble() && it % 1.0 == 0.0 }?.toLong()
        private fun JsonObject.text(key: String, limit: Int): String? = string(key)?.takeIf {
            // Match JavaScript trim's Unicode whitespace set, including the BOM character.
            it.any { c -> !isWireWhitespace(c) } && it.length <= limit && !controlPattern.containsMatchIn(it)
        }
        private fun isWireWhitespace(c: Char): Boolean = c == ' ' || c == '\u00a0' || c == '\u1680' ||
            c in '\u2000'..'\u200a' || c == '\u2028' || c == '\u2029' || c == '\u202f' ||
            c == '\u205f' || c == '\u3000' || c == '\ufeff'
    }
}
