package com.lateapex.collie.ui

import android.content.Context
import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PaneChatResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in live acceptance using the installed pairing; durable mutations require a disposable pane. */
@RunWith(AndroidJUnit4::class)
class PhysicalLiveReadAcceptanceTest {
    /** Starts one real upstream starter through the native New workflow and reports only its ID. */
    @Test
    fun nativeNewTriageStarterCreatesOneActualDurablePane() {
        val args = InstrumentationRegistry.getArguments()
        val originInput = args.getString("liveDurableOrigin")
        assumeTrue("liveDurableOrigin instrumentation argument was not supplied", originInput != null)
        assertTrue("Supplied liveDurableOrigin must not be blank", !originInput.isNullOrBlank())
        val expectedOrigin = com.lateapex.collie.network.OriginValidator().requireValid(requireNotNull(originInput))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("New triage acceptance requires the isolated completion app", app.packageName.endsWith(".completiontest"))
        val connection = app.container.connectionStore.connection.value
        assertTrue("New triage requires the already-paired durable origin", connection?.isPaired == true)
        assertEquals("The existing connection must match the exact supplied durable origin", expectedOrigin, connection?.origin)
        val repository = app.container.repository
        val scope = Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        )
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        try {
            ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { scenario ->
                awaitMain(scenario, "live dashboard and native New workflow are available") { activity ->
                    val viewModel = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
                    val state = viewModel.state.value
                    state.snapshot != null && state.savedCopy == null && state.writeAuthorized &&
                        viewModel.currentScope == scope &&
                        activity.findViewById<View>(R.id.new_workflow_button)?.isShown == true &&
                        activity.findViewById<View>(R.id.new_workflow_button)?.isEnabled == true
                }
                var beforePaneIds = emptySet<String>()
                scenario.onActivity { activity ->
                    val viewModel = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
                    assertEquals("New triage must use the exact supplied host/session scope", scope, viewModel.currentScope)
                    val state = viewModel.state.value
                    assertNotNull("New triage must start from a real live dashboard snapshot", state.snapshot)
                    assertTrue("The dashboard must be live before starting a new conversation", state.savedCopy == null)
                    beforePaneIds = state.panes.filter { it.host == scope.host && it.session == scope.session }.map { it.paneId }.toSet()
                    val newWorkflow = activity.findViewById<View>(R.id.new_workflow_button)
                    assertTrue("New triage must open through the normal native New workflow", newWorkflow.performClick())
                }

                val newActivity = awaitResumedNewActivity("native New workflow opens")
                awaitNew(newActivity, "actual New triage choice and Start control are ready") { activity ->
                    radioChoices(activity).any { it.text.toString() == NEW_TRIAGE_LABEL && it.isEnabled } &&
                        buttons(activity.findViewById(android.R.id.content), activity.getString(R.string.new_start)).singleOrNull()?.isEnabled == true
                }
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertEquals("Native New workflow must retain the requested scope", scope, newActivity.intentScope())
                    val choice = radioChoices(newActivity).single { it.text.toString() == NEW_TRIAGE_LABEL && it.isEnabled }
                    assertTrue("New triage must be selected through its actual native choice", choice.performClick())
                    val start = buttons(newActivity.findViewById(android.R.id.content), newActivity.getString(R.string.new_start)).single()
                    assertTrue("New triage must start through the actual native Start handler", start.performClick())
                }

                val launched = awaitLaunchedDurable("actual New triage result")
                val newPaneId = launched.first
                assertTrue("Native New triage must return a fresh actual pane ID", newPaneId.isNotBlank() && newPaneId !in beforePaneIds)
                awaitResumedDurable(launched.second, "fresh native triage ask is ready for operator review") { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    buttons(root, "Allow").size == 1 && buttons(root, "Deny").size == 1
                }
                val address = PaneAddress(scope, newPaneId!!)
                val actor = requireNotNull(repository.readContext(scope, address.paneId)) { "Newly launched pane must have its exact current read actor" }
                val snapshot = runBlocking { repository.snapshot(scope, actor) }
                assertTrue("A fresh real snapshot must include the created pane", snapshot is ApiResult.Success)
                assertTrue("The newly launched pane actor must remain current", repository.isCurrent(actor))
                val pane = ((snapshot as ApiResult.Success).value.agents + (snapshot as ApiResult.Success).value.shellPanes)
                    .singleOrNull { it.paneId == address.paneId && it.host == scope.host && it.session == scope.session }
                assertNotNull("The actual snapshot must contain the exact native-created pane", pane)
                val afterPaneIds = ((snapshot as ApiResult.Success).value.agents + (snapshot as ApiResult.Success).value.shellPanes)
                    .filter { it.host == scope.host && it.session == scope.session }.map { it.paneId }.toSet()
                assertEquals("The actual snapshot must show exactly one new pane from this click", setOf(newPaneId), afterPaneIds - beforePaneIds)
                assertEquals("New triage must resolve to the durable screenless agent", "durable", pane?.agent)
                Log.i(DIAGNOSTIC_TAG, "newPaneId=${address.paneId} paneAgent=${pane?.agent} paneStatus=${pane?.status} waitingForAskDecision=true starter=new-triage source=native-new-workflow")
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .singleOrNull { it is DurableChatActivity }
                resumed?.finish()
                repository.selectSubject(priorScope, priorSubject)
            }
        }
        assertTrue("New triage must preserve the same installed pairing", app.container.connectionStore.connection.value === connection)
    }

    /**
     * Read-only diagnosis for a conversation whose scripted follow-up has already been consumed.
     * Supply the same exact liveDurableOrigin/livePaneId/liveHost/liveSession as the prior run.
     * This does not answer an ask, send a turn, or alter server/app state.
     */
    @Test
    fun diagnoseCurrentDurablePromptContextReadOnly() {
        val args = InstrumentationRegistry.getArguments()
        val originInput = args.getString("liveDurableOrigin")
        assumeTrue("liveDurableOrigin instrumentation argument was not supplied", originInput != null)
        assertTrue("Supplied liveDurableOrigin must not be blank", !originInput.isNullOrBlank())
        val expectedOrigin = com.lateapex.collie.network.OriginValidator().requireValid(requireNotNull(originInput))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val connection = app.container.connectionStore.connection.value
        assertTrue("Diagnostic requires the existing paired connection", connection?.isPaired == true)
        assertEquals("Diagnostic must use the exact currently paired durable origin", expectedOrigin, connection?.origin)
        val paneId = args.getString("livePaneId")?.trim()
        assertTrue("Diagnostic requires the already-used live pane/conversation identifier", !paneId.isNullOrEmpty())
        assertTrue("The supplied current pane identifier must be bounded", requireNotNull(paneId).matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val address = PaneAddress(Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        ), requireNotNull(paneId))
        val repository = app.container.repository

        // This real read provides the precise upstream value to compare with the rendered native
        // context. It is intentionally separate from the UI's own independent poll.
        val page = requireLiveChat(app, address)
        val prompt = page.lastPrompt
        val latestUserText = page.upserts.lastOrNull { it.role == "user" }?.let(::textOf)
        assertTrue("The original pair must still be current after the real chat read", app.container.connectionStore.connection.value === connection)

        val intent = DurableChatActivity.intent(app, address, "Current durable conversation diagnostic")
        ActivityScenario.launch<DurableChatActivity>(intent).use { scenario ->
            val uiDeadline = SystemClock.uptimeMillis() + DIAGNOSTIC_UI_WAIT_MS
            var evidenceReady = false
            while (!evidenceReady && SystemClock.uptimeMillis() < uiDeadline) {
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    val promptVisible = prompt != null && hasPromptContext(activity, prompt.text)
                    val latestTurnVisible = latestUserText != null && visibleText(root).any { it == latestUserText }
                    evidenceReady = promptVisible || latestTurnVisible
                }
                if (!evidenceReady) SystemClock.sleep(100)
            }
            var composerEmpty = false
            var contextMatchesUpstream = false
            var visibleUserCount = 0
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                composerEmpty = composer(activity).text.isNullOrEmpty()
                contextMatchesUpstream = prompt != null && hasPromptContext(activity, prompt.text)
                visibleUserCount = latestUserText?.let { expected -> visibleText(root).count { it == expected } } ?: 0
            }
            val promptLength = prompt?.text?.length ?: 0
            val latestUserLength = latestUserText?.length ?: 0
            val promptIsLatestUser = prompt != null && latestUserText != null && prompt.text == latestUserText
            Log.i(DIAGNOSTIC_TAG,
                "lastPromptPresent=${prompt != null} lastPromptLength=$promptLength lastPromptTruncated=${prompt?.truncated == true} " +
                    "latestUserPresent=${latestUserText != null} latestUserLength=$latestUserLength promptEqualsLatestUser=$promptIsLatestUser " +
                    "composerEmpty=$composerEmpty promptContextExact=$contextMatchesUpstream exactVisibleLatestUserCount=$visibleUserCount")
            assertTrue("Current server chat has a nonempty upstream lastPrompt", prompt != null && prompt.text.isNotEmpty())
            assertTrue("Diagnostic is read-only and must preserve the existing pairing", app.container.connectionStore.connection.value === connection)
            val uiActor = repository.readContext(address.scope, address.paneId)
            assertNotNull("Durable UI must retain a current read actor", uiActor)
            assertTrue("Durable UI read actor must remain current", repository.isCurrent(requireNotNull(uiActor)))
            assertTrue("Current durable composer must remain empty", composerEmpty)
            assertTrue("Native prompt context must display the upstream lastPrompt exactly", contextMatchesUpstream)
            assertTrue("The server's latest user turn must be visibly rendered", visibleUserCount >= 1)
        }
    }

    /**
     * Actual UI and HTTP against the fixed official scripted durable-member example. Prepare a new
     * disposable seeded/new-triage pane waiting on its one write_note ask. Supply liveDurableOrigin,
     * livePaneId and, for a Crew member, optional liveHost/liveSession. The installed pairing must
     * already match. The script asks first; its follow-up Send is slow text, not a second question.
     * This proves activity recreation, not process death or a manufactured ambiguous response.
     */
    @Test
    fun configuredDurableOriginAnswersSendsReadsContextAndHistoryWithoutReplayingAfterRecreation() {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("liveDurableOrigin")
        assumeTrue("liveDurableOrigin instrumentation argument was not supplied", origin != null)
        assertTrue("Supplied liveDurableOrigin must not be blank", !origin.isNullOrBlank())
        val application = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val connection = application.container.connectionStore.connection.value
        assertTrue("Supplied liveDurableOrigin requires its already-paired installed connection", connection?.isPaired == true)
        val expected = com.lateapex.collie.network.OriginValidator().requireValid(requireNotNull(origin))
        assertEquals("Installed pairing must match the supplied exact durable origin", expected, connection?.origin)
        val paneId = args.getString("livePaneId")?.trim()?.takeIf(String::isNotEmpty)
        assertNotNull("Supplied liveDurableOrigin requires a disposable livePaneId", paneId)
        val address = PaneAddress(Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        ), requireNotNull(paneId))
        val repository = application.container.repository
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        val message = "Physical durable acceptance ${UUID.randomUUID()} 漢字 e\u0301 👨‍💻"
        var launchedHistory: HistoryActivity? = null
        try {
            ActivityScenario.launch<DurableChatActivity>(DurableChatActivity.intent(application, address, "Disposable scripted ticket")).use { scenario ->
                awaitDurable(scenario, "one harmless scripted write_note ask") { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    val labels = visibleText(root)
                    val allow = buttons(root, "Allow")
                    allow.size == 1 && allow.single().isEnabled && buttons(root, "Deny").size == 1 &&
                        labels.any { "write_note" in it && "Ticket 412" in it }
                }
                scenario.onActivity { activity ->
                    assertScreenless(activity)
                    val allow = buttons(activity.findViewById(android.R.id.content), "Allow").single()
                    assertTrue(allow.isEnabled)
                    assertTrue("Actual ask button must dispatch its native handler", allow.performClick())
                }
                awaitDurable(scenario, "completed scripted triage after Allow") { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    visibleText(root).any { "Triage done." in it } && buttons(root, "Allow").isEmpty()
                }
                scenario.onActivity { activity ->
                    composer(activity).setText(message)
                    val send = buttons(activity.findViewById(android.R.id.content), activity.getString(R.string.durable_chat_send)).single()
                    assertTrue("Fresh screenless send must be enabled", send.isEnabled)
                    assertTrue("Actual Send button must dispatch its native handler", send.performClick())
                }
                awaitDurable(scenario, "submitted Unicode turn and server-provided latest prompt") { activity ->
                    composer(activity).text.isNullOrEmpty() && hasPromptContext(activity, message) &&
                        visibleText(activity.findViewById(android.R.id.content)).count { it == message } >= 2
                }
                val before = requireLiveChat(application, address)
                val sentTurn = before.upserts.single { it.role == "user" && textOf(it) == message }
                assertEquals(message, before.lastPrompt?.text)

                // Recreate while the scripted follow-up is working. No test HTTP write or retry is
                // injected; the restarted native screen only reads its persisted intent journal.
                scenario.recreate()
                awaitDurable(scenario, "same latest prompt after activity recreation") { activity ->
                    assertScreenless(activity)
                    hasPromptContext(activity, message)
                }
                awaitDurable(scenario, "scripted follow-up reply") { activity ->
                    visibleText(activity.findViewById(android.R.id.content)).any { "Another thought, in detail." in it }
                }
                val after = requireLiveChat(application, address)
                assertEquals("Recreation must not append a second copy of the user turn", 1,
                    after.upserts.count { it.role == "user" && textOf(it) == message })
                assertEquals(sentTurn.uuid, after.upserts.single { it.role == "user" && textOf(it) == message }.uuid)
                assertEquals(message, after.lastPrompt?.text)
                scenario.onActivity { activity ->
                    val history = buttons(activity.findViewById(android.R.id.content), activity.getString(R.string.pane_action_history)).single()
                    assertTrue("Native History button must open the actual history screen", history.performClick())
                }
                val historyDeadline = System.currentTimeMillis() + LIVE_TIMEOUT_MS
                var historyHasMessage = false
                while (!historyHasMessage && System.currentTimeMillis() < historyDeadline) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<HistoryActivity>().singleOrNull()
                        if (activity != null) {
                            launchedHistory = activity
                            val entries = activity.findViewById<View>(R.id.history_entries)
                            historyHasMessage = entries != null && visibleText(entries).any { it == message }
                        }
                    }
                    if (!historyHasMessage) Thread.sleep(200)
                }
                assertTrue("Actual HistoryActivity must show the live durable Unicode user turn", historyHasMessage)
                InstrumentationRegistry.getInstrumentation().runOnMainSync { launchedHistory?.finish() }
                launchedHistory = null
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                launchedHistory?.finish()
                repository.selectSubject(priorScope, priorSubject)
            }
        }
        assertTrue("Live acceptance must preserve the installed paired connection", application.container.connectionStore.connection.value == connection)
    }

    private fun awaitDurable(scenario: ActivityScenario<DurableChatActivity>, checkpoint: String, ready: (DurableChatActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + LIVE_TIMEOUT_MS
        var observed = false
        while (!observed && System.currentTimeMillis() < deadline) {
            scenario.onActivity { observed = ready(it) }
            if (!observed) Thread.sleep(200)
        }
        assertTrue("Live durable UI did not reach: $checkpoint", observed)
    }

    private fun awaitMain(scenario: ActivityScenario<MainActivity>, checkpoint: String, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + LIVE_TIMEOUT_MS
        var observed = false
        while (!observed && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { observed = ready(it) }
            if (!observed) SystemClock.sleep(100)
        }
        assertTrue("Live native dashboard did not reach: $checkpoint", observed)
    }

    private fun awaitResumedNewActivity(checkpoint: String): NewActivity {
        val deadline = SystemClock.uptimeMillis() + LIVE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var found: NewActivity? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NewActivity>().singleOrNull()
            }
            found?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Live native New workflow did not reach: $checkpoint")
    }

    private fun awaitNew(activity: NewActivity, checkpoint: String, ready: (NewActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + LIVE_TIMEOUT_MS
        var observed = false
        while (!observed && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NewActivity>().singleOrNull()
                observed = resumed === activity && ready(activity)
            }
            if (!observed) SystemClock.sleep(100)
        }
        assertTrue("Live native New workflow did not reach: $checkpoint", observed)
    }

    private fun awaitLaunchedDurable(checkpoint: String): Pair<String, DurableChatActivity> {
        val deadline = SystemClock.uptimeMillis() + LIVE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var found: Pair<String, DurableChatActivity>? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<DurableChatActivity>().singleOrNull()
                val id = activity?.intent?.getStringExtra("pane_id")?.trim()?.takeIf(String::isNotEmpty)
                if (activity != null && id != null && activity.findViewById<View>(android.R.id.content)?.isShown == true) {
                    found = id to activity
                }
            }
            found?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Live native starter did not open: $checkpoint")
    }

    private fun awaitResumedDurable(activity: DurableChatActivity, checkpoint: String, ready: (DurableChatActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + LIVE_TIMEOUT_MS
        var observed = false
        while (!observed && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<DurableChatActivity>().singleOrNull()
                observed = resumed === activity && ready(activity)
            }
            if (!observed) SystemClock.sleep(100)
        }
        assertTrue("Live durable UI did not reach: $checkpoint", observed)
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun visibleText(root: View): List<String> = descendants(root).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text.toString() }.toList()

    private fun buttons(root: View, label: String): List<MaterialButton> = descendants(root).filterIsInstance<MaterialButton>()
        .filter { it.isShown && it.text.toString() == label }.toList()

    private fun radioChoices(activity: NewActivity): List<android.widget.RadioButton> =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.RadioButton>()
            .filter { it.isShown && it.contentDescription?.toString() == "Launch target: ${it.text}" }.toList()

    private fun NewActivity.intentScope() = Scope(
        host = intent.getStringExtra("host")?.takeIf(String::isNotBlank),
        session = intent.getStringExtra("session")?.takeIf(String::isNotBlank),
    )

    private fun composer(activity: DurableChatActivity): EditText = descendants(activity.findViewById(android.R.id.content))
        .filterIsInstance<EditText>().single { it.isShown && it.hint.toString() == activity.getString(R.string.durable_chat_send_hint) }

    private fun hasPromptContext(activity: DurableChatActivity, message: String): Boolean =
        descendants(activity.findViewById(android.R.id.content)).filter { it.contentDescription == activity.getString(R.string.chat_prompt_heading) && it.isShown }
            .any { visibleText(it).any { value -> value == message } }

    private fun assertScreenless(activity: Activity) {
        assertEquals("Screenless Chat exposes only its message composer", 1,
            descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().count())
        assertFalse("Screenless Chat must expose no terminal mode, direct typing or named-key controls",
            visibleText(activity.findViewById(android.R.id.content)).any { it in setOf("Terminal", "Keys", "Direct typing", "Enter", "Backspace") })
        assertTrue("Screenless Chat must not mount the terminal view", activity.findViewById<View>(R.id.terminal_text) == null)
    }

    private fun requireLiveChat(application: CollieApplication, address: PaneAddress): PaneChatResponse {
        val repository = application.container.repository
        val actor = requireNotNull(repository.readContext(address.scope, address.paneId)) { "Live Chat needs its original current actor" }
        val result = runBlocking { repository.chat(address, limit = 200, expectedContext = actor) }
        assertTrue("Real Collie HTTP Chat read must succeed", result is ApiResult.Success)
        assertTrue("Live Chat read must retain its actor", repository.isCurrent(actor))
        val page = (result as ApiResult.Success).value
        assertTrue("Actual durable Chat must be available", page.available)
        return page
    }

    private fun textOf(turn: com.lateapex.collie.network.TranscriptEntry): String =
        turn.parts.filter { it.kind == "text" }.joinToString("\n") { it.text.orEmpty() }

    @Test
    fun configuredOriginRendersAtLeastOneLivePane() {
        val origin = InstrumentationRegistry.getArguments().getString("liveOrigin")
        assumeTrue("liveOrigin instrumentation argument was not supplied", !origin.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as CollieApplication
        val connection = application.container.connectionStore.connection.value
        assumeTrue("A paired connection for liveOrigin is required", connection?.isPaired == true)
        val expected = com.lateapex.collie.network.OriginValidator().requireValid(origin!!)
        assumeTrue("The installed pairing must match liveOrigin", connection?.origin == expected)

        var paneCount = 0
        var error = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = System.currentTimeMillis() + 25_000L
            while (System.currentTimeMillis() < deadline && paneCount == 0 && error.isEmpty()) {
                Thread.sleep(250)
                scenario.onActivity { activity ->
                    val dashboard = activity.findViewById<View>(R.id.dashboard_panel)
                    if (dashboard.visibility == View.VISIBLE) {
                        paneCount = activity.findViewById<RecyclerView>(R.id.pane_list)
                            .adapter?.itemCount ?: 0
                    }
                    error = activity.findViewById<android.widget.TextView>(R.id.error_text)
                        .text?.toString().orEmpty()
                }
            }
        }
        assertTrue("live read failed: ${error.ifEmpty { "no panes rendered" }}", paneCount > 0)
    }

    private companion object {
        const val LIVE_TIMEOUT_MS = 45_000L
        const val DIAGNOSTIC_TAG = "ColliePromptDiagnostic"
        const val DIAGNOSTIC_UI_WAIT_MS = 12_000L
        const val NEW_TRIAGE_LABEL = "New triage"
    }
}
