package com.lateapex.collie.data

import android.content.Context
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Display custody only; this value is never a ScopedReadContext or a dispatch credential. */
@Serializable
data class RetainedReadScope(
    val owner: ScopedCustodyOwner,
    val incarnation: String,
    val host: String?, val session: String?, val subject: String?, val viewAll: Boolean = false,
) {
    val target: String get() = RetainedReadDatabase.digest(Json.encodeToString(serializer(), this))
    init {
        require(incarnation.matches(Regex("[a-fA-F0-9-]{36}")))
        require(listOfNotNull(host, session, subject).all { it.isNotBlank() && it.length <= 512 && it.none { c -> Character.isISOControl(c) } })
    }
    override fun toString(): String = "RetainedReadScope(<redacted>)"
}

internal class OfflineReadSelection(
    val scope: RetainedReadScope, val committed: CommittedConnectionSnapshot, val version: ScopedCustodyVersion,
    val readSource: com.lateapex.collie.ui.ReadSourceState,
) {
    @Volatile var installedVersion: ScopedCustodyVersion? = null
    override fun toString(): String = "OfflineReadSelection(<redacted>)"
}

/** Form-entry local custody. This is neither a read actor nor a dispatch/persistence opt-in. */
internal class LocalDraftCustody(
    val address: com.lateapex.collie.domain.PaneAddress,
    val owner: ScopedCustodyOwner?, val scope: RetainedReadScope?, val localIdentity: String,
    internal val connection: com.lateapex.collie.domain.Connection?, internal val incarnation: String?,
    internal val revision: Long?,
) {
    val key: String get() {
        val components = listOf(connection?.origin?.value.orEmpty(), owner?.pairedDeviceIdentity ?: localIdentity,
            address.scope.host.orEmpty(), address.scope.session.orEmpty(), address.paneId)
        // Old ordinary keys start with a canonical HTTPS origin or '|'. This version prefix is
        // disjoint, and length framing is injective even when accepted identifiers contain '|'.
        return if (components.none { it.contains('|') } && !address.scope.viewAll) components.joinToString("|")
        else "draft:v2:" + components.joinToString("") { "${it.length}:$it" } + (if (address.scope.viewAll) "1" else "0")
    }
    override fun toString(): String = "LocalDraftCustody(<redacted>)"
}

internal data class PasswordDraftRetirement(val scope: RetainedReadScope, val cutoff: Long) {
    override fun toString(): String = "PasswordDraftRetirement(<redacted>)"
}

data class SavedCopyProvenance(val scope: RetainedReadScope, val capturedAt: Long, val expiresAt: Long, val clearRevision: Long = 0) {
    override fun toString(): String = "SavedCopyProvenance(capturedAt=$capturedAt, expiresAt=$expiresAt)"
}
data class RetainedCopy<T>(val value: T, val provenance: SavedCopyProvenance) {
    override fun toString(): String = "RetainedCopy(<redacted>)"
}

@Serializable
internal data class SavedPaneContent(
    val scope: RetainedReadScope,
    val pane: PaneReadResponse? = null,
    val mirrorAt: Long? = null,
    val entries: List<TranscriptEntry> = emptyList(),
    val chatAt: Long? = null,
)
@Serializable private data class SavedDashboardContent(val scope: RetainedReadScope, val snapshot: SnapshotResponse)
@Serializable private data class SavedDraft(val key: String, val owner: String, val text: String)

/** One facade/backing database for read retention and its independent draft policy. */
class OfflineContentStore(
    private val database: RetainedReadDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Retention(val days: Int) { OFF(0), ONE_DAY(1), SEVEN_DAYS(7) }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val mutableChanges = MutableStateFlow(0L)
    private val readClearRevision = java.util.concurrent.atomic.AtomicLong()
    private val clearListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()
    private val targetConsumers = java.util.IdentityHashMap<Any, (RetainedReadScope, Boolean) -> Boolean>()
    @Volatile private var draftRetirementUnavailable = false
    internal fun registerDraftTargetConsumer(identity: Any, listener: (RetainedReadScope, Boolean) -> Boolean) {
        synchronized(targetConsumers) { if (!targetConsumers.containsKey(identity)) targetConsumers[identity] = listener }
    }
    private fun publishDraftTarget(scope: RetainedReadScope, blocked: Boolean): Boolean {
        val consumers = synchronized(targetConsumers) { targetConsumers.values.toList() }
        var success = true
        consumers.forEach { if (!it(scope, blocked)) success = false } // No lock crosses callbacks.
        if (!success) draftRetirementUnavailable = true
        return success
    }
    internal fun draftTargetAllowed(scope: RetainedReadScope): Boolean = database.targetAllowed(scope.target)
    internal fun legacyDraftAllowed(scope: RetainedReadScope): Boolean = database.legacyAllowed(scope.target)
    fun onReadsCleared(listener: (Long) -> Unit): AutoCloseable { clearListeners += listener; return AutoCloseable { clearListeners -= listener } }
    internal fun isDisplayCurrent(copy: SavedCopyProvenance): Boolean = readClearRevision.get() == copy.clearRevision
    internal fun currentTimeMillis(): Long = now()
    val changes: StateFlow<Long> get() = mutableChanges
    val retention: Retention get() = when (database.policy("reads")) { 1 -> Retention.ONE_DAY; 2 -> Retention.SEVEN_DAYS; else -> Retention.OFF }
    val draftsEnabled: Boolean get() = database.policy("drafts") == 1
    fun storageUsage(): Pair<Long, Int> = database.usage()
    val available: Boolean get() = database.isAvailable() && !draftRetirementUnavailable

    fun chooseRetention(value: Retention): Boolean = clearDisplay { database.choose("reads", value.ordinal, "read") }
    fun chooseDrafts(enabled: Boolean): Boolean = changed(database.choose("drafts", if (enabled) 1 else 0, if (enabled) null else "draft"))
    fun clearReads(): Boolean = clearDisplay(database::clearReads)
    fun clearDrafts(): Boolean = changed(database.clearDrafts())
    fun retireOwner(owner: ScopedCustodyOwner): Boolean = changed(database.retireOwner(owner.storageIdentity))
    fun retirePane(scope: RetainedReadScope): Boolean = changed(database.retireTarget(scope.target))
    internal fun passwordPane(scope: RetainedReadScope, current: () -> Boolean): Boolean {
        if (!current()) return false
        val success = database.passwordTarget(scope.target, current)
        // A storage refusal cannot leave accepted password copies readable in this process.
        val copied = if (success || current()) publishDraftTarget(scope, true) else true
        return changed(success && copied)
    }
    private fun changed(success: Boolean): Boolean { mutableChanges.value += 1; return success }
    private fun clearDisplay(action: () -> Boolean): Boolean {
        val cutoff = readClearRevision.incrementAndGet()
        val success = action()
        // Failed clear remains unavailable, never leaves a seemingly current copied display.
        clearListeners.forEach { it(cutoff) }
        return changed(success)
    }

    internal fun retainDashboard(scope: RetainedReadScope, snapshot: SnapshotResponse, current: () -> Boolean): Boolean {
        if (retention == Retention.OFF || !current()) return false
        var safe = snapshot.copy(
            bridge = "saved", controlAuthority = null, device = null, notifications = null, update = null,
            agents = snapshot.agents.filter(::validPaneIdentity).take(256).map(::displayPane), shellPanes = snapshot.shellPanes.filter(::validPaneIdentity).take(256).map(::displayPane),
            workspaces = snapshot.workspaces.filter { validIdentity(it.workspaceId) && validIdentity(it.activeTabId) }.take(256).map { it.copy(label = it.label.take(256), repoRoot = null, folder = null, isWorktree = null, focused = false) },
            tabs = snapshot.tabs.filter { validIdentity(it.tabId) && validIdentity(it.workspaceId) }.take(512).map { it.copy(label = it.label.take(256), focused = false) },
            sessions = snapshot.sessions.filter { validIdentity(it.name) && listOfNotNull(it.host).all(::validIdentity) }.take(128),
            servers = snapshot.servers?.filter { validIdentity(it.id) }?.take(64)?.map { it.copy(name = it.name.take(256), protocolDetail = null) },
        )
        val key = recordKey(scope); val epoch = database.epoch(key, scope.owner.storageIdentity, scope.target, "read") ?: return false
        var payload = json.encodeToString(SavedDashboardContent.serializer(), SavedDashboardContent(scope, safe))
        while (payload.encodeToByteArray().size > PAYLOAD_BUDGET) {
            safe = when {
                safe.shellPanes.isNotEmpty() -> safe.copy(shellPanes = safe.shellPanes.dropLast(1))
                safe.agents.isNotEmpty() -> safe.copy(agents = safe.agents.dropLast(1))
                safe.tabs.isNotEmpty() -> safe.copy(tabs = safe.tabs.dropLast(1))
                safe.workspaces.isNotEmpty() -> safe.copy(workspaces = safe.workspaces.dropLast(1))
                safe.sessions.isNotEmpty() -> safe.copy(sessions = safe.sessions.dropLast(1))
                !safe.servers.isNullOrEmpty() -> safe.copy(servers = safe.servers!!.dropLast(1))
                else -> return false
            }
            payload = json.encodeToString(SavedDashboardContent.serializer(), SavedDashboardContent(scope, safe))
        }
        return database.write(key, scope.owner.storageIdentity, scope.target, "read", payload, deadline(now(), DAY), DAY, epoch, current)
    }

    internal fun dashboard(scope: RetainedReadScope): RetainedCopy<SnapshotResponse>? {
        val revision = readClearRevision.get()
        if (retention == Retention.OFF) return null
        val row = database.read(recordKey(scope), scope.owner.storageIdentity) ?: return null
        return try {
            val content = json.decodeFromString(SavedDashboardContent.serializer(), row.payload)
            if (content.scope != scope || readClearRevision.get() != revision) null else RetainedCopy(content.snapshot, SavedCopyProvenance(scope, row.capturedAt, row.expiresAt, revision))
        } catch (_: Exception) { null }
    }

    internal fun retainPane(scope: RetainedReadScope, pane: PaneReadResponse?, entries: List<TranscriptEntry>?, current: () -> Boolean): Boolean {
        val policy = retention
        if (!current() || pane != null && pane.paneId != scope.subject) return false
        // Only an accepted, non-password mirror can reopen this target. Chat alone cannot.
        if (pane != null && !database.allowTarget(scope.target, current)) return false
        if (pane != null && current()) publishDraftTarget(scope, false)
        if (policy == Retention.OFF || !current()) return false
        val key = recordKey(scope); val epoch = database.epoch(key, scope.owner.storageIdentity, scope.target, "read") ?: return false
        val prior = pane(scope)?.value ?: SavedPaneContent(scope)
        val clock = now()
        var content = prior.copy(
            pane = pane?.copy(text = tail(pane.text, 128 * 1024), linkText = null, filesReadReady = false)
                ?: prior.pane,
            mirrorAt = if (pane != null) clock else prior.mirrorAt,
            entries = entries?.filter { validIdentity(it.uuid) }?.takeLast(100)?.map(::displayEntry) ?: prior.entries,
            chatAt = if (entries != null) clock else prior.chatAt,
        )
        var payload = json.encodeToString(SavedPaneContent.serializer(), content)
        while (payload.encodeToByteArray().size > PAYLOAD_BUDGET && content.entries.size > 1) {
            content = content.copy(entries = content.entries.drop(1)); payload = json.encodeToString(SavedPaneContent.serializer(), content)
        }
        if (payload.encodeToByteArray().size > PAYLOAD_BUDGET) {
            content = content.copy(entries = content.entries.map { it.copy(parts = listOf(TranscriptPart("text", tail(it.parts.joinToString("\n") { part -> part.text.orEmpty() }, 32 * 1024)))) },
                pane = content.pane?.copy(text = tail(content.pane!!.text, 32 * 1024), truncated = true))
            payload = json.encodeToString(SavedPaneContent.serializer(), content)
        }
        if (payload.encodeToByteArray().size > PAYLOAD_BUDGET) return false
        val expires = maxOf(content.mirrorAt?.let { deadline(it, DAY) } ?: 0,
            content.chatAt?.let { deadline(it, policy.days * DAY) } ?: 0)
        return database.write(key, scope.owner.storageIdentity, scope.target, "read", payload, expires, policy.days * DAY, epoch, current)
    }

    internal fun pane(scope: RetainedReadScope): RetainedCopy<SavedPaneContent>? {
        val revision = readClearRevision.get()
        val policy = retention
        if (policy == Retention.OFF) return null
        val row = database.read(recordKey(scope), scope.owner.storageIdentity) ?: return null
        return try {
            val content = json.decodeFromString(SavedPaneContent.serializer(), row.payload)
            if (content.scope != scope || readClearRevision.get() != revision) return null
            val clock = now()
            val mirrorAt = content.mirrorAt?.takeIf { it <= clock && clock < deadline(it, DAY) }
            val chatAt = content.chatAt?.takeIf { it <= clock && clock < deadline(it, policy.days * DAY) }
            val kept = content.copy(pane = content.pane.takeIf { mirrorAt != null }, entries = content.entries.takeIf { chatAt != null }.orEmpty(), mirrorAt = mirrorAt, chatAt = chatAt)
            if (kept.pane == null && kept.entries.isEmpty()) null
            else RetainedCopy(kept, SavedCopyProvenance(scope, maxOf(mirrorAt ?: 0, chatAt ?: 0), minOf(row.expiresAt, maxOf(mirrorAt?.let { deadline(it, DAY) } ?: 0, chatAt?.let { deadline(it, policy.days * DAY) } ?: 0)), revision))
        } catch (_: Exception) { null }
    }

    internal fun saveDraft(key: String, value: String, owner: ScopedCustodyOwner, scope: RetainedReadScope? = null, current: () -> Boolean = { true }): Boolean {
        if (!draftsEnabled || value.length > 8192 || key.length > 4096 || scope == null || scope.owner != owner || scope.subject == null || scope.viewAll) return false
        val id = draftKey(key, owner.storageIdentity); val target = scope.target
        val epoch = database.epoch(id, owner.storageIdentity, target, "draft") ?: return false
        return database.write(id, owner.storageIdentity, target, "draft",
            json.encodeToString(SavedDraft.serializer(), SavedDraft(key, owner.storageIdentity, value)), deadline(now(), 2 * DAY), 2 * DAY, epoch, current)
    }

    /** Optional owner-only access is internal fixture inspection. Configured product readers
     * always pass the original exact scope; its SQL target is also authenticated in the envelope. */
    internal fun draft(key: String, owner: ScopedCustodyOwner, expectedScope: RetainedReadScope? = null): String? {
        if (!draftsEnabled || expectedScope != null && expectedScope.owner != owner) return null
        val row = database.read(draftKey(key, owner.storageIdentity), owner.storageIdentity, expectedScope?.target) ?: return null
        return try { json.decodeFromString(SavedDraft.serializer(), row.payload).takeIf { it.key == key && it.owner == owner.storageIdentity && it.text.length <= 8192 }?.text }
        catch (_: Exception) { null }
    }
    internal fun removeDraft(key: String, owner: ScopedCustodyOwner): Boolean = database.retireRecord(draftKey(key, owner.storageIdentity))

    private fun displayPane(pane: PaneSummary): PaneSummary = PaneSummary(
        paneId = pane.paneId, workspaceId = pane.workspaceId, workspaceLabel = pane.workspaceLabel.take(256),
        workspaceNumber = pane.workspaceNumber, tabId = pane.tabId, agent = pane.agent.take(128), status = pane.status,
        cwd = "", focused = false, kind = pane.kind?.take(64), paneLabel = pane.paneLabel?.take(256),
        tabLabel = pane.tabLabel?.take(256), host = pane.host, session = pane.session,
    )
    private fun displayEntry(entry: TranscriptEntry): TranscriptEntry = entry.copy(
        uuid = entry.uuid, ts = entry.ts.take(128), role = entry.role.take(64), seq = null,
        parts = entry.parts.take(128).mapNotNull { part ->
            val text = when (part.kind) {
                "text", "thinking", "reasoning" -> part.text
                "tool" -> listOfNotNull(part.summary, part.result?.text).joinToString("\n")
                else -> null
            }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            TranscriptPart(kind = "text", text = tail(text, 32 * 1024), truncated = part.truncated)
        },
    )
    private fun validIdentity(value: String): Boolean = value.isNotBlank() && value.length <= 512 && value.none { Character.isISOControl(it) }
    private fun validPaneIdentity(pane: PaneSummary): Boolean = listOf(pane.paneId, pane.workspaceId, pane.tabId).all(::validIdentity) &&
        listOfNotNull(pane.host, pane.session).all(::validIdentity)
    private fun recordKey(scope: RetainedReadScope): String = RetainedReadDatabase.digest("read:${scope.target}")
    private fun draftKey(key: String, owner: String): String = RetainedReadDatabase.digest("draft:$owner:$key")
    private fun deadline(captured: Long, age: Long): Long = Math.addExact(captured, age)
    private fun tail(value: String, bytes: Int): String {
        val data = value.encodeToByteArray(); if (data.size <= bytes) return value
        var start = data.size - bytes
        while (start < data.size && data[start].toInt() and 0xc0 == 0x80) start++
        return data.decodeToString(startIndex = start, throwOnInvalidSequence = true)
    }
    companion object {
        private val shared = java.util.WeakHashMap<Context, OfflineContentStore>()
        fun shared(context: Context): OfflineContentStore = synchronized(shared) {
            val app = context.applicationContext
            shared.getOrPut(app) { create(app) }
        }
        private const val DAY = 24L * 60 * 60 * 1000
        private const val PAYLOAD_BUDGET = 120 * 1024 // Escaped envelope, GCM tag and IV count toward the256KiB pane cap.
        fun create(context: Context, cipher: SecretCipher? = null, now: () -> Long = System::currentTimeMillis): OfflineContentStore =
            OfflineContentStore(RetainedReadDatabase(context, cipher ?: AndroidKeystoreCipher("com.lateapex.collie.retained.v1"), now), now)
    }
}
