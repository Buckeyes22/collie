package com.lateapex.collie.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.chip.Chip
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OriginValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SAF chooser and native upload only. Never presses Send, starts STT, or supplies a fake URI. */
@RunWith(AndroidJUnit4::class)
class PhysicalNonSpeechMediaAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private data class Live(val app: CollieApplication, val paired: Connection, val address: PaneAddress,
        val actor: ScopedReadContext, val automation: UiAutomation)
    private data class TraceWindow(val at: Long, val priorSealed: Set<String>)

    private fun required(name: String): String = requireNotNull(args.getString(name)?.takeIf(String::isNotBlank)) { "Explicit $name is required with liveOrigin" }

    private fun withLive(test: (Live) -> Unit) {
        val origin = args.getString("liveOrigin")
        assumeTrue("liveOrigin absent; physical media acceptance is opt-in", origin != null)
        assertFalse("Explicit liveOrigin must not be blank", origin.isNullOrBlank())
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Only the isolated completion namespace is permitted", app.packageName.endsWith(".completiontest"))
        val paired = requireNotNull(app.container.connectionStore.connection.value)
        assertTrue("Explicit origin requires its existing paired owner", paired.isPaired)
        assertEquals("Existing exact HTTPS origin and mount must match", OriginValidator().requireValid(requireNotNull(origin)), paired.origin)
        assertEquals("Only the controlled disposable stock shell is permitted", "w1:p1", required("livePaneId"))
        assertNull("This selector belongs to the stock lead's local pane", args.getString("liveHost"))
        assertNull("This selector belongs to the stock lead's local pane", args.getString("liveSession"))
        val root = required("liveRepoPath")
        assertTrue("Only the disposable acceptance Git root is permitted", root.matches(Regex("/home/chris/collie-completion-[0-9]{8}")))
        val repository = app.container.repository
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        val priorDiagnostics = app.container.nativePreferences.diagnosticsEnabled
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = automation.serviceInfo
        val flags = info.flags
        info.flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        try {
            // Capture real network metadata through the user's existing switch; never seed a sink.
            if (!priorDiagnostics) setDiagnostics(app, true)
            val address = PaneAddress(Scope(), "w1:p1")
            instrumentation.runOnMainSync { repository.selectSubject(address.scope, address.paneId) }
            val actor = requireNotNull(repository.readContext(address.scope, address.paneId))
            val live = Live(app, paired, address, actor, automation)
            current(live)
            val response = runBlocking { repository.snapshot(address.scope, actor) }
            current(live)
            assertTrue("Actual disposable pane snapshot must be readable", response is ApiResult.Success)
            val snapshot = (response as ApiResult.Success).value
            val pane = (snapshot.agents + snapshot.shellPanes).singleOrNull { it.paneId == address.paneId && it.host == null && it.session == null }
            assertTrue("Only the controlled stock shell may prepare attachments", pane != null && (pane.agent == "shell" || pane.kind == "shell"))
            assertEquals("Actual shell CWD must be the disposable Git root", root, pane?.cwd)
            val config = runBlocking { repository.config(address.scope, actor) }
            current(live)
            assertTrue("Real server upload policy must be available", config is ApiResult.Success && config.value.upload != null)
            val capability = (config as ApiResult.Success).value.upload!!
            assertTrue("Real server must explicitly admit text and PNG", "txt" in capability.textTypes.map { it.removePrefix(".") } && "png" in capability.imageTypes.map { it.removePrefix(".") })
            test(live)
        } finally {
            try {
                if (!priorDiagnostics && app.container.nativePreferences.diagnosticsEnabled) setDiagnostics(app, false)
            } finally {
                try { instrumentation.runOnMainSync { repository.selectSubject(priorScope, priorSubject) } }
                finally {
                    info.flags = flags
                    automation.serviceInfo = info
                    assertTrue("Media acceptance must preserve the original pairing", app.container.connectionStore.connection.value === paired)
                    assertEquals("Media acceptance must restore the original diagnostic preference", priorDiagnostics, app.container.nativePreferences.diagnosticsEnabled)
                }
            }
        }
    }

    /** Run once per task-owned /sdcard/Download/cc13-upload.txt and cc13-upload.png. */
    @Test fun liveDocumentsUiUploadAcknowledgesExactFixtureWithoutTerminalSubmission() = withLive { live ->
        val name = required("liveMediaFixtureName")
        assertTrue("Only the two task-owned Download fixtures may be selected", name in setOf("cc13-upload.txt", "cc13-upload.png"))
        val geometryOnly = args.getString("nativeMediaGeometry") == "true"
        if (geometryOnly) assertEquals("The actual PNG upload already passed; diagnose only the owned TXT fixture", "cc13-upload.txt", name)
        val bytes = required("liveMediaFixtureBytes").toLongOrNull()
        assertTrue("Explicit actual fixture length must be bounded", bytes != null && bytes in 1..1_048_576)
        val marker = required("liveMediaMarker")
        assertTrue("Only the harmless task marker is permitted", marker.matches(Regex("COLLIE_CC13_UPLOAD_[A-Za-z0-9_-]{4,64}")))
        val mime = if (name.endsWith(".png")) "image/png" else "text/plain"
        val before = readback(live)
        val trace = TraceWindow(System.currentTimeMillis(), live.app.container.diagnosticsWriter.sealedFiles().map { it.name }.toSet())
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
            awaitComposer(scenario, live)
            openDocuments(live)
            if (!chooseTaskFile(live, name, geometryOnly)) {
                // Diagnostic mode observes only the real chooser. It never selects or uploads a file.
                closeDiagnosticDocuments(live, scenario)
                assertEquals("Geometry-only inspection cannot alter terminal text/revision", before, readback(live))
                val records = traces(live, trace, marker)
                assertFalse("Geometry-only inspection cannot upload a file", records.any {
                    it["method"]?.jsonPrimitive?.contentOrNull == "POST" &&
                        it["path"]?.jsonPrimitive?.contentOrNull?.endsWith("/upload") == true
                })
                assertNoTerminalPost(records)
                current(live)
                return@use
            }
            await(scenario, "actual successful native attachment acknowledgement") { activity ->
                activity.findViewById<Chip>(R.id.composer_attachment_chip).let { chip -> chip.isShown && chip.text.toString() == activity.getString(R.string.pane_attachment_details, name, mime, bytes) } &&
                    activity.findViewById<TextView>(R.id.composer_media_status).text.toString() == activity.getString(R.string.pane_attachment_added, name)
            }
            scenario.onActivity { activity ->
                assertEquals("Upload must not insert an executable host path into the draft", "", activity.findViewById<EditText>(R.id.reply_input).text.toString())
            }
            assertEquals("Preparing an attachment must leave the actual terminal unchanged", before, readback(live))
            val records = awaitUploadTrace(live, trace, marker)
            val uploaded = records.filter { it["method"]?.jsonPrimitive?.contentOrNull == "POST" && it["path"]?.jsonPrimitive?.contentOrNull?.endsWith("/upload") == true }
            assertEquals("One chooser selection must perform exactly one real upload", 1, uploaded.size)
            assertEquals("Actual server must acknowledge the native upload", 200L, uploaded.single()["status"]?.jsonPrimitive?.longOrNull)
            assertFalse("Diagnostics must exclude actual multipart attachment content", uploaded.single().containsKey("requestBody"))
            assertTrue("Diagnostics must retain only a positive multipart byte count", (uploaded.single()["requestBodyBytes"]?.jsonPrimitive?.longOrNull ?: 0) > bytes!!)
            assertNoTerminalPost(records)
            val description = live.app.getString(R.string.pane_attachment_remove, live.app.getString(R.string.pane_attachment_details, name, mime, bytes))
            assertTrue("Attachment removal must invoke the actual accessible native close control", clickVisible(live.automation) { node ->
                node.packageName?.toString() == live.app.packageName && node.contentDescription?.toString() == description
            })
            await(scenario, "native attachment removed without sending") { !it.findViewById<Chip>(R.id.composer_attachment_chip).isShown }
            assertEquals("Removing the acknowledged chip must not submit a terminal command", before, readback(live))
            SystemClock.sleep(300)
            assertNoTerminalPost(traces(live, trace, marker))
            current(live)
        }
    }

    /** Parent holds the real successful upload response; this test switches through the native sheet. */
    @Test fun liveDocumentsUiPendingUploadCannotCrossNativePaneSwitch() {
        assumeTrue("Explicit nativeMediaPaneSwitch=true is required", args.getString("nativeMediaPaneSwitch") == "true")
        withLive { live ->
            assertEquals("Only the owned stock completion mount is permitted",
                OriginValidator().requireValid("https://prod.lateapexllc.com/completion/"), live.paired.origin)
            assertEquals("Only the other owned stock shell is permitted", "w2:p1", required("liveSecondPaneId"))
            assertEquals("Reuse only the real owned TXT fixture", "cc13-upload.txt", required("liveMediaFixtureName"))
            assertEquals("The existing real TXT must retain its exact size", "63", required("liveMediaFixtureBytes"))
            assertFalse("This selector performs an upload, not geometry inspection", args.getString("nativeMediaGeometry") == "true")
            val marker = required("liveMediaMarker")
            assertTrue("Only the harmless owned upload marker is permitted", marker.matches(Regex("COLLIE_CC13_UPLOAD_[A-Za-z0-9_-]{4,64}")))
            val run = required("liveMediaSwitchRun")
            assertTrue("The parent handshake run must be a bounded non-path token", run.matches(Regex("[A-Za-z0-9_-]{4,32}")))
            val phaseFile = java.io.File(live.app.cacheDir, "cc13-upload-switch-$run.marker")
            assertFalse("Never reuse an existing parent timing marker", phaseFile.exists())
            val repository = live.app.container.repository
            val store = live.app.container.connectionStore
            val committed = requireNotNull(store.captureCommitted())
            assertTrue("The actual original committed pairing incarnation is required", committed.connection === live.paired && committed.incarnationId != null)
            fun paired() {
                assertTrue("Every phase preserves the original Connection instance", store.connection.value === live.paired)
                assertEquals("Every phase preserves the original committed pairing incarnation", committed, store.captureCommitted())
            }
            val destination = PaneAddress(Scope(), "w2:p1")
            val snapshotResult = runBlocking { repository.snapshot(live.address.scope, live.actor) }
            current(live)
            assertTrue("The real original-actor pane inventory must be readable", snapshotResult is ApiResult.Success)
            val snapshot = (snapshotResult as ApiResult.Success).value
            val target = (snapshot.agents + snapshot.shellPanes).single { it.paneId == destination.paneId && it.host == null && it.session == null }
            assertTrue("Destination must be the other real stock shell", target.agent == "shell" || target.kind == "shell")
            assertEquals("Destination must belong to the same disposable Git root", required("liveRepoPath"), target.cwd)
            val launchers = runBlocking { repository.launchers(live.address.scope) }
            current(live)
            assertTrue("Read the actual launcher home used by the native switcher", launchers is ApiResult.Success)
            val home = (launchers as ApiResult.Success).value.home
            // Read-only HTTP baselines do not reselect panes or revive the original navigation actor.
            fun terminal(address: PaneAddress): Pair<String, Long> {
                paired()
                val result = runBlocking { live.app.container.api.pane(live.paired, address, lines = 600, etag = null, markSeen = false) }
                paired()
                assertTrue("Only actual authenticated owned-pane terminal reads are accepted", result is ApiResult.Success)
                val pane = (result as ApiResult.Success).value
                assertEquals("Readback must retain its exact requested pane", address.paneId, pane.paneId)
                return pane.text to pane.revision
            }
            fun uploadCalls(): List<okhttp3.Call> =
                (live.app.container.httpClient.dispatcher.runningCalls() + live.app.container.httpClient.dispatcher.queuedCalls())
                    .distinct().filter { it.request().method == "POST" && it.request().url.pathSegments.lastOrNull() == "upload" }
            fun originalUploadPending(activity: PaneActivity): Boolean {
                paired()
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val actor = state.paneReadContext
                val calls = uploadCalls()
                val call = calls.singleOrNull()
                val status = activity.findViewById<TextView>(R.id.composer_media_status)
                // Import progress is posted to the UI thread and can replace the Uploading label.
                // The real running request and its original owner establish pending custody.
                return repository.isCurrent(live.actor) && actor != null && repository.isCurrent(actor) &&
                    actor.key == live.actor.key && actor.navigationGeneration == live.actor.navigationGeneration &&
                    actor.connection === live.paired && actor.committedSnapshot == committed &&
                    state.pane?.paneId == live.address.paneId && !state.sending &&
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() === activity &&
                    status.isShown && status.text.isNotBlank() &&
                    !activity.findViewById<View>(R.id.attach_image_button).isEnabled && !activity.findViewById<View>(R.id.send_button).isEnabled &&
                    !activity.findViewById<Chip>(R.id.composer_attachment_chip).isShown && activity.findViewById<EditText>(R.id.reply_input).text.isNullOrEmpty() &&
                    call != null && call.isExecuted() && !call.isCanceled() &&
                    call in live.app.container.httpClient.dispatcher.runningCalls() &&
                    call.request().url.pathSegments.takeLast(4) == listOf("api", "pane", "w1:p1", "upload")
            }
            fun phase(value: String, guard: () -> Unit) {
                val deadline = SystemClock.uptimeMillis() + 30_000
                while (SystemClock.uptimeMillis() < deadline) {
                    guard()
                    if (phaseFile.isFile) {
                        assertTrue("The parent timing marker must remain bounded", phaseFile.length() <= 128)
                        if (phaseFile.readText() == "$value:$run") return
                    }
                    SystemClock.sleep(100)
                }
                throw AssertionError("Parent upload timing phase did not arrive: $value")
            }
            val beforeOriginal = terminal(live.address)
            val beforeDestination = terminal(destination)
            assertTrue("Do not overlap any existing app upload", uploadCalls().isEmpty())
            val trace = TraceWindow(System.currentTimeMillis(), live.app.container.diagnosticsWriter.sealedFiles().map { it.name }.toSet())
            var destinationActivity: PaneActivity? = null
            ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
                try {
                    awaitComposer(scenario, live)
                    openDocuments(live)
                    assertTrue("Select the actual owned TXT through the existing SAF helper", chooseTaskFile(live, "cc13-upload.txt"))
                    await(scenario, "the actual original upload is pending before native navigation", ::originalUploadPending)
                    current(live)
                    val pending = uploadCalls()
                    assertEquals("One actual native upload must be in flight", 1, pending.size)
                    assertEquals("Only the original owned pane may receive this POST", listOf("api", "pane", "w1:p1", "upload"), pending.single().request().url.pathSegments.takeLast(4))
                    instrumentation.sendStatus(2, android.os.Bundle().apply {
                        putString("liveMediaSwitchPhase", "PENDING")
                        putString("liveMediaSwitchMarkerPath", phaseFile.absolutePath)
                        putString("stream", "nativeMediaSwitch=PENDING; parent must verify the held real upstream 200 and real 63-byte upload, then write the upstream-verified timing phase.\n")
                    })
                    phase("upstream-verified") { current(live); paired() }
                    assertEquals("The verified real response must still be pending before switching", 1, uploadCalls().size)
                    scenario.onActivity { activity ->
                        assertTrue("The same real original upload must remain pending until native switching", originalUploadPending(activity))
                    }
                    // Derive the exact native row label from the actual displayed pane inventory.
                    var description = ""
                    var shellsClosed = false
                    scenario.onActivity { activity ->
                        val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                        val sections = PaneSwitcherModel.sections(state.panes, live.address, true, true,
                            live.app.container.nativePreferences.dashboardShellsOpen, emptyList(), home, null, false, state.mux, state.servers, emptySet(),
                            NativePanePins(live.app).matcher(live.actor, repository::isCurrent))
                        val rows = sections.flatMap { section -> when (section) {
                            is PaneSwitcherSection.Pinned -> section.rows
                            is PaneSwitcherSection.Shells -> { shellsClosed = !section.open; section.rows }
                            is PaneSwitcherSection.Agents -> section.rows
                            is PaneSwitcherSection.Launch -> emptyList()
                        } }
                        val row = rows.single { it.pane.paneId == destination.paneId && it.pane.host == null && it.pane.session == null }
                        val title = displayAgentTitle(row.pane.agent, row.pane.paneLabel ?: row.pane.sessionName ?: row.pane.soleTabName) ?: row.pane.agent.ifBlank { row.pane.paneId }
                        val secondary = displayAgentTitle(row.pane.agent, row.secondary)?.takeUnless { it == title }
                        val label = row.project + (row.tab?.let { " · $it" } ?: "") + "\n" + title + (secondary?.let { " · $it" } ?: "")
                        description = activity.getString(R.string.pane_switcher_pane_description, label)
                    }
                    current(live)
                    onView(withId(R.id.switcher_handle)).perform(click())
                    if (shellsClosed) onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(live.app.getString(
                        R.string.pane_switcher_expand, live.app.getString(R.string.pane_switcher_shells)))).perform(click())
                    val switchDeadline = SystemClock.uptimeMillis() + 10_000
                    var clicked = false
                    while (!clicked && SystemClock.uptimeMillis() < switchDeadline) {
                        current(live)
                        clicked = clickVisible(live.automation) { it.packageName?.toString() == live.app.packageName && it.contentDescription?.toString() == description }
                        if (!clicked) SystemClock.sleep(100)
                    }
                    assertTrue("One normal native switcher row must select the exact owned destination", clicked)
                    var newActor: ScopedReadContext? = null
                    var sendBeforeRelease = false
                    val navigationDeadline = SystemClock.uptimeMillis() + 30_000
                    while (newActor == null && SystemClock.uptimeMillis() < navigationDeadline) {
                        paired()
                        instrumentation.runOnMainSync {
                            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() as? PaneActivity
                            if (activity != null) {
                                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                                val actor = state.paneReadContext
                                if (state.pane?.paneId == destination.paneId && actor != null && repository.isCurrent(actor) &&
                                    actor.key.subjectIdentity == destination.paneId && actor.connection === live.paired && actor.committedSnapshot == committed &&
                                    state.canWrite && !state.sending && activity.findViewById<View>(R.id.attach_image_button).isEnabled) {
                                    destinationActivity = activity; newActor = actor
                                    sendBeforeRelease = activity.findViewById<View>(R.id.send_button).isEnabled
                                }
                            }
                        }
                        if (newActor == null) SystemClock.sleep(100)
                    }
                    val destinationActor = requireNotNull(newActor) { "Native navigation did not load the exact current destination actor" }
                    val shown = requireNotNull(destinationActivity)
                    fun destinationCurrent() {
                        paired()
                        assertTrue("Only the destination actor remains current", repository.isCurrent(destinationActor))
                        assertFalse("The original upload actor must remain stale", repository.isCurrent(live.actor))
                        assertFalse("The destination cannot authorize an original-pane write", repository.writesAllowed(live.address.scope, live.address.paneId))
                        instrumentation.runOnMainSync {
                            val state = androidx.lifecycle.ViewModelProvider(shown)[PaneViewModel::class.java].state.value
                            val actor = requireNotNull(state.paneReadContext)
                            assertTrue("Destination UI retains its captured actor and navigation", actor.key == destinationActor.key && actor.navigationGeneration == destinationActor.navigationGeneration && repository.isCurrent(actor))
                            assertEquals("Destination remains the sole resumed pane", shown, ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull())
                            assertEquals("The late upload cannot insert a draft or host path", "", shown.findViewById<EditText>(R.id.reply_input).text.toString())
                            assertFalse("The late upload cannot attach to the destination", shown.findViewById<Chip>(R.id.composer_attachment_chip).isShown)
                            assertFalse("No inherited upload status is allowed", shown.findViewById<TextView>(R.id.composer_media_status).text.toString().contains("cc13-upload.txt"))
                            assertEquals("Late upload cannot change destination Send admission", sendBeforeRelease, shown.findViewById<View>(R.id.send_button).isEnabled)
                            assertFalse("No terminal submission may be in flight", state.sending)
                        }
                    }
                    destinationCurrent()
                    instrumentation.sendStatus(2, android.os.Bundle().apply {
                        putString("liveMediaSwitchPhase", "SWITCHED")
                        putString("stream", "nativeMediaSwitch=SWITCHED; parent may release the verified real response, then overwrite only the owned timing marker with the released phase.\n")
                    })
                    phase("released", ::destinationCurrent)
                    val settleDeadline = SystemClock.uptimeMillis() + 5_000
                    while (SystemClock.uptimeMillis() < settleDeadline) {
                        destinationCurrent()
                        assertTrue("Navigation cannot dispatch another upload", uploadCalls().size <= 1)
                        SystemClock.sleep(100)
                    }
                    assertTrue("The original upload call must finish or cancel after response release", uploadCalls().isEmpty())
                    assertEquals("Original terminal text and revision remain unchanged", beforeOriginal, terminal(live.address))
                    destinationCurrent()
                    assertEquals("Destination terminal text and revision remain unchanged", beforeDestination, terminal(destination))
                    destinationCurrent()
                    val records = traces(live, trace, marker)
                    val uploads = records.filter { it["method"]?.jsonPrimitive?.contentOrNull == "POST" && it["path"]?.jsonPrimitive?.contentOrNull?.endsWith("/upload") == true }
                    assertTrue("A canceled request may lack a response trace; never permit multiple upload responses", uploads.size <= 1)
                    uploads.forEach { record ->
                        assertTrue("Any completed upload response belongs only to the original pane", record["path"]?.jsonPrimitive?.contentOrNull?.endsWith("/api/pane/w1%3Ap1/upload", ignoreCase = true) == true)
                        assertEquals("Any delivered upload acknowledgement retains the actual success status", 200L, record["status"]?.jsonPrimitive?.longOrNull)
                        assertFalse("Diagnostics exclude raw multipart content", record.containsKey("requestBody"))
                    }
                    assertNoTerminalPost(records)
                    instrumentation.sendStatus(2, android.os.Bundle().apply {
                        putString("liveMediaSwitchPhase", "VERIFIED")
                        putString("stream", "nativeMediaSwitch=VERIFIED; original actor stale, destination clear, both real terminals unchanged. Exact upstream upload count/200 remains parent relay evidence.\n")
                    })
                } finally { instrumentation.runOnMainSync { destinationActivity?.finish() } }
            }
        }
    }

    /** Normal OS Back from real DocumentsUI must preserve a harmless unsent draft and send nothing. */
    @Test fun liveDocumentsUiCancelPreservesUnsentDraftAndMakesNoUploadOrTerminalSubmission() = withLive { live ->
        val before = readback(live)
        val trace = TraceWindow(System.currentTimeMillis(), live.app.container.diagnosticsWriter.sealedFiles().map { it.name }.toSet())
        val draft = "COLLIE_CC13_UNSENT_PICKER_CANCEL"
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
            awaitComposer(scenario, live)
            onView(withId(R.id.reply_input)).perform(replaceText(draft))
            openDocuments(live)
            current(live)
            val picker = requireNotNull(live.automation.rootInActiveWindow)
            try {
                assertTrue("Only the actual visible DocumentsUI chooser may receive Back",
                    picker.isVisibleToUser && picker.packageName?.toString() in DOCUMENT_PACKAGES)
            } finally { picker.recycle() }
            assertTrue("Chooser cancellation uses Android's normal accessibility Back action",
                live.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await(scenario, "native composer returns from canceled real chooser") { activity ->
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() === activity &&
                    activity.findViewById<View>(R.id.attach_image_button).isEnabled && activity.findViewById<EditText>(R.id.reply_input).text.toString() == draft
            }
            scenario.onActivity { assertFalse("Canceled chooser must not create an attachment chip", it.findViewById<Chip>(R.id.composer_attachment_chip).isShown) }
            SystemClock.sleep(1_000)
            assertEquals("Canceled chooser must leave actual terminal text/revision unchanged", before, readback(live))
            val records = traces(live, trace, null)
            assertTrue("Actual diagnostic metadata must witness this run's live reads", records.any { it["method"]?.jsonPrimitive?.contentOrNull == "GET" })
            assertFalse("Canceled chooser must never upload a file", records.any { it["method"]?.jsonPrimitive?.contentOrNull == "POST" && it["path"]?.jsonPrimitive?.contentOrNull?.endsWith("/upload") == true })
            assertNoTerminalPost(records)
            current(live)
            onView(withId(R.id.reply_input)).perform(replaceText(""))
        }
    }

    private fun current(live: Live) = assertTrue("Every UI action and read must retain the original paired actor", live.app.container.connectionStore.connection.value === live.paired && live.app.container.repository.isCurrent(live.actor))
    private fun readback(live: Live): Pair<String, Long> {
        current(live)
        val result = runBlocking { live.app.container.repository.readPane(live.address, markSeen = false, conditional = false, expectedContext = live.actor) }
        current(live)
        assertTrue("Actual original-actor terminal readback must succeed", result is ApiResult.Success)
        return (result as ApiResult.Success).value.pane.let { it.text to it.revision }
    }
    private fun paneIntent(live: Live) = Intent(live.app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, live.address.paneId).putExtra(PaneActivity.EXTRA_AGENT, "shell")
        .putExtra(PaneActivity.EXTRA_TITLE, "Disposable non-speech upload acceptance")
    private fun awaitComposer(scenario: ActivityScenario<PaneActivity>, live: Live) {
        await(scenario, "actual native upload control admission") { activity -> activity.findViewById<View>(R.id.attach_image_button).isEnabled && activity.findViewById<EditText>(R.id.reply_input).isEnabled }
        current(live)
        scenario.onActivity { activity ->
            assertEquals("Never replace a pre-existing unsent draft", "", activity.findViewById<EditText>(R.id.reply_input).text.toString())
            assertFalse("Never replace a pre-existing attachment", activity.findViewById<Chip>(R.id.composer_attachment_chip).isShown)
        }
    }
    private fun openDocuments(live: Live) {
        current(live)
        onView(withId(R.id.attach_image_button)).perform(click())
        onView(withText(live.app.getString(R.string.pane_attachment_choose_file))).perform(click())
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = live.automation.rootInActiveWindow
            val actual = root?.packageName?.toString() in DOCUMENT_PACKAGES
            root?.recycle()
            if (actual) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Native chooser must open actual Android DocumentsUI")
    }
    private fun chooseTaskFile(live: Live, name: String, geometryOnly: Boolean = false): Boolean {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var openedRoots = false
        var openedDownloads = false
        var requestedDownloads = false
        var scrollPages = 0
        if (geometryOnly) reportMediaGeometry(live, name, "initial", openedRoots, openedDownloads)
        while (SystemClock.uptimeMillis() < deadline) {
            current(live)
            val priorRoots = openedRoots
            val priorDownloads = openedDownloads
            // A toolbar title is not a root choice. Route through the actual roots drawer,
            // whose ListView handles a row touch even when its text has no clickable ancestor.
            if (!openedRoots) openedRoots = clickVisible(live.automation) { node ->
                node.packageName?.toString() in DOCUMENT_PACKAGES && node.contentDescription?.toString() in setOf("Show roots", "Open navigation drawer")
            }
            if (openedRoots && !requestedDownloads) requestedDownloads = selectDownloadsRoot(live, deadline)
            openedDownloads = requestedDownloads && isDownloadsDirectory(live)
            // Inspect this page immediately after root routing and before any native scroll.
            if (geometryOnly && openedDownloads && reportOwnedTxtIfPresent(live, name, openedRoots, openedDownloads)) return false
            // Select only the exact local Download item; a same-named Recent/cloud item is not
            // evidence that Android opened the task-owned fixture prepared under Download.
            if (!geometryOnly && openedDownloads && isDownloadsDirectory(live) && clickVisible(live.automation) { node ->
                val description = node.contentDescription?.toString()
                node.packageName?.toString() in DOCUMENT_PACKAGES &&
                    (node.text?.toString() == name || description == name || description?.startsWith("$name, ") == true)
            }) return true
            // API37 exposes this exact owned TXT as a non-actionable grid label.
            // Geometry-only diagnostics never enter the one-touch file-selection fallback.
            if (!geometryOnly && name == "cc13-upload.txt" && openedDownloads && tapExactOwnedTxt(live)) return true
            if (name == "cc13-upload.txt" && openedDownloads && scrollPages < MAX_DOWNLOAD_SCROLL_PAGES && SystemClock.uptimeMillis() < deadline) {
                current(live)
                scrollPages++ // Bound attempted native gestures even if the list reports no further page.
                val scrolled = scrollDownloadsForward(live)
                if (scrolled && !awaitDownloadsScrollIdle(live, deadline)) break
            }
            if (geometryOnly && (openedRoots != priorRoots || openedDownloads != priorDownloads))
                reportMediaGeometry(live, name, "routing-changed", openedRoots, openedDownloads)
            SystemClock.sleep(100)
        }
        if (geometryOnly) {
            reportMediaGeometry(live, name, "deadline-scroll-pages-$scrollPages", openedRoots, openedDownloads)
            return false
        }
        if (name == "cc13-upload.txt") reportMediaGeometry(live, name, "selection-failed", openedRoots, openedDownloads)
        throw AssertionError("Actual DocumentsUI must list the exact task-owned Download fixture after bounded native list scrolling")
    }

    /** One normal touch on the exact Downloads label inside the actual DocumentsUI roots list. */
    private fun selectDownloadsRoot(live: Live, deadline: Long): Boolean {
        current(live)
        val root = live.automation.rootInActiveWindow ?: return false
        try {
            val packageName = root.packageName?.toString() ?: return false
            if (packageName !in DOCUMENT_PACKAGES) return false
            val lists = root.findAccessibilityNodeInfosByViewId("$packageName:id/roots_list")
            try {
                val list = lists.singleOrNull { it.isVisibleToUser && it.isEnabled } ?: return false
                if (list.className?.toString() != "android.widget.ListView") return false
                val labels = list.findAccessibilityNodeInfosByText("Downloads")
                try {
                    val label = labels.singleOrNull { it.packageName?.toString() == packageName &&
                        it.isVisibleToUser && it.isEnabled && it.text?.toString() == "Downloads" } ?: return false
                    if (!list.refresh() || !label.refresh() || !list.isVisibleToUser || !label.isVisibleToUser ||
                        !list.isEnabled || !label.isEnabled || label.packageName?.toString() != packageName ||
                        label.text?.toString() != "Downloads") return false
                    // Drawer nodes can be visible while sliding into place. Accessibility idle
                    // alone does not establish stable touch coordinates: compare refreshed bounds.
                    val priorListBounds = android.graphics.Rect().also(list::getBoundsInScreen)
                    val priorLabelBounds = android.graphics.Rect().also(label::getBoundsInScreen)
                    if (!awaitDownloadsScrollIdle(live, deadline) || SystemClock.uptimeMillis() >= deadline) return false
                    if (!list.refresh() || !label.refresh() || !list.isVisibleToUser || !list.isEnabled ||
                        list.packageName?.toString() != packageName || list.viewIdResourceName != "$packageName:id/roots_list" ||
                        list.className?.toString() != "android.widget.ListView" || !label.isVisibleToUser || !label.isEnabled ||
                        label.packageName?.toString() != packageName || label.text?.toString() != "Downloads") return false
                    if (priorListBounds != android.graphics.Rect().also(list::getBoundsInScreen) ||
                        priorLabelBounds != android.graphics.Rect().also(label::getBoundsInScreen)) return false
                    val active = live.automation.rootInActiveWindow ?: return false
                    try {
                        if (active.packageName?.toString() != packageName || active.windowId != label.windowId ||
                            list.windowId != label.windowId) return false
                        val bounds = android.graphics.Rect().also(label::getBoundsInScreen)
                        val listBounds = android.graphics.Rect().also(list::getBoundsInScreen)
                        val windowBounds = android.graphics.Rect().also(active::getBoundsInScreen)
                        if (bounds.isEmpty || !listBounds.contains(bounds) || !windowBounds.contains(bounds)) return false
                        current(live)
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        val at = SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(at, at, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                        val downAccepted = try { live.automation.injectInputEvent(down, true) } finally { down.recycle() }
                        SystemClock.sleep(50)
                        val up = android.view.MotionEvent.obtain(at, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, y, 0)
                            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                        val upAccepted = try { live.automation.injectInputEvent(up, true) } finally { up.recycle() }
                        assertTrue("Only the exact visible Downloads root receives normal touch down/up", downAccepted && upAccepted)
                        return true
                    } finally { active.recycle() }
                } finally { labels.forEach { it.recycle() } }
            } finally { lists.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    /** One normal touch only on the exact task-owned TXT label inside the verified Downloads grid. */
    private fun tapExactOwnedTxt(live: Live): Boolean {
        current(live)
        if (!isDownloadsDirectory(live)) return false
        val root = live.automation.rootInActiveWindow ?: return false
        try {
            val packageName = root.packageName?.toString() ?: return false
            if (packageName !in DOCUMENT_PACKAGES) return false
            val grids = root.findAccessibilityNodeInfosByViewId("$packageName:id/dir_list")
            try {
                val grid = grids.singleOrNull { it.isVisibleToUser && it.isEnabled } ?: return false
                if (grid.className?.toString() != "android.widget.GridView") return false
                val labels = grid.findAccessibilityNodeInfosByText("cc13-upload.txt")
                try {
                    val label = labels.singleOrNull { it.packageName?.toString() == packageName &&
                        it.isVisibleToUser && it.isEnabled && it.text?.toString() == "cc13-upload.txt" } ?: return false
                    if (!grid.refresh() || !label.refresh() || !grid.isVisibleToUser || !grid.isEnabled ||
                        grid.packageName?.toString() != packageName || grid.viewIdResourceName != "$packageName:id/dir_list" ||
                        grid.className?.toString() != "android.widget.GridView" || !label.isVisibleToUser || !label.isEnabled ||
                        label.packageName?.toString() != packageName || label.text?.toString() != "cc13-upload.txt" ||
                        label.childCount != 0 || !isDownloadsDirectory(live)) return false
                    val active = live.automation.rootInActiveWindow ?: return false
                    try {
                        if (active.packageName?.toString() != packageName || active.windowId != label.windowId ||
                            grid.windowId != label.windowId) return false
                        val bounds = android.graphics.Rect().also(label::getBoundsInScreen)
                        val gridBounds = android.graphics.Rect().also(grid::getBoundsInScreen)
                        val windowBounds = android.graphics.Rect().also(active::getBoundsInScreen)
                        if (bounds.isEmpty || !gridBounds.contains(bounds) || !windowBounds.contains(bounds)) return false
                        current(live)
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        val at = SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(at, at, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                        val downAccepted = try { live.automation.injectInputEvent(down, true) } finally { down.recycle() }
                        SystemClock.sleep(50)
                        val up = android.view.MotionEvent.obtain(at, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, y, 0)
                            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                        val upAccepted = try { live.automation.injectInputEvent(up, true) } finally { up.recycle() }
                        assertTrue("Only the exact visible task-owned TXT label receives normal touch down/up", downAccepted && upAccepted)
                        return true
                    } finally { active.recycle() }
                } finally { labels.forEach { it.recycle() } }
            } finally { grids.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    /** Read-only guard evidence; called only by exact-owned-TXT geometry diagnostics. */
    private fun ownedTxtTouchGuardEvidence(live: Live, root: AccessibilityNodeInfo): String {
        current(live)
        val fields = mutableListOf("txtTouchGuards=true", "downloadsBefore=${isDownloadsDirectory(live)}")
        val packageName = root.packageName?.toString()
        fields += "rootPackageAllowed=${packageName in DOCUMENT_PACKAGES}"
        if (packageName !in DOCUMENT_PACKAGES) return fields.joinToString(" ")
        val grids = root.findAccessibilityNodeInfosByViewId("$packageName:id/dir_list")
        try {
            val eligible = grids.filter { it.isVisibleToUser && it.isEnabled }
            fields += "gridLookupCount=${grids.size} gridEligibleCount=${eligible.size}"
            val grid = eligible.singleOrNull() ?: return fields.joinToString(" ")
            fields += "gridClassMatch=${grid.className?.toString() == "android.widget.GridView"} gridVisible=${grid.isVisibleToUser} gridEnabled=${grid.isEnabled}"
            val labels = grid.findAccessibilityNodeInfosByText("cc13-upload.txt")
            try {
                val exact = labels.filter { it.packageName?.toString() == packageName &&
                    it.isVisibleToUser && it.isEnabled && it.text?.toString() == "cc13-upload.txt" }
                fields += "labelLookupCount=${labels.size} labelEligibleCount=${exact.size}"
                val label = exact.singleOrNull() ?: return fields.joinToString(" ")
                fields += "labelChildCount=${label.childCount} labelLeaf=${label.childCount == 0}"
                val gridRefreshed = grid.refresh()
                val labelRefreshed = label.refresh()
                fields += "gridRefreshed=$gridRefreshed labelRefreshed=$labelRefreshed"
                fields += "gridVisibleAfter=${grid.isVisibleToUser} gridEnabledAfter=${grid.isEnabled} gridPackageMatch=${grid.packageName?.toString() == packageName} " +
                    "gridIdMatch=${grid.viewIdResourceName == "$packageName:id/dir_list"} gridClassMatchAfter=${grid.className?.toString() == "android.widget.GridView"}"
                fields += "labelVisibleAfter=${label.isVisibleToUser} labelEnabledAfter=${label.isEnabled} labelPackageMatch=${label.packageName?.toString() == packageName} " +
                    "labelTextMatch=${label.text?.toString() == "cc13-upload.txt"} labelChildCountAfter=${label.childCount} labelLeafAfter=${label.childCount == 0} downloadsAfter=${isDownloadsDirectory(live)}"
                val active = live.automation.rootInActiveWindow
                fields += "activePresent=${active != null}"
                if (active != null) try {
                    fields += "activePackageMatch=${active.packageName?.toString() == packageName} activeLabelWindowEqual=${active.windowId == label.windowId} gridLabelWindowEqual=${grid.windowId == label.windowId}"
                    val bounds = android.graphics.Rect().also(label::getBoundsInScreen)
                    val gridBounds = android.graphics.Rect().also(grid::getBoundsInScreen)
                    val windowBounds = android.graphics.Rect().also(active::getBoundsInScreen)
                    fields += "labelBoundsEmpty=${bounds.isEmpty} gridContainsLabel=${gridBounds.contains(bounds)} windowContainsLabel=${windowBounds.contains(bounds)}"
                } finally { active.recycle() }
            } finally { labels.forEach { it.recycle() } }
        } finally { grids.forEach { it.recycle() } }
        current(live)
        return fields.joinToString(" ")
    }

    /** A root request is complete only after the drawer closes and the Downloads directory is shown. */
    private fun isDownloadsDirectory(live: Live): Boolean {
        current(live)
        val root = live.automation.rootInActiveWindow ?: return false
        try {
            val packageName = root.packageName?.toString() ?: return false
            if (packageName !in DOCUMENT_PACKAGES) return false
            fun visibleId(id: String): Boolean {
                val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
                return try { nodes.any { it.packageName?.toString() == packageName && it.isVisibleToUser } }
                finally { nodes.forEach { it.recycle() } }
            }
            if (visibleId("roots_list") || !visibleId("dir_list")) return false
            val titles = root.findAccessibilityNodeInfosByText("Downloads")
            return try { titles.any { it.packageName?.toString() == packageName && it.isVisibleToUser && it.text?.toString() == "Downloads" } }
            finally { titles.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    /** Scroll only the visible DocumentsUI Downloads directory list; never swipe an arbitrary window. */
    private fun scrollDownloadsForward(live: Live): Boolean {
        current(live)
        if (!isDownloadsDirectory(live)) return false
        val root = live.automation.rootInActiveWindow ?: return false
        var scrolled = false
        var visited = 0
        fun isCurrentDownloadsList(node: AccessibilityNodeInfo, activePackage: String): Boolean {
            val packageName = node.packageName?.toString()
            val className = node.className?.toString().orEmpty()
            val resourceName = node.viewIdResourceName.orEmpty()
            return packageName in DOCUMENT_PACKAGES && packageName == activePackage &&
                (className.contains("RecyclerView") || className == "android.widget.GridView") &&
                resourceName.substringAfter(":id/", "") == "dir_list" &&
                node.isVisibleToUser && node.isEnabled && node.isScrollable &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        }
        fun hasVisibleDownloadsLabel(node: AccessibilityNodeInfo, activePackage: String): Boolean {
            var visitedLabels = 0
            fun visitLabel(candidate: AccessibilityNodeInfo, depth: Int): Boolean {
                if (visitedLabels++ >= 4096 || depth > 32) return false
                if (candidate.packageName?.toString() == activePackage && candidate.isVisibleToUser && candidate.text?.toString() == "Downloads") return true
                for (index in 0 until candidate.childCount) {
                    if (visitedLabels >= 4096) break
                    val child = candidate.getChild(index) ?: continue
                    val found = try { visitLabel(child, depth + 1) } finally { child.recycle() }
                    if (found) return true
                }
                return false
            }
            return visitLabel(node, 0)
        }
        fun visit(node: AccessibilityNodeInfo, activePackage: String) {
            if (scrolled || visited++ >= 4096) return
            if (isCurrentDownloadsList(node, activePackage)) {
                // Refresh this exact list node and verify the active window again immediately before
                // the gesture, so an old chooser tree cannot receive a scroll action.
                if (!node.refresh()) return
                current(live)
                val activeRoot = live.automation.rootInActiveWindow
                val sameDocumentsWindow = activeRoot != null && activeRoot.windowId == node.windowId &&
                    activeRoot.packageName?.toString() == activePackage && activePackage in DOCUMENT_PACKAGES &&
                    hasVisibleDownloadsLabel(activeRoot, activePackage)
                activeRoot?.recycle()
                if (sameDocumentsWindow && isCurrentDownloadsList(node, activePackage)) {
                    current(live)
                    scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                return
            }
            for (index in 0 until node.childCount) {
                if (scrolled || visited >= 4096) break
                val child = node.getChild(index) ?: continue
                try { visit(child, activePackage) } finally { child.recycle() }
            }
        }
        try {
            val packageName = root.packageName?.toString()
            if (packageName != null && packageName in DOCUMENT_PACKAGES && hasVisibleDownloadsLabel(root, packageName)) visit(root, packageName)
        } finally { root.recycle() }
        current(live)
        return scrolled
    }

    /** Give DocumentsUI's smooth RecyclerView scroll time to settle without exceeding the chooser deadline. */
    private fun awaitDownloadsScrollIdle(live: Live, deadline: Long): Boolean {
        while (SystemClock.uptimeMillis() < deadline) {
            current(live)
            val remaining = deadline - SystemClock.uptimeMillis()
            val globalTimeout = minOf(1_000L, remaining).coerceAtLeast(1L)
            try {
                live.automation.waitForIdle(minOf(150L, globalTimeout), globalTimeout)
                current(live)
                return true
            } catch (_: java.util.concurrent.TimeoutException) {
                // Retry only the bounded idle wait; the outer chooser deadline remains authoritative.
            }
        }
        current(live)
        return false
    }

    /** Allowlisted nodes only: never emit other filenames, accounts, text, URIs or resource IDs. */
    private fun reportMediaGeometry(
        live: Live, name: String, stage: String, openedRoots: Boolean, openedDownloads: Boolean,
    ): Boolean {
        current(live)
        val rows = mutableListOf<String>()
        val root = live.automation.rootInActiveWindow
        var ownedVisible = false
        var visited = 0
        val counts = mutableMapOf<String, Int>()
        val documentRoot = root?.packageName?.toString() in DOCUMENT_PACKAGES
        fun kind(node: AccessibilityNodeInfo): String? {
            if (node.packageName?.toString() !in DOCUMENT_PACKAGES) return null
            val description = node.contentDescription?.toString()
            return when {
                node.text?.toString() == name || description == name || description?.startsWith("$name, ") == true -> "owned-txt"
                node.text?.toString() == "Downloads" -> "downloads-control"
                description in setOf("Show roots", "Open navigation drawer") -> "roots-control"
                else -> null
            }
        }
        fun capture(node: AccessibilityNodeInfo, category: String) {
            var candidate = AccessibilityNodeInfo.obtain(node)
            try {
                repeat(4) { depth ->
                    if (candidate.packageName?.toString() !in DOCUMENT_PACKAGES) return
                    val bounds = android.graphics.Rect()
                    candidate.getBoundsInScreen(bounds)
                    val actionIds = candidate.actionList.map { it.id }.take(16).joinToString(",")
                    if (category == "owned-txt" && depth == 0 && name == "cc13-upload.txt") rows +=
                        "txtManualGuard=true textExact=${candidate.text?.toString() == name} descriptionExact=${candidate.contentDescription?.toString() == name} " +
                            "descriptionOwnedPrefix=${candidate.contentDescription?.toString()?.startsWith("$name, ") == true} childCount=${candidate.childCount}"
                    rows += "kind=$category depth=$depth bounds=${bounds.toShortString()} " +
                        "width=${bounds.width()} height=${bounds.height()} visible=${candidate.isVisibleToUser} " +
                        "enabled=${candidate.isEnabled} clickable=${candidate.isClickable} " +
                        "actionClick=${candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }} actionIds=[$actionIds]"
                    if (depth == 3) return
                    val parent = candidate.parent ?: return
                    candidate.recycle()
                    candidate = parent
                }
            } finally { candidate.recycle() }
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visited++ >= 4096 || depth > 32) return
            val category = kind(node)
            if (category != null) {
                if (category == "owned-txt" && node.isVisibleToUser) ownedVisible = true
                val count = counts[category] ?: 0
                if (count < 4) { capture(node, category); counts[category] = count + 1 }
            }
            for (index in 0 until node.childCount) {
                if (visited >= 4096) break
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        try { if (root != null && documentRoot) {
            visit(root, 0)
            if (name == "cc13-upload.txt" && openedDownloads && ownedVisible) rows += ownedTxtTouchGuardEvidence(live, root)
        } } finally { root?.recycle() }
        current(live)
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            val geometryOnly = args.getString("nativeMediaGeometry") == "true"
            val mode = if (geometryOnly) "nativeMediaGeometryOnly=true" else "nativeMediaSelectionFailure=true"
            val actions = if (geometryOnly) "fileClicks=0 uploadActions=0" else "fileClicks=untracked uploadActions=untracked"
            putString("stream", "$mode stage=$stage documentRoot=$documentRoot " +
                "openedRoots=$openedRoots openedDownloads=$openedDownloads ownedTxtVisible=$ownedVisible " +
                "$actions traversalBoundReached=${visited >= 4096}\n" + rows.joinToString("\n") + "\n")
        })
        return ownedVisible
    }

    private fun reportOwnedTxtIfPresent(live: Live, name: String, openedRoots: Boolean, openedDownloads: Boolean): Boolean {
        // Do not print at every poll: report once when the exact owned TXT node is actually visible.
        var found = false
        var visited = 0
        val root = live.automation.rootInActiveWindow ?: return false
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (found || visited++ >= 4096 || depth > 32) return
            if (node.packageName?.toString() in DOCUMENT_PACKAGES && node.isVisibleToUser &&
                (node.text?.toString() == name || node.contentDescription?.toString() == name ||
                    node.contentDescription?.toString()?.startsWith("$name, ") == true)) { found = true; return }
            for (index in 0 until node.childCount) {
                if (visited >= 4096) return
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
                if (found) return
            }
        }
        try { if (root.packageName?.toString() in DOCUMENT_PACKAGES) visit(root, 0) }
        finally { root.recycle() }
        return found && reportMediaGeometry(live, name, "owned-txt-visible", openedRoots, openedDownloads)
    }

    private fun closeDiagnosticDocuments(live: Live, scenario: ActivityScenario<PaneActivity>) {
        // Normal Back may first dismiss the navigation drawer. Never target a foreign OS window.
        repeat(3) {
            current(live)
            val root = live.automation.rootInActiveWindow
            val inDocuments = root?.packageName?.toString() in DOCUMENT_PACKAGES
            root?.recycle()
            if (!inDocuments) return@repeat
            assertTrue("Close only the real diagnostic DocumentsUI through normal Back",
                live.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            SystemClock.sleep(200)
        }
        await(scenario, "the same native composer returns from geometry-only chooser inspection") { activity ->
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() === activity &&
                activity.findViewById<EditText>(R.id.reply_input).text.isNullOrEmpty() &&
                !activity.findViewById<Chip>(R.id.composer_attachment_chip).isShown &&
                !androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value.sending
        }
    }

    private fun clickVisible(automation: UiAutomation, matches: (AccessibilityNodeInfo) -> Boolean): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.isEnabled && matches(node)) {
                var candidate = AccessibilityNodeInfo.obtain(node)
                try {
                    repeat(8) { depth ->
                        if (candidate.packageName != node.packageName) return false
                        if (candidate.isVisibleToUser && candidate.isEnabled &&
                            (candidate.isClickable || depth == 0 && candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) &&
                            candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        val parent = candidate.parent ?: return false
                        candidate.recycle(); candidate = parent
                    }
                } finally { candidate.recycle() }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { if (visit(child)) return true } finally { child.recycle() }
            }
            return false
        }
        val root = automation.rootInActiveWindow ?: return false
        return try { visit(root) } finally { root.recycle() }
    }
    private fun setDiagnostics(app: CollieApplication, enabled: Boolean) {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            await(scenario, "actual Settings diagnostic control") { activity ->
                activity.findViewById<CompoundButton>(R.id.settings_diagnostics_switch)?.isEnabled == true &&
                    texts(activity).none { it == activity.getString(R.string.settings_refreshing) }
            }
            onView(withId(R.id.settings_diagnostics_switch)).perform(scrollTo(), click())
            await(scenario, "actual diagnostic preference acknowledgement") { activity ->
                activity.findViewById<CompoundButton>(R.id.settings_diagnostics_switch).isChecked == enabled &&
                    app.container.nativePreferences.diagnosticsEnabled == enabled
            }
        }
    }
    private fun awaitUploadTrace(live: Live, window: TraceWindow, marker: String): List<JsonObject> {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val records = traces(live, window, marker)
            if (records.any { it["method"]?.jsonPrimitive?.contentOrNull == "POST" && it["path"]?.jsonPrimitive?.contentOrNull?.endsWith("/upload") == true }) return records
            SystemClock.sleep(100)
        }
        throw AssertionError("Actual native upload must be witnessed by opt-in diagnostic metadata")
    }
    private fun traces(live: Live, window: TraceWindow, marker: String?): List<JsonObject> {
        val writer = live.app.container.diagnosticsWriter
        // Take the bounded read under the writer's existing append/seal monitor so normal trace
        // rotation cannot delete the active file between its existence check and the read.
        val content = synchronized(writer) { buildList {
            val active = writer.activeFile()
            if (active.exists()) { assertTrue("Active diagnostic read must stay bounded", active.length() <= 2_000_000); add(active.readText()) }
            writer.sealedFiles().filter { it.name !in window.priorSealed }.forEach { file ->
                assertTrue("New sealed diagnostic read must stay bounded", file.length() <= 2_000_000)
                add(writer.decrypt(file).also { assertTrue("Decrypted diagnostic read must stay bounded", it.length <= 2_000_000) })
            }
        } }
        content.forEach { text ->
            assertFalse("Diagnostics must never contain the current device credential", text.contains(requireNotNull(live.paired.token)))
            if (marker != null) assertFalse("Diagnostics must never contain raw uploaded document content", marker in text)
        }
        return content.flatMap { text -> text.lineSequence().mapNotNull { line ->
            (runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject)?.takeIf { record ->
                record["category"]?.jsonPrimitive?.contentOrNull == "network" && (record["at"]?.jsonPrimitive?.longOrNull ?: 0) >= window.at
            }
        }.toList() }
    }
    private fun assertNoTerminalPost(records: List<JsonObject>) = assertFalse("Chooser/upload/removal must never submit terminal keys or replies", records.any { record ->
        record["method"]?.jsonPrimitive?.contentOrNull == "POST" && record["path"]?.jsonPrimitive?.contentOrNull?.let { path ->
            path.contains("/api/pane/") && TERMINAL_ACTIONS.any { path.endsWith("/$it") }
        } == true
    })
    private fun texts(activity: Activity) = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun descendants(view: View): List<View> = buildList { add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i))) }
    private fun <T : Activity> await(scenario: ActivityScenario<T>, checkpoint: String, ready: (T) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) { var matched = false; scenario.onActivity { matched = ready(it) }; if (matched) return; SystemClock.sleep(100) }
        throw AssertionError("Physical native media checkpoint timed out: $checkpoint")
    }
    private companion object {
        val DOCUMENT_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui")
        const val MAX_DOWNLOAD_SCROLL_PAGES = 10
        val TERMINAL_ACTIONS = setOf("reply", "keys", "answer", "send", "abort", "close")
    }
}
