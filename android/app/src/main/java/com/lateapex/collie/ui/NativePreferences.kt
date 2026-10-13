package com.lateapex.collie.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.lateapex.collie.domain.Scope

/** Device-local presentation and interaction preferences for the native client. */
class NativePreferences(
    context: Context,
    offlineStoreOverride: com.lateapex.collie.data.OfflineContentStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class ThemeMode(val storedValue: String, val delegateMode: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    val offlineContentStore: com.lateapex.collie.data.OfflineContentStore by lazy {
        offlineStoreOverride ?: com.lateapex.collie.data.OfflineContentStore.shared(context.applicationContext)
    }
    init {
        val originalPreferences = preferences
        offlineContentStore.registerDraftTargetConsumer(originalPreferences) { scope, blocked ->
            retirePasswordDraftTarget(originalPreferences, scope, blocked)
        }
    }
    internal fun onPasswordDraftsRetired(listener: (com.lateapex.collie.data.PasswordDraftRetirement) -> Unit): AutoCloseable {
        passwordDraftListeners += listener; return AutoCloseable { passwordDraftListeners -= listener }
    }

    /** Local clear notifications carry a revision cutoff, never draft text. Callbacks/ACK are
     * outside both storage and preference locks; an edit committed later survives that event. */
    fun onDraftsCleared(listener: (Long) -> Unit): AutoCloseable {
        draftClearListeners += listener
        return AutoCloseable { draftClearListeners -= listener }
    }
    fun draftRevision(key: String): Long = synchronized(sensitiveContentLock) { volatileDraftRevisions[key] ?: 0 }

    fun clearRetainedDrafts(): Boolean {
        val cutoff = draftRevisionClock.get()
        if (!offlineContentStore.clearDrafts()) return false
        synchronized(sensitiveContentLock) {
            volatileDraftRevisions.filterValues { it <= cutoff }.keys.toList().forEach { key ->
                volatileDrafts.remove(key); volatileDraftOwners.remove(key); volatileDraftTimes.remove(key); volatileDraftTargets.remove(key)
                volatileDraftRevisions.remove(key)
            }
        }
        draftClearListeners.forEach { it(cutoff) }
        return true
    }

    /** Only disambiguates the editable pairing label; never derived from a device identifier. */
    val pairingLabelSuffix: String
        get() = preferences.getString("pairing_label_suffix", null)
            ?: java.util.UUID.randomUUID().toString().takeLast(4).also {
                preferences.edit().putString("pairing_label_suffix", it).apply()
            }

    var themeMode: ThemeMode
        get() {
            val stored = preferences.getString(THEME_MODE, null)
            return ThemeMode.entries.firstOrNull { it.storedValue == stored } ?: ThemeMode.SYSTEM
        }
        set(value) {
            preferences.edit().putString(THEME_MODE, value.storedValue).apply()
        }

    var hapticsEnabled: Boolean
        get() = preferences.getBoolean(HAPTICS_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(HAPTICS_ENABLED, value).apply()
        }

    /** Gates [com.lateapex.collie.diagnostics.DiagnosticsRecorder]; capture is opt-in because
     * network bodies include terminal content. Existing explicit preferences are preserved. */
    var diagnosticsEnabled: Boolean
        get() = preferences.getBoolean(DIAGNOSTICS_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(DIAGNOSTICS_ENABLED, value).apply()
        }

    var terminalFontSize: Int
        get() = preferences.getInt(TERMINAL_FONT_SIZE, 10).coerceIn(9, 16)
        set(value) {
            preferences.edit().putInt(TERMINAL_FONT_SIZE, value.coerceIn(9, 16)).apply()
        }

    var draftFontSize: Int
        get() = preferences.getInt(DRAFT_FONT_SIZE, 14).coerceIn(13, 16)
        set(value) {
            preferences.edit().putInt(DRAFT_FONT_SIZE, value.coerceIn(13, 16)).apply()
        }

    var handsFreeEnabled: Boolean
        get() = preferences.getBoolean(HANDS_FREE_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(HANDS_FREE_ENABLED, value).apply()
        }

    var dashboardRecentOpen: Boolean
        get() = preferences.getBoolean(DASHBOARD_RECENT_OPEN, true)
        set(value) { preferences.edit().putBoolean(DASHBOARD_RECENT_OPEN, value).apply() }

    var dashboardRecentNewest: Boolean
        get() = preferences.getString(DASHBOARD_RECENT_SORT, "newest") != "oldest"
        set(value) {
            preferences.edit().putString(DASHBOARD_RECENT_SORT, if (value) "newest" else "oldest").apply()
        }

    var dashboardSpacesOpen: Boolean?
        get() = if (preferences.contains(DASHBOARD_SPACES_OPEN)) {
            preferences.getBoolean(DASHBOARD_SPACES_OPEN, true)
        } else {
            null
        }
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(DASHBOARD_SPACES_OPEN) else editor.putBoolean(DASHBOARD_SPACES_OPEN, value)
            editor.apply()
        }

    /** Bounded Changes discovery depth; the bridge supports values 1 through 4. */
    var changesDepth: Int
        get() = preferences.getInt(CHANGES_DEPTH, 2).coerceIn(1, 4)
        set(value) { preferences.edit().putInt(CHANGES_DEPTH, value.coerceIn(1, 4)).apply() }

    /** Include nested repositories in Changes discovery. */
    var changesNested: Boolean
        get() = preferences.getBoolean(CHANGES_NESTED, true)
        set(value) { preferences.edit().putBoolean(CHANGES_NESTED, value).apply() }

    var dashboardLaunchOpen: Boolean?
        get() = if (preferences.contains(DASHBOARD_LAUNCH_OPEN)) {
            preferences.getBoolean(DASHBOARD_LAUNCH_OPEN, true)
        } else {
            null
        }
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(DASHBOARD_LAUNCH_OPEN) else editor.putBoolean(DASHBOARD_LAUNCH_OPEN, value)
            editor.apply()
        }

    var dashboardShellsOpen: Boolean?
        get() = if (preferences.contains(DASHBOARD_SHELLS_OPEN)) {
            preferences.getBoolean(DASHBOARD_SHELLS_OPEN, true)
        } else {
            null
        }
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(DASHBOARD_SHELLS_OPEN) else editor.putBoolean(DASHBOARD_SHELLS_OPEN, value)
            editor.apply()
        }

    var paneStripsVisible: Boolean
        get() = preferences.getBoolean(PANE_STRIPS_VISIBLE, true)
        set(value) {
            preferences.edit().putBoolean(PANE_STRIPS_VISIBLE, value).apply()
        }

    var dashboardScope: Scope
        get() {
            val viewAll = preferences.getBoolean(DASHBOARD_SCOPE_VIEW_ALL, false)
            return Scope(
                host = preferences.getString(DASHBOARD_SCOPE_HOST, null)?.takeIf(String::isNotBlank),
                session = preferences.getString(DASHBOARD_SCOPE_SESSION, null)
                    ?.takeIf(String::isNotBlank)
                    ?.takeUnless { viewAll },
                viewAll = viewAll,
            )
        }
        set(value) {
            preferences.edit().apply {
                if (value.host == null) remove(DASHBOARD_SCOPE_HOST)
                else putString(DASHBOARD_SCOPE_HOST, value.host)
                if (value.session == null || value.viewAll) remove(DASHBOARD_SCOPE_SESSION)
                else putString(DASHBOARD_SCOPE_SESSION, value.session)
                putBoolean(DASHBOARD_SCOPE_VIEW_ALL, value.viewAll)
            }.apply()
        }

    fun paneDraft(key: String, owner: com.lateapex.collie.data.ScopedCustodyOwner? = null,
        scope: com.lateapex.collie.data.RetainedReadScope? = null, localIdentity: String? = null): String = synchronized(sensitiveContentLock) {
        val identity = owner?.storageIdentity ?: localIdentity
        if (key in passwordBlockedKeys || scope?.target?.let { it in passwordBlockedTargets } == true) return@synchronized ""
        val savedOwner = volatileDraftOwners[key]
        if (savedOwner != null && identity != null && savedOwner != identity) return@synchronized ""
        volatileDrafts[key]?.let {
            if (scope != null && volatileDraftTargets[key] != scope.target) return@synchronized ""
            val time = volatileDraftTimes[key] ?: now()
            if (now() >= time && now() - time <= PANE_DRAFT_TTL_MS) return@synchronized it
            volatileDrafts.remove(key); volatileDraftOwners.remove(key); volatileDraftTimes.remove(key); volatileDraftTargets.remove(key)
        }
        if (owner != null && scope != null) offlineContentStore.draft(key, owner, scope)?.let { return@synchronized it }
        // Existing plaintext is not silently migrated or destroyed by installing this release.
        // Its exact old key remains readable; owned mismatches never become a successor's draft.
        val legacyOwner = preferences.getString("$PANE_DRAFT_OWNER_PREFIX$key", null)
        if (key in passwordRetiredLegacyKeys || scope != null && !offlineContentStore.legacyDraftAllowed(scope)) return@synchronized ""
        if (identity != null && legacyOwner != null && legacyOwner != identity) return@synchronized ""
        val savedAt = preferences.getLong("$PANE_DRAFT_SAVED_PREFIX$key", 0L)
        if (savedAt != 0L && (now() < savedAt || now() - savedAt > PANE_DRAFT_TTL_MS)) return@synchronized ""
        preferences.getString("$PANE_DRAFT_PREFIX$key", "").orEmpty()
    }

    fun savePaneDraft(
        key: String, value: String, owner: com.lateapex.collie.data.ScopedCustodyOwner? = null,
        scope: com.lateapex.collie.data.RetainedReadScope? = null, localIdentity: String? = null,
        current: () -> Boolean = { true },
    ) = synchronized(sensitiveContentLock) {
        if (!current()) return@synchronized
        if (value.isNotBlank() && (key in passwordBlockedKeys || scope?.target?.let { it in passwordBlockedTargets } == true ||
            scope != null && !offlineContentStore.draftTargetAllowed(scope))) return@synchronized
        volatileDraftRevisions[key] = draftRevisionClock.incrementAndGet()
        if (value.isBlank()) {
            volatileDrafts.remove(key); volatileDraftOwners.remove(key); volatileDraftTimes.remove(key); volatileDraftTargets.remove(key)
            if (owner != null) offlineContentStore.removeDraft(key, owner)
            // Editing/clearing this exact legacy entry is an explicit user action, not migration.
            preferences.edit().remove("$PANE_DRAFT_PREFIX$key").remove("$PANE_DRAFT_SAVED_PREFIX$key").remove("$PANE_DRAFT_OWNER_PREFIX$key").commit()
            return@synchronized
        }
        volatileDrafts[key] = value; volatileDraftTimes[key] = now()
        val identity = owner?.storageIdentity ?: localIdentity
        if (identity == null) volatileDraftOwners.remove(key) else volatileDraftOwners[key] = identity
        if (scope == null) volatileDraftTargets.remove(key) else volatileDraftTargets[key] = scope.target
        if (owner != null && fitsPaneDraftStore(value) && offlineContentStore.draftsEnabled &&
            offlineContentStore.saveDraft(key, value, owner, scope, current)) {
            // New writes are ciphertext only. A failed disk ACK never deletes a legacy source.
            preferences.edit().remove("$PANE_DRAFT_PREFIX$key").remove("$PANE_DRAFT_SAVED_PREFIX$key").remove("$PANE_DRAFT_OWNER_PREFIX$key").commit()
        }
    }

    val legacyDraftCount: Int get() = preferences.all.keys.count { it.startsWith(PANE_DRAFT_PREFIX) }

    /** Explicit consent migrates only entries whose existing owner matches this original owner.
     * Unknown legacy ownership remains intact for a separate deliberate discard choice. */
    fun migrateLegacyDrafts(
        owner: com.lateapex.collie.data.ScopedCustodyOwner,
        scope: com.lateapex.collie.data.RetainedReadScope? = null,
        current: () -> Boolean,
    ): Boolean = synchronized(sensitiveContentLock) {
        if (!offlineContentStore.draftsEnabled || !current() || scope == null || scope.owner != owner || scope.subject == null || scope.viewAll) return@synchronized false
        if (!offlineContentStore.legacyDraftAllowed(scope)) return@synchronized false
        val components = listOf(owner.origin, owner.pairedDeviceIdentity, scope.host.orEmpty(), scope.session.orEmpty(), requireNotNull(scope.subject))
        // Unknown old formats, hashes or ambiguous delimiters do not prove a pane target.
        if (components.any { it.contains('|') }) return@synchronized false
        val exactKey = components.joinToString("|")
        var success = true
        for (storedKey in preferences.all.keys.filter { it.startsWith(PANE_DRAFT_PREFIX) }) {
            val key = storedKey.removePrefix(PANE_DRAFT_PREFIX)
            if (key != exactKey) continue
            if (preferences.getString("$PANE_DRAFT_OWNER_PREFIX$key", null) != owner.storageIdentity) continue
            val value = preferences.getString(storedKey, null) ?: continue
            val savedAt = preferences.getLong("$PANE_DRAFT_SAVED_PREFIX$key", now())
            if (!fitsPaneDraftStore(value) || savedAt > now() || now() - savedAt > PANE_DRAFT_TTL_MS) continue
            if (!offlineContentStore.saveDraft(key, value, owner, scope, current)) { success = false; continue }
            if (!preferences.edit().remove(storedKey).remove("$PANE_DRAFT_SAVED_PREFIX$key").remove("$PANE_DRAFT_OWNER_PREFIX$key").commit()) success = false
        }
        success
    }

    fun discardLegacyDrafts(): Boolean = synchronized(sensitiveContentLock) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(PANE_DRAFT_PREFIX) || it.startsWith(PANE_DRAFT_SAVED_PREFIX) || it.startsWith(PANE_DRAFT_OWNER_PREFIX) }.forEach(editor::remove)
        editor.commit()
    }

    /** Explicit clear retires encrypted and volatile content; cosmetic preferences survive. */
    fun clearSensitiveContent() {
        check(offlineContentStore.clearDrafts()) { "Unable to clear encrypted drafts" }
        synchronized(sensitiveContentLock) {
        check(discardLegacyDrafts()) { "Unable to clear legacy drafts" }
        volatileDrafts.clear(); volatileDraftOwners.clear(); volatileDraftTimes.clear(); volatileDraftTargets.clear()
        }
    }

    fun retireSensitiveContent(owner: com.lateapex.collie.data.ScopedCustodyOwner) {
        check(offlineContentStore.retireOwner(owner)) { "Unable to retire encrypted content" }
        synchronized(sensitiveContentLock) {
        val identity = owner.storageIdentity
        val diskKeys = preferences.all.keys.filter { it.startsWith(PANE_DRAFT_PREFIX) }.map { it.removePrefix(PANE_DRAFT_PREFIX) }
        val retired = (diskKeys + volatileDrafts.keys).filter { key ->
            val stored = volatileDraftOwners[key] ?: preferences.getString("$PANE_DRAFT_OWNER_PREFIX$key", null)
            stored == null || stored == identity
        }.toSet()
        val editor = preferences.edit()
        retired.forEach { key -> editor.remove("$PANE_DRAFT_PREFIX$key").remove("$PANE_DRAFT_SAVED_PREFIX$key").remove("$PANE_DRAFT_OWNER_PREFIX$key") }
        check(editor.commit()) { "Unable to retire original-owner pane drafts" }
        retired.forEach { volatileDrafts.remove(it); volatileDraftOwners.remove(it); volatileDraftTimes.remove(it); volatileDraftTargets.remove(it) }
        }
    }

    fun fitsPaneDraftStore(value: String): Boolean = value.length <= MAX_PERSISTED_DRAFT_CHARS

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(themeMode.delegateMode)
    }

    companion object {
        internal const val PREFERENCES = "collie_native_preferences"
        internal const val THEME_MODE = "theme_mode"
        internal const val HAPTICS_ENABLED = "haptics_enabled"
        internal const val DIAGNOSTICS_ENABLED = "diagnostics_enabled"
        internal const val TERMINAL_FONT_SIZE = "terminal_font_size"
        internal const val DRAFT_FONT_SIZE = "draft_font_size"
        internal const val HANDS_FREE_ENABLED = "hands_free_enabled"
        internal const val PANE_DRAFT_PREFIX = "pane_draft:"
        internal const val PANE_DRAFT_SAVED_PREFIX = "pane_draft_saved:"
        internal const val MAX_PERSISTED_DRAFT_CHARS = 8_192
        internal const val PANE_DRAFT_TTL_MS = 48L * 60L * 60L * 1_000L
        internal const val DASHBOARD_RECENT_OPEN = "dashboard_recent_open"
        internal const val DASHBOARD_RECENT_SORT = "dashboard_recent_sort"
        internal const val DASHBOARD_SPACES_OPEN = "dashboard_spaces_open"
        internal const val CHANGES_DEPTH = "changes_depth"
        internal const val CHANGES_NESTED = "changes_nested"
        internal const val DASHBOARD_LAUNCH_OPEN = "dashboard_launch_open"
        internal const val DASHBOARD_SHELLS_OPEN = "dashboard_shells_open"
        internal const val PANE_STRIPS_VISIBLE = "pane_strips_visible"
        internal const val DASHBOARD_SCOPE_HOST = "dashboard_scope_host"
        internal const val DASHBOARD_SCOPE_SESSION = "dashboard_scope_session"
        internal const val DASHBOARD_SCOPE_VIEW_ALL = "dashboard_scope_view_all"
        private val sensitiveContentLock = Any()
        private val volatileDraftOwners = mutableMapOf<String, String>()
        private const val PANE_DRAFT_OWNER_PREFIX = "pane_draft_owner:"
        private val volatileDrafts = mutableMapOf<String, String>()
        private val volatileDraftTimes = mutableMapOf<String, Long>()
        private val volatileDraftTargets = mutableMapOf<String, String>()
        private val passwordBlockedTargets = mutableSetOf<String>()
        private val passwordBlockedKeys = mutableSetOf<String>()
        private val passwordRetiredLegacyKeys = mutableSetOf<String>()
        private val passwordDraftListeners = java.util.concurrent.CopyOnWriteArrayList<(com.lateapex.collie.data.PasswordDraftRetirement) -> Unit>()
        private val volatileDraftRevisions = mutableMapOf<String, Long>()
        private val draftRevisionClock = java.util.concurrent.atomic.AtomicLong()
        private val draftClearListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()

        private fun retirePasswordDraftTarget(preferences: android.content.SharedPreferences,
            scope: com.lateapex.collie.data.RetainedReadScope, blocked: Boolean): Boolean {
            val identity = scope.owner.storageIdentity
            val components = listOf(scope.owner.origin, scope.owner.pairedDeviceIdentity, scope.host.orEmpty(), scope.session.orEmpty(), scope.subject.orEmpty())
            val exactKey = components.takeIf { scope.subject != null && it.none { value -> value.contains('|') } }?.joinToString("|")
            var legacyDeleted = true
            val cutoff = synchronized(sensitiveContentLock) {
                if (!blocked) {
                    passwordBlockedTargets.remove(scope.target); exactKey?.let(passwordBlockedKeys::remove)
                    return true
                }
                passwordBlockedTargets += scope.target
                exactKey?.let { passwordBlockedKeys += it; passwordRetiredLegacyKeys += it }
                val keys = volatileDrafts.keys.filter { key -> volatileDraftOwners[key] == identity &&
                    (volatileDraftTargets[key] == scope.target || key == exactKey) }
                keys.forEach { key -> volatileDrafts.remove(key); volatileDraftOwners.remove(key); volatileDraftTimes.remove(key); volatileDraftTargets.remove(key) }
                if (exactKey != null && preferences.getString("$PANE_DRAFT_OWNER_PREFIX$exactKey", null) == identity) {
                    // Only this proved original legacy key is retired; failure is quarantined by
                    // the durable target legacy-retired flag and cannot resurrect after clean read.
                    legacyDeleted = preferences.edit().remove("$PANE_DRAFT_PREFIX$exactKey").remove("$PANE_DRAFT_SAVED_PREFIX$exactKey")
                        .remove("$PANE_DRAFT_OWNER_PREFIX$exactKey").commit()
                }
                draftRevisionClock.get()
            }
            val event = com.lateapex.collie.data.PasswordDraftRetirement(scope, cutoff)
            passwordDraftListeners.forEach { it(event) } // ACK/cancel never under RAM/storage locks.
            return legacyDeleted
        }
    }
}
