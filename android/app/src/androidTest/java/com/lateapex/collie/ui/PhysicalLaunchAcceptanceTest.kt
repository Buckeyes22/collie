package com.lateapex.collie.ui

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.LaunchIntentReceiptMode
import com.lateapex.collie.data.LaunchIntentState
import com.lateapex.collie.data.LaunchIntentStore
import com.lateapex.collie.data.StoredLaunchIntent
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PaneSummary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real operator launcher / published API only. External process death is a separate two-phase run. */
@RunWith(AndroidJUnit4::class)
class PhysicalLaunchAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private data class Live(val app: CollieApplication, val scope: Scope, val owner: String)

    private fun required(name: String): String = args.getString(name)?.takeIf(String::isNotBlank)
        ?: error("Supplied liveOrigin requires explicit $name")

    private fun withLive(test: (Live) -> Unit) {
        val origin = args.getString("liveOrigin")
        assumeTrue("No liveOrigin supplied; physical launch acceptance is opt-in", origin != null)
        assertFalse("Supplied liveOrigin must not be blank", origin.isNullOrBlank())
        assertTrue("Launch acceptance is confined to the isolated app", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        assertEquals("Only the declared harmless operator launcher is permitted", LABEL, required("liveLauncherName"))
        assertEquals("Only the task-owned fixture folder is permitted", ROOT, required("liveRepoPath"))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val repository = app.container.repository
        val connection = app.container.connectionStore.connection.value
        assertTrue("Preserve the existing live pairing", connection?.isPaired == true)
        assertEquals(app.container.originValidator.requireValid(requireNotNull(origin)), connection?.origin)
        val scope = Scope(args.getString("liveHost")?.takeIf(String::isNotBlank), args.getString("liveSession")?.takeIf(String::isNotBlank))
        val previousScope = repository.selectedScope.value
        val previousSubject = repository.readContext()?.key?.subjectIdentity
        try {
            instrumentation.runOnMainSync { repository.selectSubject(scope, null) }
            val actor = requireNotNull(repository.readContext(scope))
            val owner = LaunchIntentStore.sha256(actor.key.pairedDeviceIdentity)
            val config = runBlocking { repository.config(scope, actor) }
            assertTrue("Original stock host must declare owner-bound receipts", config is ApiResult.Success && config.value.receiptContextBound == true)
            val catalog = runBlocking { repository.launchers(scope, actor) }
            assertTrue("Read the actual launch catalog under its original actor", catalog is ApiResult.Success)
            val row = (catalog as ApiResult.Success).value.items.orEmpty().singleOrNull { it.label == LABEL }
            assertNotNull("Private operator declaration must be present exactly once", row)
            assertEquals(COMMAND, row?.start?.command)
            assertEquals(ROOT, row?.cwd)
            assertEquals("operator", row?.source)
            assertTrue("Harmless command must be available without prompt skipping", row?.available == true && row.noPrompts == false)
            assertTrue(repository.isCurrent(actor))
            test(Live(app, scope, owner))
        } finally {
            assertSame("Acceptance must retain the original paired connection", connection, app.container.connectionStore.connection.value)
            instrumentation.runOnMainSync { repository.selectSubject(previousScope, previousSubject) }
        }
    }

    @Test fun nativeConfiguredLaunchCreatesExactlyOnePaneAcrossActivityRecreation() = withLive { live ->
        assertTrue("Resolve earlier uncertainty before starting another launch", live.app.container.repository.unresolvedLaunchIntents(live.scope).isEmpty())
        val beforePanes = panes(live).map { it.paneId }.toSet()
        val beforeIds = live.app.container.launchIntentStore.all().map { it.intent.requestId }.toSet()
        ActivityScenario.launch<NewActivity>(NewActivity.intent(live.app, live.scope)).use { scenario ->
            chooseLauncher(scenario)
            scenario.recreate() // Rotation/recreation coverage; this is expressly not process death.
            waitFor("Recreated New screen must retain the actual selected launcher") {
                var selected = false
                scenario.onActivity { selected = descendants(it.window.decorView).filterIsInstance<RadioButton>().any { row -> row.text.toString() == LABEL && row.isChecked && row.isEnabled } }
                selected
            }
            onView(withText(R.string.new_start)).perform(scrollTo(), click())
            val paneActivity = awaitPane()
            try {
                val createdId = requireNotNull(paneActivity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
                val record = newRecord(live, beforeIds, LaunchIntentState.succeeded)
                assertFrozen(live, record)
                assertOneNewPane(live, beforePanes, createdId)
                assertEquals(live.scope.host, paneActivity.intent.getStringExtra(PaneActivity.EXTRA_HOST))
                assertEquals(live.scope.session, paneActivity.intent.getStringExtra(PaneActivity.EXTRA_SESSION))
                assertEquals(ROOT, paneActivity.intent.getStringExtra(PaneActivity.EXTRA_CWD))
                emit(record, beforePanes, createdId, live.owner)
            } finally { instrumentation.runOnMainSync { paneActivity.finish() } }
        }
    }

    /** Parent must hold only the POST /completion/api/launch RESPONSE, leaving normal reads intact. */
    @Test fun holdNativeLaunchForExternalProcessDeath() = withLive { live ->
        assertEquals("External kill preparation requires an explicit controlled response hold", "true", required("liveHoldForKill"))
        assertTrue(live.app.container.repository.unresolvedLaunchIntents(live.scope).isEmpty())
        val beforePanes = panes(live).map { it.paneId }.toSet()
        val beforeIds = live.app.container.launchIntentStore.all().map { it.intent.requestId }.toSet()
        ActivityScenario.launch<NewActivity>(NewActivity.intent(live.app, live.scope)).use { scenario ->
            chooseLauncher(scenario)
            onView(withText(R.string.new_start)).perform(scrollTo(), click())
            val record = newRecord(live, beforeIds, LaunchIntentState.pending)
            assertFrozen(live, record)
            var createdId: String? = null
            waitFor("Actual launch must appear in stock snapshot while response is held") {
                val added = panes(live).map { it.paneId }.toSet() - beforePanes
                assertTrue("Only one pane may be created", added.size <= 1)
                createdId = added.singleOrNull()
                createdId != null
            }
            assertEquals("Kill marker requires a still-pending durable request", LaunchIntentState.pending, live.app.container.launchIntentStore.get(record.intent.requestId)?.state)
            assertOneNewPane(live, beforePanes, requireNotNull(createdId))
            emit(record, beforePanes, requireNotNull(createdId), live.owner)
            // A real external force-stop terminates this instrumentation; that interrupted run is
            // preparation evidence, not a passing test. Never manufacture a journal transition.
            SystemClock.sleep(60_000)
            fail("Parent did not perform the external process kill; no process-death proof exists")
        }
    }

    @Test fun recoverSavedLaunchAfterActualProcessDeath() = withLive { live ->
        assertNotEquals("Recovery must run in a different actual process", required("liveOldPid").toInt(), Process.myPid())
        val requestId = required("liveRequestId")
        assertEquals(requestId, UUID.fromString(requestId).toString())
        val bodyHash = required("liveBodySha256")
        assertTrue(bodyHash.matches(Regex("[0-9a-f]{64}")))
        assertEquals("Recovery must retain the original pairing owner", required("liveOwnerHash"), live.owner)
        val createdId = required("liveCreatedPaneId")
        val beforeText = required("liveBeforePaneIds")
        assertTrue("Bound the explicit pre-kill inventory", beforeText.length <= 16_384)
        val beforePanes = beforeText.split(',').toSet()
        assertTrue(beforePanes.size <= 200 && beforePanes.all { it.matches(Regex("[A-Za-z0-9:._-]{1,128}")) })
        assertTrue(createdId.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val store = live.app.container.launchIntentStore
        val record = requireNotNull(store.get(requestId))
        assertEquals("Startup must recover the actual pending write as unknown", LaunchIntentState.unknown, record.state)
        assertEquals(bodyHash, record.intent.bodySha256)
        assertFrozen(live, record)
        val beforeIds = store.all().map { it.intent.requestId }.toSet()
        assertOneNewPane(live, beforePanes, createdId)
        ActivityScenario.launch<NewActivity>(NewActivity.intent(live.app, live.scope)).use { scenario ->
            waitFor("Native recovery must offer deliberate same-ID retry") { shown(scenario, live.app.getString(R.string.new_retry_same_intent)) }
            assertEquals("Opening New must not silently retry", LaunchIntentState.unknown, store.get(requestId)?.state)
            assertEquals(beforeIds, store.all().map { it.intent.requestId }.toSet())
            assertOneNewPane(live, beforePanes, createdId)
            onView(withText(R.string.new_retry_same_intent)).perform(scrollTo(), click())
            val paneActivity = awaitPane()
            try {
                assertEquals("Replay must open the exact original receipt pane", createdId, paneActivity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
                assertEquals(live.scope.host, paneActivity.intent.getStringExtra(PaneActivity.EXTRA_HOST))
                assertEquals(live.scope.session, paneActivity.intent.getStringExtra(PaneActivity.EXTRA_SESSION))
                assertEquals(LaunchIntentState.succeeded, store.get(requestId)?.state)
                assertEquals("Retry must preserve every byte of the frozen intent", record.intent, store.get(requestId)?.intent)
                assertEquals("Retry must not mint another request UUID", beforeIds, store.all().map { it.intent.requestId }.toSet())
                assertOneNewPane(live, beforePanes, createdId)
            } finally { instrumentation.runOnMainSync { paneActivity.finish() } }
        }
    }

    private fun panes(live: Live): List<PaneSummary> {
        val repository = live.app.container.repository
        val actor = requireNotNull(repository.readContext(live.scope))
        assertEquals(live.owner, LaunchIntentStore.sha256(actor.key.pairedDeviceIdentity))
        val result = runBlocking { repository.snapshot(live.scope, actor) }
        assertTrue("Stock snapshot must succeed under the admitted actor", result is ApiResult.Success)
        assertTrue(repository.isCurrent(actor))
        return (result as ApiResult.Success).value.let { it.agents + it.shellPanes }.filter { it.host == live.scope.host && it.session == live.scope.session }
    }

    private fun assertOneNewPane(live: Live, before: Set<String>, created: String) {
        val current = panes(live)
        assertEquals("Original fixture panes must remain and exactly one new pane must exist", before + created, current.map { it.paneId }.toSet())
        val pane = current.single { it.paneId == created }
        assertEquals(ROOT, pane.cwd)
        assertEquals(LABEL, pane.workspaceLabel)
        assertTrue("Configured command must create a shell, not a vendor agent", pane.agent == "shell" || pane.kind == "shell")
    }

    private fun assertFrozen(live: Live, record: StoredLaunchIntent) {
        val frozen = record.intent
        assertEquals(frozen.requestId, UUID.fromString(frozen.requestId).toString())
        assertEquals("launch", frozen.operation)
        assertEquals(LaunchIntentReceiptMode.ownerBound, frozen.receiptMode)
        assertEquals(live.owner, LaunchIntentStore.sha256(frozen.pairedDeviceIdentity))
        assertEquals(live.app.container.connectionStore.connection.value?.origin?.value, frozen.origin)
        assertEquals(frozen.origin, frozen.mount)
        assertEquals(live.scope.host, frozen.host)
        assertEquals(live.scope.session, frozen.session)
        assertNull(frozen.subjectIdentity)
        assertFalse(frozen.confirmedNoPrompts)
        val body = Json.parseToJsonElement(frozen.canonicalBody).jsonObject
        assertEquals(setOf("requestId", "cwd", "command"), body.keys)
        assertEquals(frozen.requestId, body["requestId"]?.jsonPrimitive?.content)
        assertEquals(ROOT, body["cwd"]?.jsonPrimitive?.content)
        assertEquals(COMMAND, body["command"]?.jsonPrimitive?.content)
        assertEquals(LaunchIntentStore.sha256(frozen.canonicalBody), frozen.bodySha256)
    }

    private fun newRecord(live: Live, before: Set<String>, state: LaunchIntentState): StoredLaunchIntent {
        var found: StoredLaunchIntent? = null
        waitFor("Native Start must persist exactly one new request UUID in state $state") {
            val added = live.app.container.launchIntentStore.all().filter { it.intent.requestId !in before }
            assertTrue("One Start must not reserve multiple UUIDs", added.size <= 1)
            found = added.singleOrNull()?.takeIf { it.state == state }
            found != null
        }
        return requireNotNull(found)
    }

    private fun chooseLauncher(scenario: ActivityScenario<NewActivity>) {
        waitFor("Live configured launcher must load in native New") { shown(scenario, LABEL) }
        onView(withText(LABEL)).perform(scrollTo(), click())
        waitFor("Start must be enabled for the selected live command") { shown(scenario, scenarioText(R.string.new_start), enabled = true) }
    }

    private fun scenarioText(id: Int) = instrumentation.targetContext.getString(id)
    private fun shown(scenario: ActivityScenario<NewActivity>, text: String, enabled: Boolean = false): Boolean {
        var found = false
        scenario.onActivity { found = descendants(it.window.decorView).filterIsInstance<TextView>().any { view -> view.text.toString() == text && view.isShown && (!enabled || view.isEnabled) } }
        return found
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }
    private fun awaitPane(): PaneActivity {
        var found: PaneActivity? = null
        waitFor("Actual native launch must resume its receipt PaneActivity") {
            instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PaneActivity>().singleOrNull() }
            found != null
        }
        return requireNotNull(found)
    }
    private fun emit(record: StoredLaunchIntent, before: Set<String>, created: String, owner: String) {
        assertTrue("Bound the inventory supplied to the recovery run", before.size in 1..200)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("liveRequestId", record.intent.requestId)
            putString("liveBodySha256", record.intent.bodySha256)
            putString("liveBeforePaneIds", before.sorted().joinToString(","))
            putString("liveCreatedPaneId", created)
            putString("liveOwnerHash", owner)
            putString("liveOldPid", Process.myPid().toString())
            putString("stream", "Native launch receipt/inventory marker; contains no bearer or bootstrap code.\n")
        })
    }

    companion object {
        val ROOT get() = PhysicalFixturePaths.root
        const val LABEL = "Completion harmless shell"
        val COMMAND get() = "bash --noprofile --norc ${PhysicalFixturePaths.root}/completion-shell.sh"
    }
}
