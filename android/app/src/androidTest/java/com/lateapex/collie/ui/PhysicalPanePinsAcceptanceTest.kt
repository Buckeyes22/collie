package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CommittedConnectionSnapshot
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PaneSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf

/** Pin lifecycle through the real More menu, dashboard, and pane switcher on the owned stock shell. */
@RunWith(AndroidJUnit4::class)
class PhysicalPanePinsAcceptanceTest {
    private data class Live(
        val app: CollieApplication,
        val connection: Connection,
        val committed: CommittedConnectionSnapshot,
        val scope: Scope,
        val paneId: String,
        val root: String,
        var actor: ScopedReadContext,
    ) {
        val repository get() = app.container.repository
        val address get() = PaneAddress(scope, paneId)
    }

    @Test
    fun nativePinUnpinPersistsThroughDashboardSwitcherAndActivityRecreation() {
        val live = live()
        val pins = NativePanePins(live.app)
        val initial = exactSummary(live, live.actor)
        val originallyPinned = pins.isPinned(initial, live.actor, live.repository::isCurrent)
        var originalRevision = -1L
        var pinnedSummary: PaneSummary? = null

        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { pane ->
            adoptActualPaneActor(pane, live)
            pane.onActivity { activity ->
                originalRevision = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value.pane?.revision
                    ?: error("The real pane snapshot has no revision")
            }
            val currentSummary = exactSummary(live, live.actor)
            assertEquals("The exact owned shell's pre-test pin state is read from the local pin store",
                originallyPinned, pins.isPinned(currentSummary, live.actor, live.repository::isCurrent))

            if (originallyPinned) {
                setPinThroughMore(pane, live, currentSummary, pins, false)
            }
            setPinThroughMore(pane, live, currentSummary, pins, true)
            pinnedSummary = currentSummary
            assertTrue("The exact controlled shell is pinned before leaving its real PaneActivity",
                pins.isPinned(currentSummary, live.actor, live.repository::isCurrent))
        }

        // The dashboard is launched only after its prior PaneActivity scenario has ended. The
        // later switcher/recreation checks use a fresh normal PaneActivity for the same address.
        assertOriginalPair(live)
        assertPinnedDashboard(live, requireNotNull(pinnedSummary), pins)
        assertOriginalPair(live)

        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { pane ->
            adoptActualPaneActor(pane, live)
            val afterReopen = exactSummary(live, live.actor)
            assertTrue("The same owned pane remains pinned when opened in a fresh PaneActivity",
                pins.isPinned(afterReopen, live.actor, live.repository::isCurrent))
            assertPinnedSwitcher(pane, live, afterReopen)

            // Recreating this fresh controller verifies persistence without reusing a destroyed
            // ActivityScenario from the dashboard phase.
            pane.recreate()
            adoptActualPaneActor(pane, live)
            val afterRecreation = exactSummary(live, live.actor)
            assertTrue("The local pin survives actual PaneActivity recreation",
                pins.isPinned(afterRecreation, live.actor, live.repository::isCurrent))
            assertPinnedSwitcher(pane, live, afterRecreation)
            pane.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                assertEquals("Pinning never mutates the terminal journal revision", originalRevision, state.pane?.revision)
            }

            if (!originallyPinned) {
                setPinThroughMore(pane, live, afterRecreation, pins, false)
                assertFalse("The success path restores the fixture's original unpinned state",
                    pins.isPinned(afterRecreation, live.actor, live.repository::isCurrent))
            } else {
                // The first unpin exercised the other real More-menu action; pinning above restored
                // the originally pinned state. No cleanup write runs after a failed assertion.
                assertTrue("The success path preserves the fixture's original pinned state",
                    pins.isPinned(afterRecreation, live.actor, live.repository::isCurrent))
            }
            pane.onActivity { activity ->
                assertEquals("Pin/unpin navigation leaves the controlled terminal revision unchanged", originalRevision,
                    androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value.pane?.revision)
            }
            assertCurrent(live)
        }
    }

    private fun setPinThroughMore(
        pane: ActivityScenario<PaneActivity>,
        live: Live,
        summary: PaneSummary,
        pins: NativePanePins,
        pinned: Boolean,
    ) {
        assertCurrent(live)
        pane.onActivity { activity ->
            val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
            assertEquals("The native More action is bound to the exact displayed pane", live.paneId, state.pane?.paneId)
            val displayed = requireNotNull(state.paneReadContext)
            assertTrue("The displayed Pane actor is the same immutable authority witness",
                sameActorWitness(live.actor, displayed))
            assertTrue("The original accepted Pane actor remains current", live.repository.isCurrent(live.actor))
            assertTrue("The displayed Pane actor remains current", live.repository.isCurrent(displayed))
            assertEquals("No saved copy can receive a pin action", null, state.savedCopy)
        }
        assertEquals("The current exact fixture summary reflects the requested prior state", !pinned,
            pins.isPinned(summary, live.actor, live.repository::isCurrent))
        onView(withId(R.id.refresh_button)).perform(click())
        onView(withText(live.app.getString(if (pinned) R.string.pane_pin else R.string.pane_unpin))).perform(click())
        assertCurrent(live)
        pane.onActivity { activity ->
            val displayed = requireNotNull(
                androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value.paneReadContext)
            assertTrue("The action remains on the accepted immutable Pane authority witness",
                sameActorWitness(live.actor, displayed))
            assertTrue("The original accepted Pane actor remains current after the action",
                live.repository.isCurrent(live.actor))
            assertTrue("The displayed Pane actor remains current after the action",
                live.repository.isCurrent(displayed))
        }
        assertEquals("The real More-menu action synchronously updates only the requested local pin",
            pinned, pins.isPinned(summary, live.actor, live.repository::isCurrent))
    }

    private fun sameActorWitness(first: ScopedReadContext, second: ScopedReadContext): Boolean =
        first.key == second.key && first.connection === second.connection &&
            first.navigationGeneration == second.navigationGeneration &&
            first.connectionIncarnation == second.connectionIncarnation &&
            first.committedStore == second.committedStore &&
            first.committedSnapshot == second.committedSnapshot &&
            first.custodyProcessIdentity == second.custodyProcessIdentity

    private fun assertPinnedDashboard(live: Live, target: PaneSummary, pins: NativePanePins) {
        assertOriginalPair(live)
        ActivityScenario.launch<MainActivity>(Intent(live.app, MainActivity::class.java)).use { dashboard ->
            val deadline = SystemClock.uptimeMillis() + WAIT_MS
            var loaded = false
            while (SystemClock.uptimeMillis() < deadline && !loaded) {
                dashboard.onActivity { activity ->
                    val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
                    val actor = state.originalReadContext
                    val pane = state.panes.singleOrNull {
                        it.paneId == live.paneId && it.host == live.scope.host && it.session == live.scope.session
                    }
                    loaded = state.snapshot != null && state.lastSuccessAt != null &&
                        actor != null && pane != null && live.repository.isCurrent(actor) &&
                        actor.key.host == live.scope.host && actor.key.session == live.scope.session &&
                        pane.cwd == live.root && pins.isPinned(pane, actor, live.repository::isCurrent) &&
                        dashboardHasPinnedTarget(activity, target)
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("The real dashboard loaded the exact owned pinned pane under a current original-profile actor", loaded)
            dashboard.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.pane_list)
                val pinnedSection = (0 until list.childCount).map(list::getChildAt).firstOrNull { section ->
                    section.findViewById<TextView>(R.id.section_title)?.text?.toString() ==
                        activity.getString(R.string.pane_pinned_title).uppercase(java.util.Locale.ENGLISH)
                }
                assertNotNull("The real dashboard renders a Pinned section", pinnedSection)
                val sectionRows = requireNotNull(pinnedSection).findViewById<ViewGroup>(R.id.section_rows)
                val expectedTitle = dashboardPaneText(target).primary
                assertTrue("The exact owned pane is rendered inside the Pinned dashboard group",
                    descendants(sectionRows).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == expectedTitle })
                val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
                val actor = requireNotNull(state.originalReadContext)
                assertTrue("Dashboard grouping is derived from the same exact pane and current profile actor",
                    pins.isPinned(target, actor, live.repository::isCurrent))
                assertTrue("Dashboard verification preserves the exact committed pair", live.app.container.connectionStore.captureCommitted() == live.committed)
            }
        }
        assertTrue("The original paired connection remains installed after dashboard navigation",
            live.app.container.connectionStore.connection.value === live.connection)
    }

    private fun assertPinnedSwitcher(pane: ActivityScenario<PaneActivity>, live: Live, target: PaneSummary) {
        assertCurrent(live)
        val catalog = runBlocking { live.repository.launchers(live.scope, live.actor) }
        assertCurrent(live)
        assertTrue("The original pane actor reads the actual switcher's launcher-home configuration", catalog is ApiResult.Success)
        val launcherHome = (catalog as ApiResult.Success).value.home
        var expectedLabel: String? = null
        pane.onActivity { activity ->
            val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
            val actor = requireNotNull(state.paneReadContext)
            val row = PaneSwitcherModel.sections(
                panes = state.panes,
                current = live.address,
                recentOpen = true,
                recentNewest = true,
                shellsPreference = null,
                launchers = emptyList(),
                launcherHome = launcherHome,
                launchPreference = null,
                writesAuthorized = false,
                mux = state.mux,
                servers = state.servers,
                launching = emptySet(),
                pinned = NativePanePins(live.app).matcher(actor, live.repository::isCurrent),
            ).filterIsInstance<PaneSwitcherSection.Pinned>().single().rows.single {
                it.pane.paneId == target.paneId && it.pane.host == target.host && it.pane.session == target.session
            }
            assertEquals("The switcher row retains the exact pane address", target.paneId, row.pane.paneId)
            assertEquals("The switcher row retains the exact workspace identity", target.workspaceId, row.pane.workspaceId)
            assertTrue("The exact owned switcher row remains the current pane", row.active)
            expectedLabel = switcherLabel(row)
        }
        onView(withId(R.id.switcher_handle)).perform(click())
        onView(withText(live.app.getString(R.string.pane_pinned_title))).check { view, noView ->
            if (noView != null) throw noView
            assertTrue("The native pane switcher displays its Pinned group", view.isShown)
        }
        val label = requireNotNull(expectedLabel)
        val rowMatcher = allOf(withText(label), withContentDescription(
            live.app.getString(R.string.pane_switcher_current_description, label)))
        // The real switcher first paints without launcher data, then its one normal catalog
        // read rebuilds labels with the server-declared home abbreviation. Wait only for that
        // exact visible current-pane row; do not reopen the sheet or perform another action.
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var rendered = false
        while (!rendered && SystemClock.uptimeMillis() < deadline) {
            assertCurrent(live)
            try {
                onView(rowMatcher).check { view, noView ->
                    if (noView != null) throw noView
                    assertTrue("The Pinned group contains the exact owned pane's native label", view.isShown)
                }
                rendered = true
            } catch (_: NoMatchingViewException) {
                SystemClock.sleep(100)
            }
        }
        assertTrue("The real switcher must render the exact current-pane label using its actual launcher home", rendered)
        androidx.test.espresso.Espresso.pressBack()
        assertCurrent(live)
    }

    private fun switcherLabel(row: SwitcherPaneRow): String {
        val pane = row.pane
        val title = displayAgentTitle(pane.agent, pane.paneLabel ?: pane.sessionName ?: pane.soleTabName)
            ?: pane.agent.ifBlank { pane.paneId }
        val secondary = displayAgentTitle(pane.agent, row.secondary)?.takeUnless { it == title }
        return buildString {
            append(row.project)
            row.tab?.let { append(" · ").append(it) }
            append('\n').append(title)
            secondary?.let { append(" · ").append(it) }
        }
    }

    private fun live(): Live {
        val args = InstrumentationRegistry.getArguments()
        val originInput = args.getString("liveOrigin")
        assumeTrue("liveOrigin was not supplied; physical pin acceptance is opt-in", originInput != null)
        assertTrue("A supplied liveOrigin must not be blank", !originInput.isNullOrBlank())
        val origin = OriginValidator().requireValid(requireNotNull(originInput))
        assertEquals("Only the task-owned completion HTTPS origin is accepted",
            OriginValidator().requireValid(REQUIRED_ORIGIN), origin)
        assertTrue("The explicitly owned w1:p1 shell is required", args.getString("livePaneId") == CONTROLLED_PANE)
        val root = args.getString("liveRepoPath")?.trim()
        assertTrue("The dated owned repository root is required", root?.matches(Regex("/home/chris/collie-completion-[0-9]{8}")) == true)
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Pin acceptance requires the isolated completion app", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        val connection = app.container.connectionStore.connection.value
        assertTrue("The existing stock completion profile remains paired", connection?.isPaired == true)
        val paired = requireNotNull(connection)
        assertEquals("The installed pair is the exact task-owned origin", origin, paired.origin)
        val scope = Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        )
        assertEquals("The exact controlled scope must already be selected", scope, app.container.repository.selectedScope.value)
        val committed = requireNotNull(app.container.connectionStore.captureCommitted())
        assertTrue("The test captures the existing committed pairing incarnation", committed.connection === paired && committed.incarnationId != null)
        val actor = requireNotNull(app.container.repository.readContext(scope, CONTROLLED_PANE))
        val live = Live(app, paired, committed, scope, CONTROLLED_PANE, requireNotNull(root), actor)
        val summary = exactSummary(live, actor)
        assertEquals("The target is rooted at the exact owned Git fixture", live.root, summary.cwd)
        assertTrue("Only the stock shell is accepted", summary.agent == "shell" || summary.kind == "shell")
        assertCurrent(live)
        return live
    }

    private fun exactSummary(live: Live, actor: ScopedReadContext): PaneSummary {
        assertCurrent(live)
        val result = runBlocking { live.repository.snapshot(live.scope, actor) }
        assertTrue("The real original actor can read its live pane inventory", result is ApiResult.Success)
        assertCurrent(live)
        val snapshot = (result as ApiResult.Success).value
        val matches = (snapshot.agents + snapshot.shellPanes).filter {
            it.paneId == live.paneId && it.host == live.scope.host && it.session == live.scope.session
        }
        assertEquals("The exact pane address appears once in the live snapshot", 1, matches.size)
        return matches.single()
    }

    private fun adoptActualPaneActor(pane: ActivityScenario<PaneActivity>, live: Live) {
        var accepted: ScopedReadContext? = null
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (accepted == null && SystemClock.uptimeMillis() < deadline) {
            pane.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val actor = state.paneReadContext
                val summary = state.panes.singleOrNull { it.paneId == live.paneId && it.host == live.scope.host && it.session == live.scope.session }
                val valid = actor != null && actor.key.origin == live.connection.origin.value &&
                    actor.key.pairedDeviceIdentity == live.actor.key.pairedDeviceIdentity &&
                    actor.key.host == live.scope.host && actor.key.session == live.scope.session &&
                    actor.key.subjectIdentity == live.paneId && live.repository.isCurrent(actor) &&
                    state.pane?.paneId == live.paneId && state.savedCopy == null && state.lastSuccessAt != null &&
                    summary?.cwd == live.root && activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) == live.paneId
                if (valid) accepted = actor
            }
            if (accepted == null) SystemClock.sleep(100)
        }
        assertNotNull("The actual PaneActivity has loaded the exact original scope/pane/root under its current actor", accepted)
        val next = requireNotNull(accepted)
        assertTrue("Navigation may only advance within the same installed pairing and committed incarnation",
            next.connection === live.connection && next.connectionIncarnation == live.committed.incarnationId)
        live.actor = next
        assertCurrent(live)
    }

    private fun paneIntent(live: Live) = Intent(live.app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, live.paneId)
        .putExtra(PaneActivity.EXTRA_HOST, live.scope.host)
        .putExtra(PaneActivity.EXTRA_SESSION, live.scope.session)
        .putExtra(PaneActivity.EXTRA_AGENT, "shell")
        .putExtra(PaneActivity.EXTRA_CWD, live.root)

    private fun assertCurrent(live: Live) {
        assertTrue("Every action remains fenced to the captured pane actor", live.repository.isCurrent(live.actor))
        assertOriginalPair(live)
    }

    private fun assertOriginalPair(live: Live) {
        assertTrue("The same installed connection remains selected", live.app.container.connectionStore.connection.value === live.connection)
        assertEquals("The exact committed pairing owner and incarnation remain unchanged", live.committed,
            live.app.container.connectionStore.captureCommitted())
    }

    private fun dashboardHasPinnedTarget(activity: MainActivity, target: PaneSummary): Boolean {
        val list = activity.findViewById<RecyclerView>(R.id.pane_list) ?: return false
        val section = (0 until list.childCount).asSequence().map(list::getChildAt).firstOrNull { child ->
            child.findViewById<TextView>(R.id.section_title)?.text?.toString() ==
                activity.getString(R.string.pane_pinned_title).uppercase(java.util.Locale.ENGLISH)
        } ?: return false
        val rows = section.findViewById<ViewGroup>(R.id.section_rows) ?: return false
        val expectedTitle = dashboardPaneText(target).primary
        return descendants(rows).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == expectedTitle }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private companion object {
        const val REQUIRED_ORIGIN = "https://prod.lateapexllc.com/completion/"
        const val CONTROLLED_PANE = "w1:p1"
        const val WAIT_MS = 25_000L
    }
}
