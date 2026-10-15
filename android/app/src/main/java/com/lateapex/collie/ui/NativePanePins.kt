package com.lateapex.collie.ui

import android.content.Context
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.custodyOwner
import com.lateapex.collie.network.PaneSummary
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Device-local places, matching official web pins.ts: row + workspace name, never status.
 * Missing panes remain dormant. Only an explicit operator action writes or evicts a record.
 * The original paired actor fences each action; no bridge request or bearer is stored here.
 */
class NativePanePins(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val changes: StateFlow<Long> get() = revision.asStateFlow()

    fun matcher(actor: ScopedReadContext, actorCurrent: (ScopedReadContext) -> Boolean): (PaneSummary) -> Boolean {
        val owner = ownerKey(actor)
        val ids = synchronized(lock) { read().filter { it.owner == owner }.map { it.id }.toSet() }
        return { pane -> owner != null && actorCurrent(actor) && inScope(pane, actor) && id(pane, actor) in ids }
    }

    fun isPinned(pane: PaneSummary, actor: ScopedReadContext, actorCurrent: (ScopedReadContext) -> Boolean): Boolean =
        matcher(actor, actorCurrent)(pane)

    fun setPinned(pane: PaneSummary, on: Boolean, herd: List<PaneSummary>, actor: ScopedReadContext,
        actorCurrent: (ScopedReadContext) -> Boolean): Boolean = synchronized(lock) {
        val owner = ownerKey(actor) ?: return@synchronized false
        if (!actorCurrent(actor) || !admitted(pane, actor)) return@synchronized false
        val target = id(pane, actor)
        val live = (herd + pane).filter { inScope(it, actor) }.associate { id(it, actor).let { key -> key.row to key.space } }
        val next = read().filterNot { it.owner == owner && it.id == target }.toMutableList()
        val keep = if (on) Pin(owner, target, System.currentTimeMillis().coerceAtLeast(0)).also { next.add(it) } else null
        // A currently reused row in this owner cannot inherit an older workspace's pin.
        next.removeAll { it.owner == owner && live[it.id.row]?.let { space -> space != it.id.space } == true }
        if (next.size > MAX_PINS) {
            val victims = next.filter { it !== keep }.sortedWith(
                compareBy<Pin> { if (it.owner == owner && live[it.id.row] == it.id.space) 1 else 0 }.thenBy { it.at }
            ).take(next.size - MAX_PINS).toSet()
            next.removeAll(victims)
        }
        if (!actorCurrent(actor)) return@synchronized false
        save(next) { actorCurrent(actor) }
    }

    /** Call only after a close of this captured pane was positively confirmed by the native action. */
    fun dropAfterConfirmedClose(pane: PaneSummary, actor: ScopedReadContext,
        actorCurrent: (ScopedReadContext) -> Boolean): Boolean = synchronized(lock) {
        val owner = ownerKey(actor) ?: return@synchronized false
        if (!actorCurrent(actor) || !admitted(pane, actor)) return@synchronized false
        val before = read()
        val next = before.filterNot { it.owner == owner && it.id == id(pane, actor) }
        if (!actorCurrent(actor)) return@synchronized false
        if (next.size == before.size) true else save(next) { actorCurrent(actor) }
    }

    /** Reserved for the existing explicit Clear phone data action, never a poll or retirement. */
    fun clear(): Boolean = synchronized(lock) {
        if (!preferences.edit().remove(RECORDS).commit()) false
        else { revision.value += 1; true }
    }

    private data class Row(val host: String, val session: String, val pane: String)
    private data class Id(val row: Row, val space: String)
    private data class Pin(val owner: String, val id: Id, val at: Long)

    private fun id(pane: PaneSummary, actor: ScopedReadContext): Id = Id(
        Row(pane.host ?: actor.key.host.orEmpty(), pane.session ?: actor.key.session.orEmpty(), pane.paneId),
        pane.workspaceLabel.ifEmpty { pane.workspaceId },
    )

    private fun admitted(pane: PaneSummary, actor: ScopedReadContext): Boolean = inScope(pane, actor) &&
        (actor.key.subjectIdentity == null || actor.key.subjectIdentity == pane.paneId)

    private fun inScope(pane: PaneSummary, actor: ScopedReadContext): Boolean {
        val key = id(pane, actor)
        return valid(key.row.host, empty = true) && valid(key.row.session, empty = true) &&
            valid(key.row.pane) && valid(key.space) &&
            (actor.key.host == null || key.row.host == actor.key.host) &&
            (actor.key.session == null || key.row.session == actor.key.session)
    }

    private fun ownerKey(actor: ScopedReadContext): String? {
        val incarnation = actor.connectionIncarnation ?: return null
        if (!actor.committedStore || !actor.connection.isPaired || incarnation.isBlank()) return null
        val bytes = (actor.custodyOwner.storageIdentity + "\u0000" + incarnation).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun read(): List<Pin> {
        val raw = preferences.getString(RECORDS, null) ?: return emptyList()
        if (raw.length > MAX_STORAGE_CHARS) return emptyList()
        return try {
            val array = JSONArray(raw)
            val rows = LinkedHashMap<Pair<String, Id>, Pin>()
            for (index in 0 until minOf(array.length(), MAX_PINS)) {
                val item = array.optJSONObject(index) ?: continue
                val owner = item.opt("owner") as? String ?: continue
                val host = item.opt("host") as? String ?: continue
                val session = item.opt("session") as? String ?: continue
                val pane = item.opt("pane") as? String ?: continue
                val space = item.opt("space") as? String ?: continue
                val at = item.opt("at") as? Long ?: (item.opt("at") as? Int)?.toLong() ?: continue
                if (!owner.matches(OWNER_PATTERN) || !valid(host, true) || !valid(session, true) ||
                    !valid(pane) || !valid(space) || at < 0) continue
                val id = Id(Row(host, session, pane), space)
                rows.putIfAbsent(owner to id, Pin(owner, id, at))
            }
            rows.values.toList()
        } catch (_: org.json.JSONException) { emptyList() }
    }

    private fun save(rows: List<Pin>, current: () -> Boolean): Boolean {
        val previous = preferences.getString(RECORDS, null)
        val array = JSONArray()
        rows.forEach { pin -> array.put(JSONObject().put("owner", pin.owner).put("host", pin.id.row.host)
            .put("session", pin.id.row.session).put("pane", pin.id.row.pane).put("space", pin.id.space).put("at", pin.at)) }
        val committed = preferences.edit().putString(RECORDS, array.toString()).commit()
        if (!committed || !current()) {
            // A synchronous preferences write may overlap actor retirement. Do not publish or
            // acknowledge it; restore the exact prior local value under the same surface lock.
            val restore = preferences.edit()
            if (previous == null) restore.remove(RECORDS) else restore.putString(RECORDS, previous)
            restore.commit()
            return false
        }
        revision.value += 1
        return true
    }

    private fun valid(value: String, empty: Boolean = false): Boolean = value.length <= MAX_FIELD_CHARS &&
        (empty || value.isNotBlank()) && value.none { it.code < 32 || it.code in 127..159 }

    companion object {
        const val MAX_PINS = 32
        internal const val PREFERENCES = "collie_native_pane_pins_v1"
        internal const val RECORDS = "pins"
        private const val MAX_FIELD_CHARS = 1024
        private const val MAX_STORAGE_CHARS = 512 * 1024
        private val OWNER_PATTERN = Regex("[a-f0-9]{64}")
        private val lock = Any()
        private val revision = MutableStateFlow(0L)
    }
}
