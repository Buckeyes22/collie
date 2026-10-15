package com.lateapex.collie.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.ScopedRequestKey
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.PaneSummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativePanePinsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences(NativePanePins.PREFERENCES, Context.MODE_PRIVATE)
    private fun actor(origin: String = "https://collie.example/mount/", incarnation: String? = "paired-install-1",
        host: String? = null, session: String? = null, subject: String? = null,
        token: String? = "fixture-bearer", committed: Boolean = true) = ScopedReadContext(
        ScopedRequestKey(origin, 1, "fixture-owner", 0, host, session, subject),
        Connection(CollieOrigin(origin), "fixture phone", token), 0,
        connectionIncarnation = incarnation, committedStore = committed,
    )
    private fun pane(id: String = "w1:p1", space: String = "Work", host: String? = null,
        session: String? = null) = PaneSummary(id, "w1", space, 1, "t1", "shell", AgentStatus.IDLE,
        "/disposable", false, host = host, session = session)

    @Before fun reset() { preferences.edit().clear().commit() }

    @Test fun dormantPinReturnsWithoutPollWritesAndReusedWorkspaceDoesNotInheritIt() {
        val store = NativePanePins(context); val actor = actor(); val original = pane()
        assertTrue(store.setPinned(original, true, listOf(original), actor) { true })
        val raw = preferences.getString(NativePanePins.RECORDS, null)
        val revision = store.changes.value
        val matcher = store.matcher(actor) { true }
        assertEquals(emptyList<PaneSummary>(), emptyList<PaneSummary>().filter(matcher))
        assertFalse(matcher(original.copy(workspaceLabel = "Other")))
        assertTrue(matcher(original.copy(status = AgentStatus.BLOCKED, workspaceId = "renumbered")))
        assertEquals(raw, preferences.getString(NativePanePins.RECORDS, null))
        assertEquals(revision, store.changes.value)
        // The next explicit act observes the reused row and removes the obsolete workspace pin.
        val other = pane("w2:p1", "Second")
        assertTrue(store.setPinned(other, true, listOf(original.copy(workspaceLabel = "Other")), actor) { true })
        assertFalse(store.isPinned(original, actor) { true })
        assertTrue(store.isPinned(other, actor) { true })
    }

    @Test fun allSurfacesSeeAcknowledgedWritesWithoutSavingOldCopies() {
        val menu = NativePanePins(context); val dashboard = NativePanePins(context); val actor = actor()
        val before = dashboard.changes.value
        assertTrue(menu.setPinned(pane(), true, emptyList(), actor) { true })
        assertTrue(dashboard.changes.value > before)
        assertTrue(dashboard.isPinned(pane(), actor) { true })
        assertTrue(dashboard.setPinned(pane("w2:p1"), true, emptyList(), actor) { true })
        val restored = NativePanePins(context)
        assertTrue(restored.isPinned(pane(), actor) { true })
        assertTrue(restored.isPinned(pane("w2:p1"), actor) { true })
        assertTrue(menu.setPinned(pane(), false, emptyList(), actor) { true })
        assertFalse(restored.isPinned(pane(), actor) { true })
        assertTrue(restored.isPinned(pane("w2:p1"), actor) { true })
    }

    @Test fun boundEvictsOldestDormantBeforeOlderLiveAndProtectsNewPin() {
        val store = NativePanePins(context); val actor = actor()
        val panes = (0 until 32).map { pane("p$it") }
        panes.forEach { assertTrue(store.setPinned(it, true, panes, actor) { true }) }
        val newest = pane("new")
        assertTrue(store.setPinned(newest, true, panes.filterNot { it.paneId == "p5" }, actor) { true })
        assertEquals(32, JSONArray(preferences.getString(NativePanePins.RECORDS, null)).length())
        assertFalse(store.isPinned(panes[5], actor) { true })
        assertTrue(store.isPinned(panes[0], actor) { true })
        assertTrue(store.isPinned(newest, actor) { true })
        val next = pane("next")
        assertTrue(store.setPinned(next, true, panes + newest, actor) { true })
        assertFalse(store.isPinned(panes[0], actor) { true })
        assertTrue(store.isPinned(next, actor) { true })
    }

    @Test fun addressScopeAndWorkspaceFallbackMatchAcrossPaneAndUnionSurfaces() {
        val store = NativePanePins(context)
        val scoped = actor(host = "peer", session = "work", subject = "w1:p1")
        val original = pane(space = "")
        assertTrue(store.setPinned(original, true, emptyList(), scoped) { true })
        val union = actor()
        assertTrue(store.isPinned(original.copy(host = "peer", session = "work"), union) { true })
        assertFalse(store.isPinned(original.copy(host = "another", session = "work"), union) { true })
        assertFalse(store.isPinned(original.copy(host = "peer", session = "other"), union) { true })
        assertFalse(store.setPinned(original.copy(host = "another"), true, emptyList(), scoped) { true })
        assertFalse(store.setPinned(pane("different"), true, emptyList(), scoped) { true })
        val other = pane("different", host = "peer", session = "work")
        assertTrue(store.setPinned(other, true, emptyList(), union) { true })
        // A pane actor may render its switcher's same-scope pins, without authorizing another target's act.
        assertTrue(store.matcher(scoped) { true }(other))
    }

    @Test fun originalOwnerRetirementFencesOldMatcherAndOperatorActions() {
        val store = NativePanePins(context); val original = actor(); var current = true
        assertTrue(store.setPinned(pane(), true, emptyList(), original) { current })
        val matcher = store.matcher(original) { current }
        val saved = preferences.getString(NativePanePins.RECORDS, null)
        current = false
        assertFalse(matcher(pane()))
        assertFalse(store.setPinned(pane(), false, emptyList(), original) { current })
        assertFalse(store.dropAfterConfirmedClose(pane(), original) { current })
        assertEquals(saved, preferences.getString(NativePanePins.RECORDS, null))
        assertFalse(store.isPinned(pane(), actor(incarnation = "replacement")) { true })
        assertFalse(store.isPinned(pane(), actor(origin = "https://collie.example/other/")) { true })
        assertTrue(store.isPinned(pane(), actor()) { true })
    }

    @Test fun retirementBetweenEntryAndCommitDoesNotWriteAndUnsupportedCustodyRefuses() {
        val store = NativePanePins(context); var calls = 0
        assertFalse(store.setPinned(pane(), true, emptyList(), actor()) { ++calls == 1 })
        assertNull(preferences.getString(NativePanePins.RECORDS, null))
        assertFalse(store.setPinned(pane(), true, emptyList(), actor(incarnation = null)) { true })
        assertFalse(store.setPinned(pane(), true, emptyList(), actor(token = null)) { true })
        assertFalse(store.setPinned(pane(), true, emptyList(), actor(committed = false)) { true })
        assertNull(preferences.getString(NativePanePins.RECORDS, null))
    }

    @Test fun confirmedCloseOnlyDropsExactPlaceAndExplicitClearRemovesAllOwners() {
        val store = NativePanePins(context); val actor = actor()
        assertTrue(store.setPinned(pane(), true, emptyList(), actor) { true })
        assertTrue(store.dropAfterConfirmedClose(pane(space = "Other"), actor) { true })
        assertTrue(store.isPinned(pane(), actor) { true })
        assertTrue(store.dropAfterConfirmedClose(pane(), actor) { true })
        assertFalse(store.isPinned(pane(), actor) { true })
        assertTrue(store.setPinned(pane(), true, emptyList(), actor) { true })
        val another = actor(origin = "https://other.example/")
        assertTrue(store.setPinned(pane(), true, emptyList(), another) { true })
        assertTrue(store.clear())
        assertFalse(store.isPinned(pane(), actor) { true })
        assertFalse(store.isPinned(pane(), another) { true })
    }

    @Test fun retirementInsideSynchronousCommitRestoresPriorValueWithoutPublishingSuccess() {
        val actual = preferences
        var afterCommit: (() -> Unit)? = null
        val hookedPreferences = object : android.content.SharedPreferences by actual {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = actual.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun remove(key: String): android.content.SharedPreferences.Editor {
                        editor.remove(key)
                        return this
                    }
                    override fun commit(): Boolean = editor.commit().also { afterCommit?.invoke() }
                }
            }
        }
        val hookedContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = hookedPreferences
        }
        val store = NativePanePins(hookedContext); val actor = actor()
        assertTrue(store.setPinned(pane(), true, emptyList(), actor) { true })
        val before = preferences.getString(NativePanePins.RECORDS, null)
        val beforeRevision = store.changes.value
        var current = true
        afterCommit = { current = false }
        try {
            assertFalse(store.setPinned(pane("second"), true, emptyList(), actor) { current })
            assertFalse("The actual preference write must trigger retirement before its acknowledgement", current)
            assertEquals(before, preferences.getString(NativePanePins.RECORDS, null))
            assertEquals(beforeRevision, store.changes.value)
            current = true
            assertFalse(store.dropAfterConfirmedClose(pane(), actor) { current })
            assertFalse(current)
            assertEquals(before, preferences.getString(NativePanePins.RECORDS, null))
            assertEquals(beforeRevision, store.changes.value)
        } finally { afterCommit = null }
        assertTrue(store.isPinned(pane(), actor) { true })
        assertFalse(store.isPinned(pane("second"), actor) { true })
    }

    @Test fun malformedRecordReadNeverRepairsAndStorageDoesNotContainConnectionSecrets() {
        val store = NativePanePins(context); val actor = actor()
        assertTrue(store.setPinned(pane(), true, emptyList(), actor) { true })
        val array = JSONArray(preferences.getString(NativePanePins.RECORDS, null))
        array.put(JSONObject().put("owner", "malformed").put("pane", "foreign"))
        val raw = array.toString()
        preferences.edit().putString(NativePanePins.RECORDS, raw).commit()
        assertTrue(store.isPinned(pane(), actor) { true })
        assertEquals(raw, preferences.getString(NativePanePins.RECORDS, null))
        assertFalse(raw.contains("fixture-bearer"))
        assertFalse(raw.contains("https://collie.example"))
        assertFalse(raw.contains("paired-install-1"))
        assertFalse(raw.contains("fixture-owner"))
        preferences.edit().putString(NativePanePins.RECORDS, "not json").commit()
        assertFalse(store.isPinned(pane(), actor) { true })
        assertEquals("not json", preferences.getString(NativePanePins.RECORDS, null))
    }

    @Test fun statusChangesCannotAlterPinnedPlaceMembershipOrCallerOrder() {
        val store = NativePanePins(context); val actor = actor()
        val first = pane("first"); val second = pane("second")
        assertTrue(store.setPinned(second, true, emptyList(), actor) { true })
        assertTrue(store.setPinned(first, true, emptyList(), actor) { true })
        val inPlaceOrder = listOf(first, second)
        assertEquals(listOf("first", "second"), inPlaceOrder.filter(store.matcher(actor) { true }).map { it.paneId })
        assertEquals(listOf("first", "second"), inPlaceOrder.mapIndexed { index, p ->
            p.copy(status = if (index == 0) AgentStatus.WORKING else AgentStatus.BLOCKED)
        }.filter(store.matcher(actor) { true }).map { it.paneId })
    }
}
