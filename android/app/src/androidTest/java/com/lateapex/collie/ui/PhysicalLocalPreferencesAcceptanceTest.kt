package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
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
import com.lateapex.collie.ui.terminal.TerminalPlainText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Settings and composer controls on the explicitly owned completion shell only. */
@RunWith(AndroidJUnit4::class)
class PhysicalLocalPreferencesAcceptanceTest {
    private data class Live(
        val app: CollieApplication,
        val connection: Connection,
        var actor: ScopedReadContext,
        val committed: CommittedConnectionSnapshot,
        val address: PaneAddress,
        val root: String,
    )

    @Test
    fun nativeSettingsSizesSurviveActivityRecreationAndReopenedSettings() {
        val live = live()
        val preferences = NativePreferences(live.app)
        val originalTerminalSize = preferences.terminalFontSize
        val originalDraftSize = preferences.draftFontSize
        val changedTerminalSize = adjacent(originalTerminalSize, 9, 16)
        val changedDraftSize = adjacent(originalDraftSize, 13, 16)

        ActivityScenario.launch(SettingsActivity::class.java).use { settings ->
            changeSetting(live, settings, R.id.settings_terminal_size_increase, R.id.settings_terminal_size_decrease,
                R.id.settings_terminal_size_value, originalTerminalSize, changedTerminalSize)
            changeSetting(live, settings, R.id.settings_draft_size_increase, R.id.settings_draft_size_decrease,
                R.id.settings_draft_size_value, originalDraftSize, changedDraftSize)
            assertEquals("Native Settings changes the stored terminal text size through its visible control",
                changedTerminalSize, preferences.terminalFontSize)
            assertEquals("Native Settings changes the stored composer text size through its visible control",
                changedDraftSize, preferences.draftFontSize)
            settings.recreate()
            awaitSettings(settings, changedTerminalSize, changedDraftSize)
        }

        // A fresh Settings Activity checks normal reopen behavior, then restores only the two
        // values this test changed, using the same visible native steppers.
        ActivityScenario.launch(SettingsActivity::class.java).use { reopened ->
            awaitSettings(reopened, changedTerminalSize, changedDraftSize)
            changeSetting(live, reopened, R.id.settings_terminal_size_increase, R.id.settings_terminal_size_decrease,
                R.id.settings_terminal_size_value, changedTerminalSize, originalTerminalSize)
            changeSetting(live, reopened, R.id.settings_draft_size_increase, R.id.settings_draft_size_decrease,
                R.id.settings_draft_size_value, changedDraftSize, originalDraftSize)
            assertEquals("The test restores the original terminal size via native Settings", originalTerminalSize, preferences.terminalFontSize)
            assertEquals("The test restores the original draft size via native Settings", originalDraftSize, preferences.draftFontSize)
        }
        assertSamePair(live)
    }

    @Test
    fun nativeQuickReplyAndSingleKeyPresetStayOnTheOwnedShellAndKeepComposerLocal() {
        val live = live()
        val repository = live.app.container.repository
        val marker = "COLLIE_CC13_QUICK_REPLY_OK"
        val reply = "printf 'COLLIE_CC13_QUICK_REPLY_OK\\n'"
        val keyLabel = "CC13 cursor left"
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { pane ->
            awaitOwnedComposer(pane, live)
            var oldDraft = ""
            var baselineRevision = 0L
            var baselineMarkerCount = 0
            pane.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                assertCurrent(live)
                assertTrue("The exact controlled shell must be write-admitted for deliberate physical controls", state.canWrite && repository.writesAllowed(live.address.scope, live.address.paneId))
                oldDraft = activity.findViewById<EditText>(R.id.reply_input).text.toString()
                assertTrue("This disposable shell starts with an empty composer", oldDraft.isEmpty())
                val paneBefore = requireNotNull(state.pane)
                baselineRevision = paneBefore.revision
                baselineMarkerCount = standaloneLineCount(paneBefore.text, marker)
            }

            // This is the single explicit harmless terminal reply approved for the controlled
            // w1:p1 shell. The quick-reply UI submits it normally; no direct repository call,
            // Send button, Enter action or other pane is involved.
            assertCurrent(live)
            onView(withId(R.id.quick_mode_button)).perform(click())
            awaitQuickReplyVisible(pane, reply)
            assertCurrent(live)
            onView(withText(reply)).perform(click())
            awaitActualReply(pane, live, marker, baselineRevision, baselineMarkerCount)

            // Choosing the preset's one Left key sends neither Enter nor a command. It only moves
            // the cursor at the shell prompt, and its success remains a real PaneViewModel result.
            assertCurrent(live)
            onView(withId(R.id.keys_mode_button)).perform(click())
            pane.onActivity { activity ->
                assertTrue("The actual native keypad drawer is shown", activity.findViewById<View>(R.id.key_row).isShown)
            }
            assertCurrent(live)
            onView(withId(R.id.pane_keys_more)).perform(click())
            onView(withText(keyLabel)).check { view, noView ->
                if (noView != null) throw noView
                assertTrue("The exact configured key preset is visible in the native More sheet", view.isShown)
            }
            pane.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val matches = state.keyPresets.filter { it.label == keyLabel }
                assertTrue("The loaded operator preset has one exact matching label", matches.size == 1)
                assertTrue("The preset sends only Left and is not marked dangerous",
                    matches.single().keys == listOf("Left") && !matches.single().dangerous)
            }
            assertCurrent(live)
            onView(withText(keyLabel)).perform(click())
            awaitKeySent(pane, live)
            // The preset handler leaves More open; dismiss it through normal system navigation
            // before touching the composer below.
            pressBack()
            assertCurrent(live)

            // Confirm the controlled screen still displays its owner-bound reply field and the
            // exact returned terminal marker after the key action. No new draft is entered here.
            pane.onActivity { activity ->
                assertCurrent(live)
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                assertTrue("The controlled pane remains the live original shell", state.pane?.paneId == live.address.paneId && state.savedCopy == null)
                assertTrue("The quick reply output remains visibly rendered", activity.findViewById<TextView>(R.id.terminal_text).text.toString().contains(marker))
                assertTrue("The composer remains the original empty local draft",
                    activity.findViewById<EditText>(R.id.reply_input).text.toString() == oldDraft)
            }

            val localDraft = "CC13_UNSENT_LOCAL_DRAFT"
            assertCurrent(live)
            onView(withId(R.id.reply_input)).perform(replaceText(localDraft))
            pane.onActivity { activity ->
                assertCurrent(live)
                assertTrue("Only the explicitly owned local composer receives this unsent draft",
                    activity.findViewById<EditText>(R.id.reply_input).text.toString() == localDraft)
            }
            pane.recreate()
            awaitOwnedComposer(pane, live)
            pane.onActivity { activity ->
                assertCurrent(live)
                assertTrue("The owner-bound unsent composer draft survives actual Activity recreation",
                    activity.findViewById<EditText>(R.id.reply_input).text.toString() == localDraft)
            }
            assertCurrent(live)
            onView(withId(R.id.quick_mode_button)).perform(click())
            awaitQuickReplyVisible(pane, reply)
            assertCurrent(live)
            onView(withId(R.id.quick_mode_button)).perform(click())
            assertCurrent(live)
            onView(withId(R.id.keys_mode_button)).perform(click())
            assertCurrent(live)
            onView(withId(R.id.pane_keys_more)).perform(click())
            onView(withText(keyLabel)).check { view, noView ->
                if (noView != null) throw noView
                assertTrue("The exact operator key preset remains available after Activity recreation", view.isShown)
            }
            assertCurrent(live)
            pressBack()
            assertCurrent(live)
            onView(withId(R.id.reply_input)).perform(replaceText(oldDraft))
            pane.onActivity { activity ->
                assertCurrent(live)
                assertTrue("The test restores the prior local composer value without sending it",
                    activity.findViewById<EditText>(R.id.reply_input).text.toString() == oldDraft)
            }
        }
        assertSamePair(live)
    }

    @Test
    fun zenIsAnExplicitTransientPaneActionAndComposerDrawerCanCloseWithoutSending() {
        val live = live()
        val repository = live.app.container.repository
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { pane ->
            awaitOwnedComposer(pane, live)
            assertCurrent(live)
            onView(withId(R.id.quick_mode_button)).perform(click())
            pane.onActivity { activity ->
                assertTrue("Quick replies open as a local composer drawer", activity.findViewById<View>(R.id.quick_actions_container).isVisible)
                assertTrue("The native Send button is not activated while inspecting quick replies", activity.findViewById<View>(R.id.send_button).isEnabled)
                assertTrue("The exact selected pane read actor remains current", repository.isCurrent(activity.findViewById<View>(R.id.reply_input).let {
                    androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value.paneReadContext
                }!!))
            }
            assertCurrent(live)
            onView(withId(R.id.quick_mode_button)).perform(click())
            assertCurrent(live)
            onView(withId(R.id.refresh_button)).perform(click())
            assertCurrent(live)
            val zenAction = withText(live.app.getString(R.string.pane_action_zen))
            onView(zenAction).perform(requestRectangleOnScreen())
            if (InstrumentationRegistry.getArguments().getString("nativeZenGeometry") == "true") {
                reportZenGeometry(zenAction, live)
                assertCurrent(live)
                pane.onActivity { activity ->
                    assertTrue("Geometry inspection leaves the original empty composer untouched",
                        activity.findViewById<EditText>(R.id.reply_input).text.isNullOrEmpty())
                    assertTrue("Geometry inspection cannot activate Zen",
                        activity.findViewById<View>(R.id.composer_chrome).isVisible)
                    assertTrue("Geometry inspection cannot submit a reply",
                        !androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value.sending)
                }
                return@use
            }
            expandVisibleOwnedSheet(org.hamcrest.Matchers.allOf(withId(R.id.collie_sheet_title), withText(R.string.pane_actions))) {
                assertCurrent(live)
                val current = requireNotNull(repository.readContext(live.address.scope, live.address.paneId))
                assertTrue("The sheet keeps the original displayed pane actor",
                    repository.isCurrent(current) && sameActorWitness(live.actor, current))
            }
            onView(zenAction).perform(requestRectangleOnScreen())
            awaitZenActionVisible(zenAction)
            assertCurrent(live)
            onView(zenAction).perform(click())
            pane.onActivity { activity ->
                assertTrue("Zen hides normal pane composer chrome", !activity.findViewById<View>(R.id.composer_chrome).isVisible)
                assertTrue("Zen leaves its native exit control visible", activity.findViewById<View>(R.id.new_output_button).isVisible)
            }
            assertCurrent(live)
            onView(withId(R.id.new_output_button)).perform(click())
            pane.onActivity { activity ->
                assertTrue("The explicit Zen exit restores the composer", activity.findViewById<View>(R.id.composer_chrome).isVisible)
                assertTrue("The original composer remains empty", activity.findViewById<EditText>(R.id.reply_input).text.isNullOrEmpty())
            }
            assertCurrent(live)
        }
        assertSamePair(live)
    }

    private fun live(): Live {
        val args = InstrumentationRegistry.getArguments()
        val originText = args.getString("liveOrigin")
        assumeTrue("liveOrigin was not supplied; physical local preferences are opt-in", originText != null)
        assertTrue("A supplied liveOrigin must not be blank", !originText.isNullOrBlank())
        val expectedOrigin = OriginValidator().requireValid(REQUIRED_ORIGIN)
        assertEquals("Only the task-owned completion HTTPS mount is accepted", expectedOrigin,
            OriginValidator().requireValid(requireNotNull(originText)))
        val paneId = args.getString("livePaneId")?.trim()
        assertEquals("This acceptance is limited to the explicitly owned w1:p1 stock shell", CONTROLLED_PANE_ID, paneId)
        val root = args.getString("liveRepoPath")?.trim()
        assertTrue("The exact dated disposable Git root is required", root?.matches(PhysicalFixturePaths.datedRootPattern) == true)
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Physical local preference acceptance requires the isolated completion app", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        val connection = app.container.connectionStore.connection.value
        assertTrue("The existing completion pair must remain installed", connection?.isPaired == true)
        val paired = requireNotNull(connection)
        assertEquals("The existing pair must match the exact task-owned mount", expectedOrigin, paired.origin)
        val scope = Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        )
        val address = PaneAddress(scope, CONTROLLED_PANE_ID)
        val repository = app.container.repository
        val actor = repository.readContext(scope, address.paneId)
        assertNotNull("The exact controlled pane must have an admitted read actor", actor)
        val captured = requireNotNull(actor)
        val committed = requireNotNull(app.container.connectionStore.captureCommitted())
        assertTrue("The original pair is the same committed profile owner as its first admitted actor",
            committed.connection === paired && committed.incarnationId != null)
        val result = runBlocking { repository.snapshot(scope, captured) }
        assertTrue("The controlled live snapshot must succeed", result is ApiResult.Success)
        assertTrue("The exact initial actor remains current after the snapshot", repository.isCurrent(captured))
        val inventory = (result as ApiResult.Success).value
        val shell = (inventory.shellPanes + inventory.agents).singleOrNull {
            it.paneId == address.paneId && it.host == scope.host && it.session == scope.session && (it.agent == "shell" || it.kind == "shell")
        }
        assertNotNull("The fixture must be the exact existing stock shell", shell)
        assertEquals("The fixture shell must already be rooted in the explicitly owned dated repository", root, shell?.cwd)
        assertTrue("The existing app pair remains the original connection", app.container.connectionStore.connection.value === paired)
        return Live(app, paired, captured, committed, address, requireNotNull(root))
    }

    private fun changeSetting(
        live: Live,
        scenario: ActivityScenario<SettingsActivity>,
        increaseId: Int,
        decreaseId: Int,
        valueId: Int,
        from: Int,
        to: Int,
    ) {
        val button = if (to > from) increaseId else decreaseId
        repeat(kotlin.math.abs(to - from)) {
            assertCurrent(live)
            onView(withId(button)).perform(scrollTo(), click())
        }
        scenario.onActivity { activity ->
            assertEquals("The visible native stepper must show the selected value", to,
                activity.findViewById<TextView>(valueId).text.toString().toInt())
        }
    }

    private fun awaitSettings(scenario: ActivityScenario<SettingsActivity>, terminal: Int, draft: Int) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { activity ->
                matched = activity.findViewById<TextView>(R.id.settings_terminal_size_value)?.text?.toString() == terminal.toString() &&
                    activity.findViewById<TextView>(R.id.settings_draft_size_value)?.text?.toString() == draft.toString()
            }
            if (matched) return
            SystemClock.sleep(100)
        }
        error("Native Settings did not restore the selected local font sizes")
    }

    private fun awaitOwnedComposer(scenario: ActivityScenario<PaneActivity>, live: Live) {
        var admitted: ScopedReadContext? = null
        await(scenario, "actual owned pane composer admission") { activity ->
            val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
            val read = state.paneReadContext
            val exactRead = read != null && read.key.origin == live.connection.origin.value &&
                read.key.pairedDeviceIdentity == live.actor.key.pairedDeviceIdentity &&
                read.key.host == live.address.scope.host && read.key.session == live.address.scope.session &&
                read.key.subjectIdentity == live.address.paneId && live.app.container.repository.isCurrent(read)
            val exactShell = state.panes.singleOrNull { it.paneId == live.address.paneId }?.let { it.cwd == live.root } == true
            if (exactRead && exactShell && activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) == live.address.paneId &&
                activity.intent.getStringExtra(PaneActivity.EXTRA_CWD) == live.root &&
                state.pane?.paneId == live.address.paneId && state.savedCopy == null && state.lastSuccessAt != null &&
                state.canWrite && state.composerReady && activity.findViewById<EditText>(R.id.reply_input).isEnabled) {
                admitted = read
                true
            } else false
        }
        // PaneActivity's normal constructor selects its pane and advances navigation generation.
        // Adopt that screen's actor once after proving it still belongs to the original committed
        // pair, exact host/session/pane and root; all UI actions below use this accepted actor.
        live.actor = requireNotNull(admitted)
        assertCurrent(live)
    }

    private fun awaitQuickReplyVisible(scenario: ActivityScenario<PaneActivity>, reply: String) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var visible = false
            scenario.onActivity { activity ->
                val container = activity.findViewById<ViewGroup>(R.id.quick_actions_container)
                visible = container.isShown && descendants(container).filterIsInstance<TextView>().any { it.text.toString() == reply && it.isShown }
            }
            if (visible) return
            SystemClock.sleep(100)
        }
        error("The configured harmless quick reply did not appear in the native composer drawer")
    }

    private fun awaitActualReply(
        scenario: ActivityScenario<PaneActivity>,
        live: Live,
        marker: String,
        baselineRevision: Long,
        baselineMarkerCount: Int,
    ) {
        val deadline = SystemClock.uptimeMillis() + 30_000L
        var lastEvidence = "no pane sample yet"
        while (SystemClock.uptimeMillis() < deadline) {
            var visible = false
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val displayedActor = state.paneReadContext
                val actorCurrent = displayedActor != null &&
                    sameActorWitness(live.actor, displayedActor) &&
                    live.app.container.repository.isCurrent(live.actor) &&
                    live.app.container.repository.isCurrent(displayedActor)
                val pane = state.pane
                val exactPane = pane?.paneId == live.address.paneId && state.savedCopy == null &&
                    state.panes.singleOrNull {
                        it.paneId == live.address.paneId && it.host == live.address.scope.host &&
                            it.session == live.address.scope.session && it.cwd == live.root
                    } != null
                val revision = pane?.revision
                val journalLines = TerminalPlainText.strip(pane?.text.orEmpty()).lineSequence().map(String::trim).toList()
                val renderedLines = TerminalPlainText.strip(
                    activity.findViewById<TextView>(R.id.terminal_text).text.toString(),
                ).lineSequence().map(String::trim).toList()
                val journalMarkerCount = journalLines.count { it == marker }
                val renderedMarkerCount = renderedLines.count { it == marker }
                val acknowledged = state.status?.contains(
                    activity.getString(R.string.pane_reply_sent), ignoreCase = true,
                ) == true
                lastEvidence = "exactPane=$exactPane actorCurrent=$actorCurrent revision=$revision " +
                    "revisionNotRegressed=${revision != null && revision >= baselineRevision} " +
                    "journalMarkerCount=$journalMarkerCount renderedMarkerCount=$renderedMarkerCount " +
                    "acknowledged=$acknowledged sending=${state.sending}"
                visible = exactPane && actorCurrent && revision != null && revision >= baselineRevision &&
                    journalMarkerCount == baselineMarkerCount + 1 &&
                    renderedMarkerCount == baselineMarkerCount + 1 && acknowledged && !state.sending
            }
            if (visible) return
            SystemClock.sleep(100)
        }
        error("The controlled shell did not display the genuine harmless quick-reply output; $lastEvidence")
    }

    private fun sameActorWitness(first: ScopedReadContext, second: ScopedReadContext): Boolean =
        first.key == second.key && first.connection === second.connection &&
            first.navigationGeneration == second.navigationGeneration &&
            first.connectionIncarnation == second.connectionIncarnation &&
            first.committedStore == second.committedStore &&
            first.committedSnapshot == second.committedSnapshot &&
            first.custodyProcessIdentity == second.custodyProcessIdentity

    private fun standaloneLineCount(text: String, expected: String): Int =
        TerminalPlainText.strip(text).lineSequence().count { it.trim() == expected }

    private fun awaitKeySent(scenario: ActivityScenario<PaneActivity>, live: Live) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var sent = false
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                sent = state.pane?.paneId == live.address.paneId && state.paneReadContext?.let(live.app.container.repository::isCurrent) == true &&
                    !state.sending && state.status?.contains("Left", ignoreCase = true) == true
            }
            if (sent) return
            SystemClock.sleep(100)
        }
        error("The exact native Left key preset did not report completion under the original pane actor")
    }

    private fun paneIntent(live: Live) = Intent(live.app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, live.address.paneId)
        .putExtra(PaneActivity.EXTRA_HOST, live.address.scope.host)
        .putExtra(PaneActivity.EXTRA_SESSION, live.address.scope.session)
        .putExtra(PaneActivity.EXTRA_AGENT, "shell")
        .putExtra(PaneActivity.EXTRA_CWD, live.root)

    private fun assertCurrent(live: Live) {
        assertTrue("The exact accepted pane actor must remain current before every UI control", live.app.container.repository.isCurrent(live.actor))
        assertTrue("No connection replacement is allowed during physical local preference acceptance",
            live.app.container.connectionStore.connection.value === live.connection)
        assertTrue("The committed profile incarnation remains exactly the original owner",
            live.committed == live.app.container.connectionStore.captureCommitted())
    }

    private fun assertSamePair(live: Live) {
        assertCurrent(live)
    }

    private fun await(scenario: ActivityScenario<PaneActivity>, label: String, condition: (PaneActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for $label")
    }

    private fun expandVisibleOwnedSheet(title: org.hamcrest.Matcher<View>, originalActor: () -> Unit) {
        // A normal drag begins at the fully visible title, outside the scrollable content.
        // Default swipeUp on the short title text can be below touch slop; use the existing
        // Espresso slow-swipe pattern with a bounded upward drag inside the visible window.
        val swipe = GeneralSwipeAction(
            Swipe.SLOW,
            GeneralLocation.CENTER,
            { view -> GeneralLocation.CENTER.calculateCoordinates(view).also { end ->
                val window = Rect()
                assertTrue("The owned sheet must have a visible window", view.rootView.getGlobalVisibleRect(window))
                end[1] = maxOf(window.top + 1f, end[1] - 180f * view.resources.displayMetrics.density)
            } },
            Press.FINGER,
        )
        onView(title).perform(object : ViewAction {
            override fun getDescription() = "Expand the actual owned sheet with one normal visible-header drag"
            override fun getConstraints(): org.hamcrest.Matcher<View> = swipe.constraints
            override fun perform(controller: UiController, view: View) {
                originalActor()
                swipe.perform(controller, view)
                originalActor()
            }
        })
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var expanded = false
            onView(title).perform(object : ViewAction {
                override fun getDescription() = "Observe actual sheet expansion without changing its behavior"
                override fun getConstraints(): org.hamcrest.Matcher<View> = isAssignableFrom(View::class.java)
                override fun perform(controller: UiController, view: View) {
                    originalActor()
                    val sheet = requireNotNull(view.rootView.findViewById<android.widget.FrameLayout>(
                        com.google.android.material.R.id.design_bottom_sheet))
                    expanded = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state ==
                        com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                }
            })
            if (expanded) return
            SystemClock.sleep(100)
        }
        error("The single normal header drag did not expand the owned sheet; no action was clicked")
    }

    private fun requestRectangleOnScreen(): ViewAction = object : ViewAction {
        override fun getDescription() = "Scroll the native Zen action fully into the visible More sheet"
        override fun getConstraints(): org.hamcrest.Matcher<View> = isAssignableFrom(View::class.java)
        override fun perform(uiController: UiController, view: View) {
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun reportZenGeometry(matcher: org.hamcrest.Matcher<View>, live: Live) {
        val geometry = mutableListOf<String>()
        onView(matcher).perform(object : ViewAction {
            override fun getDescription() = "Inspect only the owned Zen action and five parent layouts"
            override fun getConstraints(): org.hamcrest.Matcher<View> = isAssignableFrom(View::class.java)
            override fun perform(uiController: UiController, view: View) {
                assertCurrent(live)
                val current = requireNotNull(live.app.container.repository.readContext(live.address.scope, live.address.paneId))
                assertTrue("Geometry must retain the original displayed pane actor",
                    live.app.container.repository.isCurrent(current) && sameActorWitness(live.actor, current))
                uiController.loopMainThreadUntilIdle()
                var node: View? = view
                repeat(6) { depth ->
                    val item = node ?: return@repeat
                    val visible = Rect()
                    val hasVisibleRect = item.getGlobalVisibleRect(visible)
                    val group = item as? ViewGroup
                    val behavior = if (item.id == com.google.android.material.R.id.design_bottom_sheet)
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(item) else null
                    geometry += "depth=$depth class=${item.javaClass.simpleName} " +
                        "left=${item.left} top=${item.top} x=${item.x} y=${item.y} " +
                        "width=${item.width} height=${item.height} scrollY=${item.scrollY} " +
                        "hasGlobalVisibleRect=$hasVisibleRect globalVisibleRect=${visible.toShortString()} " +
                        "clipChildren=${group?.clipChildren} clipToPadding=${group?.clipToPadding} " +
                        "bottomSheetState=${behavior?.state} peekHeight=${behavior?.peekHeight} maxHeight=${behavior?.maxHeight}"
                    node = item.parent as? View
                }
                assertCurrent(live)
            }
        })
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("stream", "nativeZenGeometryOnly=true zenClicks=0 sendClicks=0\n" +
                geometry.joinToString("\n") + "\n")
        })
    }

    private fun awaitZenActionVisible(matcher: org.hamcrest.Matcher<View>) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val fullyVisible = try {
                onView(matcher).check(matches(isCompletelyDisplayed()))
                true
            } catch (_: NoMatchingViewException) {
                false
            } catch (_: AssertionError) {
                false
            }
            if (fullyVisible) return
            SystemClock.sleep(100)
        }
        error("The native Zen action did not become fully visible in the More sheet")
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun adjacent(value: Int, minimum: Int, maximum: Int): Int = if (value < maximum) value + 1 else value - 1

    private companion object {
        const val REQUIRED_ORIGIN = "https://prod.lateapexllc.com/completion/"
        const val CONTROLLED_PANE_ID = "w1:p1"
        const val WAIT_MS = 20_000L
    }
}
