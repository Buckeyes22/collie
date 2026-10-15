package com.lateapex.collie.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.TranscriptEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in, native-only acceptance around an operator-launched vendor journal fixture. */
@RunWith(AndroidJUnit4::class)
class PhysicalVendorJournalAcceptanceTest {
    /** Start the one configured harmless Codex fixture only through the normal native New UI. */
    @Test
    fun nativeNewStartsConfiguredVendorJournalFixtureOnce() {
        val app = appForCompletion()
        val connection = requireExistingPair(app)
        val repository = app.container.repository
        val args = InstrumentationRegistry.getArguments()
        val scope = scopeFromArgs()
        val root = requireFixtureRoot()
        val launcherActor = requireNotNull(repository.readContext(scope, null)) { "The paired host must supply its current New launcher actor" }
        val launcherResult = runBlocking { repository.launchers(scope, launcherActor) }
        assertTrue("The real host launcher catalog must be readable", launcherResult is ApiResult.Success)
        assertTrue("The launcher catalog actor must remain current", repository.isCurrent(launcherActor))
        val launcher = NewLaunchModel.choices((launcherResult as ApiResult.Success).value)
            .singleOrNull { it.label == LAUNCHER_LABEL }
        assertNotNull("The exact configured Codex journal launcher must exist once", launcher)
        assertTrue("The exact host-owned Codex journal launcher must be available", launcher?.available == true)
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        var createdPaneId: String? = null
        try {
            ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { main ->
                awaitMain(main, "live dashboard and native New control") { activity ->
                    val model = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
                    val state = model.state.value
                    model.currentScope == scope && state.snapshot != null && state.savedCopy == null && state.writeAuthorized &&
                        activity.findViewById<View>(R.id.new_workflow_button)?.let { it.isShown && it.isEnabled } == true
                }
                val before = snapshotForMain(app, scope)
                val beforeIds = before.panes().filter { it.host == scope.host && it.session == scope.session }
                    .map(PaneSummary::paneId).toSet()
                main.onActivity { activity ->
                    val model = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
                    assertEquals("Native New must use the supplied exact host/session scope", scope, model.currentScope)
                    assertTrue("The dashboard must be live before launching the fixture", model.state.value.savedCopy == null)
                    assertPairAndActor(app, connection, repository, launcherActor, "immediately before native New")
                    assertTrue("Fixture starts through the actual native New button",
                        activity.findViewById<View>(R.id.new_workflow_button).performClick())
                }

                val newActivity = awaitResumedNewActivity()
                awaitNew(newActivity, "the one configured vendor launcher and native Start are ready") { activity ->
                    launcherChoice(activity) != null && startButton(activity)?.isEnabled == true
                }
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertEquals("Native New must retain the exact supplied scope", scope, newActivity.intentScope())
                    val choice = requireNotNull(launcherChoice(newActivity))
                    assertTrue("The exact host-owned vendor launcher must be available", choice.isEnabled)
                    assertTrue("Select the configured Codex journal via the native launcher row", choice.performClick())
                    val cwd = folderInput(newActivity)
                    assertEquals("Configured vendor launcher must target the dated fixture root", root, cwd.text.toString())
                    assertPairAndActor(app, connection, repository, launcherActor, "immediately before native Start")
                    val start = requireNotNull(startButton(newActivity))
                    assertTrue("Configured launcher must be ready for one native Start", start.isEnabled)
                    assertTrue("Launch the configured vendor journal through the real native Start handler", start.performClick())
                }
                if (launcher?.noPrompts == true) {
                    assertPairAndActor(app, connection, repository, launcherActor, "immediately before native no-prompts confirmation")
                    onView(withText(R.string.launch_no_prompts_start)).inRoot(isDialog()).perform(click())
                }

                val paneActivity = awaitResumedPane("native New opens its created fixture pane", beforeIds) { activity ->
                    val paneId = activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID)
                    val actor = paneId?.let { repository.readContext(scope, it) }
                    paneId != null && actor != null && repository.isCurrent(actor) &&
                        activity.intent.getStringExtra(PaneActivity.EXTRA_CWD) == root
                }
                createdPaneId = requireNotNull(paneActivity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
                val actor = requireNotNull(repository.readContext(scope, createdPaneId))
                val (snapshot, pane) = awaitCodexPane(app, scope, actor, createdPaneId!!, root)
                assertEquals("The fixture launcher must create the actual Codex pane", "codex", pane.agent.lowercase())
                assertEquals("The actual created pane must belong to the dated fixture root", root, pane.cwd)
                val afterIds = snapshot.panes().filter { it.host == scope.host && it.session == scope.session }
                    .map(PaneSummary::paneId).toSet()
                assertTrue("Starting the Codex fixture must preserve all pre-existing panes", afterIds.containsAll(beforeIds))
                assertEquals("One native Start must add exactly its one actual pane", setOf(createdPaneId), afterIds - beforeIds)
                assertTrue("The pane actor must remain current after inventory read", repository.isCurrent(actor))
                Log.i(TAG, "vendorFixturePaneId=$createdPaneId agent=${pane.agent} cwdMatches=true source=native-new-workflow startCount=1")
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .singleOrNull { it is PaneActivity || it is NewActivity || it is MainActivity }?.finish()
                repository.selectSubject(priorScope, priorSubject)
            }
        }
        assertTrue("Vendor fixture launch must preserve the exact installed pairing", app.container.connectionStore.connection.value === connection)
    }

    /** Read-only check after the parent/operator binds the real vendor UUID through the stock API. */
    @Test
    fun readsActualVendorSessionChatAndHistoryWithoutSending() {
        val app = appForCompletion()
        val connection = requireExistingPair(app)
        val args = InstrumentationRegistry.getArguments()
        val scope = scopeFromArgs()
        val root = requireFixtureRoot()
        val paneId = args.getString("livePaneId")?.trim()?.takeIf(String::isNotEmpty)
        assertNotNull("Supply the actual native-created vendor pane ID", paneId)
        requireBoundedId(requireNotNull(paneId), "livePaneId")
        val sessionId = args.getString("liveCodexSessionId")?.trim()?.takeIf(String::isNotEmpty)
        assertNotNull("Supply only the actual vendor session UUID reported for this pane", sessionId)
        val expectedSessionId = UUID.fromString(requireNotNull(sessionId)).toString()
        assertEquals("The supplied session must be a canonical UUID", expectedSessionId, sessionId)
        val address = PaneAddress(scope, requireNotNull(paneId))
        val repository = app.container.repository
        val (actor, committed) = awaitOriginalPaneReadActor(app, connection, scope, address.paneId)
        val firstSnapshot = runBlocking { repository.snapshot(scope, actor) }
        assertTrue(
            "Exact scoped live snapshot must succeed (failureType=${safeFailureType(firstSnapshot)})",
            firstSnapshot is ApiResult.Success,
        )
        assertTrue("The original pane actor remains current after the first live snapshot", repository.isCurrent(actor))
        assertSame("The original installed connection remains selected after the first live snapshot",
            connection, app.container.connectionStore.connection.value)
        assertEquals("The original committed pairing incarnation remains unchanged after the first live snapshot",
            committed, app.container.connectionStore.captureCommitted())
        val snapshot = (firstSnapshot as ApiResult.Success).value
        val pane = exactPane(snapshot, scope, address.paneId)
        assertEquals("The exact supplied pane must still be a Codex pane", "codex", pane.agent.lowercase())
        assertEquals("The exact supplied Codex pane must remain inside the owned dated Git fixture", root, pane.cwd)
        assertTrue("The current pane actor must survive snapshot admission", repository.isCurrent(actor))

        val identityResult = runBlocking { repository.paneAgentIdentity(address, actor) }
        assertTrue("Stock agent identity read must succeed", identityResult is ApiResult.Success)
        assertTrue("The original pane actor must remain current after identity read", repository.isCurrent(actor))
        val identity = (identityResult as ApiResult.Success).value
        assertTrue("The identity response must be an admitted successful result", identity.ok)
        assertEquals("Identity endpoint must report this exact pane", address.paneId, identity.paneId)
        assertEquals("Only the actual vendor-reported Codex session UUID is accepted", expectedSessionId, identity.session?.value)
        assertEquals("The reported session must identify Codex", "codex", identity.session?.agent?.lowercase())

        val expectedReply = DEFAULT_EXPECTED_REPLY
        val chat = awaitLiveReply(app, address, actor, expectedReply)
        val assistantTurn = chat.upserts.singleOrNull { it.role == "assistant" && textOf(it) == expectedReply }
        assertNotNull("The real Chat API must contain exactly one exact literal vendor reply", assistantTurn)
        assertIdentitySession(app, address, actor, expectedSessionId, "after Chat read")
        assertTrue("Chat actor must remain current after read", repository.isCurrent(actor))
        assertTrue("Read-only transcript UI must retain the original installed profile", app.container.connectionStore.connection.value === connection)

        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        try {
            val intent = Intent(app, PaneActivity::class.java).apply {
                putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
                putExtra(PaneActivity.EXTRA_HOST, address.scope.host)
                putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
                putExtra(PaneActivity.EXTRA_AGENT, "codex")
                putExtra(PaneActivity.EXTRA_TITLE, "Vendor journal acceptance")
                putExtra(PaneActivity.EXTRA_CWD, root)
            }
            ActivityScenario.launch<PaneActivity>(intent).use { scenario ->
                awaitPane(scenario, "actual Codex PaneActivity has loaded the fixture pane") { activity ->
                    val paneActor = repository.readContext(scope, address.paneId)
                    paneActor != null && repository.isCurrent(paneActor) &&
                        activity.findViewById<View>(R.id.body_mode_row)?.isShown == true
                }
                scenario.onActivity { activity ->
                    assertEquals("Native Chat must remain bound to the exact supplied pane", address.paneId,
                        activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
                    val modeSwitch = activity.findViewById<MaterialButton>(R.id.body_mode_switch)
                    if (modeSwitch.isShown && modeSwitch.text.toString() == activity.getString(R.string.pane_body_switch_to_transcript)) {
                        assertTrue("Switch the real Codex pane to its transcript through the native body control", modeSwitch.performClick())
                    }
                }
                awaitPane(scenario, "transcript body displays the exact actual vendor reply") { activity ->
                    val body = activity.findViewById<View>(R.id.transcript_body)
                    body?.isShown == true && visibleText(body).any { it == expectedReply }
                }
                val uiActor = requireNotNull(repository.readContext(scope, address.paneId))
                assertTrue("Native transcript UI must retain a current exact pane actor", repository.isCurrent(uiActor))
                assertIdentitySession(app, address, uiActor, expectedSessionId, "before opening native History")
                scenario.onActivity { activity ->
                    val label = activity.findViewById<TextView>(R.id.body_mode_label)
                    assertTrue("Open full journal through the actual Transcript label", label.isShown && label.isEnabled && label.performClick())
                }
                val history = awaitResumedHistory()
                awaitHistory(history, "History displays the exact actual vendor reply") {
                    it.intent.getStringExtra("pane_id") == address.paneId &&
                        visibleText(it.findViewById<View>(R.id.history_entries)).any { text -> text == expectedReply }
                }
                assertIdentitySession(app, address, uiActor, expectedSessionId, "after native History read")
                assertTrue("Read-only vendor journal acceptance preserves the installed pairing", app.container.connectionStore.connection.value === connection)
                InstrumentationRegistry.getInstrumentation().runOnMainSync { history.finish() }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .singleOrNull { it is HistoryActivity || it is PaneActivity }?.finish()
                repository.selectSubject(priorScope, priorSubject)
            }
        }
        Log.i(TAG, "vendorPaneId=${address.paneId} codexSessionId=$expectedSessionId exactReplyPresent=true nativeHistoryPresent=true writes=0")
        assertTrue("Read-only vendor journal acceptance preserves the exact installed pairing", app.container.connectionStore.connection.value === connection)
    }

    /** Read-only native Find and genuine older-range navigation on the existing owned Codex journal. */
    @Test fun nativeHistoryFindNavigatesActualVendorJournalWithoutSending() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit nativeVendorHistoryFind=true is required", args.getString("nativeVendorHistoryFind") == "true")
        val app = appForCompletion()
        val connection = requireExistingPair(app)
        val repository = app.container.repository
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = scopeFromArgs()
        assertEquals("Only the owned stock scope may supply this journal", Scope(), scope)
        assertEquals("Only the existing owned vendor pane is accepted", FOLLOWUP_PANE, args.getString("livePaneId"))
        assertEquals("Only its genuine existing Codex session is accepted", FOLLOWUP_SESSION, args.getString("liveCodexSessionId"))
        assertEquals("Only the existing disposable Git root is accepted", FOLLOWUP_ROOT, requireFixtureRoot())
        val query = args.getString("liveHistoryFindTerm") ?: "COLLIE_CC13_VENDOR_"
        assertTrue("Search only known genuine fixture source text", query in setOf("COLLIE_CC13_VENDOR_", DEFAULT_EXPECTED_REPLY, FOLLOWUP_REPLY))
        val address = PaneAddress(scope, FOLLOWUP_PANE)
        val committed = requireNotNull(app.container.connectionStore.captureCommitted())
        assertSame("The installed committed pair owns the journal", connection, committed.connection)
        assertNotNull("The real committed pairing incarnation is required", committed.incarnationId)
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        var history: HistoryActivity? = null
        val intent = Intent(app, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
            putExtra(PaneActivity.EXTRA_AGENT, "codex")
            putExtra(PaneActivity.EXTRA_TITLE, "Vendor History Find acceptance")
            putExtra(PaneActivity.EXTRA_CWD, FOLLOWUP_ROOT)
        }
        try {
            ActivityScenario.launch<PaneActivity>(intent).use { scenario ->
                var accepted: ScopedReadContext? = null
                awaitPane(scenario, "the actual original Codex pane actor and native History entry point") { activity ->
                    val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                    val actor = state.paneReadContext
                    if (state.pane?.paneId == address.paneId && actor != null && repository.isCurrent(actor) &&
                        actor.connection === connection && actor.committedSnapshot == committed && state.savedCopy == null &&
                        (repository.readSource.value as? ReadSourceState.Live)?.key == actor.key &&
                        activity.findViewById<View>(R.id.body_mode_row)?.isShown == true) accepted = actor
                    accepted != null
                }
                val actor = requireNotNull(accepted)
                fun current() {
                    assertSame("Read-only History preserves the original credential instance", connection, app.container.connectionStore.connection.value)
                    assertEquals("Read-only History preserves the original committed incarnation", committed, app.container.connectionStore.captureCommitted())
                    assertTrue("The original exact pane actor remains current", repository.isCurrent(actor) && actor.key.subjectIdentity == address.paneId &&
                        actor.key.host == null && actor.key.session == null && actor.connectionIncarnation == committed.incarnationId)
                    assertEquals("The original local scope remains selected", scope, repository.selectedScope.value)
                    assertTrue("History uses the original live source rather than a saved copy", (repository.readSource.value as? ReadSourceState.Live)?.key == actor.key)
                }
                current()
                val pane = exactPane(snapshot(app, scope, actor), scope, address.paneId)
                assertEquals("The owned pane remains a genuine Codex pane", "codex", pane.agent.lowercase())
                assertEquals("The owned pane remains in its original root", FOLLOWUP_ROOT, pane.cwd)
                assertIdentitySession(app, address, actor, FOLLOWUP_SESSION, "before native History Find")
                fun page(before: String? = null): com.lateapex.collie.network.PaneHistoryResponse {
                    current()
                    val result = runBlocking { repository.history(address, limit = HistoryActivity.HISTORY_PAGE_SIZE, before = before, expectedContext = actor) }
                    current()
                    assertTrue("Actual original-actor journal history must be readable", result is ApiResult.Success)
                    val value = (result as ApiResult.Success).value
                    assertTrue("Only actual available history for this pane is accepted", value.available && value.paneId == address.paneId && value.entries.isNotEmpty())
                    return value
                }
                fun terminal(): Pair<String, Long> {
                    current()
                    val result = runBlocking { repository.readPane(address, markSeen = false, conditional = false, expectedContext = actor) }
                    current()
                    assertTrue("Actual terminal readback must succeed", result is ApiResult.Success)
                    return (result as ApiResult.Success).value.pane.let { it.text to it.revision }
                }
                val before = page()
                assertTrue("The actual vendor journal must contain its known genuine reply", before.entries.any { textOf(it) == DEFAULT_EXPECTED_REPLY })
                val terminalBefore = terminal()
                val launchJournalBefore = app.container.launchIntentStore.all()
                scenario.onActivity { activity ->
                    val mode = activity.findViewById<MaterialButton>(R.id.body_mode_switch)
                    if (mode.isShown && mode.text.toString() == activity.getString(R.string.pane_body_switch_to_transcript)) {
                        assertTrue("Open the real native transcript body", mode.performClick())
                    }
                }
                awaitPane(scenario, "actual transcript displays the genuine vendor reply") { activity ->
                    visibleText(activity.findViewById(R.id.transcript_body)).any { it == DEFAULT_EXPECTED_REPLY }
                }
                current()
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.body_mode_label)).perform(click())
                val opened = awaitResumedHistory().also { history = it }
                fun shownCount(): Int = opened.findViewById<TextView>(R.id.history_count).text.toString().substringBefore('/').toIntOrNull() ?: 0
                awaitHistory(opened, "normal History has loaded real source turns") {
                    current()
                    it.intent.getStringExtra("pane_id") == address.paneId && it.intent.getStringExtra("host") == null && it.intent.getStringExtra("session") == null &&
                        shownCount() > 0 && visibleText(it.findViewById(R.id.history_entries)).any { text -> text == DEFAULT_EXPECTED_REPLY }
                }
                var initialShown = 0
                instrumentation.runOnMainSync { initialShown = shownCount() }
                val hasOlderRange = initialShown < before.entries.size || before.hasMore
                var olderPage: com.lateapex.collie.network.PaneHistoryResponse? = null
                var olderExercised = false
                if (hasOlderRange) {
                    if (initialShown >= before.entries.size && before.hasMore) olderPage = page(before.entries.first().uuid)
                    current()
                    // Normal scrolling can itself grow History; click only if that read has not already grown it.
                    onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.history_load_older))
                        .perform(androidx.test.espresso.action.ViewActions.scrollTo())
                    var grew = false
                    instrumentation.runOnMainSync { grew = shownCount() > initialShown }
                    if (!grew) onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.history_load_older)).perform(click())
                    awaitHistory(opened, "native older navigation reveals additional genuine source turns") { current(); shownCount() > initialShown }
                    olderExercised = true
                } else instrumentation.runOnMainSync {
                    assertTrue("The real journal has no older native range", !opened.findViewById<View>(R.id.history_load_older).isShown)
                    val expected = opened.getString(if (before.fileTruncated) R.string.history_start_clipped else R.string.history_start)
                    assertTrue("Native History must honestly label its actual journal boundary", visibleText(opened.window.decorView).contains(expected))
                }
                val loaded = (olderPage?.entries.orEmpty() + before.entries).distinctBy { it.uuid }
                val stops = HistoryPresentation.search(loaded, query, resources = app.resources).map { it.key.entryUuid }.distinct()
                assertTrue("The genuine source term must match at least two turns for real result navigation", stops.size >= 2)
                current()
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.history_find_open)).perform(click())
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.history_find_query))
                    .perform(androidx.test.espresso.action.ViewActions.replaceText(query))
                awaitHistory(opened, "native Find reports real loaded-source matches") { activity ->
                    current()
                    activity.findViewById<TextView>(R.id.history_find_count).text.toString() == "0/${stops.size}" &&
                        activity.findViewById<EditText>(R.id.history_find_query).contentDescription == activity.getString(R.string.history_find_coverage)
                }
                fun findCount(expected: String) = awaitHistory(opened, "native Find reaches $expected and a visible highlighted source match") { activity ->
                    current()
                    activity.findViewById<TextView>(R.id.history_find_count).text.toString() == expected &&
                        descendants(activity.findViewById(R.id.history_entries)).filterIsInstance<TextView>().any { view ->
                            val bounds = android.graphics.Rect()
                            val text = view.text
                            view.isShown && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty && text.toString().contains(query, ignoreCase = true) &&
                                text is android.text.Spanned && text.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java).isNotEmpty()
                        }
                }
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.history_find_next)).perform(click())
                findCount("1/${stops.size}")
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.history_find_previous)).perform(click())
                findCount("${stops.size}/${stops.size}")
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.history_find_next)).perform(click())
                findCount("1/${stops.size}")
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.history_find_close)).perform(click())
                awaitHistory(opened, "closing native Find clears only its local search query") {
                    current(); it.findViewById<EditText>(R.id.history_find_query).text.isNullOrEmpty() && it.findViewById<View>(R.id.history_find_open).isShown
                }
                assertIdentitySession(app, address, actor, FOLLOWUP_SESSION, "after native History Find and older navigation")
                assertEquals("Native History and Find cannot mutate the genuine journal", before, page())
                olderPage?.let { assertEquals("Older navigation cannot mutate its actual source page", it, page(before.entries.first().uuid)) }
                assertEquals("Native History and Find cannot mutate terminal text or revision", terminalBefore, terminal())
                assertEquals("Read-only History navigation preserves the launch journal", launchJournalBefore, app.container.launchIntentStore.all())
                instrumentation.sendStatus(2, android.os.Bundle().apply {
                    putBoolean("nativeHistoryFindPassed", true)
                    putBoolean("nativeHistoryOlderAvailable", hasOlderRange)
                    putBoolean("nativeHistoryOlderExercised", olderExercised)
                    putBoolean("nativeHistorySourceHasMore", before.hasMore)
                    putBoolean("nativeHistorySourceClipped", before.fileTruncated)
                    putString("stream", "Real native History Find/Next/Previous passed; olderAvailable=$hasOlderRange olderExercised=$olderExercised sourceHasMore=${before.hasMore}. No journal or terminal mutation. An absent older range is not live pagination acceptance.\n")
                })
            }
        } finally {
            instrumentation.runOnMainSync {
                history?.finish()
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PaneActivity>().singleOrNull()?.finish()
                repository.selectSubject(priorScope, priorSubject)
            }
        }
    }

    /** One real follow-up, never a resend: the existing vendor process supplies every journal/cache fact. */
    @Test
    fun nativeHarmlessFollowupRefreshesActualVendorCacheWithoutReplaying() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit nativeVendorFollowup=true is required for the one harmless vendor turn",
            args.getString("nativeVendorFollowup") == "true")
        val app = appForCompletion()
        val connection = requireExistingPair(app)
        val repository = app.container.repository
        val scope = scopeFromArgs()
        assertEquals("The write fixture belongs only to the local stock scope", Scope(), scope)
        assertEquals("Only the owned stock pane may receive the follow-up", FOLLOWUP_PANE, args.getString("livePaneId"))
        assertEquals("Only the factual owned vendor UUID may receive the follow-up", FOLLOWUP_SESSION,
            args.getString("liveCodexSessionId"))
        assertEquals("Only the exact disposable Git fixture may receive input", FOLLOWUP_ROOT, requireFixtureRoot())
        val address = PaneAddress(scope, FOLLOWUP_PANE)
        val committed = requireNotNull(app.container.connectionStore.captureCommitted())
        assertSame("The original committed profile must own the installed pair", connection, committed.connection)
        assertNotNull("The installed pair must have a committed incarnation", committed.incarnationId)
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        val intent = Intent(app, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
            putExtra(PaneActivity.EXTRA_HOST, scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, scope.session)
            putExtra(PaneActivity.EXTRA_AGENT, "codex")
            putExtra(PaneActivity.EXTRA_TITLE, "Vendor cache acceptance")
            putExtra(PaneActivity.EXTRA_CWD, FOLLOWUP_ROOT)
        }
        try {
            ActivityScenario.launch<PaneActivity>(intent).use { scenario ->
                var displayedActor: ScopedReadContext? = null
                awaitPane(scenario, "the actual live owned Codex composer is admitted") { activity ->
                    assertSame("Waiting for the composer cannot adopt another connection", connection,
                        app.container.connectionStore.connection.value)
                    assertEquals("Waiting for the composer cannot adopt another incarnation", committed,
                        app.container.connectionStore.captureCommitted())
                    val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                    val actor = state.paneReadContext
                    val summary = state.panes.singleOrNull {
                        it.paneId == address.paneId && it.host == scope.host && it.session == scope.session
                    }
                    val ready = actor != null && repository.isCurrent(actor) && actor.connection === connection &&
                        actor.committedSnapshot == committed && actor.key.subjectIdentity == address.paneId &&
                        actor.requestScope == scope && state.pane?.paneId == address.paneId &&
                        summary != null && summary.cwd == FOLLOWUP_ROOT && summary.agent.equals("codex", ignoreCase = true) &&
                        state.savedCopy == null && state.lastSuccessAt != null && state.canWrite && state.composerReady &&
                        !state.sending && state.mutationError == null && repository.writesAllowed(scope, address.paneId) &&
                        activity.findViewById<EditText>(R.id.reply_input)?.let { it.isShown && it.isEnabled } == true
                    if (ready) displayedActor = actor
                    ready
                }
                val actor = requireNotNull(displayedActor)
                fun original() {
                    assertSame("The follow-up keeps the exact installed pair", connection, app.container.connectionStore.connection.value)
                    assertEquals("The follow-up keeps the exact committed incarnation", committed,
                        app.container.connectionStore.captureCommitted())
                    assertTrue("The one captured displayed actor must remain current", repository.isCurrent(actor))
                }
                fun liveState(activity: PaneActivity): PaneUiState {
                    original()
                    val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                    val current = requireNotNull(state.paneReadContext)
                    assertTrue("The displayed pane cannot adopt a successor actor", sameActorWitness(actor, current))
                    assertTrue("The displayed actor remains current", repository.isCurrent(current))
                    assertEquals("The displayed pane remains exact", address.paneId, state.pane?.paneId)
                    val summary = state.panes.single { it.paneId == address.paneId && it.host == scope.host && it.session == scope.session }
                    assertEquals("The live pane remains in the owned root", FOLLOWUP_ROOT, summary.cwd)
                    assertEquals("The live pane remains Codex", "codex", summary.agent.lowercase())
                    assertTrue("A saved copy never admits follow-up input", state.savedCopy == null)
                    return state
                }
                assertIdentitySession(app, address, actor, FOLLOWUP_SESSION, "before the sole follow-up")
                val beforeChat = awaitLiveReply(app, address, actor, DEFAULT_EXPECTED_REPLY)
                original()
                val originalReply = beforeChat.upserts.single { it.role == "assistant" && textOf(it) == DEFAULT_EXPECTED_REPLY }
                val beforeIds = beforeChat.upserts.map(TranscriptEntry::uuid).toSet()
                assertTrue("A prior follow-up prompt must prevent another send",
                    beforeChat.upserts.none { it.role == "user" && textOf(it) == FOLLOWUP_PROMPT })
                assertTrue("A prior follow-up reply must prevent another send",
                    beforeChat.upserts.none { it.role == "assistant" && textOf(it) == FOLLOWUP_REPLY })
                val beforePane = exactPane(snapshot(app, scope, actor), scope, address.paneId)
                assertEquals("The actual inventory still locates the disposable root", FOLLOWUP_ROOT, beforePane.cwd)
                val beforeCache = requireNotNull(beforePane.cache) { "The genuine vendor must already report cache telemetry" }
                val beforeRequest = requireNotNull(beforeCache.lastRequestAt)
                val beforeExpiry = requireNotNull(beforeCache.expiresAt)
                original()
                scenario.onActivity { activity ->
                    val state = liveState(activity)
                    assertTrue("Draft preparation requires fresh native write admission",
                        state.canWrite && state.composerReady && !state.sending && repository.writesAllowed(scope, address.paneId))
                    assertTrue("No attachment can be accidentally submitted",
                        !activity.findViewById<View>(R.id.composer_attachment_chip).isShown)
                    val input = activity.findViewById<EditText>(R.id.reply_input)
                    assertTrue("The composer must remain in ordinary phone draft mode",
                        !(input as DirectTypingEditText).directTypingEnabled)
                    assertTrue("The existing phone draft must be empty; never overwrite it", input.text.isNullOrEmpty())
                    input.setText(FOLLOWUP_PROMPT)
                }
                awaitPane(scenario, "normal draft rendering enables the one native Send") { activity ->
                    val state = liveState(activity)
                    assertTrue("A mutation error must stop this attempt", state.mutationError == null)
                    val input = activity.findViewById<EditText>(R.id.reply_input)
                    state.canWrite && state.composerReady && !state.sending &&
                        repository.writesAllowed(scope, address.paneId) && input.text.toString() == FOLLOWUP_PROMPT &&
                        activity.findViewById<View>(R.id.send_button).let { it.isShown && it.isEnabled }
                }
                assertIdentitySession(app, address, actor, FOLLOWUP_SESSION, "immediately before the sole Send")
                scenario.onActivity { activity ->
                    val state = liveState(activity)
                    assertTrue("The sole click requires the original fresh write admission",
                        state.canWrite && state.composerReady && !state.sending && state.mutationError == null &&
                            repository.writesAllowed(scope, address.paneId))
                    assertTrue("The final phone draft remains exactly the harmless prompt",
                        activity.findViewById<EditText>(R.id.reply_input).text.toString() == FOLLOWUP_PROMPT)
                    assertTrue("The sole Send must use ordinary composer mode",
                        !activity.findViewById<DirectTypingEditText>(R.id.reply_input).directTypingEnabled)
                    assertTrue("No attachment joined the reviewed draft",
                        !activity.findViewById<View>(R.id.composer_attachment_chip).isShown)
                    val send = activity.findViewById<View>(R.id.send_button)
                    assertTrue("One actual native Send must be visible, enabled and clicked once",
                        send.isShown && send.isEnabled && send.performClick())
                }
                awaitPane(scenario, "the production Codex guard positively acknowledges the single send") { activity ->
                    val state = liveState(activity)
                    assertTrue("Unknown or refused outcomes must stop without replay", state.mutationError == null)
                    !state.sending && state.status?.contains(activity.getString(R.string.pane_codex_accepted)) == true &&
                        activity.findViewById<EditText>(R.id.reply_input).text.isNullOrEmpty()
                }
                val afterChat = awaitLiveReply(app, address, actor, FOLLOWUP_REPLY)
                original()
                val user = afterChat.upserts.single { it.role == "user" && textOf(it) == FOLLOWUP_PROMPT }
                val assistant = afterChat.upserts.single { it.role == "assistant" && textOf(it) == FOLLOWUP_REPLY }
                assertTrue("The real user and assistant turns must both be new journal entries",
                    user.uuid !in beforeIds && assistant.uuid !in beforeIds && user.uuid != assistant.uuid)
                assertTrue("The bounded actual journal must preserve every earlier entry",
                    afterChat.upserts.map(TranscriptEntry::uuid).toSet().containsAll(beforeIds))
                assertEquals("The earlier exact vendor reply must survive once with its original UUID", originalReply.uuid,
                    afterChat.upserts.single { it.role == "assistant" && textOf(it) == DEFAULT_EXPECTED_REPLY }.uuid)
                assertIdentitySession(app, address, actor, FOLLOWUP_SESSION, "after the genuine follow-up journal")
                val deadline = SystemClock.uptimeMillis() + WAIT_MS
                var advanced = false
                while (!advanced && SystemClock.uptimeMillis() < deadline) {
                    original()
                    val currentPane = exactPane(snapshot(app, scope, actor), scope, address.paneId)
                    assertEquals("Cache polling cannot change the owned pane root", FOLLOWUP_ROOT, currentPane.cwd)
                    val cache = currentPane.cache
                    advanced = cache != null && cache.lastRequestAt?.let { it > beforeRequest } == true &&
                        cache.expiresAt?.let { it > beforeExpiry } == true
                    if (!advanced) SystemClock.sleep(1_000)
                }
                assertTrue("Actual vendor lastRequestAt and expiresAt must both advance after the one real turn", advanced)
                assertIdentitySession(app, address, actor, FOLLOWUP_SESSION, "after the genuine cache refresh")
                original()
                Log.i(TAG, "vendorPaneId=${address.paneId} codexSessionId=$FOLLOWUP_SESSION nativeSendCount=1 newUserCount=1 newAssistantCount=1 cacheAdvanced=true")
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.selectSubject(priorScope, priorSubject) }
        }
    }

    private fun sameActorWitness(first: ScopedReadContext, second: ScopedReadContext): Boolean =
        first.key == second.key && first.connection === second.connection &&
            first.navigationGeneration == second.navigationGeneration &&
            first.connectionIncarnation == second.connectionIncarnation &&
            first.committedStore == second.committedStore && first.committedSnapshot == second.committedSnapshot &&
            first.custodyProcessIdentity == second.custodyProcessIdentity

    private fun appForCompletion(): CollieApplication {
        val args = InstrumentationRegistry.getArguments()
        val originInput = args.getString("liveDurableOrigin")
        assumeTrue("liveDurableOrigin is required for this opt-in stock completion test", originInput != null)
        assertTrue("Supplied liveDurableOrigin must not be blank", !originInput.isNullOrBlank())
        val origin = OriginValidator().requireValid(requireNotNull(originInput))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Vendor journal acceptance requires the isolated completion app", app.packageName.endsWith(".completiontest"))
        assertEquals("Use only the fixed stock completion HTTPS origin", OriginValidator().requireValid(REQUIRED_ORIGIN), origin)
        return app
    }

    private fun requireExistingPair(app: CollieApplication): com.lateapex.collie.domain.Connection {
        val expected = OriginValidator().requireValid(requireNotNull(InstrumentationRegistry.getArguments().getString("liveDurableOrigin")))
        val connection = app.container.connectionStore.connection.value
        assertTrue("Acceptance must use the already-installed paired profile", connection?.isPaired == true)
        assertEquals("The existing pair must match the exact supplied stock origin", expected, connection?.origin)
        return requireNotNull(connection)
    }

    private fun scopeFromArgs() = Scope(
        host = InstrumentationRegistry.getArguments().getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
        session = InstrumentationRegistry.getArguments().getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
    )

    private fun requireFixtureRoot(): String {
        val root = InstrumentationRegistry.getArguments().getString("liveRepoPath")?.trim()
        assertTrue("Use the owned dated Git completion fixture root", root?.matches(PhysicalFixturePaths.datedRootPattern) == true)
        return requireNotNull(root)
    }

    private fun requireBoundedId(value: String, name: String) {
        assertTrue("$name must be a bounded public pane identifier", value.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
    }

    private fun snapshotForMain(app: CollieApplication, scope: Scope): SnapshotResponse {
        val repository = app.container.repository
        val actor = requireNotNull(repository.readContext(scope, null)) { "Live dashboard needs its current scoped actor" }
        val result = runBlocking { repository.snapshot(scope, actor) }
        assertTrue("Live dashboard snapshot must come from the real paired origin", result is ApiResult.Success)
        assertTrue("Dashboard read actor must remain current", repository.isCurrent(actor))
        return (result as ApiResult.Success).value
    }

    private fun snapshot(app: CollieApplication, scope: Scope, actor: ScopedReadContext): SnapshotResponse {
        val result = runBlocking { app.container.repository.snapshot(scope, actor) }
        assertTrue("Exact scoped live snapshot must succeed (failureType=${safeFailureType(result)})", result is ApiResult.Success)
        assertTrue("The supplied read actor must remain current", app.container.repository.isCurrent(actor))
        return (result as ApiResult.Success).value
    }

    private fun awaitOriginalPaneReadActor(
        app: CollieApplication,
        connection: com.lateapex.collie.domain.Connection,
        scope: Scope,
        paneId: String,
    ): Pair<ScopedReadContext, com.lateapex.collie.data.CommittedConnectionSnapshot> {
        val store = app.container.connectionStore
        val repository = app.container.repository
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var committed: com.lateapex.collie.data.CommittedConnectionSnapshot? = null
        var accepted: ScopedReadContext? = null
        while (SystemClock.uptimeMillis() < deadline && accepted == null) {
            assertSame("The existing paired profile cannot be replaced while waiting for its read actor",
                connection, store.connection.value)
            val currentCommitted = store.captureCommitted()
            if (currentCommitted != null) {
                assertSame("The original connection owns the current committed profile witness",
                    connection, currentCommitted.connection)
                assertNotNull("The existing production pairing must have a committed incarnation",
                    currentCommitted.incarnationId)
                if (committed == null) committed = currentCommitted
                else assertEquals("The committed profile witness cannot change during actor readiness",
                    committed, currentCommitted)
                val candidate = repository.readContext(scope, paneId)
                if (candidate != null && repository.isCurrent(candidate) &&
                    candidate.connection === connection && candidate.requestScope == scope &&
                    candidate.key.host == scope.host && candidate.key.session == scope.session &&
                    candidate.key.subjectIdentity == paneId &&
                    candidate.connectionIncarnation == currentCommitted.incarnationId &&
                    candidate.committedStore && candidate.committedSnapshot == currentCommitted
                ) {
                    accepted = candidate
                }
            }
            if (accepted == null) SystemClock.sleep(100)
        }
        assertNotNull("A current exact-scope pane read actor must be admitted before the first network read", accepted)
        val originalActor = requireNotNull(accepted)
        val originalCommitted = requireNotNull(committed)
        assertTrue("The accepted original pane actor remains current before its first network read",
            repository.isCurrent(originalActor))
        assertSame("The exact original installed pair remains selected before its first network read",
            connection, store.connection.value)
        assertEquals("The exact original committed incarnation remains current before its first network read",
            originalCommitted, store.captureCommitted())
        return originalActor to originalCommitted
    }

    private fun safeFailureType(result: ApiResult<*>): String =
        (result as? ApiResult.Failure)?.error?.javaClass?.simpleName ?: "none"

    private fun awaitCodexPane(
        app: CollieApplication,
        scope: Scope,
        actor: ScopedReadContext,
        paneId: String,
        root: String,
    ): Pair<SnapshotResponse, PaneSummary> {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            assertTrue("Codex startup observation must keep the original pane actor current", app.container.repository.isCurrent(actor))
            val result = runBlocking { app.container.repository.snapshot(scope, actor) }
            if (result is ApiResult.Success) {
                val snapshot = result.value
                val pane = snapshot.panes().singleOrNull { it.paneId == paneId && it.host == scope.host && it.session == scope.session }
                if (pane != null && pane.cwd == root && pane.agent.equals("codex", ignoreCase = true)) {
                    assertTrue("The original actor must remain current after Codex startup read", app.container.repository.isCurrent(actor))
                    return snapshot to pane
                }
            }
            SystemClock.sleep(1_000)
        }
        throw AssertionError("Bounded read-only startup observation did not find this pane running Codex in the fixture root")
    }

    private fun assertPairAndActor(
        app: CollieApplication,
        connection: com.lateapex.collie.domain.Connection,
        repository: com.lateapex.collie.data.CollieRepository,
        actor: ScopedReadContext,
        checkpoint: String,
    ) {
        assertTrue("Installed connection must remain unchanged $checkpoint", app.container.connectionStore.connection.value === connection)
        assertTrue("Original launcher actor must remain current $checkpoint", repository.isCurrent(actor))
    }

    private fun assertIdentitySession(
        app: CollieApplication,
        address: PaneAddress,
        actor: ScopedReadContext,
        expectedSessionId: String,
        checkpoint: String,
    ) {
        assertTrue("Original read actor must remain current $checkpoint", app.container.repository.isCurrent(actor))
        val result = runBlocking { app.container.repository.paneAgentIdentity(address, actor) }
        assertTrue("Read-only public pane identity must succeed $checkpoint", result is ApiResult.Success)
        assertTrue("Pane actor must remain current after identity read $checkpoint", app.container.repository.isCurrent(actor))
        val identity = (result as ApiResult.Success).value
        assertTrue("Public identity response must be successful $checkpoint", identity.ok)
        assertEquals("Public identity must still report this exact pane $checkpoint", address.paneId, identity.paneId)
        assertEquals("Reported Codex session must remain the exact actual UUID $checkpoint", expectedSessionId, identity.session?.value)
        assertEquals("Reported session must still belong to Codex $checkpoint", "codex", identity.session?.agent?.lowercase())
    }

    private fun exactPane(snapshot: SnapshotResponse, scope: Scope, paneId: String): PaneSummary =
        requireNotNull(snapshot.panes().singleOrNull { it.paneId == paneId && it.host == scope.host && it.session == scope.session }) {
            "Snapshot must contain the exact supplied host/session pane"
        }

    private fun SnapshotResponse.panes() = agents + shellPanes

    private fun awaitLiveReply(app: CollieApplication, address: PaneAddress, actor: ScopedReadContext, expectedReply: String): PaneChatResponse {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            assertTrue("Bounded Chat polling must keep the original pane actor current", app.container.repository.isCurrent(actor))
            val result = runBlocking { app.container.repository.chat(address, limit = 200, expectedContext = actor) }
            if (result is ApiResult.Success) {
                val page = result.value
                assertTrue("Actual vendor conversation must be available from the paired origin", page.available)
                assertEquals("Chat response must belong to the exact supplied pane", address.paneId, page.paneId)
                if (page.upserts.any { it.role == "assistant" && textOf(it) == expectedReply }) return page
            }
            SystemClock.sleep(1_000)
        }
        throw AssertionError("Read-only Chat polling did not observe the exact actual vendor reply")
    }

    private fun textOf(turn: TranscriptEntry): String = turn.parts.filter { it.kind == "text" }.joinToString("\n") { it.text.orEmpty() }

    private fun awaitMain(scenario: ActivityScenario<MainActivity>, checkpoint: String, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var observed = false
        while (!observed && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { observed = ready(it) }
            if (!observed) SystemClock.sleep(150)
        }
        assertTrue("Vendor journal acceptance did not reach: $checkpoint", observed)
    }

    private fun awaitNew(activity: NewActivity, checkpoint: String, ready: (NewActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var observed = false
        while (!observed && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NewActivity>().singleOrNull()
                observed = resumed === activity && ready(activity)
            }
            if (!observed) SystemClock.sleep(100)
        }
        assertTrue("Vendor journal native New workflow did not reach: $checkpoint", observed)
    }

    private fun awaitResumedNewActivity(): NewActivity = awaitActivity("native New is resumed") { it is NewActivity } as NewActivity

    private fun awaitResumedPane(checkpoint: String, beforeIds: Set<String>, ready: (PaneActivity) -> Boolean): PaneActivity {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var result: PaneActivity? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PaneActivity>().singleOrNull()
                val paneId = activity?.intent?.getStringExtra(PaneActivity.EXTRA_PANE_ID)
                if (activity != null && paneId != null && paneId !in beforeIds && ready(activity)) result = activity
            }
            result?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Vendor journal acceptance did not reach: $checkpoint")
    }

    private fun awaitActivity(checkpoint: String, ready: (Activity) -> Boolean): Activity {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var result: Activity? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .singleOrNull()?.takeIf(ready)
            }
            result?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Vendor journal acceptance did not reach: $checkpoint")
    }

    private fun awaitPane(scenario: ActivityScenario<PaneActivity>, checkpoint: String, ready: (PaneActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var observed = false
        while (!observed && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { observed = ready(it) }
            if (!observed) SystemClock.sleep(150)
        }
        assertTrue("Vendor journal native PaneActivity did not reach: $checkpoint", observed)
    }

    private fun awaitResumedHistory(): HistoryActivity = awaitActivity("native History screen opens") { it is HistoryActivity } as HistoryActivity

    private fun awaitHistory(activity: HistoryActivity, checkpoint: String, ready: (HistoryActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var observed = false
        while (!observed && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<HistoryActivity>().singleOrNull()
                observed = resumed === activity && ready(activity)
            }
            if (!observed) SystemClock.sleep(150)
        }
        assertTrue("Vendor journal native History did not reach: $checkpoint", observed)
    }

    private fun launcherChoice(activity: NewActivity): RadioButton? = descendants(activity.findViewById(android.R.id.content))
        .filterIsInstance<RadioButton>().singleOrNull { it.isShown && it.text.toString() == LAUNCHER_LABEL }

    private fun startButton(activity: NewActivity): MaterialButton? = buttons(activity.findViewById(android.R.id.content), activity.getString(R.string.new_start)).singleOrNull()

    private fun folderInput(activity: NewActivity): EditText = descendants(activity.findViewById(android.R.id.content))
        .filterIsInstance<EditText>().single { it.isShown }

    private fun NewActivity.intentScope() = Scope(
        host = intent.getStringExtra("host")?.takeIf(String::isNotBlank),
        session = intent.getStringExtra("session")?.takeIf(String::isNotBlank),
    )

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun visibleText(root: View): List<String> = descendants(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()

    private fun buttons(root: View, label: String): List<MaterialButton> = descendants(root).filterIsInstance<MaterialButton>()
        .filter { it.isShown && it.text.toString() == label }.toList()

    companion object {
        private const val WAIT_MS = 90_000L
        private const val REQUIRED_ORIGIN = "https://prod.lateapexllc.com/completion/"
        private const val LAUNCHER_LABEL = "Completion Codex journal"
        private const val DEFAULT_EXPECTED_REPLY = "COLLIE_CC13_VENDOR_SOL_JOURNAL_OK"
        private const val FOLLOWUP_PANE = "w7:p1"
        private const val FOLLOWUP_SESSION = "01a13895-0983-7f41-8dd0-363486e18e4d"
        private val FOLLOWUP_ROOT get() = PhysicalFixturePaths.root
        private const val FOLLOWUP_REPLY = "COLLIE_CC13_VENDOR_CACHE_OK"
        private const val FOLLOWUP_PROMPT = "Reply with exactly COLLIE_CC13_VENDOR_CACHE_OK. Do not use tools or inspect files."
        private const val TAG = "VendorJournalAccept"
    }
}
