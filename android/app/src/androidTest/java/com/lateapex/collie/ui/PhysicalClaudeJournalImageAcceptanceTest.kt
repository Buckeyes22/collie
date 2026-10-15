package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.AgentIdentityResponse
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
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Private candidate: read-only evidence for one real Claude image turn in the owned completion profile. */
@RunWith(AndroidJUnit4::class)
class PhysicalClaudeJournalImageAcceptanceTest {
    @Test
    fun actualClaudeInputImageProjectsAndRendersThroughScopedNativeHistory() {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val args = InstrumentationRegistry.getArguments()
        val originText = requireNotNull(args.getString("liveDurableOrigin"))
        val origin = OriginValidator().requireValid(originText)
        assertEquals("Use only the fixed stock completion HTTPS origin",
            OriginValidator().requireValid(STOCK_ORIGIN), origin)
        assertTrue("This acceptance must use the isolated completion package", app.packageName.endsWith(".completiontest"))
        val root = requireNotNull(args.getString("liveRepoPath"))
        assertTrue("Use only the owned dated Git fixture root", root.matches(PhysicalFixturePaths.datedRootPattern))
        val scope = Scope(args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty))
        val paneId = requireNotNull(args.getString("livePaneId"))
        assertTrue("Pane ID must be bounded", paneId.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val sessionId = requireNotNull(args.getString("liveClaudeSessionId"))
        assertEquals("Supply the actual canonical Claude UUID", UUID.fromString(sessionId).toString(), sessionId)
        val expectedPrompt = requireNotNull(args.getString("liveImagePrompt"))
        assertTrue("The expected marker must be the harmless task-owned acceptance marker",
            expectedPrompt.matches(Regex("COLLIE_CC13_CLAUDE_IMAGE_[A-Z0-9_]{1,48}")))

        val store = app.container.connectionStore
        val repository = app.container.repository
        val connection = requireNotNull(store.connection.value)
        assertTrue("Use the existing paired profile", connection.isPaired)
        assertEquals("The existing pair must match the exact supplied origin", origin, connection.origin)
        val committed = requireNotNull(store.captureCommitted())
        assertSame("The original committed profile must belong to the selected connection", connection, committed.connection)
        val address = PaneAddress(scope, paneId)
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        val launchIntent = Intent(app, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, paneId)
            putExtra(PaneActivity.EXTRA_HOST, scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, scope.session)
            putExtra(PaneActivity.EXTRA_AGENT, "claude")
            putExtra(PaneActivity.EXTRA_CWD, root)
            putExtra(PaneActivity.EXTRA_TITLE, "Claude image acceptance")
        }

        try {
            ActivityScenario.launch<PaneActivity>(launchIntent).use { paneScenario ->
                awaitPane(paneScenario) { activity ->
                    activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) == paneId &&
                        activity.findViewById<View>(R.id.body_mode_row)?.isShown == true
                }
                val actor = awaitOwnedPaneActor(app, paneScenario, connection, committed, scope, paneId, root)
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                val snapshotResult = runBlocking { repository.snapshot(scope, actor) }
                assertTrue("Exact read-only snapshot must succeed (failureType=${safeFailureType(snapshotResult)})",
                    snapshotResult is ApiResult.Success)
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                val pane = exactPane((snapshotResult as ApiResult.Success).value, scope, paneId)
                assertEquals("The supplied pane must be Claude", "claude", pane.agent.lowercase())
                assertEquals("The supplied Claude pane must stay in the owned root", root, pane.cwd)

                val identity = readIdentity(app, address, actor)
                assertEquals("The actual identity endpoint must report the supplied session UUID", sessionId, identity.session?.value)
                assertEquals("The actual identity endpoint must report Claude", "claude", identity.session?.agent?.lowercase())
                val chat = awaitActualImageChat(app, address, actor, connection, committed, scope, paneId, expectedPrompt)
                val imageTurn = chat.upserts.single { it.role == "user" && textOf(it) == expectedPrompt }
                val imageParts = imageTurn.parts.filter { it.kind == "image" }
                assertEquals("The real journal user turn must contain exactly one projected image part", 1, imageParts.size)
                val image = imageParts.single()
                assertTrue("Image MIME type must be declared", image.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
                assertTrue("Image reference must use an admitted inline image or Collie blob route",
                    SAFE_IMAGE_DATA.matches(image.url.orEmpty()) || SAFE_BLOB.matches(image.url.orEmpty()))
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                assertIdentityUnchanged(app, address, actor, sessionId)

                paneScenario.onActivity { activity ->
                    val mode = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.body_mode_switch)
                    if (mode.isShown && mode.text == activity.getString(R.string.pane_body_switch_to_transcript)) {
                        assertTrue("Select the real transcript using the native body control", mode.performClick())
                    }
                }
                awaitPane(paneScenario) { it.findViewById<View>(R.id.transcript_body)?.isShown == true }
                paneScenario.onActivity { activity ->
                    val label = activity.findViewById<TextView>(R.id.body_mode_label)
                    assertTrue("Open History with the native transcript control", label.isShown && label.isEnabled && label.performClick())
                }
                val history = awaitResumedHistory()
                awaitHistoryImage(history, expectedPrompt)
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                assertIdentityUnchanged(app, address, actor, sessionId)
                assertSame("Native read-only image viewing must preserve the installed profile", connection, store.connection.value)
                assertEquals("Native read-only image viewing must preserve the committed profile", committed, store.captureCommitted())
                InstrumentationRegistry.getInstrumentation().runOnMainSync { history.finish() }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .singleOrNull { it is HistoryActivity || it is PaneActivity }?.finish()
                repository.selectSubject(priorScope, priorSubject)
            }
        }
    }

    /** Parent holds the genuine HTTPS History response carrying the existing inline Claude image. */
    @Test
    fun heldRealClaudeImageHistoryCannotPublishAfterNativeHostSwitchOrPairingRevocation() {
        val args = InstrumentationRegistry.getArguments()
        fun required(name: String) = requireNotNull(args.getString(name)?.takeIf(String::isNotBlank)) { "Supply $name" }
        val suppliedMode = args.getString("nativeImageRetirement")
        org.junit.Assume.assumeTrue("Explicit nativeImageRetirement opt-in is required", suppliedMode != null)
        val mode = requireNotNull(suppliedMode)
        assertTrue("Choose only the approved host or revoke workflow", mode in setOf("host", "revoke"))
        val run = required("nativeImageRetirementRun")
        assertTrue("Use a fresh bounded timing token", run.matches(Regex("[A-Za-z0-9_-]{4,32}")))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Only the isolated completion app may run this acceptance", app.packageName.endsWith(".completiontest"))
        val origin = OriginValidator().requireValid(required("liveDurableOrigin"))
        val expectedOrigin = OriginValidator().requireValid(if (mode == "host") "https://prod.lateapexllc.com/completion-crew/" else STOCK_ORIGIN)
        assertEquals("Use only the exact existing owned fixture origin", expectedOrigin, origin)
        val scope = Scope(if (mode == "host") "qa-lead" else null)
        assertEquals("The source host must be the actual owned peer, or local Stock for revocation", scope.host, args.getString("liveHost"))
        assertTrue("The original journal remains in the default session", args.getString("liveSession").isNullOrBlank())
        assertEquals("Reuse only the original real Claude pane", "w9:p1", required("livePaneId"))
        assertEquals("Reuse only the existing paid-call journal UUID", "33d0ccd1-f054-4f98-84dc-871ec27cf39b", required("liveClaudeSessionId"))
        assertEquals("Reuse only the genuine original technical-image marker", "COLLIE_CC13_CLAUDE_IMAGE_INPUT", required("liveImagePrompt"))
        val root = required("liveRepoPath")
        assertEquals("Reuse only the actual dated fixture root", PhysicalFixturePaths.root, root)
        val store = app.container.connectionStore
        val connection = requireNotNull(store.connection.value)
        assertTrue("The original installed fixture profile must be paired", connection.isPaired)
        assertEquals("The paired origin must be exact", origin, connection.origin)
        assertEquals("Only the task-owned native pairing may be revoked", "completion Pixel", connection.label)
        val committed = requireNotNull(store.captureCommitted())
        assertTrue("The original committed incarnation is required", committed.connection === connection && committed.incarnationId != null)
        val repository = app.container.repository
        if (mode == "host") assertEquals("First select qa-lead through the normal dashboard host menu; never seed preferences", scope,
            app.container.nativePreferences.dashboardScope)
        val address = PaneAddress(scope, "w9:p1")
        val launchIds = app.container.launchIntentStore.all().map { it.intent.requestId }.toSet()
        val phaseFile = java.io.File(app.cacheDir, "cc13-image-retirement-$run.marker")
        assertTrue("Never reuse a parent timing marker", !phaseFile.exists())
        val expectedPathSegments = java.net.URI(origin.value).path.trim('/').split('/') + listOf("api", "pane", "w9:p1", "history")
        fun historyCalls() = app.container.httpClient.dispatcher.runningCalls().filter { call ->
            val request = call.request()
            request.method == "GET" && request.url.scheme == "https" && request.url.host == "prod.lateapexllc.com" &&
                request.url.port == 443 && request.url.pathSegments == expectedPathSegments &&
                request.url.queryParameter("host") == scope.host && request.url.queryParameter("session") == null
        }
        // Parent writes bounded JSON atomically after independently inspecting the real relay.
        fun phase(expected: String, guard: () -> Unit = {}): org.json.JSONObject {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                guard()
                if (phaseFile.isFile) {
                    assertTrue("Timing metadata must remain bounded", phaseFile.length() in 1L..2048L)
                    val value = org.json.JSONObject(phaseFile.readText())
                    assertEquals("Relay metadata must belong to this exact fresh run", run, value.getString("run"))
                    if (value.getString("phase") == expected) return value
                }
                SystemClock.sleep(25)
            }
            throw AssertionError("Parent image response timing phase did not arrive: $expected")
        }
        fun emit(phase: String, actor: ScopedReadContext, retirementElapsedMs: Long? = null,
            retirementAckToken: String? = null) = InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("nativeImagePhase", phase)
            putString("nativeImageMarkerPath", phaseFile.absolutePath)
            putString("nativeImageOrigin", origin.value)
            putString("nativeImageHost", scope.host ?: "local")
            putString("nativeImagePane", address.paneId)
            putString("nativeImageIncarnation", committed.incarnationId)
            putString("nativeImageNavigationGeneration", actor.navigationGeneration.toString())
            retirementElapsedMs?.let { putString("nativeImageRetirementElapsedMs", it.toString()) }
            retirementAckToken?.let { putString("nativeImageRetirementAckToken", it) }
            putString("stream", "Native pending image-bearing History read phase=$phase; this proves pending-read cancellation, not delivery of a late client 200 or decoder/bitmap purge.\n")
        })
        val paneIntent = Intent(app, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
            putExtra(PaneActivity.EXTRA_HOST, scope.host)
            putExtra(PaneActivity.EXTRA_AGENT, "claude")
            putExtra(PaneActivity.EXTRA_CWD, root)
            putExtra(PaneActivity.EXTRA_TITLE, "Existing Claude image retirement")
        }
        ActivityScenario.launch<PaneActivity>(paneIntent).use { paneScenario ->
            val actor = awaitOwnedPaneActor(app, paneScenario, connection, committed, scope, address.paneId, root)
            assertIdentityUnchanged(app, address, actor, required("liveClaudeSessionId"))
            val chat = awaitActualImageChat(app, address, actor, connection, committed, scope, address.paneId, required("liveImagePrompt"))
            val part = chat.upserts.single { it.role == "user" && textOf(it) == required("liveImagePrompt") }
                .parts.single { it.kind == "image" }
            assertTrue("This is the genuine inline image carried over authenticated HTTPS, not an invented blob fetch", SAFE_IMAGE_DATA.matches(part.url.orEmpty()))
            val imageBytes = android.util.Base64.decode(requireNotNull(part.url).substringAfter(";base64,"), android.util.Base64.DEFAULT)
            val imageSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(imageBytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val retiredObservation = java.util.concurrent.atomic.AtomicReference<Pair<Long, com.lateapex.collie.data.AuthorityInvalidation>?>(null)
            // Observe the shipping retirement event; never synthesize retirement or write state.
            val retirement = repository.onReadContextRetired { event ->
                if (event.retiresReadActor && event.affects(actor)) {
                    retiredObservation.compareAndSet(null, SystemClock.elapsedRealtime() to event.reason)
                }
            }
            var mainScenario: ActivityScenario<MainActivity>? = null
            try {
                val historyLaunchedAt = SystemClock.elapsedRealtime()
                fun retiredBeforeTimeout(reason: com.lateapex.collie.data.AuthorityInvalidation) {
                    val observation = requireNotNull(retiredObservation.get()) { "The actual original-actor retirement event was not observed" }
                    val at = observation.first
                    assertTrue("Actual authority retirement must precede the real 10-second timeout by a conservative margin",
                        at > 0L && at >= historyLaunchedAt && at - historyLaunchedAt < 9_000L)
                    assertEquals("The original actor must retire through the expected normal native boundary", reason, observation.second)
                    assertTrue("The original image-bearing read actor must be retired", !repository.isCurrent(actor))
                }
                ActivityScenario.launch<HistoryActivity>(HistoryActivity.intent(app, address, "Existing Claude image retirement", "claude")).use { historyScenario ->
                    var historyRoot: View? = null
                    historyScenario.onActivity { historyRoot = it.window.decorView }
                    var held: okhttp3.Call? = null
                    val pendingDeadline = SystemClock.uptimeMillis() + 5_000
                    while (held == null && SystemClock.uptimeMillis() < pendingDeadline) {
                        assertCurrentOriginal(app, connection, committed, actor, scope, address.paneId)
                        held = historyCalls().singleOrNull()?.takeIf { it.isExecuted() && !it.isCanceled() }
                        if (held == null) SystemClock.sleep(25)
                    }
                    val originalCall = requireNotNull(held) { "No actual original-origin HTTPS History request was held" }
                    // These are the normal shipping OkHttp request custody tags, not test hooks.
                    assertSame("The actual HTTPS request carries the exact original Connection", connection,
                        originalCall.request().tag(Connection::class.java))
                    val requestActor = requireNotNull(originalCall.request().tag(ScopedReadContext::class.java))
                    assertEquals("The actual HTTPS request carries the original host/session/subject actor", actor.key, requestActor.key)
                    assertEquals("The actual HTTPS request carries the original navigation generation", actor.navigationGeneration, requestActor.navigationGeneration)
                    assertEquals("The actual HTTPS request carries the original committed pair incarnation", committed, requestActor.committedSnapshot)
                    fun pending() {
                        assertCurrentOriginal(app, connection, committed, actor, scope, address.paneId)
                        assertTrue("No earlier retirement can substitute for the chosen boundary", retiredObservation.get() == null)
                        assertTrue("There must still be time to retire authority before the actual HTTP timeout",
                            SystemClock.elapsedRealtime() - historyLaunchedAt < 9_000L)
                        assertTrue("The same real read must remain in flight; a network timeout is not retirement acceptance",
                            originalCall in historyCalls() && !originalCall.isCanceled())
                        InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            val view = requireNotNull(historyRoot)
                            assertTrue("The real History screen must still await its held first page", view.findViewById<View>(R.id.history_progress).isShown)
                            assertEquals("The native History state must remain the shipped loading state, without an error",
                                app.getString(R.string.history_loading), view.findViewById<TextView>(R.id.history_state).text.toString())
                            assertTrue("The held original image marker must not have rendered or been retained early",
                                descendants(view.findViewById<ViewGroup>(R.id.history_entries)).filterIsInstance<TextView>()
                                    .none { it.text.toString().contains(required("liveImagePrompt")) })
                            assertTrue("No History image card has been admitted before its held page",
                                descendants(view).none { it is AuthenticatedImageView })
                        }
                    }
                    emit("PENDING", actor)
                    // The normal API read timeout is 10 seconds. Parent must inspect its real 200
                    // body/hash and acknowledge promptly; this helper never extends that timeout.
                    val upstream = phase("upstream-verified", ::pending)
                    fun responseEvidence(value: org.json.JSONObject) {
                        assertEquals("Parent must independently verify the actual upstream success", 200, value.getInt("upstreamStatus"))
                        assertTrue("Parent relay evidence must name a bounded actual response body", value.getLong("responseBytes") in 1L..8_388_608L)
                        assertTrue("The actual response hash must be a SHA-256", value.getString("responseSha256").matches(Regex("[a-f0-9]{64}")))
                        assertEquals("The parent-inspected body must contain the genuine original image", imageSha256, value.getString("imageSha256"))
                        assertEquals("Relay evidence must name the exact actual HTTPS request path", originalCall.request().url.encodedPath, value.getString("requestPath"))
                        assertEquals("Relay evidence must name the original host scope", scope.host ?: "local", value.getString("sourceHost"))
                        // Relay timestamps belong to the host clock, not the phone clock. The
                        // actual completed-body acknowledgement provides the observation order.
                        assertTrue("The real upstream completion must have a positive relay timestamp",
                            value.getLong("upstreamCompletedAtEpochMs") > 0L)
                    }
                    responseEvidence(upstream)
                    pending()
                    if (mode == "host") {
                        // Launching the shipped dashboard retires the pane subject before its host
                        // row is clicked. That launch, not the later row click, is this boundary.
                        pending()
                        mainScenario = ActivityScenario.launch(MainActivity::class.java)
                        retiredBeforeTimeout(com.lateapex.collie.data.AuthorityInvalidation.SCOPE_CHANGED)
                        var leadName: String? = null
                        val dashboardDeadline = SystemClock.uptimeMillis() + 5_000
                        while (leadName == null && SystemClock.uptimeMillis() < dashboardDeadline) {
                            requireNotNull(mainScenario).onActivity { activity ->
                                val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
                                val servers = state.snapshot?.servers.orEmpty()
                                val lead = servers.singleOrNull { it.isLead && it.id == "qa-deputy" }
                                val fresh = state.originalReadContext
                                if (repository.selectedScope.value == scope && state.savedCopy == null && state.lastSuccessAt != null &&
                                    fresh != null && repository.isCurrent(fresh) && fresh.connection === connection &&
                                    fresh.committedSnapshot == committed && lead != null &&
                                    activity.findViewById<View>(R.id.host_switcher).isShown) leadName = lead.name.ifBlank { lead.id }
                            }
                            if (leadName == null) SystemClock.sleep(50)
                        }
                        val title = requireNotNull(leadName) { "Native dashboard did not expose the existing promoted lead" }
                        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.host_switcher))
                            .perform(androidx.test.espresso.action.ViewActions.click())
                        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(title))
                            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                            .perform(androidx.test.espresso.action.ViewActions.click())
                        val selectedDeadline = SystemClock.uptimeMillis() + 5_000
                        while (repository.selectedScope.value != Scope() && SystemClock.uptimeMillis() < selectedDeadline) SystemClock.sleep(25)
                        assertEquals("The actual native host row must select the promoted lead", Scope(), repository.selectedScope.value)
                        assertSame("Native host navigation preserves the original paired credential", connection, store.connection.value)
                        assertEquals("Native host navigation preserves its committed pairing incarnation", committed, store.captureCommitted())
                    } else {
                        emit("WAIT_REVOKE", actor)
                        // Every poll, including the acknowledgement poll, must still observe the
                        // same live uncanceled request. Timeout/automatic prior retirement fails.
                        phase("revoked", ::pending)
                        pending()
                        val read = runBlocking { repository.snapshot(scope, actor) }
                        val failure = (read as? ApiResult.Failure)?.error as? com.lateapex.collie.network.ApiFailure.Http
                        assertEquals("Normal parent CLI revocation must yield a real pairing denial", 403, failure?.status)
                        retiredBeforeTimeout(com.lateapex.collie.data.AuthorityInvalidation.PAIRING_RETIRED)
                        val retiredDeadline = SystemClock.uptimeMillis() + 5_000
                        while (store.connection.value?.isPaired == true && SystemClock.uptimeMillis() < retiredDeadline) SystemClock.sleep(25)
                        val retiredPair = requireNotNull(store.connection.value)
                        assertEquals("Retirement preserves the original public origin", connection.origin, retiredPair.origin)
                        assertEquals("Retirement preserves the original public label", connection.label, retiredPair.label)
                        assertTrue("Revocation removes native pairing authority", !retiredPair.isPaired)
                    }
                    fun erased() {
                        retiredBeforeTimeout(if (mode == "host") com.lateapex.collie.data.AuthorityInvalidation.SCOPE_CHANGED
                            else com.lateapex.collie.data.AuthorityInvalidation.PAIRING_RETIRED)
                        assertTrue("Native retirement must cancel the original pending HTTP read", originalCall.isCanceled())
                        InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            val retainedRoot = requireNotNull(historyRoot)
                            // Inspect retained/stopped views too: isShown would hide late publication.
                            assertTrue("No retained History text may receive the original held image marker",
                                descendants(retainedRoot).filterIsInstance<TextView>()
                                    .none { it.text.toString().contains(required("liveImagePrompt")) })
                            val cards = descendants(retainedRoot).filterIsInstance<AuthenticatedImageView>().toList()
                            assertTrue("No thumbnail drawable from the unadmitted page may appear",
                                cards.all { card -> descendants(card).filterIsInstance<ImageView>().all { it.drawable == null } })
                            assertTrue("The pending page must not admit any History image card after retirement", cards.isEmpty())
                        }
                    }
                    erased()
                    // Fresh only after actual native retirement/cancellation is verified. Parent
                    // echoes this token only after observing RETIRED, releasing, and reading the
                    // actual relay ended record; do not compare the phone and relay wall clocks.
                    val retirementAckToken = java.util.UUID.randomUUID().toString()
                    emit("RETIRED", actor, requireNotNull(retiredObservation.get()).first - historyLaunchedAt, retirementAckToken)
                    val released = phase("released", ::erased)
                    responseEvidence(released)
                    for (key in listOf("responseSha256", "responseBytes", "imageSha256", "requestPath", "sourceHost", "upstreamCompletedAtEpochMs")) {
                        assertEquals("Release metadata must identify the same independently verified held response", upstream.get(key), released.get(key))
                    }
                    assertEquals("Parent must observe the relay hold actually end by release", "released", released.getString("relayHoldEndReason"))
                    assertEquals("Parent must acknowledge the actual relay end after observing this fresh native retirement",
                        retirementAckToken, released.getString("retirementAckToken"))
                    assertTrue("The same relay clock must place the actual release after its completed upstream body",
                        released.getLong("relayHoldEndedAtEpochMs") >= released.getLong("upstreamCompletedAtEpochMs"))
                    val lateDeadline = SystemClock.uptimeMillis() + 3_000
                    while (SystemClock.uptimeMillis() < lateDeadline) { erased(); SystemClock.sleep(50) }
                    assertTrue("Read/navigation/retirement must not create any new mutation intent", app.container.launchIntentStore.all().all { it.intent.requestId in launchIds })
                    emit("VERIFIED", actor, requireNotNull(retiredObservation.get()).first - historyLaunchedAt)
                }
            } finally { retirement.close(); mainScenario?.close() }
        }
    }

    private fun readIdentity(app: CollieApplication, address: PaneAddress, actor: ScopedReadContext): AgentIdentityResponse {
        val repo = app.container.repository
        val result = runBlocking { repo.paneAgentIdentity(address, actor) }
        assertTrue("Actual read-only pane identity must succeed", result is ApiResult.Success)
        assertTrue("The original pane actor remains current after identity read", repo.isCurrent(actor))
        return (result as ApiResult.Success).value.also {
            assertTrue("Agent identity must be admitted", it.ok)
            assertEquals("Identity must match the supplied pane", address.paneId, it.paneId)
        }
    }

    private fun assertIdentityUnchanged(app: CollieApplication, address: PaneAddress, actor: ScopedReadContext, sessionId: String) {
        val identity = readIdentity(app, address, actor)
        assertEquals("The original vendor session must still own this pane after UI history read", sessionId, identity.session?.value)
        assertEquals("The original agent must still own this pane after UI history read", "claude", identity.session?.agent?.lowercase())
    }

    private fun awaitActualImageChat(
        app: CollieApplication, address: PaneAddress, actor: ScopedReadContext, connection: Connection,
        committed: com.lateapex.collie.data.CommittedConnectionSnapshot, scope: Scope, paneId: String, prompt: String,
    ): PaneChatResponse {
        val repo = app.container.repository
        val end = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < end) {
            assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
            val result = runBlocking { repo.chat(address, limit = 200, expectedContext = actor) }
            if (result is ApiResult.Success) {
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                val page = result.value
                assertTrue("The real Claude conversation must be available", page.available)
                assertEquals("The real Chat response must match the exact supplied pane", paneId, page.paneId)
                if (page.upserts.any { it.role == "user" && textOf(it) == prompt && it.parts.any { part -> part.kind == "image" } }) return page
            }
            SystemClock.sleep(800)
        }
        throw AssertionError("The real read-only Chat projection did not expose the exact Claude image turn")
    }

    private fun awaitOwnedPaneActor(
        app: CollieApplication,
        scenario: ActivityScenario<PaneActivity>,
        connection: Connection,
        committed: com.lateapex.collie.data.CommittedConnectionSnapshot,
        scope: Scope,
        paneId: String,
        root: String,
    ): ScopedReadContext {
        val repository = app.container.repository
        val end = SystemClock.uptimeMillis() + WAIT_MS
        var accepted: ScopedReadContext? = null
        while (accepted == null && SystemClock.uptimeMillis() < end) {
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val actor = state.paneReadContext
                val summary = state.panes.singleOrNull {
                    it.paneId == paneId && it.host == scope.host && it.session == scope.session
                }
                val authorityMatches = actor != null && repository.isCurrent(actor) &&
                    actor.connection === connection && actor.requestScope == scope &&
                    actor.key.host == scope.host && actor.key.session == scope.session &&
                    actor.key.subjectIdentity == paneId && actor.connectionIncarnation == committed.incarnationId &&
                    actor.committedStore && actor.committedSnapshot == committed
                val paneMatches = state.pane?.paneId == paneId && state.savedCopy == null &&
                    state.lastSuccessAt != null && summary?.agent.equals("claude", ignoreCase = true) && summary?.cwd == root
                if (authorityMatches && paneMatches) accepted = actor
            }
            if (accepted == null) SystemClock.sleep(100)
        }
        assertNotNull("Before the first API read, PaneViewModel must display a live current Claude pane under the exact original paired actor and owned root", accepted)
        return requireNotNull(accepted)
    }

    private fun awaitPane(scenario: ActivityScenario<PaneActivity>, ready: (PaneActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + WAIT_MS
        var seen = false
        while (!seen && SystemClock.uptimeMillis() < end) {
            scenario.onActivity { seen = ready(it) }
            if (!seen) SystemClock.sleep(100)
        }
        assertTrue("Native PaneActivity did not load the exact Claude pane", seen)
    }

    private fun awaitResumedHistory(): HistoryActivity {
        val end = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < end) {
            var found: HistoryActivity? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<HistoryActivity>().singleOrNull()
            }
            found?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("The native History screen did not open")
    }

    private fun awaitHistoryImage(activity: HistoryActivity, prompt: String) {
        val end = SystemClock.uptimeMillis() + WAIT_MS
        var rendered = false
        while (!rendered && SystemClock.uptimeMillis() < end) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val root = activity.findViewById<ViewGroup>(R.id.history_entries)
                // HistoryActivity adds one turn root per direct child. Bind the image to the same
                // rendered turn as the exact marker instead of matching unrelated history content.
                val markedTurns = (0 until root.childCount).map(root::getChildAt)
                    .filterIsInstance<ViewGroup>()
                    .filter { visibleText(it).contains(prompt) }
                val markedTurn = markedTurns.singleOrNull()
                val card = markedTurn?.let { turn ->
                    descendants(turn).filterIsInstance<AuthenticatedImageView>()
                        .singleOrNull { it.isShown && it.isClickable }
                }
                val imageVisible = card?.let { imageCard ->
                    descendants(imageCard).filterIsInstance<ImageView>().any { it.isShown && it.drawable != null }
                } == true
                val expectedCardDescription = activity.getString(R.string.history_image_open, activity.getString(R.string.history_image))
                rendered = imageVisible && card?.contentDescription == expectedCardDescription
            }
            if (!rendered) SystemClock.sleep(150)
        }
        assertTrue("Native History must show the exact marker and a decoded authenticated image card", rendered)
    }

    private fun assertCurrentOriginal(
        app: CollieApplication, connection: Connection,
        committed: com.lateapex.collie.data.CommittedConnectionSnapshot,
        actor: ScopedReadContext, scope: Scope, paneId: String,
    ) {
        val repo = app.container.repository
        assertSame("The original installed profile cannot change", connection, app.container.connectionStore.connection.value)
        assertEquals("The original committed profile witness cannot change", committed, app.container.connectionStore.captureCommitted())
        assertTrue("The original exact-scope pane actor must remain current", repo.isCurrent(actor))
        assertEquals("The original actor must remain scoped to the supplied pane", scope, actor.requestScope)
        assertEquals("The original actor must remain scoped to the supplied subject", paneId, actor.key.subjectIdentity)
    }

    private fun exactPane(snapshot: SnapshotResponse, scope: Scope, paneId: String): PaneSummary =
        requireNotNull((snapshot.agents + snapshot.shellPanes).singleOrNull {
            it.paneId == paneId && it.host == scope.host && it.session == scope.session
        }) { "Live snapshot must contain the exact supplied host/session pane" }

    private fun textOf(entry: TranscriptEntry): String = entry.parts.filter { it.kind == "text" }.joinToString("\n") { it.text.orEmpty() }
    private fun safeFailureType(result: ApiResult<*>): String =
        (result as? ApiResult.Failure)?.error?.javaClass?.simpleName ?: "none"

    private fun visibleText(root: View): List<String> = descendants(root).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text?.toString().orEmpty() }.toList()

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    companion object {
        private const val STOCK_ORIGIN = "https://prod.lateapexllc.com/completion"
        private const val WAIT_MS = 90_000L
        private val SAFE_IMAGE_DATA = Regex("data:image/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=\\s]+")
        private val SAFE_BLOB = Regex("/api/blobs/[a-fA-F0-9]{64}")
    }
}
