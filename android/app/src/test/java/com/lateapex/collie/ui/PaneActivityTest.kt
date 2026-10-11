package com.lateapex.collie.ui

import android.content.Intent
import android.os.Bundle
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.URLSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.time.Duration
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.diagnostics.DiagnosticsRecorder
import com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.TabSummary
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowSystemClock

// Exercise the same native View/matrix backend across this actual-screen fixture. Robolectric
// 4.14.1 defaults to legacy graphics; the attached draw cases already require native graphics.
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PaneActivityTest {
    @Test
    fun ompMultiCardShowsCheckedRowsAndKeepsUnmeasuredCustomAnswerOutOfToggleActions() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("omp:multi", "omp"))
            .create().start().resume().get()
        val fixture = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "web/src/fixtures/panes/omp--v18-4-ask-multi-checked.txt") }
            .firstOrNull(File::isFile)?.readText() ?: error("Could not locate the OMP fixture")
        val writableScope = PaneUiState(
            pane = PaneReadResponse("omp:multi", fixture, truncated = false, revision = 51),
            loading = false,
            canWrite = true,
            topologyKnown = true,
            panes = listOf(PaneSummary(
                paneId = "omp:multi",
                workspaceId = "w1",
                workspaceLabel = "project",
                workspaceNumber = 1,
                tabId = "w1:t1",
                agent = "omp",
                status = AgentStatus.WORKING,
                cwd = "/repo",
                focused = true,
            )),
        )
        assertNull(PaneWriteGate.block(com.lateapex.collie.domain.PaneAddress(com.lateapex.collie.domain.Scope(), "omp:multi"), writableScope))
        render(activity, writableScope)

        val rows = descendants(activity.findViewById<ViewGroup>(R.id.semantic_actions)).toList()
        val cheese = rows.filterIsInstance<MaterialButton>().single { it.text.contains("Cheese") }
        val other = rows.filterIsInstance<TextView>().single { it.text.contains("Other (type your own)") }
        val submit = rows.filterIsInstance<MaterialButton>().single { it.text.contains("Submit selected answers") }
        assertTrue(cheese.text.toString().contains("☑"))
        assertFalse(other.isClickable)
        assertTrue(other.text.toString().contains("Use the terminal to enter a custom answer"))
        assertTrue(submit.isEnabled)
    }

    @Test
    fun ompWriteApprovalRendersTheWholeApprovedPathAndLongContentOnTheActionCard() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("omp:write", "omp"))
            .create().start().resume().get()
        val fixture = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "web/src/fixtures/panes/omp--v18-4-approval-write-long.txt") }
            .firstOrNull(File::isFile)?.readText() ?: error("Could not locate the OMP fixture")
        render(activity, PaneUiState(
            pane = PaneReadResponse("omp:write", fixture, truncated = false, revision = 52),
            loading = false,
        ))

        val actions = descendants(activity.findViewById<ViewGroup>(R.id.semantic_actions)).filterIsInstance<MaterialButton>().toList()
        val approve = actions.single { it.text.toString().contains("Approve") }
        assertTrue(approve.text.toString().contains("Path: /tmp/omp-sandbox-approval/long.txt"))
        assertTrue(approve.text.toString().contains("sample line 1 for the approval dialog"))
        assertTrue(approve.text.toString().contains("sample line 14 for the approval dialog"))
    }


    @Test
    fun plainMirrorRetainsPlatformSelectionAndTapToTypeAfterRepeatedFilesUnavailableRedraws() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("plain:tap")).create().start().resume()
        val activity = controller.get()
        try {
            val writable = PaneUiState(
                pane = PaneReadResponse("plain:tap", "Fixture terminal\n$ ", false, 1, filesReadReady = false),
                loading = false,
                canWrite = true,
                panes = listOf(paneSummary("plain:tap")),
            )
            setViewModelState(activity, writable)
            repeat(3) {
                render(activity, writable.copy(pane = writable.pane!!.copy(revision = it.toLong())))
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                assertTrue("Plain output retains the platform tap listener", mirror.isClickable)
                assertTrue("Plain output retains focus and selection", mirror.isFocusable)
                assertTrue(mirror.isLongClickable)
                assertTrue(mirror.isTextSelectable)
                assertTrue(mirror.movementMethod is android.text.method.ArrowKeyMovementMethod)
                assertTrue(printedSpans(activity).isEmpty())
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
            val mirror = activity.findViewById<TextView>(R.id.terminal_text)
            mirror.clearFocus()
            mirror.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20f, 10f, 0))
            mirror.dispatchTouchEvent(MotionEvent.obtain(0, 60, MotionEvent.ACTION_UP, 20f, 10f, 0))
            assertTrue(activity.findViewById<View>(R.id.reply_input).hasFocus())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun printedPathRedrawCannotAcquireANewActorForRetiredMirrorOutput() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val initialConnection = com.lateapex.collie.domain.Connection(
            com.lateapex.collie.domain.CollieOrigin("https://printed-path.fixture/"), "phone", "token",
        )
        val store = object : com.lateapex.collie.data.ConnectionStore {
            override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(initialConnection)
            override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        var fileReads = 0
        val api = object : com.lateapex.collie.network.CollieApi by app.container.api {
            override suspend fun config(connection: com.lateapex.collie.domain.Connection) =
                com.lateapex.collie.network.ApiResult.Success(com.lateapex.collie.network.BridgeConfigResponse(control = com.lateapex.collie.network.ControlCapabilityResponse(mapOf("filesDiscovery" to true))), 200)
            override suspend fun pane(
                connection: com.lateapex.collie.domain.Connection,
                address: com.lateapex.collie.domain.PaneAddress,
                lines: Int,
                etag: String?,
                markSeen: Boolean,
            ) = com.lateapex.collie.network.ApiResult.Success(PaneReadResponse(address.paneId, "src/Main.kt:12", false, 1, filesReadReady = true), 200)
            override suspend fun files(
                connection: com.lateapex.collie.domain.Connection,
                target: com.lateapex.collie.network.FilesTarget,
                scope: com.lateapex.collie.domain.Scope,
                path: com.lateapex.collie.network.RelativeFilePath?,
                dir: com.lateapex.collie.network.RelativeFilePath?,
                ignored: Boolean,
            ): com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.FilesAnswer> {
                fileReads++
                return com.lateapex.collie.network.ApiResult.Success(
                    com.lateapex.collie.network.FilesAnswer.Listing("/repo", dir?.value.orEmpty(), emptyList(), false), 200,
                )
            }
            override suspend fun filesExist(
                connection: com.lateapex.collie.domain.Connection,
                target: com.lateapex.collie.network.FilesTarget,
                scope: com.lateapex.collie.domain.Scope,
                paths: List<com.lateapex.collie.network.RelativeFilePath>,
            ) = com.lateapex.collie.network.ApiResult.Success(
                // The root contains this exact source file, not every legal interpretation of
                // its printed editor suffix (src/Main.kt:12 is also a legal POSIX filename).
                com.lateapex.collie.network.FilesExistAnswer(paths.map { it.value }.filter { it == "src/Main.kt" }.toSet()), 200,
            )
        }
        val repository = com.lateapex.collie.data.CollieRepository(api, store, app.container.originValidator)
        val original = app.container.repository
        val repositoryField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        repositoryField.set(app.container, repository)
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("printed:pane")).create().visible()
        val activity = controller.get()
        try {
            assertTrue(kotlinx.coroutines.runBlocking { repository.readPane(com.lateapex.collie.domain.PaneAddress(com.lateapex.collie.domain.Scope(), "printed:pane"), 100) } is com.lateapex.collie.network.ApiResult.Success)
            val accepted = requireNotNull(repository.readContext(com.lateapex.collie.domain.Scope(), "printed:pane"))
            render(activity, PaneUiState(
                loading = false,
                pane = PaneReadResponse("printed:pane", "src/Main.kt:12", false, 1, filesReadReady = true),
                paneReadContext = accepted, filesConfigContext = accepted,
            ))
            val update = PaneActivity::class.java.getDeclaredMethod("updatePrintedPathLinks", CharSequence::class.java)
                .apply { isAccessible = true }
            update.invoke(activity, "src/Main.kt:12")
            waitForPrintedPane { (activity.findViewById<TextView>(R.id.terminal_text).text as? Spanned)?.getSpans(0, activity.findViewById<TextView>(R.id.terminal_text).text.length, android.text.style.ClickableSpan::class.java)?.isNotEmpty() == true }
            val admittedReads = fileReads
            assertTrue("the accepted output actor may perform its scoped root lookup", admittedReads > 0)
            val terminal = activity.findViewById<android.widget.TextView>(R.id.terminal_text)
            assertTrue("the confirmed mirror is the actual shown native terminal view", terminal.isShown)
            val oldSpans = (terminal.text as? android.text.Spanned)?.getSpans(0, terminal.text.length, android.text.style.ClickableSpan::class.java).orEmpty()
            assertTrue("confirmed terminal path is exposed as a visible native span", oldSpans.isNotEmpty())
            val oldSpan = oldSpans.first()

            repository.setLeadEpoch(2)
            update.invoke(activity, "src/Main.kt:12")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertEquals("redrawing retained mirror text cannot mint a replacement actor", admittedReads, fileReads)
            assertNull(PaneActivity::class.java.getDeclaredField("terminalOutputContext").apply { isAccessible = true }.get(activity))
            assertNull(PaneActivity::class.java.getDeclaredField("printedLinkContext").apply { isAccessible = true }.get(activity))
            assertNull(PaneActivity::class.java.getDeclaredField("printedLinkText").apply { isAccessible = true }.get(activity))

            assertTrue(kotlinx.coroutines.runBlocking { repository.readPane(com.lateapex.collie.domain.PaneAddress(com.lateapex.collie.domain.Scope(), "printed:pane"), 100) } is com.lateapex.collie.network.ApiResult.Success)
            val replacement = requireNotNull(repository.readContext(com.lateapex.collie.domain.Scope(), "printed:pane"))
            render(activity, PaneUiState(
                loading = false,
                pane = PaneReadResponse("printed:pane", "src/Main.kt:12", false, 1, filesReadReady = true),
                paneReadContext = replacement, filesConfigContext = replacement,
            ))
            update.invoke(activity, "src/Main.kt:12")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            val newSpans = (terminal.text as? android.text.Spanned)?.getSpans(0, terminal.text.length, android.text.style.ClickableSpan::class.java).orEmpty()
            assertTrue("a genuinely reaccepted body can establish a new link span", newSpans.isNotEmpty())
            val readsBeforeOldClick = fileReads
            oldSpan.onClick(terminal)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertEquals("an old span cannot borrow the replacement actor/root confirmation", readsBeforeOldClick, fileReads)
            newSpans.single().onClick(terminal)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            val opened = shadowOf(app).nextStartedActivity
            assertEquals("src/Main.kt", opened.getStringExtra("files_path"))
            assertEquals(12, opened.getIntExtra("files_line", 0))
        } finally {
            controller.destroy()
            repositoryField.set(app.container, original)
        }
    }

    @Test fun anAwaitedOldPrintedTapCannotRefreshOrClearReplacementConfirmations() {
        val fixture = PrintedPaneFixture()
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("printed:pane"))
            .create().start().resume().visible()
        try {
            val activity = controller.get()
            waitForPrintedPane { printedSpans(activity).isNotEmpty() }
            val terminal = activity.findViewById<TextView>(R.id.terminal_text)
            assertTrue("the accepted mirror span belongs to the shown native terminal view", terminal.isShown)
            val oldSpan = printedSpans(activity).single()
            val delayed = kotlinx.coroutines.CompletableDeferred<Unit>()
            fixture.nextRootWait = delayed
            val beforeTap = fixture.fileReads
            oldSpan.onClick(terminal)
            waitForPrintedPane { fixture.fileReads > beforeTap && fixture.nextRootWait == null }
            fixture.repository.setLeadEpoch(2)
            fixture.pane = fixture.pane.copy(revision = 2)
            val actions = openActionsSheet(activity)
            descendants(actions.window!!.decorView).filterIsInstance<MaterialButton>()
                .single { it.text.toString() == activity.getString(R.string.pane_refresh) }.performClick()
            waitForPrintedPane { printedSpans(activity).isNotEmpty() }
            val newSpan = printedSpans(activity).single()
            val currentReads = fixture.fileReads
            delayed.complete(Unit)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertEquals("old awaited completion does not dispatch replacement reads", currentReads, fixture.fileReads)
            oldSpan.onClick(terminal)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertEquals("retained old tap remains inert", currentReads, fixture.fileReads)
            newSpan.onClick(terminal)
            waitForPrintedPane { fixture.fileReads > currentReads }
            val opened = shadowOf(fixture.app).nextStartedActivity
            assertNotNull("old completion cannot erase the new span's admission", opened)
            assertEquals("src/Main.kt", opened.getStringExtra("files_path"))
            assertEquals(12, opened.getIntExtra("files_line", 0))
        } finally { controller.pause().stop().destroy(); fixture.close() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun drawnAbsoluteTranscriptAndToolPathsReachExactNativeFilesWhileOutsidePathsStayPlain() {
        val fixture = PrintedPaneFixture()
        fixture.transcript = listOf(
            TranscriptEntry("absolute-source", "2026-09-10T00:00:00Z", "assistant", listOf(
                TranscriptPart("text", text = "/repo/src/Main.kt:12"),
                TranscriptPart("tool", name = "read", summary = "/repo/docs/tool.kt:7", result = com.lateapex.collie.network.TranscriptResult("/repo/docs/expanded.kt:9")),
                TranscriptPart("text", text = "/outside/secret.kt"),
            ), seq = 1),
        )
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("printed:pane"))
            .create().start().resume().visible()
        try {
            val activity = controller.get()
            val body = activity.findViewById<ViewGroup>(R.id.transcript_body)
            fun linked(text: String) = descendants(body).filterIsInstance<TextView>().firstOrNull { view ->
                view.isShown && view.text.toString().contains(text) && (view.text as? Spanned)?.getSpans(0, view.text.length, android.text.style.ClickableSpan::class.java)?.isNotEmpty() == true
            }
            waitForPrintedPane { linked("src/Main.kt") != null && linked("docs/tool.kt") != null }
            assertTrue("drawn absolute source is checked as the exact root-relative path", fixture.existenceRequests.any { "src/Main.kt" in it })
            assertTrue("drawn tool summary participates in root-backed discovery", fixture.existenceRequests.any { "docs/tool.kt" in it })
            assertTrue("outside absolute evidence never becomes an in-root basename request", fixture.existenceRequests.flatten().none { it.contains("secret") })
            val outside = descendants(body).filterIsInstance<TextView>().first { it.text.toString().contains("/outside/secret.kt") }
            assertTrue("outside text has zero Files click spans", (outside.text as? Spanned)?.getSpans(0, outside.text.length, android.text.style.ClickableSpan::class.java).isNullOrEmpty())
            for ((needle, path, line) in listOf(Triple("src/Main.kt", "src/Main.kt", 12), Triple("docs/tool.kt", "docs/tool.kt", 7))) {
                val view = requireNotNull(linked(needle))
                (view.text as Spanned).getSpans(0, view.text.length, android.text.style.ClickableSpan::class.java).single().onClick(view)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val opened = shadowOf(fixture.app).nextStartedActivity
                assertNotNull("current visible path opens native Files", opened)
                assertEquals(path, opened.getStringExtra("files_path"))
                assertEquals(line, opened.getIntExtra("files_line", 0))
            }
            fun toggle(state: String): View = descendants(body).first {
                it.isClickable && it.contentDescription?.toString().orEmpty().contains(state) &&
                    it.contentDescription?.toString().orEmpty().contains("docs/tool.kt")
            }
            val collapsed = toggle("collapsed")
            assertTrue("actual visible tool disclosure is reachable", collapsed.isShown)
            assertTrue(collapsed.performClick())
            waitForPrintedPane { linked("docs/expanded.kt")?.isShown == true && linked("docs/tool.kt") != null }
            assertTrue("actual expanded result reaches Files existence admission", fixture.existenceRequests.any { "docs/expanded.kt" in it })
            assertTrue(toggle("expanded").performClick())
            waitForPrintedPane { linked("docs/tool.kt") != null }
            assertTrue(toggle("collapsed").performClick())
            waitForPrintedPane { linked("docs/expanded.kt")?.isShown == true }
            val expandedView = requireNotNull(linked("docs/expanded.kt"))
            val expandedSpan = (expandedView.text as Spanned).getSpans(0, expandedView.text.length, android.text.style.ClickableSpan::class.java).single()
            val oldDisclosure = toggle("expanded")
            val beforePoll = fixture.chatCalls
            val model = PaneActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }.get(activity) as PaneViewModel
            model.refresh()
            waitForPrintedPane { fixture.chatCalls > beforePoll }
            assertSame("unchanged actual poll retains decorated rebuilt text", expandedView, linked("docs/expanded.kt"))
            expandedSpan.onClick(expandedView)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val expandedIntent = shadowOf(fixture.app).nextStartedActivity
            assertEquals("docs/expanded.kt", expandedIntent.getStringExtra("files_path"))
            assertEquals(9, expandedIntent.getIntExtra("files_line", 0))
            controller.pause().stop()
            fixture.repository.setLeadEpoch(2)
            val rootReads = fixture.fileReads
            val existenceReads = fixture.existenceRequests.size
            expandedSpan.onClick(expandedView)
            oldDisclosure.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("retired rebuilt span makes zero root GET", rootReads, fixture.fileReads)
            assertEquals("retired toggle cannot reacquire existence authority", existenceReads, fixture.existenceRequests.size)
            assertNull("retired rebuilt text cannot open Files", shadowOf(fixture.app).nextStartedActivity)
        } finally { controller.pause().stop().destroy(); fixture.close() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun repeatedVisibleToolTogglesRecheckCurrentPathsWithoutAdmittingA65thDistinctPath() {
        val fixture = PrintedPaneFixture()
        // This case measures one transcript batch. The ordinary fixture's src/Main.kt mirror
        // has its own independent discovery and would add an unrelated65th counter entry.
        fixture.pane = PaneReadResponse("printed:pane", "ordinary mirror output", false, 1, filesReadReady = true)
        fixture.transcript = (1..63).map { number ->
            TranscriptEntry("bounded-$number", "2026-09-10T00:00:00Z", "assistant",
                listOf(TranscriptPart("text", text = "docs/part-$number.kt")), seq = number.toLong())
        } + TranscriptEntry("bounded-tool", "2026-09-10T00:00:00Z", "assistant", listOf(
            TranscriptPart("tool", name = "read", summary = "/repo/docs/tool.kt:7",
                result = com.lateapex.collie.network.TranscriptResult("/repo/docs/distinct-65.kt:9")),
        ), seq = 64)
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("printed:pane"))
            .create().start().resume().visible()
        try {
            val activity = controller.get()
            val body = activity.findViewById<ViewGroup>(R.id.transcript_body)
            fun summary() = descendants(body).filterIsInstance<TextView>().firstOrNull { view ->
                view.isShown && view.text.toString().contains("docs/tool.kt") &&
                    (view.text as? Spanned)?.getSpans(0, view.text.length, android.text.style.ClickableSpan::class.java)?.isNotEmpty() == true
            }
            fun toggle(state: String): View = descendants(body).first {
                it.isClickable && it.contentDescription?.toString().orEmpty().contains(state) &&
                    it.contentDescription?.toString().orEmpty().contains("docs/tool.kt")
            }
            val model = PaneActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }.get(activity) as PaneViewModel
            waitForPrintedPane(diagnostic = {
                val state = model.state.value
                "chatCalls=${fixture.chatCalls}; loading=${state.loading}; errorPresent=${state.error != null}; " +
                    "liveEntries=${state.transcript.size}; available=${state.transcriptAvailable}; " +
                    "accepted=${state.transcriptReadContext != null}; current=${state.transcriptReadContext?.let(fixture.repository::isCurrent)}; " +
                    "rootReads=${fixture.fileReads}; confirmationBatches=${fixture.existenceRequests.size}; " +
                    "uniquePaths=${fixture.existenceRequests.flatten().toSet().take(70)}; summaryLinked=${summary() != null}"
            }) { summary() != null && fixture.existenceRequests.flatten().toSet().size == 64 }
            val initiallyConfirmedPaths = fixture.existenceRequests.flatten().toSet().sorted().take(70)
            val initialChatCalls = fixture.chatCalls
            // Keep the actual accepted, resumed screen in one render batch. Stopping only the
            // periodic producer prevents a fresh poll from legitimately beginning a new batch.
            model.stopPolling()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            val settledConfirmedPaths = fixture.existenceRequests.flatten().toSet().sorted().take(70)
            fun visibleToggle(state: String): View = toggle(state).also { disclosure ->
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                assertTrue("the disclosure has a measured touch target", disclosure.width > 0 && disclosure.height > 0)
                disclosure.requestRectangleOnScreen(android.graphics.Rect(0, 0, disclosure.width, disclosure.height), true)
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                val visible = android.graphics.Rect()
                assertTrue("the scrolled disclosure is on screen", disclosure.getGlobalVisibleRect(visible))
                assertTrue("the complete disclosure touch target is visible", visible.height() >= disclosure.height)
            }
            repeat(65) { iteration ->
                val beforeExpand = fixture.existenceRequests.size
                assertTrue("visible tool expands", visibleToggle("collapsed").performClick())
                waitForPrintedPane { summary() != null && fixture.existenceRequests.size > beforeExpand }
                val result = descendants(body).filterIsInstance<TextView>().first {
                    it.isShown && it.text.toString().contains("docs/distinct-65.kt")
                }
                val resultSpans = (result.text as? Spanned)
                    ?.getSpans(0, result.text.length, android.text.style.ClickableSpan::class.java).orEmpty()
                val state = model.state.value
                val quotaDiagnostic = "iteration=$iteration; initialPaths=$initiallyConfirmedPaths; " +
                    "settledPaths=$settledConfirmedPaths; currentPaths=${fixture.existenceRequests.flatten().toSet().sorted().take(70)}; " +
                    "chatCalls=$initialChatCalls->${fixture.chatCalls}; rootReads=${fixture.fileReads}; " +
                    "batches=$beforeExpand->${fixture.existenceRequests.size}; " +
                    "recentBatches=${fixture.existenceRequests.takeLast(5)}; " +
                    "spanTypes=${resultSpans.map { it.javaClass.name }}; resultVisible=${result.isShown}; " +
                    "liveEntries=${state.transcript.size}; accepted=${state.transcriptReadContext != null}; " +
                    "current=${state.transcriptReadContext?.let(fixture.repository::isCurrent)}"
                assertTrue("the 65th distinct path remains plain: $quotaDiagnostic", resultSpans.isEmpty())
                assertTrue("new distinct path is never sent for confirmation: $quotaDiagnostic", fixture.existenceRequests.flatten().none { it == "docs/distinct-65.kt" })
                val beforeCollapse = fixture.existenceRequests.size
                assertTrue("visible tool collapses", visibleToggle("expanded").performClick())
                waitForPrintedPane { summary() != null && fixture.existenceRequests.size > beforeCollapse }
            }
            assertEquals("one displayed batch admits at most64 distinct paths", 64, fixture.existenceRequests.flatten().toSet().size)
            assertTrue("each rebuilt summary is freshly confirmed", fixture.existenceRequests.count { "docs/tool.kt" in it } >= 131)
            assertTrue("every existence request retains the server batch cap", fixture.existenceRequests.all { it.size <= 64 })
            val retainedSummary = requireNotNull(summary())
            val beforeUnchangedPoll = fixture.chatCalls
            model.refresh()
            waitForPrintedPane { fixture.chatCalls > beforeUnchangedPoll && summary() != null }
            model.stopPolling()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertSame("unchanged accepted poll keeps the actual rendered batch", retainedSummary, summary())
            val beforeRebindExpansion = fixture.existenceRequests.size
            assertTrue("visible tool still expands after unchanged bind", visibleToggle("collapsed").performClick())
            waitForPrintedPane { summary() != null && fixture.existenceRequests.size > beforeRebindExpansion }
            val recheckedResult = descendants(body).filterIsInstance<TextView>().first {
                it.isShown && it.text.toString().contains("docs/distinct-65.kt")
            }
            assertTrue("unchanged accepted bind cannot admit the 65th path", (recheckedResult.text as? Spanned)
                ?.getSpans(0, recheckedResult.text.length, android.text.style.ClickableSpan::class.java).isNullOrEmpty())
            assertTrue(fixture.existenceRequests.flatten().none { it == "docs/distinct-65.kt" })
            val beforeRebindCollapse = fixture.existenceRequests.size
            assertTrue(visibleToggle("expanded").performClick())
            waitForPrintedPane { summary() != null && fixture.existenceRequests.size > beforeRebindCollapse }
            val current = requireNotNull(summary())
            val span = (current.text as Spanned).getSpans(0, current.text.length, android.text.style.ClickableSpan::class.java).single()
            val oldDisclosure = toggle("collapsed")
            span.onClick(current)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val opened = shadowOf(fixture.app).nextStartedActivity
            assertNotNull("previously confirmed current path still opens Files", opened)
            assertEquals("docs/tool.kt", opened.getStringExtra("files_path"))
            assertEquals(7, opened.getIntExtra("files_line", 0))
            controller.pause().stop()
            fixture.repository.setLeadEpoch(2)
            val reads = fixture.fileReads
            val confirmations = fixture.existenceRequests.size
            span.onClick(current)
            oldDisclosure.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("retired repeated summary makes zero root GET", reads, fixture.fileReads)
            assertEquals("retired disclosure makes zero existence GET", confirmations, fixture.existenceRequests.size)
            assertNull(shadowOf(fixture.app).nextStartedActivity)
            // Only a deliberate fresh read under the replacement authority starts another batch.
            fixture.transcript = fixture.transcript.takeLast(1)
            fixture.chatGen = 2
            controller.start().resume()
            model.refresh()
            waitForPrintedPane {
                model.state.value.transcript.size == 1 &&
                    model.state.value.transcriptReadContext?.let(fixture.repository::isCurrent) == true &&
                    summary() != null && summary() !== current
            }
            model.stopPolling()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (descendants(body).none {
                it.isClickable && it.contentDescription?.toString().orEmpty().contains("collapsed") &&
                    it.contentDescription?.toString().orEmpty().contains("docs/tool.kt")
            }) {
                assertTrue("retained expanded disclosure can deliberately collapse", visibleToggle("expanded").performClick())
                waitForPrintedPane { summary() != null }
            }
            assertTrue(visibleToggle("collapsed").performClick())
            waitForPrintedPane {
                descendants(body).filterIsInstance<TextView>().any { view ->
                    view.isShown && view.text.toString().contains("docs/distinct-65.kt") &&
                        (view.text as? Spanned)?.getSpans(0, view.text.length, android.text.style.ClickableSpan::class.java)?.isNotEmpty() == true
                }
            }
            val replacementResult = descendants(body).filterIsInstance<TextView>().first {
                it.isShown && it.text.toString().contains("docs/distinct-65.kt")
            }
            assertTrue("replacement actor freshly confirms the formerly refused path",
                fixture.existenceRequests.drop(confirmations).any { "docs/distinct-65.kt" in it })
            (replacementResult.text as Spanned).getSpans(0, replacementResult.text.length,
                android.text.style.ClickableSpan::class.java).single().onClick(replacementResult)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val replacementIntent = shadowOf(fixture.app).nextStartedActivity
            assertNotNull("fresh accepted replacement authority reaches Files", replacementIntent)
            assertEquals("docs/distinct-65.kt", replacementIntent.getStringExtra("files_path"))
            assertEquals(9, replacementIntent.getIntExtra("files_line", 0))
        } finally { controller.pause().stop().destroy(); fixture.close() }
    }

    private fun printedSpans(activity: PaneActivity): List<android.text.style.ClickableSpan> {
        val text = activity.findViewById<TextView>(R.id.terminal_text).text
        return (text as? Spanned)?.getSpans(0, text.length, android.text.style.ClickableSpan::class.java)?.toList().orEmpty()
    }

    private fun waitForPrintedPane(diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(10)
        }
        assertTrue("an admitted visible Files path completes before timeout: ${if (condition()) "" else diagnostic()}", condition())
    }

    @Test fun failedPrintedPathReadRemovesSpansWithoutStartingAnotherReadFromRetirement() {
        val fixture = PrintedPaneFixture()
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("printed:pane"))
            .create().start().resume().visible()
        try {
            val activity = controller.get()
            waitForPrintedPane { printedSpans(activity).isNotEmpty() }
            val model = PaneActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }
                .get(activity) as PaneViewModel
            model.stopPolling()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            fixture.fileFailure = com.lateapex.collie.network.ApiFailure.Http(404, "Files unavailable", null)
            // Expire the production60-second confirmation lease in the simulated clock.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
            val update = PaneActivity::class.java.getDeclaredMethod("updatePrintedPathLinks", CharSequence::class.java)
                .apply { isAccessible = true }
            update.invoke(activity, "src/Main.kt:12")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertEquals("a refusal redraw must not issue a recursive Files request", 1, fixture.failedFileReads)
            assertTrue("retained mirror remains readable", activity.findViewById<TextView>(R.id.terminal_text).text.toString().contains("src/Main.kt"))
            assertTrue("failed authority removes prior tappable confirmations", printedSpans(activity).isEmpty())
            assertNull(PaneActivity::class.java.getDeclaredField("printedLinkRoot").apply { isAccessible = true }.get(activity))
            update.invoke(activity, "src/Main.kt:12")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertEquals("an inert failed lookup lease also bounds subsequent redraws", 1, fixture.failedFileReads)
            fixture.fileFailure = null
            ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
            update.invoke(activity, "src/Main.kt:12")
            waitForPrintedPane { printedSpans(activity).isNotEmpty() }
            assertEquals("a later deliberate refresh can confirm a current path", 1, fixture.failedFileReads)
        } finally {
            controller.destroy()
            fixture.close()
        }
    }

    @Test fun automaticMirrorAndTranscriptDiscoveryRequiresPositiveOwningSupportAndReadAdmission() {
        for ((support, ready, refused) in listOf(
            Triple(null, true, false), Triple(false, true, false),
            Triple(true, null, false), Triple(true, false, false), Triple(true, true, true),
        )) {
            val fixture = PrintedPaneFixture()
            fixture.filesSupport = support
            fixture.configRefused = refused
            fixture.pane = fixture.pane.copy(filesReadReady = ready)
            fixture.transcript = listOf(TranscriptEntry("unsupported-files", "2026-09-10T00:00:00Z", "assistant",
                listOf(TranscriptPart("text", text = "docs/tool.kt:7")), seq = 1))
            val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("printed:pane"))
                .create().start().resume().visible()
            try {
                val activity = controller.get()
                val model = PaneActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }
                    .get(activity) as PaneViewModel
                waitForPrintedPane { fixture.configCalls > 0 && (refused || model.state.value.transcriptAvailable == true) }
                model.stopPolling()
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                assertEquals("absent/false/refused admission never dispatches a Files root read", 0, fixture.fileReads)
                assertTrue("absent/false/refused admission never dispatches Files/exist", fixture.existenceRequests.isEmpty())
                assertTrue("mirror paths remain plain", printedSpans(activity).isEmpty())
                assertNull("no automatic path can open Files", shadowOf(fixture.app).nextStartedActivity)
                if (!refused) {
                    assertNull("unsupported discovery does not freeze ordinary paired reads", fixture.repository.accessFailure)
                    assertTrue("ordinary terminal text remains visible", activity.findViewById<TextView>(R.id.terminal_text)
                        .text.toString().contains("src/Main.kt"))
                    val body = activity.findViewById<ViewGroup>(R.id.transcript_body)
                    assertTrue("transcript path is rendered without a Files span", descendants(body).filterIsInstance<TextView>()
                        .any { it.text.toString().contains("docs/tool.kt") &&
                            (it.text as? Spanned)?.getSpans(0, it.text.length, android.text.style.ClickableSpan::class.java).isNullOrEmpty() })
                }
            } finally { controller.pause().stop().destroy(); fixture.close() }
        }
    }

    @Test fun withdrawingCurrentFilesAdmissionRetiresUnchangedMirrorAndTranscriptLinksWithoutReads() {
        for (withdrawReadiness in listOf(true, false)) {
            val fixture = PrintedPaneFixture()
            fixture.transcript = listOf(TranscriptEntry("admission-loss", "2026-09-10T00:00:00Z", "assistant",
                listOf(TranscriptPart("text", text = "docs/tool.kt:7")), seq = 1))
            val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("printed:pane"))
                .create().start().resume().visible()
            try {
                val activity = controller.get()
                val model = PaneActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }
                    .get(activity) as PaneViewModel
                val terminal = activity.findViewById<TextView>(R.id.terminal_text)
                val body = activity.findViewById<ViewGroup>(R.id.transcript_body)
                fun transcriptText() = descendants(body).filterIsInstance<TextView>()
                    .firstOrNull { it.isShown && it.text.toString().contains("docs/tool.kt") }
                fun spans(view: TextView) = (view.text as? Spanned)
                    ?.getSpans(0, view.text.length, android.text.style.ClickableSpan::class.java).orEmpty()
                waitForPrintedPane { transcriptText()?.let { spans(it).isNotEmpty() } == true }
                val oldTranscript = requireNotNull(transcriptText())
                val oldTranscriptSpan = spans(oldTranscript).single()
                activity.findViewById<View>(R.id.body_mode_switch).performClick()
                waitForPrintedPane { terminal.isShown && printedSpans(activity).isNotEmpty() }
                val oldMirrorSpan = printedSpans(activity).single()
                model.stopPolling()
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                val bodyBefore = fixture.pane
                val readsBefore = fixture.fileReads
                val existenceBefore = fixture.existenceRequests.size
                val configBefore = fixture.configCalls
                if (withdrawReadiness) fixture.pane = fixture.pane.copy(filesReadReady = false)
                else fixture.filesSupport = false
                model.refresh()
                waitForPrintedPane {
                    fixture.configCalls > configBefore &&
                        (if (withdrawReadiness) model.state.value.pane?.filesReadReady == false
                        else model.state.value.filesConfigContext == null)
                }
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                assertEquals("the accepted body text has not changed", bodyBefore.text, model.state.value.pane?.text)
                assertEquals("the accepted body revision has not changed", bodyBefore.revision, model.state.value.pane?.revision)
                assertTrue("withdrawn Files admission draws a plain visible mirror", terminal.isShown && printedSpans(activity).isEmpty())
                for (name in listOf("printedLinkRoot", "printedLinkContext", "printedLinkText")) {
                    assertNull("withdrawal releases $name custody", PaneActivity::class.java.getDeclaredField(name)
                        .apply { isAccessible = true }.get(activity))
                }
                assertEquals("withdrawal cannot launch a new Files root lookup", readsBefore, fixture.fileReads)
                assertEquals("withdrawal cannot launch a new Files existence lookup", existenceBefore, fixture.existenceRequests.size)
                oldMirrorSpan.onClick(terminal)
                oldTranscriptSpan.onClick(oldTranscript)
                activity.findViewById<View>(R.id.body_mode_switch).performClick()
                waitForPrintedPane { transcriptText() != null }
                assertTrue("the retained transcript is visibly plain after admission loss", spans(requireNotNull(transcriptText())).isEmpty())
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                assertEquals("old callbacks and the body switch make zero root requests", readsBefore, fixture.fileReads)
                assertEquals("old callbacks and the body switch make zero existence requests", existenceBefore, fixture.existenceRequests.size)
                assertNull("retired visible links cannot open Files", shadowOf(fixture.app).nextStartedActivity)
                fixture.pane = bodyBefore
                fixture.filesSupport = true
                model.refresh()
                waitForPrintedPane { transcriptText()?.let { spans(it).isNotEmpty() } == true && fixture.fileReads > readsBefore }
                activity.findViewById<View>(R.id.body_mode_switch).performClick()
                waitForPrintedPane { terminal.isShown && printedSpans(activity).isNotEmpty() }
                val restoredReads = fixture.fileReads
                oldMirrorSpan.onClick(terminal)
                oldTranscriptSpan.onClick(oldTranscript)
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                assertEquals("old confirmations cannot borrow restored current admission", restoredReads, fixture.fileReads)
                assertNull(shadowOf(fixture.app).nextStartedActivity)
                printedSpans(activity).single().onClick(terminal)
                waitForPrintedPane { fixture.fileReads > restoredReads }
                val opened = shadowOf(fixture.app).nextStartedActivity
                assertNotNull("fresh same-owner positive admission still reaches Files", opened)
                assertEquals("src/Main.kt", opened.getStringExtra("files_path"))
                assertEquals(12, opened.getIntExtra("files_line", 0))
            } finally { controller.pause().stop().destroy(); fixture.close() }
        }
    }

    @Test fun hiddenTranscriptReconfirmsAfterEntireFilesAdmissionWithdrawalAndRestoration() {
        for (withdrawReadiness in listOf(true, false)) {
            val fixture = PrintedPaneFixture()
            fixture.transcript = listOf(TranscriptEntry("admission-loss", "2026-09-10T00:00:00Z", "assistant",
                listOf(TranscriptPart("text", text = "docs/tool.kt:7")), seq = 1))
            val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("printed:pane"))
                .create().start().resume().visible()
            try {
                val activity = controller.get()
                val model = PaneActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }
                    .get(activity) as PaneViewModel
                val terminal = activity.findViewById<TextView>(R.id.terminal_text)
                val body = activity.findViewById<ViewGroup>(R.id.transcript_body)
                fun transcriptText() = descendants(body).filterIsInstance<TextView>()
                    .firstOrNull { it.isShown && it.text.toString().contains("docs/tool.kt") }
                fun spans(view: TextView) = (view.text as? Spanned)
                    ?.getSpans(0, view.text.length, android.text.style.ClickableSpan::class.java).orEmpty()
                waitForPrintedPane { transcriptText()?.let { spans(it).isNotEmpty() } == true }
                val oldTranscript = requireNotNull(transcriptText())
                val oldTranscriptSpan = spans(oldTranscript).single()
                activity.findViewById<View>(R.id.body_mode_switch).performClick()
                waitForPrintedPane { terminal.isShown && printedSpans(activity).isNotEmpty() }
                val oldMirrorSpan = printedSpans(activity).single()
                model.stopPolling()
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                val bodyBefore = fixture.pane
                val readsBefore = fixture.fileReads
                val existenceBefore = fixture.existenceRequests.size
                val configBefore = fixture.configCalls
                if (withdrawReadiness) fixture.pane = fixture.pane.copy(filesReadReady = false)
                else fixture.filesSupport = false
                model.refresh()
                waitForPrintedPane {
                    fixture.configCalls > configBefore &&
                        (if (withdrawReadiness) model.state.value.pane?.filesReadReady == false
                        else model.state.value.filesConfigContext == null)
                }
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                assertEquals("the accepted body text has not changed", bodyBefore.text, model.state.value.pane?.text)
                assertEquals("the accepted body revision has not changed", bodyBefore.revision, model.state.value.pane?.revision)
                assertTrue("withdrawn Files admission draws a plain visible mirror", terminal.isShown && printedSpans(activity).isEmpty())
                for (name in listOf("printedLinkRoot", "printedLinkContext", "printedLinkText")) {
                    assertNull("withdrawal releases $name custody", PaneActivity::class.java.getDeclaredField(name)
                        .apply { isAccessible = true }.get(activity))
                }
                assertEquals("withdrawal cannot launch a new Files root lookup", readsBefore, fixture.fileReads)
                assertEquals("withdrawal cannot launch a new Files existence lookup", existenceBefore, fixture.existenceRequests.size)
                oldMirrorSpan.onClick(terminal)
                oldTranscriptSpan.onClick(oldTranscript)
                assertTrue("transcript stays hidden throughout withdrawal", terminal.isShown && transcriptText() == null)
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                assertEquals("old callbacks and the body switch make zero root requests", readsBefore, fixture.fileReads)
                assertEquals("old callbacks and the body switch make zero existence requests", existenceBefore, fixture.existenceRequests.size)
                assertNull("retired visible links cannot open Files", shadowOf(fixture.app).nextStartedActivity)
                fixture.pane = bodyBefore
                fixture.filesSupport = true
                model.refresh()
                waitForPrintedPane { terminal.isShown && printedSpans(activity).isNotEmpty() && fixture.fileReads > readsBefore }
                assertNull("transcript is still hidden after restoring current admission", transcriptText())
                val restoredReads = fixture.fileReads
                oldMirrorSpan.onClick(terminal)
                oldTranscriptSpan.onClick(oldTranscript)
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                assertEquals("old confirmations cannot borrow restored current admission", restoredReads, fixture.fileReads)
                assertNull(shadowOf(fixture.app).nextStartedActivity)
                val beforeTranscriptReads = fixture.fileReads
                val beforeTranscriptConfirmations = fixture.existenceRequests.size
                activity.findViewById<View>(R.id.body_mode_switch).performClick()
                waitForPrintedPane { transcriptText()?.let { spans(it).isNotEmpty() } == true &&
                    fixture.fileReads > beforeTranscriptReads && fixture.existenceRequests.size > beforeTranscriptConfirmations }
                val currentTranscript = requireNotNull(transcriptText())
                assertSame("unchanged turn retains its actual text view", oldTranscript, currentTranscript)
                val currentTranscriptSpan = spans(currentTranscript).single()
                assertNotSame("restoration installs fresh confirmation", oldTranscriptSpan, currentTranscriptSpan)
                val currentReads = fixture.fileReads
                oldTranscriptSpan.onClick(oldTranscript)
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                assertEquals("old hidden confirmation remains inert after reopening", currentReads, fixture.fileReads)
                assertNull(shadowOf(fixture.app).nextStartedActivity)
                currentTranscriptSpan.onClick(currentTranscript)
                waitForPrintedPane { fixture.fileReads > currentReads }
                val opened = shadowOf(fixture.app).nextStartedActivity
                assertNotNull("fresh same-owner positive admission still reaches Files", opened)
                assertEquals("docs/tool.kt", opened.getStringExtra("files_path"))
                assertEquals(7, opened.getIntExtra("files_line", 0))
            } finally { controller.pause().stop().destroy(); fixture.close() }
        }
    }

    private class PrintedPaneFixture : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        private val original = app.container.repository
        private val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        private val connection = com.lateapex.collie.domain.Connection(com.lateapex.collie.domain.CollieOrigin("https://printed.fixture/"), "phone", "token")
        private val store = object : com.lateapex.collie.data.ConnectionStore {
            override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(this@PrintedPaneFixture.connection)
            override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        var pane = PaneReadResponse("printed:pane", "src/Main.kt:12", false, 1, filesReadReady = true)
        var filesSupport: Boolean? = true
        var configRefused = false
        var configCalls = 0
        var transcript = emptyList<TranscriptEntry>()
        var chatCalls = 0
        var chatGen = 1L
        var fileReads = 0
        var fileFailure: com.lateapex.collie.network.ApiFailure? = null
        var failedFileReads = 0
        val unexpectedFailureRetry = kotlinx.coroutines.CompletableDeferred<Unit>()
        val existenceRequests = mutableListOf<List<String>>()
        // A filesystem answers existence from its inventory, not from the candidates sent by
        // the client. Confirming both Main.kt and Main.kt:12 intentionally makes that text inert.
        private val existingFiles = setOf("src/Main.kt", "docs/tool.kt", "docs/expanded.kt", "docs/distinct-65.kt") +
            (1..63).map { "docs/part-$it.kt" }
        var nextRootWait: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        // An omitted fixture route must fail visibly, never escape to the workstation network.
        private val unscriptedApi = java.lang.reflect.Proxy.newProxyInstance(
            com.lateapex.collie.network.CollieApi::class.java.classLoader,
            arrayOf(com.lateapex.collie.network.CollieApi::class.java),
        ) { _, method, _ ->
            com.lateapex.collie.network.ApiResult.Failure(
                com.lateapex.collie.network.ApiFailure.Protocol("Unscripted printed fixture API: ${method.name}"))
        } as com.lateapex.collie.network.CollieApi
        private val api = object : com.lateapex.collie.network.CollieApi by unscriptedApi {
            override suspend fun config(connection: com.lateapex.collie.domain.Connection, scope: com.lateapex.collie.domain.Scope) = config(connection)
            override suspend fun config(connection: com.lateapex.collie.domain.Connection): com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.BridgeConfigResponse> {
                configCalls++
                if (configRefused) return com.lateapex.collie.network.ApiResult.Failure(
                    com.lateapex.collie.network.ApiFailure.Http(403, "Config refused", null))
                return com.lateapex.collie.network.ApiResult.Success(com.lateapex.collie.network.BridgeConfigResponse(
                    control = filesSupport?.let { com.lateapex.collie.network.ControlCapabilityResponse(mapOf("filesDiscovery" to it)) }), 200)
            }
            override suspend fun snapshot(connection: com.lateapex.collie.domain.Connection, scope: com.lateapex.collie.domain.Scope) =
                com.lateapex.collie.network.ApiResult.Success(com.lateapex.collie.network.SnapshotResponse("1", agents = listOf(
                    PaneSummary(paneId = "printed:pane", workspaceId = "w1", workspaceLabel = "project", workspaceNumber = 1,
                        tabId = "w1:t1", agent = "opencode", status = AgentStatus.WORKING, cwd = "/repo", focused = true, tabLabel = "files"),
                ), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1), 200)
            override suspend fun pane(connection: com.lateapex.collie.domain.Connection, address: com.lateapex.collie.domain.PaneAddress, lines: Int, etag: String?, markSeen: Boolean) =
                com.lateapex.collie.network.ApiResult.Success(pane, 200)
            override suspend fun chat(connection: com.lateapex.collie.domain.Connection, address: com.lateapex.collie.domain.PaneAddress, limit: Int, after: com.lateapex.collie.network.ChatAfter?, before: com.lateapex.collie.network.ChatBefore?, etag: String?): com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.PaneChatResponse> {
                chatCalls++
                return com.lateapex.collie.network.ApiResult.Success(com.lateapex.collie.network.PaneChatResponse(address.paneId, available = transcript.isNotEmpty(), page = "live", gen = chatGen, rev = 1, head = transcript.mapNotNull { it.seq }.maxOrNull() ?: 1, oldest = transcript.mapNotNull { it.seq }.minOrNull() ?: 1, hasOlder = false, upserts = transcript), 200)
            }
            override suspend fun history(connection: com.lateapex.collie.domain.Connection, address: com.lateapex.collie.domain.PaneAddress, limit: Int, before: String?, etag: String?) =
                com.lateapex.collie.network.ApiResult.Success(com.lateapex.collie.network.PaneHistoryResponse(address.paneId, available = false), 200)
            override suspend fun files(connection: com.lateapex.collie.domain.Connection, target: com.lateapex.collie.network.FilesTarget, scope: com.lateapex.collie.domain.Scope, path: com.lateapex.collie.network.RelativeFilePath?, dir: com.lateapex.collie.network.RelativeFilePath?, ignored: Boolean): com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.FilesAnswer> {
                fileReads++
                fileFailure?.let { failure ->
                    failedFileReads++
                    // A regression can be observed as a second request without hanging the test.
                    if (failedFileReads > 1) unexpectedFailureRetry.await()
                    return com.lateapex.collie.network.ApiResult.Failure(failure)
                }
                val wait = nextRootWait
                nextRootWait = null
                if (wait != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { wait.await() }
                return com.lateapex.collie.network.ApiResult.Success(com.lateapex.collie.network.FilesAnswer.Listing("/repo", dir?.value.orEmpty(), emptyList(), false), 200)
            }
            override suspend fun filesExist(connection: com.lateapex.collie.domain.Connection, target: com.lateapex.collie.network.FilesTarget, scope: com.lateapex.collie.domain.Scope, paths: List<com.lateapex.collie.network.RelativeFilePath>): com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.FilesExistAnswer> {
                existenceRequests += paths.map { it.value }
                return com.lateapex.collie.network.ApiResult.Success(com.lateapex.collie.network.FilesExistAnswer(paths.map { it.value }.filter { it in existingFiles }.toSet()), 200)
            }
        }
        val repository = com.lateapex.collie.data.CollieRepository(api, store, app.container.originValidator)
        init { field.set(app.container, repository) }
        override fun close() { field.set(app.container, original) }
    }

    @Test
    fun externalResultsCannotConsumeReplacementActorsOrRebindBeforeOldCallbacksDrain() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val initial = com.lateapex.collie.domain.Connection(com.lateapex.collie.domain.CollieOrigin("https://picker.fixture/"), "same-label", "old-token")
        val store = object : com.lateapex.collie.data.ConnectionStore {
            override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(initial)
            override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        var uploads = 0
        val api = object : com.lateapex.collie.network.CollieApi by app.container.api {
            override suspend fun snapshot(connection: com.lateapex.collie.domain.Connection, scope: com.lateapex.collie.domain.Scope) =
                com.lateapex.collie.network.ApiResult.Success(com.lateapex.collie.network.SnapshotResponse("connected",
                    agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(),
                    device = com.lateapex.collie.network.DeviceAuthorization(false, null, true), ts = 1), 200)
            override suspend fun upload(connection: com.lateapex.collie.domain.Connection, address: com.lateapex.collie.domain.PaneAddress,
                upload: com.lateapex.collie.network.ImageUpload): com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.UploadResponse> {
                uploads++
                return com.lateapex.collie.network.ApiResult.Success(com.lateapex.collie.network.UploadResponse(true, "/new/upload.png"), 200)
            }
        }
        val repository = com.lateapex.collie.data.CollieRepository(api, store, app.container.originValidator)
        val original = app.container.repository
        val repositoryField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        repositoryField.set(app.container, repository)
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("picker:pane")).create()
        val activity = controller.get()
        fun field(name: String) = PaneActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun launch(name: String, target: PaneActivity = activity) = PaneActivity::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(target)
        try {
            kotlinx.coroutines.runBlocking { repository.snapshot() }
            val pickerContext = field("imagePickerContext")
            val permissionContext = field("microphonePermissionContext")
            launch("launchImagePicker")
            launch("requestMicrophonePermission")
            assertNotNull(pickerContext.get(activity))
            assertNotNull(permissionContext.get(activity))
            assertTrue(field("imagePickerOutstanding").getBoolean(activity))
            assertTrue(field("microphonePermissionOutstanding").getBoolean(activity))
            store.save(com.lateapex.collie.domain.Connection(initial.origin, initial.label, "replacement-token"))
            kotlinx.coroutines.runBlocking { repository.snapshot() }
            assertTrue(repository.writesAllowed(com.lateapex.collie.domain.Scope(), "picker:pane"))
            assertNull(pickerContext.get(activity))
            assertNull(permissionContext.get(activity))
            // The external system still owns the old callback slots despite replacement Live admission.
            launch("launchImagePicker")
            launch("requestMicrophonePermission")
            assertNull(pickerContext.get(activity))
            assertNull(permissionContext.get(activity))
            assertTrue(field("imagePickerOutstanding").getBoolean(activity))
            assertTrue(field("microphonePermissionOutstanding").getBoolean(activity))
            render(activity, PaneUiState(loading = false, canWrite = true,
                pane = PaneReadResponse("picker:pane", "replacement output", false, 1), composerReady = true))
            // Invoke the same callback registered with ActivityResultRegistry, after new Live admission.
            PaneActivity::class.java.getDeclaredMethod("onImagePicked", android.net.Uri::class.java)
                .apply { isAccessible = true }.invoke(activity, android.net.Uri.parse("content://fixture/old-image"))
            PaneActivity::class.java.getDeclaredMethod("onMicrophonePermissionResult", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, true)
            assertFalse(field("imagePickerOutstanding").getBoolean(activity))
            assertFalse(field("microphonePermissionOutstanding").getBoolean(activity))
            assertNull(field("recordingContext").get(activity))
            assertFalse(PaneActivity::class.java.getDeclaredField("composerMediaBusy").apply { isAccessible = true }.getBoolean(activity))
            assertEquals(0, uploads)
            assertEquals("replacement output", (PaneActivity::class.java.getDeclaredField("currentState")
                .apply { isAccessible = true }.get(activity) as PaneUiState).pane?.text)

            // Rotation saves only outstanding latches; callbacks must not regain sensitive actor custody.
            launch("launchImagePicker")
            launch("requestMicrophonePermission")
            val saved = Bundle()
            controller.saveInstanceState(saved).destroy()
            val recreated = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("picker:pane")).create(saved)
            try {
                val replacement = recreated.get()
                assertTrue(field("imagePickerOutstanding").getBoolean(replacement))
                assertTrue(field("microphonePermissionOutstanding").getBoolean(replacement))
                launch("launchImagePicker", replacement)
                launch("requestMicrophonePermission", replacement)
                assertNull(pickerContext.get(replacement))
                assertNull(permissionContext.get(replacement))
                PaneActivity::class.java.getDeclaredMethod("onImagePicked", android.net.Uri::class.java)
                    .apply { isAccessible = true }.invoke(replacement, android.net.Uri.parse("content://fixture/rotated-image"))
                PaneActivity::class.java.getDeclaredMethod("onMicrophonePermissionResult", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(replacement, true)
                assertFalse(field("imagePickerOutstanding").getBoolean(replacement))
                assertFalse(field("microphonePermissionOutstanding").getBoolean(replacement))
                assertNull(field("recordingContext").get(replacement))
                assertEquals(0, uploads)
            } finally { recreated.destroy() }
        } finally {
            if (!activity.isDestroyed) controller.destroy()
            repositoryField.set(app.container, original)
        }
    }

    @Test
    fun attachmentRecreationRefusesReplacementCredentialAtTheSameOrigin() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val initial = com.lateapex.collie.domain.Connection(com.lateapex.collie.domain.CollieOrigin("https://custody.fixture/"), "same-label", "old-token")
        val store = object : com.lateapex.collie.data.ConnectionStore {
            override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(initial)
            override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val repository = com.lateapex.collie.data.CollieRepository(app.container.api, store, app.container.originValidator)
        val original = app.container.repository
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        field.set(app.container, repository)
        try {
            val intent = paneIntent("custody:restore")
            val controller = Robolectric.buildActivity(PaneActivity::class.java, intent).create().start()
            controller.get().acceptUploadForTest("/private/old-upload.png")
            val saved = Bundle()
            controller.saveInstanceState(saved).stop().destroy()
            store.save(com.lateapex.collie.domain.Connection(initial.origin, initial.label, "replacement-token"))
            val recreated = Robolectric.buildActivity(PaneActivity::class.java, intent).create(saved)
            assertEquals("draft", recreated.get().outgoingReplyForTest("draft"))
            recreated.destroy()
        } finally { field.set(app.container, original) }
    }

    @Test
    fun retiringConnectionSynchronouslyDestroysCopiedTerminalAndAttachmentCustody() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val store = object : com.lateapex.collie.data.ConnectionStore {
            override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(
                com.lateapex.collie.domain.Connection(com.lateapex.collie.domain.CollieOrigin("https://custody.fixture/"), "phone", "old-token"))
            override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val repository = com.lateapex.collie.data.CollieRepository(app.container.api, store, app.container.originValidator)
        val original = app.container.repository
        val repositoryField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        repositoryField.set(app.container, repository)
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("custody:pane")).create()
        val activity = controller.get()
        fun field(name: String) = PaneActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            render(activity, PaneUiState(loading = false, pane = PaneReadResponse("custody:pane", "private terminal body", false, 1)))
            activity.acceptUploadForTest("/private/upload.png")
            activity.findViewById<EditText>(R.id.reply_input).setText("private draft")
            field("pendingTerminalText").set(activity, "queued private text")
            val job = kotlinx.coroutines.Job()
            field("terminalRenderJob").set(activity, job)
            val generation = field("terminalRenderGeneration").getLong(activity)
            field("paneActionInFlight").setBoolean(activity, true)
            field("tabCreateInFlight").setBoolean(activity, true)
            kotlinx.coroutines.runBlocking { repository.disconnect() }
            // No Looper drain: copied custody must retire before a delayed collector/render can run.
            for (name in listOf("lastRawText", "latestRawText", "renderedTerminalText")) assertEquals("", field(name).get(activity))
            for (name in listOf("pendingAttachment", "pendingAttachmentThumb", "pendingTerminalText", "lastRenderedState")) assertNull(field(name).get(activity))
            assertNull((field("currentState").get(activity) as PaneUiState).pane)
            assertTrue(job.isCancelled)
            assertFalse(field("paneActionInFlight").getBoolean(activity))
            assertFalse(field("tabCreateInFlight").getBoolean(activity))
            assertTrue(field("terminalRenderGeneration").getLong(activity) > generation)
            // A cancellation-resistant CPU projection finishing afterwards is pure, so cannot refill custody.
            PaneActivity::class.java.getDeclaredMethod("prepareTerminalText", String::class.java, Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                String::class.java, String::class.java).apply { isAccessible = true }
                .invoke(activity, "private late body", 1L, false, false, true, "claude", "omitted")
            assertEquals(emptyList<String>(), field("renderedStatusRows").get(activity))
            assertEquals("", field("renderedTerminalText").get(activity))
            assertEquals("draft", activity.outgoingReplyForTest("draft"))
        } finally {
            controller.destroy()
            repositoryField.set(app.container, original)
        }
    }

    @Test
    fun codexAcceptedPreviewUsesRetainedStateInsteadOfMirrorChangesOrATimer() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("accepted:pane")).create().get()
        val state = PaneUiState(loading = false, pane = PaneReadResponse("accepted:pane", "output", false, 1),
            codexAcceptedReplies = listOf("waiting input"))
        render(activity, state)
        val preview = activity.findViewById<TextView>(R.id.pending_send_notice)
        assertTrue(preview.text.toString().contains("waiting input"))
        assertTrue(preview.text.toString().startsWith("Accepted by Codex"))
        render(activity, state.copy(pane = state.pane!!.copy(text = "other output", revision = 2)))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, preview.visibility)
        // A recreated view renders the same retained ViewModel state without a sent-draft event.
        val recreated = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("accepted:pane")).create().get()
        render(recreated, state.copy(clearReplyDraft = false))
        assertTrue(recreated.findViewById<TextView>(R.id.pending_send_notice).text.contains("waiting input"))
        render(recreated, state.copy(codexAcceptedReplies = emptyList()))
        assertEquals(View.GONE, recreated.findViewById<View>(R.id.pending_send_notice).visibility)
    }
    @Test
    fun acceptedCodexPreviewIsRetainedByTheActualViewModelDuringConfigurationChange() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("accepted:rotation"))
            .create().start().resume()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        val modelField = PaneActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }
        val model = modelField.get(activity) as PaneViewModel
        @Suppress("UNCHECKED_CAST")
        val state = PaneViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(model) as kotlinx.coroutines.flow.MutableStateFlow<PaneUiState>
        state.value = state.value.copy(codexAcceptedReplies = listOf("retained submission"), clearReplyDraft = false)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        controller.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply {
            orientation = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        val recreated = controller.get()
        assertNotSame(activity, recreated)
        assertSame(model, modelField.get(recreated))
        assertTrue(recreated.findViewById<TextView>(R.id.pending_send_notice).text.contains("retained submission"))
        controller.pause().stop().destroy()
    }

    @Test
    fun pendingMediaBlocksButtonAndKeyboardSubmissionUntilItIsReady() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("media:pending")).create().get()
        val input = activity.findViewById<DirectTypingEditText>(R.id.reply_input)
        input.setText("inspect the image")
        render(activity, PaneUiState(
            loading = false,
            pane = PaneReadResponse("media:pending", "~\n❯ ", false, 0),
            canWrite = true,
            composerReady = true,
        ))
        val busy = PaneActivity::class.java.getDeclaredMethod("setComposerMediaBusy", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val attempt = PaneActivity::class.java.getDeclaredField("pendingReplyAttempt").apply { isAccessible = true }
        busy.invoke(activity, true)
        assertFalse(activity.findViewById<View>(R.id.send_button).isEnabled)
        // Ctrl+Enter has its own callback and must obey the same gate as the button.
        input.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(
            0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_CTRL_ON,
        ))
        assertNull(attempt.get(activity))
        assertEquals("inspect the image", input.text.toString())
        activity.acceptUploadForTest("/fixture/pending.png")
        busy.invoke(activity, false)
        assertTrue(activity.findViewById<View>(R.id.send_button).isEnabled)
        assertEquals("/fixture/pending.png\ninspect the image", activity.outgoingReplyForTest(input.text.toString()))
        input.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(
            0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_CTRL_ON,
        ))
        assertNotNull(attempt.get(activity))
    }

    @Test
    fun delayedMicrophoneGrantRechecksSendingAndDirectTyping() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("speech:permission")).create().get()
        PaneActivity::class.java.getDeclaredField("speechAvailability").apply { isAccessible = true }
            .set(activity, SpeechAvailability.Available("fixture"))
        val start = PaneActivity::class.java.getDeclaredMethod("startSpeechRecording").apply { isAccessible = true }
        render(activity, PaneUiState(loading = false, canWrite = true, sending = true,
            pane = PaneReadResponse("speech:permission", "~\n❯ ", false, 0), composerReady = true))
        start.invoke(activity)
        assertFalse(activity.findViewById<TextView>(R.id.composer_media_status).text.toString().contains("failed", true))
        render(activity, PaneUiState(loading = false, canWrite = true,
            pane = PaneReadResponse("speech:permission", "~\n❯ ", false, 0), composerReady = true))
        PaneActivity::class.java.getDeclaredField("directTyping").apply { isAccessible = true }.setBoolean(activity, true)
        start.invoke(activity)
        assertFalse(activity.findViewById<TextView>(R.id.composer_media_status).text.toString().contains("failed", true))
        val recorder = PaneActivity::class.java.getDeclaredField("speechRecorder").apply { isAccessible = true }
            .get(activity) as ComposerSpeechRecorder
        assertFalse(recorder.isRecording)
    }

    @Test
    @org.robolectric.annotation.Config(shadows = [FocusedActivityShadow::class])
    fun completedHandsFreeTranscriptionCanSubmitAfterItsOwnBusyGateClears() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("speech:complete"))
            .create()
        val activity = controller.get()
        assertTrue("focused fixture", activity.hasWindowFocus())
        activity.findViewById<EditText>(R.id.reply_input).setText("")
        render(activity, PaneUiState(loading = false,
            pane = PaneReadResponse("speech:complete", "~\n❯ ", false, 0), canWrite = true, composerReady = true))
        PaneActivity::class.java.getDeclaredMethod("setComposerMediaBusy", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, true)
        PaneActivity::class.java.getDeclaredMethod("completeComposerMediaResult", ComposerMediaResult::class.java)
            .apply { isAccessible = true }.invoke(activity, ComposerMediaResult.AutoSend("spoken message"))
        assertEquals("spoken message", activity.findViewById<EditText>(R.id.reply_input).text.toString())
        assertNotNull(PaneActivity::class.java.getDeclaredField("pendingReplyAttempt")
            .apply { isAccessible = true }.get(activity))
        controller.destroy()
    }

    // Window focus is supplied independently of layout; this test exercises media completion.
    @org.robolectric.annotation.Implements(android.app.Activity::class)
    class FocusedActivityShadow : org.robolectric.shadows.ShadowActivity() {
        @org.robolectric.annotation.Implementation
        fun hasWindowFocus(): Boolean = true
    }

    @Test
    fun attachmentSurvivesRecreationAndIsOnlyRemovedAfterAConfirmedSend() {
        val intent = paneIntent("attach:recreate")
        val controller = Robolectric.buildActivity(PaneActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        val path = "/fixture/upload.png"
        activity.acceptUploadForTest(path)
        activity.findViewById<EditText>(R.id.reply_input).setText("look at this")
        // Even a refused send must retain the uploaded attachment for review.
        activity.findViewById<View>(R.id.send_button).performClick()
        assertEquals("$path\nlook at this", activity.outgoingReplyForTest("look at this"))
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val recreated = Robolectric.buildActivity(PaneActivity::class.java, intent).create(saved).get()
        assertEquals("$path\nlook at this", recreated.outgoingReplyForTest("look at this"))
        render(recreated, PaneUiState(loading = false, clearReplyDraft = true))
        assertEquals("look at this", recreated.outgoingReplyForTest("look at this"))
    }

    @Test
    fun paneOverlayActionsKeepTheSharedTouchTargetFloor() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("touch:pane")).create().get()
        val floor = activity.resources.getDimensionPixelSize(R.dimen.collie_touch_target)

        listOf(
            activity.findViewById<View>(R.id.show_tabs_button),
            activity.findViewById<View>(R.id.pane_buffer_action),
            activity.findViewById<View>(R.id.new_output_button),
        ).forEach { action ->
            assertTrue(action.minimumHeight >= floor || action.layoutParams.height >= floor)
        }
    }

    @Test
    fun stoppingPaneCancelsHeldKeyAndIgnoresItsLaterRelease() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("repeat:stop"))
            .create().start().resume()
        val activity = controller.get()
        val button = MaterialButton(activity).apply { text = "Up" }
        PaneActivity::class.java.getDeclaredMethod("bindKeyRepeat", MaterialButton::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(activity, button, "Up")
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        button.dispatchTouchEvent(down)
        down.recycle()
        controller.pause().stop()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(activity.getString(R.string.pane_key_up), button.text.toString())
        assertFalse(button.isPressed)
        val cancel = PaneActivity::class.java.getDeclaredField("cancelActiveKeyRepeat").apply { isAccessible = true }
        assertNull(cancel.get(activity))
        var clicked = false
        button.setOnClickListener { clicked = true }
        val up = MotionEvent.obtain(0, 2000, MotionEvent.ACTION_UP, 0f, 0f, 0)
        button.dispatchTouchEvent(up)
        up.recycle()
        assertFalse(clicked)
        controller.destroy()
    }

    @Test
    fun repeatedKeysCommitOnlyOnAnEngagedActionUp() {
        assertTrue(PaneActivity.shouldCommitRepeatKeys(true, MotionEvent.ACTION_UP))
        assertFalse(PaneActivity.shouldCommitRepeatKeys(true, MotionEvent.ACTION_CANCEL))
        assertFalse(PaneActivity.shouldCommitRepeatKeys(false, MotionEvent.ACTION_UP))
    }

    @Test
    fun paneActionsUseCanonicalPullUpSheetAndKeepReadOnlySafeActionsUsable() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("actions:pane")).create().get()
        render(
            activity,
            PaneUiState(
                loading = false,
                panes = listOf(paneSummary("actions:pane").copy(hasSession = true)),
                mux = MuxConfigResponse(capabilities = mapOf("agentSessionRef" to true, "paneLayout" to true)),
            ),
        )

        activity.findViewById<View>(R.id.refresh_button).performClick()

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog is CollieBottomSheetDialog)
        assertEquals(
            activity.getString(R.string.pane_actions),
            dialog.findViewById<TextView>(R.id.collie_sheet_title).text.toString(),
        )
        val rows = descendants(dialog.findViewById(R.id.collie_sheet_content))
            .filterIsInstance<TextView>()
            .associateBy { it.text.toString() }
        assertTrue(rows.getValue(activity.getString(R.string.pane_action_find)).isEnabled)
        assertTrue(rows.getValue(activity.getString(R.string.pane_action_history)).isEnabled)
        assertTrue(rows.getValue(activity.getString(R.string.pane_layout_controls)).isEnabled)
        assertTrue(rows.containsKey("topology timing: not declared · snapshot: not read"))
        val processAction = rows.getValue(activity.getString(R.string.pane_action_processes))
        assertTrue(processAction.isEnabled)
        assertTrue(rows.getValue(activity.getString(R.string.pane_action_changes)).isEnabled)
        assertFalse(rows.containsKey("Refresh"))
        assertFalse(rows.getValue(activity.getString(R.string.pane_action_read_only)).isEnabled)

        processAction.performClick()
        val processIntent = shadowOf(activity).nextStartedActivity
        assertEquals(PaneProcessesActivity::class.java.name, processIntent.component?.className)
        assertEquals("actions:pane", processIntent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
    }

    @Test
    fun currentPaneRenameAndCloseOpenTheirOwnSheets() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("current:pane")).create().get()
        render(
            activity,
            PaneUiState(
                loading = false,
                canWrite = true,
                panes = listOf(paneSummary("current:pane").copy(paneLabel = "Current label")),
                mux = MuxConfigResponse(
                    capabilities = mapOf("renamePane" to true, "closePane" to true),
                ),
            ),
        )
        activity.findViewById<View>(R.id.refresh_button).performClick()
        val dialog = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog

        sheetButton(dialog, activity.getString(R.string.pane_action_rename)).performClick()

        val rename = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        assertFalse(dialog == rename)
        assertTrue(rename.isShowing)
        assertEquals(
            activity.getString(R.string.pane_rename_title),
            rename.findViewById<TextView>(R.id.collie_sheet_title)!!.text.toString(),
        )
        assertEquals(
            "Current label",
            descendants(requireNotNull(rename.findViewById<View>(R.id.collie_sheet_content)))
                .filterIsInstance<EditText>()
                .single()
                .text
                .toString(),
        )

        rename.findViewById<View>(R.id.collie_sheet_close)!!.performClick()
        assertFalse(activity.isFinishing)

        val actions = openActionsSheet(activity)
        sheetButton(actions, activity.getString(R.string.pane_action_close)).performClick()
        val confirm = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        assertFalse(actions == confirm)
        assertTrue(confirm.isShowing)
        assertEquals(
            activity.getString(R.string.pane_close_title),
            confirm.findViewById<TextView>(R.id.collie_sheet_title)!!.text.toString(),
        )
        assertFalse(activity.isFinishing)
    }

    @Test
    fun topOfBufferShowsHistoryOnlyForAVerifiedSessionAndOpensIt() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("history:pane")).create().get()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("history:pane", "output", false, 1),
                loading = false,
                panes = listOf(paneSummary("history:pane").copy(hasSession = true, readableLines = 4_000)),
                mux = MuxConfigResponse(
                    capabilities = mapOf("agentSessionRef" to true, "gridScrollback" to true),
                ),
                loadedLines = 600,
            ),
        )

        val action = activity.findViewById<TextView>(R.id.pane_buffer_action)
        assertEquals(View.VISIBLE, action.visibility)
        assertEquals(activity.getString(R.string.pane_scrollback_show_history), action.text.toString())

        action.performClick()

        assertEquals(
            HistoryActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun topOfBufferKeepsLoadOlderWhenTranscriptCapabilityIsUnavailableAndExplainsWhy() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("scroll:pane")).create().get()
        val note = "This multiplexer keeps no agent session log for Collie to read."
        val state = PaneUiState(
                pane = PaneReadResponse("scroll:pane", "output", false, 1),
                loading = false,
                panes = listOf(
                    paneSummary("scroll:pane").copy(hasSession = false, readableLines = 3_000),
                ),
                mux = MuxConfigResponse(
                    capabilities = mapOf("agentSessionRef" to false, "gridScrollback" to true),
                    notes = mapOf("agentSessionRef" to note),
                ),
                requestedLines = 600,
                loadedLines = 600,
            )
        render(activity, state)

        assertEquals(
            activity.getString(R.string.pane_scrollback_load_older),
            activity.findViewById<TextView>(R.id.pane_buffer_action).text.toString(),
        )
        val explanation = activity.findViewById<TextView>(R.id.pane_buffer_explanation)
        assertEquals(View.VISIBLE, explanation.visibility)
        assertEquals(
            "$note Served 1 of 600 requested rows; available depth is unknown.",
            explanation.text.toString(),
        )

        render(activity, state.copy(requestedLines = 1_000, loadingOlder = true))
        val loading = activity.findViewById<TextView>(R.id.pane_buffer_action)
        assertEquals(View.VISIBLE, loading.visibility)
        assertEquals(activity.getString(R.string.pane_scrollback_loading), loading.text.toString())
        assertFalse(loading.isEnabled)
    }

    @Test
    fun journalAgentWithoutSessionShowsTheActionableExplanation() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("no-session:pane")).create().get()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("no-session:pane", "output", false, 1),
                loading = false,
                panes = listOf(paneSummary("no-session:pane").copy(agent = "opencode", hasSession = false)),
                mux = MuxConfigResponse(capabilities = mapOf("agentSessionRef" to true)),
            ),
        )

        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_buffer_action).visibility)
        val explanation = activity.findViewById<TextView>(R.id.pane_buffer_explanation)
        assertEquals(View.VISIBLE, explanation.visibility)
        assertTrue(explanation.text.toString().startsWith("opencode has not reported a session"))
    }

    @Test
    fun switcherRecentCanBeExpandedWhenTheDashboardLeftItCollapsed() {
        // The integrator made Recent a plain heading, but its rows still follow the dashboard's
        // collapsed state, so the switcher showed "Recent" with nothing under it and no way to
        // open it (S25 Ultra, 2026-09-11).
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        NativePreferences(context).dashboardRecentOpen = false
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().get()
        val state = PaneUiState(
            loading = false,
            canWrite = true,
            panes = listOf(paneSummary("w1:p1"), paneSummary("w1:p2").copy(status = AgentStatus.IDLE)),
        )
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }
        activity.findViewById<View>(R.id.switcher_handle).performClick()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        fun views(): List<View> = generateSequence(listOf<View>(sheet.window!!.decorView)) { level ->
            level.filterIsInstance<ViewGroup>().flatMap { g -> (0 until g.childCount).map(g::getChildAt) }.takeIf { it.isNotEmpty() }
        }.flatten().toList()
        val toggle = views().single { it.contentDescription == activity.getString(R.string.expand_recent) }
        toggle.performClick()
        assertTrue(views().any { it.contentDescription == activity.getString(R.string.collapse_recent) })
        assertTrue(NativePreferences(context).dashboardRecentOpen)
    }

    @Test
    fun paneSwitcherAndAgentPaletteUseCanonicalPullUpSheets() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().get()
        val state = PaneUiState(
            loading = false,
            canWrite = true,
            composerReady = true,
            panes = listOf(paneSummary("w1:p1"), paneSummary("w1:p2")),
            commands = listOf(AgentCommand("/review", "Review changes", common = true)),
        )
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }

        activity.findViewById<View>(R.id.switcher_handle).performClick()
        assertTrue(ShadowDialog.getLatestDialog() is CollieBottomSheetDialog)
        ShadowDialog.getLatestDialog().dismiss()

        activity.findViewById<View>(R.id.agent_mode_button).performClick()
        assertTrue(ShadowDialog.getLatestDialog() is CollieBottomSheetDialog)
    }

    @Test
    fun composerControlsAreWiredAndToggleMutuallyExclusiveInFlowDocks() {
        val intent = paneIntent("w1:p1")
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()
        val keys = activity.findViewById<View>(R.id.keys_mode_button)
        val quick = activity.findViewById<View>(R.id.quick_mode_button)
        val agent = activity.findViewById<View>(R.id.agent_mode_button)
        val dock = activity.findViewById<View>(R.id.composer_dock)

        assertTrue(keys.hasOnClickListeners())
        assertTrue(quick.hasOnClickListeners())
        assertTrue(agent.hasOnClickListeners())
        assertEquals(View.GONE, dock.visibility)

        quick.performClick()
        assertEquals(View.VISIBLE, dock.visibility)
        val quickActions = activity.findViewById<ViewGroup>(R.id.quick_actions_container)
        assertEquals(View.VISIBLE, quickActions.visibility)
        assertTrue(quickActions.childCount > 0)

        quick.performClick()
        assertEquals(View.GONE, dock.visibility)
    }

    @Test
    fun keysDrawerUsesSegmentedPrimaryAndDigitPadsWithThumbSizedTargets() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-layout:pane")).create().get()

        activity.findViewById<View>(R.id.keys_mode_button).performClick()

        val keysTab = activity.findViewById<View>(R.id.pane_keys_segment_keys)
        val digitsTab = activity.findViewById<View>(R.id.pane_keys_segment_digits)
        val primary = activity.findViewById<View>(R.id.pane_keys_primary)
        val digits = activity.findViewById<GridLayout>(R.id.pane_keys_digits)
        assertTrue(keysTab.hasOnClickListeners())
        assertTrue(digitsTab.hasOnClickListeners())
        assertEquals(View.VISIBLE, primary.visibility)
        assertEquals(View.GONE, digits.visibility)
        assertEquals(9, digits.childCount)

        digitsTab.performClick()

        assertEquals(View.GONE, primary.visibility)
        assertEquals(View.VISIBLE, digits.visibility)
        assertTrue(
            descendants(activity.findViewById(R.id.key_pad_container))
                .filter { it.hasOnClickListeners() }
                .all { it.minimumHeight >= 44 || it.layoutParams?.height ?: 0 >= 44 },
        )
    }

    @Test
    fun switchingToDigitsPreservesTheModifierQueueAndClosingResetsToKeys() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-state:pane")).create().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        val modifiers = activity.findViewById<ViewGroup>(R.id.pane_keys_modifiers)
        val ctrl = descendants(modifiers).filterIsInstance<MaterialButton>()
            .single { it.text.toString() == activity.getString(R.string.pane_key_ctrl) }
        ctrl.performClick()

        activity.findViewById<View>(R.id.pane_keys_segment_digits).performClick()
        val one = descendants(activity.findViewById(R.id.pane_keys_digits))
            .filterIsInstance<MaterialButton>()
            .single { it.text.toString() == "1" }
        one.performClick()

        assertEquals(listOf("ctrl+1"), keyQueue(activity).staged)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_queue).visibility)

        // The first close guards the staged sequence; the second confirms its discard.
        activity.findViewById<View>(R.id.composer_dock_close).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.composer_dock).visibility)
        activity.findViewById<View>(R.id.composer_dock_close).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.composer_dock).visibility)
        activity.findViewById<View>(R.id.keys_mode_button).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_primary).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_keys_digits).visibility)
        assertTrue(keyQueue(activity).staged.isEmpty())
    }

    @Test
    fun presetsAndFunctionKeysLiveInTheMoreSheet() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-sections:pane")).create().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        assertNull(activity.findViewById<View?>(R.id.pane_keys_presets_toggle))

        activity.showMoreKeysSheetForTest()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        val presets = sheet.findViewById<GridLayout>(R.id.pane_keys_presets)!!
        val functions = sheet.findViewById<GridLayout>(R.id.pane_keys_functions)!!

        assertEquals(View.VISIBLE, presets.visibility)
        assertEquals(View.VISIBLE, functions.visibility)
        assertEquals(12, functions.childCount)
    }

    @Test
    fun everyKeyInBothSegmentsKeepsTheTerminalWriteGate() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-gate:pane")).create().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("keys-gate:pane", "output", false, 1),
                loading = false,
                canWrite = false,
                panes = listOf(paneSummary("keys-gate:pane")),
            ),
        )

        val wireButtons = listOf(
            activity.findViewById<ViewGroup>(R.id.pane_keys_primary),
            activity.findViewById<ViewGroup>(R.id.pane_keys_digits),
        ).flatMap { descendants(it).filterIsInstance<MaterialButton>().toList() }
            .filterNot {
                it.id == R.id.pane_keys_presets_toggle || it.id == R.id.pane_keys_functions_toggle
            }

        assertTrue(wireButtons.isNotEmpty())
        assertTrue(wireButtons.none { it.isEnabled })
        assertFalse(activity.findViewById<View>(R.id.keys_mode_button).isEnabled)
    }

    @Test
    fun modifierBaseInputStagesAChipThatCanBeRemovedWithoutSending() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-chip:pane")).create().get()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("keys-chip:pane", "prompt", false, 1),
                loading = false,
                canWrite = true,
                panes = listOf(paneSummary("keys-chip:pane")),
            ),
        )
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        val ctrl = descendants(activity.findViewById(R.id.pane_keys_modifiers))
            .filterIsInstance<MaterialButton>()
            .single { it.text.toString() == activity.getString(R.string.pane_key_ctrl) }

        ctrl.performClick()

        val baseInput = activity.findViewById<EditText>(R.id.pane_keys_base_input)
        assertTrue(baseInput.isEnabled)
        assertTrue(baseInput.minimumHeight >= 44 || baseInput.layoutParams.height >= 44)
        assertTrue(
            descendants(activity.findViewById(R.id.pane_keys_queue_chips))
                .filterIsInstance<TextView>()
                .any { it.text.toString().contains("Ctrl +") },
        )

        baseInput.setText("G")

        assertEquals(listOf("ctrl+g"), keyQueue(activity).staged)
        assertEquals(null, activity.findViewById<View>(R.id.pane_keys_base_input))
        val removeChip = descendants(activity.findViewById(R.id.pane_keys_queue_chips))
            .filterIsInstance<MaterialButton>()
            .single { it.contentDescription.toString() == "Remove Ctrl G" }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_queue).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_queue_send).visibility)
        assertTrue(activity.findViewById<View>(R.id.pane_keys_queue_clear).hasOnClickListeners())

        removeChip.performClick()

        assertTrue(keyQueue(activity).staged.isEmpty())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_keys_queue).visibility)
    }

    @Test
    fun immediateKeyEchoOnlyShowsAcceptedForTheMatchingViewModelResult() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-echo:pane")).create().get()
        val button = activity.findViewById<MaterialButton>(R.id.escape_button)
        val state = PaneUiState(
            pane = PaneReadResponse("keys-echo:pane", "prompt", false, 1),
            loading = false,
            canWrite = true,
            panes = listOf(paneSummary("keys-echo:pane")),
        )
        render(activity, state)
        PaneActivity::class.java.getDeclaredMethod("beginKeyEcho", MaterialButton::class.java, List::class.java).apply {
            isAccessible = true
            invoke(activity, button, listOf("Escape"))
        }

        assertEquals("Esc …", button.text.toString())
        render(activity, state.copy(status = activity.getString(R.string.pane_keys_sent, "Tab")))
        assertEquals("Esc …", button.text.toString())

        render(activity, state.copy(status = activity.getString(R.string.pane_keys_sent, "Escape")))

        assertEquals(activity.getString(R.string.check_mark), button.text.toString())
        assertEquals("Esc accepted", button.contentDescription.toString())
    }

    @Test
    fun backClosesAnOpenComposerDrawerBeforeLeavingThePane() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().start().resume()
        val activity = controller.get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.composer_dock).visibility)

        activity.onBackPressedDispatcher.onBackPressed()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.composer_dock).visibility)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun stagedKeysRequireASecondBackBeforeTheDrawerCanDiscardThem() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-back:pane"))
            .create().start().resume()
        val activity = controller.get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        keyQueue(activity).apply {
            cycle(PaneKeyModifier.CTRL)
            press(listOf("x"))
        }

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.composer_dock).visibility)
        assertEquals(listOf("ctrl+x"), keyQueue(activity).staged)
        assertTrue(activity.findViewById<TextView>(R.id.error_text).text.toString().contains("Clear"))

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.composer_dock).visibility)
        assertTrue(keyQueue(activity).staged.isEmpty())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun stagedKeysRequireASecondTapBeforeSwitchingDrawers() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-switch:pane")).create().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        keyQueue(activity).apply {
            cycle(PaneKeyModifier.ALT)
            press(listOf("Enter"))
        }

        activity.findViewById<View>(R.id.quick_mode_button).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.key_row).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.quick_actions_container).visibility)

        activity.findViewById<View>(R.id.quick_mode_button).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.key_row).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.quick_actions_container).visibility)
        assertTrue(keyQueue(activity).staged.isEmpty())
    }

    @Test
    fun unsentDraftRestoresOnlyForTheSamePane() {
        val first = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("draft:pane")).create().start().resume()
        first.get().findViewById<EditText>(R.id.reply_input).setText("line one\nline two")
        first.pause().stop().destroy()

        val restored = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("draft:pane")).create().get()
        val other = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("other:pane")).create().get()

        assertEquals("line one\nline two", restored.findViewById<EditText>(R.id.reply_input).text.toString())
        assertEquals("", other.findViewById<EditText>(R.id.reply_input).text.toString())
    }

    @Test
    fun activityReconstructionKeepsTheReplyDraftButDisarmsTransientDirectTyping() {
        val normal = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("recreate-reply:pane"))
            .create().start().resume()
        normal.get().findViewById<EditText>(R.id.reply_input).setText("survives recreation")
        val normalState = android.os.Bundle()

        normal.pause().saveInstanceState(normalState).stop().destroy()
        val reconstructedNormal = Robolectric.buildActivity(
            PaneActivity::class.java,
            paneIntent("recreate-reply:pane"),
        ).create(normalState).get()

        assertEquals(
            "survives recreation",
            reconstructedNormal.findViewById<EditText>(R.id.reply_input).text.toString(),
        )

        val direct = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("recreate-direct:pane"))
            .create().start().resume()
        val directInput = direct.get().findViewById<DirectTypingEditText>(R.id.reply_input)
        directInput.beginDirectTyping()
        PaneActivity::class.java.getDeclaredField("directTyping").apply {
            isAccessible = true
            setBoolean(direct.get(), true)
        }
        directInput.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            ?.setComposingText("never persist this", 1)
        val directState = android.os.Bundle()

        direct.pause().saveInstanceState(directState).stop().destroy()

        val reconstructedDirect = Robolectric.buildActivity(
            PaneActivity::class.java,
            paneIntent("recreate-direct:pane"),
        ).create(directState).get()
        val recreatedInput = reconstructedDirect.findViewById<DirectTypingEditText>(R.id.reply_input)
        assertFalse(recreatedInput.directTypingEnabled)
        assertEquals("", recreatedInput.text?.toString())
    }

    @Test
    fun ctrlOrCommandEnterUsesTheSameGuardedReplyPathAsSend() {
        listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_META_ON).forEachIndexed { index, modifier ->
            val activity = Robolectric.buildActivity(
                PaneActivity::class.java,
                paneIntent("shortcut-$index:pane"),
            ).create().get()
            val input = activity.findViewById<DirectTypingEditText>(R.id.reply_input)
            input.setText("rm -rf build")

            val down = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, modifier)
            val up = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, modifier)
            assertTrue(input.dispatchKeyEvent(down))
            assertTrue(input.dispatchKeyEvent(up))

            val status = activity.findViewById<TextView>(R.id.error_text)
            assertEquals(View.VISIBLE, status.visibility)
            assertTrue(status.text.toString().startsWith("Destructive:"))
        }
    }

    @Test
    fun switcherPullShowsTheActualNonModalPaneListBeforeRelease() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().get()
        render(
            activity,
            PaneUiState(
                loading = false,
                panes = listOf(
                    paneSummary("w1:p1"),
                    paneSummary("w1:p2").copy(workspaceLabel = "another project", tabLabel = "review"),
                ),
            ),
        )
        val handle = activity.findViewById<View>(R.id.switcher_handle)
        val chrome = activity.findViewById<View>(R.id.composer_chrome)
        val peek = activity.findViewById<View>(R.id.switcher_peek)
        val dialogsBeforePull = ShadowDialog.getShownDialogs().size
        handle.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 400f, 0))
        handle.dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, 10f, 340f, 0))
        assertTrue(chrome.translationY < 0f)
        assertEquals(View.VISIBLE, peek.visibility)
        assertTrue(peek.translationY < activity.resources.displayMetrics.density * 320f)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, peek.importantForAccessibility)
        assertEquals(dialogsBeforePull, ShadowDialog.getShownDialogs().size)

        val previewTexts = descendants(peek).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(previewTexts.contains(activity.getString(R.string.pane_switcher_title)))
        assertTrue(previewTexts.any { it.contains("project") })
        assertTrue(previewTexts.any { it.contains("another project") })
        assertFalse(previewTexts.any { it.contains("available") })
        assertFalse(descendants(peek).any(View::hasOnClickListeners))

        handle.dispatchTouchEvent(MotionEvent.obtain(0, 30, MotionEvent.ACTION_CANCEL, 10f, 340f, 0))
    }

    @Test
    fun switcherPullOnlyBecomesModalAfterAnOpenRelease() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().get()
        render(
            activity,
            PaneUiState(loading = false, panes = listOf(paneSummary("w1:p1"), paneSummary("w1:p2"))),
        )
        val handle = activity.findViewById<View>(R.id.switcher_handle)
        val dialogsBeforePull = ShadowDialog.getShownDialogs().size

        handle.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 400f, 0))
        handle.dispatchTouchEvent(MotionEvent.obtain(0, 200, MotionEvent.ACTION_MOVE, 10f, 260f, 0))
        assertEquals(dialogsBeforePull, ShadowDialog.getShownDialogs().size)

        handle.dispatchTouchEvent(MotionEvent.obtain(0, 220, MotionEvent.ACTION_UP, 10f, 260f, 0))
        assertEquals(dialogsBeforePull + 1, ShadowDialog.getShownDialogs().size)
        assertTrue(ShadowDialog.getLatestDialog() is CollieBottomSheetDialog)
    }

    @Test
    fun blankAgentQueryShowsOnlyCommonCommandsAndSearchUsesTheFullCatalog() {
        val commands = (1..12).map { index ->
            AgentCommand("/command$index", "description $index", common = index <= 3)
        }

        assertEquals(commands.take(3), PaneActivity.filterAgentCommands(commands, ""))
        assertEquals(listOf(commands.last()), PaneActivity.filterAgentCommands(commands, "12"))
        assertTrue(PaneActivity.filterAgentCommands(commands, "missing").isEmpty())
    }

    @Test
    fun findHighlightsLiteralOutputAndBackClosesFindFirst() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("find:pane"))
            .create().start().resume().get()
        PaneActivity::class.java.getDeclaredMethod("renderTerminal", String::class.java).apply {
            isAccessible = true
            invoke(activity, "A.B then a.b; not axb")
        }
        PaneActivity::class.java.getDeclaredMethod("openFind").apply {
            isAccessible = true
            invoke(activity)
        }
        activity.findViewById<EditText>(R.id.find_query).setText("a.b")

        val output = activity.findViewById<TextView>(R.id.terminal_text).text as Spanned
        assertEquals(3, output.getSpans(0, output.length, BackgroundColorSpan::class.java).size)
        assertEquals("1/2", activity.findViewById<TextView>(R.id.find_count).text.toString())

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.find_bar).visibility)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun findTakesOverTheHeaderWithoutMovingBelowTheStrips() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("find-header:pane")).create().get()
        val parent = activity.findViewById<View>(R.id.pane_header).parent as ViewGroup
        val find = activity.findViewById<View>(R.id.find_bar)
        val header = activity.findViewById<View>(R.id.pane_header)
        val strips = activity.findViewById<View>(R.id.tab_strip)

        assertTrue(parent.indexOfChild(find) < parent.indexOfChild(strips))
        PaneActivity::class.java.getDeclaredMethod("openFind").apply {
            isAccessible = true
            invoke(activity)
        }
        assertEquals(View.GONE, header.visibility)
        assertEquals(View.VISIBLE, find.visibility)
    }

    @Test
    fun frozenOutputKeepsTheDisplayedRevisionUntilJumpToLatest() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("freeze:pane")).create().get()
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        render.invoke(activity, PaneUiState(pane = PaneReadResponse("freeze:pane", "old output", false, 1), loading = false))
        PaneActivity::class.java.getDeclaredField("followingOutput").apply {
            isAccessible = true
            setBoolean(activity, false)
        }

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("freeze:pane", "new output", false, 2), loading = false))
        assertEquals("old output", activity.findViewById<TextView>(R.id.terminal_text).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.new_output_button).visibility)

        activity.findViewById<View>(R.id.new_output_button).performClick()
        assertEquals("new output", activity.findViewById<TextView>(R.id.terminal_text).text.toString())
    }

    @Test
    fun displayWrapSwitchChangesBetweenViewportWrapAndHorizontalPan() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("wrap:pane")).create().get()
        val horizontal = activity.findViewById<HorizontalScrollView>(R.id.terminal_horizontal_scroll)
        val output = activity.findViewById<TextView>(R.id.terminal_text)
        val wrap = openActionsSheet(activity).findViewById<android.widget.CompoundButton>(R.id.pane_action_wrap)!!

        wrap.isChecked = false
        assertFalse(horizontal.isFillViewport)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, output.layoutParams.width)

        wrap.isChecked = true
        assertTrue(horizontal.isFillViewport)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, output.layoutParams.width)
    }

    @Test
    fun wrappedOutputPansOnlyTableRunsWhileSurroundingProseStillWraps() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("table:pane")).create().get()
        val text = "prose before\n| Name | A very wide value |\n| --- | --- |\n| row | 12345678901234567890 |\nprose after"
        PaneActivity::class.java.getDeclaredMethod("renderTerminal", String::class.java).apply {
            isAccessible = true
            invoke(activity, text)
        }

        val flat = activity.findViewById<View>(R.id.terminal_horizontal_scroll)
        val blocks = activity.findViewById<ViewGroup>(R.id.terminal_block_content)
        assertEquals(View.GONE, flat.visibility)
        assertEquals(View.VISIBLE, blocks.visibility)
        assertEquals(3, blocks.childCount)
        assertTrue(blocks.getChildAt(1) is HorizontalScrollView)

        openActionsSheet(activity).findViewById<android.widget.CompoundButton>(R.id.pane_action_wrap)!!.isChecked = false
        assertEquals(View.VISIBLE, flat.visibility)
        assertEquals(View.GONE, blocks.visibility)
    }

    private fun openActionsSheet(activity: PaneActivity): CollieBottomSheetDialog {
        activity.showPaneActionsForTest()
        return ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
    }

    @Test
    fun terminalAutolinksOnlyAllowlistedSchemesWithoutDroppingAnsi() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("links:pane")).create().get()
        PaneActivity::class.java.getDeclaredMethod("renderTerminal", String::class.java).apply {
            isAccessible = true
            invoke(activity, "\u001b[31mhttps://example.test/a\u001b[0m javascript:bad")
        }
        val output = activity.findViewById<TextView>(R.id.terminal_text).text as Spanned

        assertEquals(1, output.getSpans(0, output.length, URLSpan::class.java).size)
        assertTrue(output.getSpans(0, output.length, android.text.style.ForegroundColorSpan::class.java).isNotEmpty())
    }

    @Test
    fun verifiedSendShowsEchoGapPreviewUntilTheMirrorChanges() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("sent:pane")).create().get()
        val pane = PaneReadResponse("sent:pane", "prompt", false, 4)
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        render.invoke(activity, PaneUiState(pane = pane, loading = false))
        PaneActivity::class.java.getDeclaredMethod("beginReplySend", String::class.java).apply {
            isAccessible = true
            invoke(activity, "check the patch")
        }

        render.invoke(activity, PaneUiState(pane = pane, loading = false, clearReplyDraft = true))
        val preview = activity.findViewById<TextView>(R.id.pending_send_notice)
        assertEquals(View.VISIBLE, preview.visibility)
        assertTrue(preview.text.toString().contains("check the patch"))

        render.invoke(activity, PaneUiState(pane = pane.copy(text = "prompt\ncheck the patch", revision = 5), loading = false))
        assertEquals(View.GONE, preview.visibility)
    }

    @Test
    fun aMirrorRenderNeverTakesFocusFromTheComposer() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("focus:pane")).create().start().resume().get()
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        val field = activity.findViewById<View>(R.id.reply_input)
        render.invoke(activity, PaneUiState(pane = PaneReadResponse("focus:pane", "~\n❯ ", false, 0), loading = false, canWrite = true))
        field.requestFocus()
        assertTrue("composer must be focusable once a writable pane is shown", field.isFocused)

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("focus:pane", "~\n❯ a", false, 0), loading = false, canWrite = true))
        render.invoke(activity, PaneUiState(pane = PaneReadResponse("focus:pane", "~\n❯ ab", false, 0), loading = false, canWrite = true))

        assertTrue(field.isFocused)
        assertFalse(activity.findViewById<View>(R.id.terminal_text).isFocused)
    }

    @Test
    fun anAnsweredDialogClearsItsPanelEvenWhenHerdrRepeatsRevisionZero() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("dialog:pane")).create().start().resume().get()
        PaneActivity::class.java.getDeclaredField("agentName").apply { isAccessible = true; set(activity, "claude") }
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        val panel = activity.findViewById<View>(R.id.semantic_panel)
        val dialog = "Which colour?\n❯ 1. Red\n  2. Green\nEnter to select · ↑/↓ to navigate · Esc to cancel"

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("dialog:pane", dialog, false, 0), loading = false, canWrite = true))
        assertEquals(View.VISIBLE, panel.visibility)

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("dialog:pane", "● Green\n❯ ", false, 0), loading = false, canWrite = true))
        assertEquals(View.GONE, panel.visibility)
    }

    @Test
    fun mirrorFollowsChangedTextWhenHerdrRepeatsRevisionZero() {
        // Herdr 0.7.x stubs `revision` to 0 on every read (HERDR_API.md); only the text can key change.
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("rev0:pane")).create().get()
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        val mirror = activity.findViewById<TextView>(R.id.terminal_text)

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("rev0:pane", "~\n❯ ", false, 0), loading = false))
        assertTrue(mirror.text.toString().contains("❯"))

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("rev0:pane", "~\n❯ echo hi\nhi\n❯ ", false, 0), loading = false))
        assertTrue(mirror.text.toString().contains("echo hi"))

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("rev0:pane", "", false, 0), loading = false))
        assertEquals("", mirror.text.toString())
    }

    @Test
    fun microphoneOwnsThePrimaryActionOnlyWhileTheDraftIsEmpty() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("mic:pane")).create().get()
        PaneActivity::class.java.getDeclaredField("speechAvailability").apply {
            isAccessible = true
            set(activity, SpeechAvailability.Available("test"))
        }
        PaneActivity::class.java.getDeclaredMethod("renderComposerMediaControls").apply {
            isAccessible = true
            invoke(activity)
        }
        val microphone = activity.findViewById<View>(R.id.speech_button)
        val send = activity.findViewById<View>(R.id.send_button)
        val input = activity.findViewById<EditText>(R.id.reply_input)
        assertEquals(View.VISIBLE, microphone.visibility)
        assertEquals(View.GONE, send.visibility)

        activity.acceptUploadForTest("/fixture/upload.png")
        assertEquals(View.GONE, microphone.visibility)
        assertEquals(View.VISIBLE, send.visibility)

        input.setText("hello")
        assertEquals(View.GONE, microphone.visibility)
        assertEquals(View.VISIBLE, send.visibility)

        input.text.clear()
        assertEquals(View.GONE, microphone.visibility)
        assertEquals(View.VISIBLE, send.visibility)
        activity.findViewById<com.google.android.material.chip.Chip>(R.id.composer_attachment_chip)
            .performCloseIconClick()
        assertEquals(View.VISIBLE, microphone.visibility)
        assertEquals(View.GONE, send.visibility)
    }

    @Test
    fun livePaneSummaryReplacesIntentHeaderAndAddsMultiPaneDiscriminator() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().get()
        val panes = listOf(
            paneSummary("w1:p1"),
            paneSummary("w1:p2"),
        )
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, PaneUiState(loading = false, panes = panes))
        }

        assertEquals("project › build", activity.findViewById<TextView>(R.id.pane_title).text.toString())
        assertEquals("p1", activity.findViewById<TextView>(R.id.pane_discriminator).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_cwd).visibility)
    }

    @Test
    fun terminalAndDraftUseNativeTextSizes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        NativePreferences(context).apply {
            terminalFontSize = 15
            draftFontSize = 16
        }
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("font:pane")).create().get()

        val density = activity.resources.displayMetrics.scaledDensity
        assertEquals(15f, activity.findViewById<TextView>(R.id.terminal_text).textSize / density)
        assertEquals(16f, activity.findViewById<TextView>(R.id.reply_input).textSize / density)
    }

    @Test
    fun rawModeHidesSemanticReplacementAndRestoresItWithoutANewRevision() {
        val intent = paneIntent("semantic:pane").putExtra(PaneActivity.EXTRA_AGENT, "claude")
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()
        val raw = """
            prior output
            Which color?
            ❯ 1. Red
              2. Blue
            Enter to select · Esc to cancel
        """.trimIndent()
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(
                activity,
                PaneUiState(
                    pane = PaneReadResponse("semantic:pane", raw, truncated = false, revision = 7),
                    loading = false,
                ),
            )
        }
        val panel = activity.findViewById<View>(R.id.semantic_panel)
        val terminal = activity.findViewById<TextView>(R.id.terminal_text)

        assertEquals(View.VISIBLE, panel.visibility)
        assertFalse(terminal.text.toString().contains("Which color?"))
        val rawSwitch = openActionsSheet(activity).findViewById<android.widget.CompoundButton>(R.id.pane_action_raw)!!
        rawSwitch.isChecked = true
        assertEquals(View.GONE, panel.visibility)
        assertTrue(terminal.text.toString().contains("Which color?"))
        rawSwitch.isChecked = false
        assertEquals(View.VISIBLE, panel.visibility)
        assertFalse(terminal.text.toString().contains("Which color?"))
    }

    @Test
    fun passwordPromptDisablesOrdinaryAndMediaInputButOffersExplicitType() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("password:pane"))
            .create().get()
        val input = activity.findViewById<EditText>(R.id.reply_input)
        input.setText("not retained as a password")
        val state = PaneUiState(
            pane = PaneReadResponse(
                "password:pane",
                "$ sudo -v\n[sudo] password for operator:",
                truncated = false,
                revision = 9,
            ),
            loading = false,
            canWrite = true,
        )
        setViewModelState(activity, state)
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.no_echo_notice).visibility)
        assertFalse(activity.findViewById<View>(R.id.send_button).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.attach_image_button).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.quick_mode_button).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.type_mode_button).isEnabled)
        val draftKey = PaneActivity::class.java.getDeclaredField("paneDraftKey").run {
            isAccessible = true
            get(activity) as String
        }
        assertEquals("", NativePreferences(activity).paneDraft(draftKey))

        input.setText("still never persisted")
        assertEquals("", NativePreferences(activity).paneDraft(draftKey))

        activity.findViewById<View>(R.id.no_echo_type).performClick()
        assertEquals("", input.text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.no_echo_notice).visibility)
        assertTrue(input.isEnabled)
    }

    @Test
    fun passwordPromptCannotBePersistedByOnStop() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("password-stop:pane"))
            .create().start().resume()
        val activity = controller.get()
        val input = activity.findViewById<EditText>(R.id.reply_input)
        input.setText("stored before no-echo detection")
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(
                activity,
                PaneUiState(
                    pane = PaneReadResponse(
                        "password-stop:pane",
                        "Password:",
                        truncated = false,
                        revision = 1,
                    ),
                    loading = false,
                    canWrite = true,
                ),
            )
        }
        input.setText("must remain memory-only")

        controller.pause().stop()

        val restored = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("password-stop:pane")).create().get()
        assertEquals("", restored.findViewById<EditText>(R.id.reply_input).text.toString())
    }

    @Test
    fun passwordPromptWarningCanBeDismissedWhenDetectionIsWrong() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("password-dismiss:pane"))
            .create().get()
        val input = activity.findViewById<EditText>(R.id.reply_input)
        val pane = PaneReadResponse("password-dismiss:pane", "Password:", false, 3)
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, PaneUiState(pane = pane, loading = false, canWrite = true))
        }

        activity.findViewById<View>(R.id.no_echo_dismiss).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.no_echo_notice).visibility)
        assertTrue(input.isEnabled)

        input.setText("this is ordinary input")
        val draftKey = PaneActivity::class.java.getDeclaredField("paneDraftKey").run {
            isAccessible = true
            get(activity) as String
        }
        assertEquals("this is ordinary input", NativePreferences(activity).paneDraft(draftKey))

        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, PaneUiState(pane = pane.copy(revision = 4), loading = false, canWrite = true))
        }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.no_echo_notice).visibility)
    }

    @Test
    fun zenKeepsAnOnScreenExitAndEscapeLeavesZen() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("zen:pane")).create().get()
        PaneActivity::class.java.getDeclaredMethod("setZenMode", java.lang.Boolean.TYPE).apply {
            isAccessible = true
            invoke(activity, true)
        }

        val exit = activity.findViewById<View>(R.id.new_output_button)
        assertEquals(View.VISIBLE, exit.visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_header).visibility)

        exit.performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_header).visibility)
        assertEquals(View.GONE, exit.visibility)

        PaneActivity::class.java.getDeclaredMethod("setZenMode", java.lang.Boolean.TYPE).apply {
            isAccessible = true
            invoke(activity, true)
        }
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_header).visibility)
        assertEquals(View.GONE, exit.visibility)
    }

    @Test
    fun stableTerminalDraftTakeOverCopiesAndMakesTheBoundReplyAvailable() {
        val intent = paneIntent("draft-host:pane").putExtra(PaneActivity.EXTRA_AGENT, "claude")
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()
        val rule = "─".repeat(40)
        val state = PaneUiState(
            pane = PaneReadResponse(
                "draft-host:pane",
                "prior\n$rule\n❯ continue from the host\n$rule\n  Opus · /repo",
                truncated = false,
                revision = 10,
            ),
            loading = false,
            canWrite = true,
        )
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
        }
        render.invoke(activity, state)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.terminal_draft_notice).visibility)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_500))
        render.invoke(activity, state.copy(lastSuccessAt = 1_500))

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.terminal_draft_notice).visibility)
        activity.findViewById<View>(R.id.terminal_draft_take_over).performClick()
        assertEquals("continue from the host", activity.findViewById<EditText>(R.id.reply_input).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.terminal_draft_notice).visibility)
        assertTrue(activity.findViewById<View>(R.id.send_button).isEnabled)
    }

    @Test
    fun ordinaryPaneMissingFromAHealthyTopologyReturnsToItsScopedHome() {
        val intent = paneIntent("gone:pane")
            .putExtra(PaneActivity.EXTRA_HOST, "peer-1")
            .putExtra(PaneActivity.EXTRA_SESSION, "work")
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()

        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("gone:pane", "still cached", false, 5),
                loading = false,
                canWrite = true,
                topologyKnown = true,
                panes = emptyList(),
            ),
        )

        val home = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, home.component?.className)
        assertTrue(home.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(home.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals("Pane closed", ShadowToast.getTextOfLatestToast())
        assertEquals("peer-1", activity.repositoryForTest().selectedScope.value.host)
        assertEquals("work", activity.repositoryForTest().selectedScope.value.session)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun missingPaneDoesNotRedirectBeforeTopologyIsKnownHealthy() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("pending:pane")).create().get()

        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("pending:pane", "cached output", false, 4),
                loading = false,
                topologyKnown = false,
                panes = emptyList(),
            ),
        )

        assertFalse(activity.isFinishing)
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun freshlyCreatedPaneMayRemainOpenBeforeItsFirstTopologyAppearance() {
        val intent = paneIntent("fresh:pane").putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()

        render(activity, PaneUiState(loading = false, topologyKnown = true, panes = emptyList()))

        assertFalse(activity.isFinishing)
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun freshlyCreatedPaneEvictsAfterItWasSeenAndThenDisappears() {
        val intent = paneIntent("fresh-seen:pane").putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()
        val present = paneSummary("fresh-seen:pane")

        render(activity, PaneUiState(loading = false, topologyKnown = true, panes = listOf(present)))
        assertFalse(activity.isFinishing)

        render(activity, PaneUiState(loading = false, topologyKnown = true, panes = emptyList()))

        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun freshlyCreatedPaneKeepsItsSeenStateAcrossActivityRecreation() {
        val intent = paneIntent("fresh-recreated:pane").putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        val controller = Robolectric.buildActivity(PaneActivity::class.java, intent).create()
        val activity = controller.get()
        val present = paneSummary("fresh-recreated:pane")
        render(activity, PaneUiState(loading = false, topologyKnown = true, panes = listOf(present)))
        val savedState = Bundle()
        controller.saveInstanceState(savedState).pause().stop().destroy()

        val recreated = Robolectric.buildActivity(PaneActivity::class.java, intent).create(savedState).get()
        render(recreated, PaneUiState(loading = false, topologyKnown = true, panes = emptyList()))

        assertEquals(MainActivity::class.java.name, shadowOf(recreated).nextStartedActivity.component?.className)
        assertTrue(recreated.isFinishing)
    }

    @Test
    fun noticeLivesInTheComposerBandNotOverTheMirror() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("notice:pane")).create().get()
        val notice = activity.findViewById<View>(R.id.error_text)
        assertSame(activity.findViewById<View>(R.id.composer_chrome), notice.parent)
        assertEquals(0f, notice.elevation, 0f)
    }

    @Test
    fun collapsedTabStripShowsTheTabCountNotTheTitle() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("tabs:pane")).create().start().resume().get()
        render(
            activity,
            PaneUiState(
                loading = false,
                panes = listOf(paneSummary("tabs:pane")),
                tabs = listOf(tab("t1", "alpha"), tab("t2", "beta")),
            ),
        )
        assertEquals("2 tabs", activity.findViewById<TextView>(R.id.show_tabs_button).text.toString())
    }

    private fun tab(id: String, label: String) = TabSummary(
        tabId = id,
        workspaceId = "w1",
        number = 1,
        label = label,
        focused = false,
        paneCount = 1,
    )

    @Test
    fun oneTapOnAnUnfocusedMirrorOpensTheComposer() {
        // The mirror is selectable text, so in touch mode Android spends the first tap on
        // focusing it and never delivers the click: tap-to-type took two taps (S25 Ultra,
        // 2026-09-11; NativeInteractionTest tapToTypeOpensTheSoftKeyboard on a Pixel).
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("tap:pane")).create().start().resume().get()
        val writable = PaneUiState(
            pane = PaneReadResponse("tap:pane", "~\n❯ ", false, 0),
            loading = false,
            canWrite = true,
            panes = listOf(paneSummary("tap:pane")),
        )
        setViewModelState(activity, writable)
        render(activity, writable)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val mirror = activity.findViewById<TextView>(R.id.terminal_text)
        assertTrue(mirror.isInTouchMode)
        assertFalse(mirror.isFocused)
        mirror.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20f, 10f, 0))
        mirror.dispatchTouchEvent(MotionEvent.obtain(0, 60, MotionEvent.ACTION_UP, 20f, 10f, 0))
        assertTrue(activity.findViewById<View>(R.id.reply_input).hasFocus())
    }

    @Test
    fun directTypingSwapsSendForStopAndShowsOneLabel() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("direct:pane")).create().start().resume().get()
        val writable = PaneUiState(
            pane = PaneReadResponse("direct:pane", "prompt", false, 1),
            loading = false,
            canWrite = true,
            panes = listOf(paneSummary("direct:pane")),
        )
        setViewModelState(activity, writable)
        render(activity, writable)
        activity.setDirectTypingForTest(true)
        assertEquals(activity.getString(R.string.pane_direct_stop), activity.findViewById<View>(R.id.send_button).contentDescription)
        assertEquals(R.drawable.ic_composer_stop, shadowOf(activity.findViewById<ImageView>(R.id.send_button).drawable).createdFromResId)
        activity.setDirectTypingForTest(false)
        assertEquals(R.drawable.ic_pane_send, shadowOf(activity.findViewById<ImageView>(R.id.send_button).drawable).createdFromResId)
    }

    @Test
    fun attachmentIsAChipNotAPathInTheDraft() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("attach:pane")).create().start().resume().get()
        activity.acceptUploadForTest("/home/x/.local/state/collie/uploads/wM_p1.png")
        assertEquals("", activity.findViewById<EditText>(R.id.reply_input).text.toString())
        val chip = activity.findViewById<com.google.android.material.chip.Chip>(R.id.composer_attachment_chip)
        assertEquals(View.VISIBLE, chip.visibility)
        assertEquals(activity.getString(R.string.pane_attachment_image), chip.text.toString())
        assertEquals("/home/x/.local/state/collie/uploads/wM_p1.png\nhello", activity.outgoingReplyForTest("hello"))
    }

    @Test
    fun acceptedDocumentChipShowsFilenameMimeAndActualBytesAccessiblyAcrossRecreation() {
        val intent = paneIntent("attach:document")
        val controller = Robolectric.buildActivity(PaneActivity::class.java, intent).create().start()
        val path = "/fixture/accepted-document.txt"
        val filename = "notes.txt"
        val mime = "text/plain"
        val bytes = 37L
        val activity = controller.get()
        activity.acceptDocumentUpload(path, filename, mime, bytes)
        fun assertChip(target: PaneActivity) {
            val chip = target.findViewById<com.google.android.material.chip.Chip>(R.id.composer_attachment_chip)
            val expected = target.getString(R.string.pane_attachment_details, filename, mime, bytes)
            assertEquals(View.VISIBLE, chip.visibility)
            assertEquals(expected, chip.text.toString())
            assertEquals(expected, chip.contentDescription.toString())
            assertEquals(target.getString(R.string.pane_attachment_remove, expected), chip.closeIconContentDescription.toString())
            assertEquals("$path\ndraft", target.outgoingReplyForTest("draft"))
            assertEquals("", target.findViewById<EditText>(R.id.reply_input).text.toString())
        }
        assertChip(activity)
        val saved = Bundle()
        controller.saveInstanceState(saved).stop().destroy()
        assertFalse(saved.keySet().any { it.contains("uri") })
        val recreated = Robolectric.buildActivity(PaneActivity::class.java, intent).create(saved)
        try {
            assertChip(recreated.get())
            recreated.get().acceptUploadForTest("/fixture/photo.png")
            val image = recreated.get().findViewById<com.google.android.material.chip.Chip>(R.id.composer_attachment_chip)
            assertEquals(recreated.get().getString(R.string.pane_attachment_image), image.text.toString())
            assertEquals(image.text.toString(), image.contentDescription.toString())
        } finally { recreated.destroy() }
    }

    @Test
    fun attachmentChipShowsAThumbnailAndSaysImageNotAFileName() {
        // S25 Ultra, 2026-09-10: the chip read the bridge copy's name, then the picker's media id
        // ("1000005957.png"). Neither means anything to the operator.
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("attach:thumb")).create().start().resume().get()
        val thumb = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        activity.acceptUploadForTest("/home/x/.local/state/collie/uploads/w5_p4-mtwctvhh-54a3c283.png", thumbnail = thumb)
        val chip = activity.findViewById<com.google.android.material.chip.Chip>(R.id.composer_attachment_chip)
        assertEquals(activity.getString(R.string.pane_attachment_image), chip.text.toString())
        assertTrue(chip.chipIcon is android.graphics.drawable.BitmapDrawable)
        assertEquals("/home/x/.local/state/collie/uploads/w5_p4-mtwctvhh-54a3c283.png\nhi", activity.outgoingReplyForTest("hi"))
    }

    @Test
    fun stagedChipsRowFadesAtTheEdge() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("chips:pane")).create().start().resume().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        val scroller = activity.findViewById<View>(R.id.pane_key_queue_scroll) as HorizontalScrollView
        assertTrue(scroller.isHorizontalFadingEdgeEnabled)
    }

    @Test
    fun renameSheetIsTitledAndPrefilled() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("rename:pane")).create().start().resume().get()
        render(
            activity,
            PaneUiState(
                loading = false,
                canWrite = true,
                panes = listOf(paneSummary("rename:pane").copy(paneLabel = null, sessionName = "Codex session log 01a0")),
            ),
        )
        activity.showRenameForTest()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        assertEquals("Rename pane", sheet.findViewById<TextView>(R.id.collie_sheet_title)!!.text.toString())
        assertEquals("Codex session log 01a0", sheet.findViewById<EditText>(R.id.pane_rename_input)!!.text.toString())
    }

    @Test
    fun statusLabelUsesOneVocabulary() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("status:v", agent = "claude")).create().start().resume().get()
        render(
            activity,
            PaneUiState(
                loading = false,
                panes = listOf(paneSummary("status:v").copy(status = AgentStatus.BLOCKED)),
            ),
        )
        assertEquals("Needs input", activity.findViewById<TextView>(R.id.pane_status).text.toString())
    }

    @Test
    fun closeConfirmIsItsOwnSheetWithOneRedButton() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("close:pane")).create().start().resume().get()
        render(activity, PaneUiState(loading = false, canWrite = true, panes = listOf(paneSummary("close:pane"))))
        val sheet = openActionsSheet(activity)
        sheet.findViewById<View>(R.id.pane_action_close_button)!!.performClick()
        val confirm = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        assertNotSame(sheet, confirm)
        assertEquals("Close pane", confirm.findViewById<TextView>(R.id.collie_sheet_title)!!.text.toString())
        val again = confirm.findViewById<MaterialButton>(R.id.pane_close_again)!!
        assertEquals(activity.getString(R.string.pane_action_close), again.text.toString())
        assertEquals(ContextCompat.getColor(activity, R.color.collie_destructive), again.backgroundTintList!!.defaultColor)
    }

    @Test
    fun keysDrawerHasOneCompactRowAndAMoreButton() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys:pane")).create().start().resume().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        assertNull(activity.findViewById<View?>(R.id.pane_keys_presets_toggle))
        assertNull(activity.findViewById<View?>(R.id.pane_keys_functions_toggle))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_more).visibility)
        val primary = activity.findViewById<ViewGroup>(R.id.pane_keys_primary)
        assertEquals(
            listOf("Esc", "Tab", "↑", "↓", "←", "→", "Enter"),
            (0 until primary.childCount).map { (primary.getChildAt(it) as TextView).text.toString() },
        )
    }

    @Test
    fun keysDrawerKeepsCtrlCAndSpaceOneTapAway() {
        // The compact rework had moved the interrupt and Space into the More sheet, three taps
        // from a runaway command; A.4 only sent Presets and F-keys there (S25 Ultra, 2026-09-10).
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys:ctrlc")).create().start().resume().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        val row = activity.findViewById<ViewGroup>(R.id.pane_keys_modifiers)
        assertEquals(
            listOf("Ctrl C", "Space", "Shift", "Ctrl", "Alt", "More"),
            (0 until row.childCount).map { (row.getChildAt(it) as TextView).text.toString() },
        )
        activity.showMoreKeysSheetForTest()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        val sheetLabels = generateSequence(listOf<View>(sheet.window!!.decorView)) { level ->
            level.filterIsInstance<ViewGroup>().flatMap { group -> (0 until group.childCount).map(group::getChildAt) }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<TextView>().map { it.text.toString() }.toList()
        // The sheet may still list the shipped "Ctrl C" preset (keys.toml can replace it); the
        // fixed one-tap key itself lives in the drawer.
        assertNull(sheet.findViewById<View?>(R.id.ctrl_c_button))
        assertFalse(sheetLabels.contains("Space"))
    }

    @Test
    fun displayDrawerIsGoneAndActionsSheetHoldsWrapAndRaw() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("display:pane")).create().start().resume().get()
        assertNull(activity.findViewById<View?>(R.id.display_prefs_container))
        assertNull(activity.findViewById<View?>(R.id.composer_settings_button))
        activity.showPaneActionsForTest()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        assertNotNull(sheet.findViewById<View>(R.id.pane_action_wrap))
        assertNotNull(sheet.findViewById<View>(R.id.pane_action_raw))
    }

    @Test
    fun statuslineRowRendersUnderTheMirrorForClaude() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("status:pane", agent = "claude")).create().start().resume().get()
        val text = listOf("⏺ hi", "─".repeat(30), "❯ ", "─".repeat(30), "  Opus 5 · 12%").joinToString("\n")
        render(activity, PaneUiState(pane = PaneReadResponse("status:pane", text, truncated = false, revision = 0), loading = false))
        val row = activity.findViewById<TextView>(R.id.terminal_statusline)
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals("Opus 5 · 12%", row.text.toString())
    }

    @Test
    fun claudePaneShowsTranscriptWhenJournalIsAvailableAndNoDialog() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("body:pane", agent = "claude")).create().start().resume().get()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("body:pane", "⏺ done\n", truncated = false, revision = 0),
                loading = false,
                transcript = listOf(TranscriptEntry("a", "2026-09-10T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = "done")))),
                transcriptAvailable = true,
            ),
        )
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcript_body).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.terminal_scroll).visibility)
    }

    @Test
    fun paneBodyDecisionChangesAreRecordedToDiagnostics() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("body:diag", agent = "claude"))
            .create().start().resume().get()
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
        val app = activity.applicationContext as CollieApplication
        val recorder = RecordingDiagnosticsRecorder(lines)
        setDiagnosticsForTest(app, recorder)

        val transcript = listOf(TranscriptEntry("a", "2026-09-18T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = "hi"))))
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("body:diag", "screen", truncated = false, revision = 0),
                loading = false,
                transcript = transcript,
                transcriptAvailable = true,
            ),
        )

        assertTrue(lines.any { it.first == "paneBodyDecision" })
    }

    @Test
    fun showTerminalRestoresTheStatuslinePaddingUnderTheMirror() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("pad:pane", agent = "claude")).create().start().resume().get()
        val text = listOf("⏺ hi", "─".repeat(30), "❯ ", "─".repeat(30), "  Opus 5 · 12%").joinToString("\n")
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("pad:pane", text, truncated = false, revision = 0),
                loading = false,
                transcript = listOf(TranscriptEntry("a", "2026-09-10T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = "done")))),
                transcriptAvailable = true,
            ),
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.terminal_scroll).visibility)
        activity.findViewById<View>(R.id.body_mode_switch).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val statusline = activity.findViewById<View>(R.id.terminal_statusline)
        statusline.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST),
        )
        statusline.layout(0, 0, statusline.measuredWidth, statusline.measuredHeight)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.terminal_scroll).visibility)
        assertTrue(activity.findViewById<View>(R.id.terminal_scroll).paddingBottom > 0)
    }

    @Test
    fun aWorkingClaudePaneKeepsItsTranscriptWhenItsBoxIsNotRecognised() {
        // A rule that showed the terminal whenever Claude's input box went unrecognised also fired
        // on a working Claude waiting for background agents, hiding its transcript (S25 Ultra,
        // 2026-09-11). Only a recognised dialog, Raw, or no journal shows the mirror.
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("body:working", agent = "claude")).create().start().resume().get()
        val screen = "⏺ Lane 06 is committed.\n\n✻ Waiting for 5 background agents to finish\n\n717587 tokens\n"
        val transcript = listOf(TranscriptEntry("a", "2026-09-11T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = "hi"))))
        fun poll(at: Long) = render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("body:working", screen, truncated = false, revision = 0),
                loading = false,
                composerReady = false,
                lastSuccessAt = at,
                transcript = transcript,
                transcriptAvailable = true,
            ),
        )
        poll(1); poll(2); poll(3)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcript_body).visibility)
        assertEquals(activity.getString(R.string.pane_body_showing_transcript), activity.findViewById<TextView>(R.id.body_mode_label).text.toString())
    }

    @Test
    fun aDialogBeforeAnyJournalOffersNoTranscriptSwitch() {
        // A new Claude's first dialog (a trust prompt, an early question) comes before its session
        // log exists; "Show transcript" then opened an empty body (S25 Ultra, 2026-09-11).
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("body:early", agent = "claude")).create().start().resume().get()
        val fixture = File("../../web/src/fixtures/panes/claude--select-ask-no-question-mark.txt").readText()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("body:early", fixture, truncated = false, revision = 0),
                loading = false,
                transcriptAvailable = false,
                transcriptReason = "no-session",
            ),
        )
        assertEquals(activity.getString(R.string.pane_body_showing_mirror_dialog), activity.findViewById<TextView>(R.id.body_mode_label).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.body_mode_switch).visibility)
    }

    @Test
    fun dialogSwitchesTheWholeBodyToTheMirror() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("body:dialog", agent = "claude")).create().start().resume().get()
        val fixture = File("../../web/src/fixtures/panes/claude--select-ask-no-question-mark.txt").readText()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("body:dialog", fixture, truncated = false, revision = 0),
                loading = false,
                transcript = emptyList(),
                transcriptAvailable = true,
            ),
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcript_body).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.terminal_scroll).visibility)
        assertEquals(activity.getString(R.string.pane_body_showing_mirror_dialog), activity.findViewById<TextView>(R.id.body_mode_label).text.toString())
    }

    private fun paneIntent(paneId: String, agent: String = "opencode"): Intent =
        Intent(ApplicationProvider.getApplicationContext(), PaneActivity::class.java)
            .putExtra(PaneActivity.EXTRA_PANE_ID, paneId)
            .putExtra(PaneActivity.EXTRA_AGENT, agent)

    private fun paneSummary(paneId: String) = PaneSummary(
        paneId = paneId,
        workspaceId = "w1",
        workspaceLabel = "project",
        workspaceNumber = 1,
        tabId = "w1:t1",
        agent = "codex",
        status = AgentStatus.WORKING,
        cwd = "/home/operator/project/build",
        focused = paneId == "w1:p1",
        tabLabel = "build",
    )

    private fun keyQueue(activity: PaneActivity): PaneKeyQueue =
        PaneActivity::class.java.getDeclaredField("paneKeyQueue").run {
            isAccessible = true
            get(activity) as PaneKeyQueue
        }

    private fun render(activity: PaneActivity, state: PaneUiState) {
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }
    }

    private fun PaneActivity.repositoryForTest() = (application as com.lateapex.collie.CollieApplication).container.repository

    private fun sheetButton(dialog: CollieBottomSheetDialog, label: String): MaterialButton =
        descendants(requireNotNull(dialog.findViewById<View>(R.id.collie_sheet_content)))
            .filterIsInstance<MaterialButton>()
            .single { it.text.toString() == label }

    @Suppress("UNCHECKED_CAST")
    private fun setViewModelState(activity: PaneActivity, state: PaneUiState) {
        val viewModel = PaneActivity::class.java.getDeclaredField("viewModel").run {
            isAccessible = true
            get(activity) as PaneViewModel
        }
        val mutable = PaneViewModel::class.java.getDeclaredField("mutableState").run {
            isAccessible = true
            get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PaneUiState>
        }
        mutable.value = state
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    @Test
    fun bodyModeRowIsStackedAboveBothBodies() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("zorder:pane")).create().get()
        val surface = activity.findViewById<ViewGroup>(R.id.terminal_surface)
        val row = surface.indexOfChild(activity.findViewById(R.id.body_mode_row))
        assertTrue(row > surface.indexOfChild(activity.findViewById(R.id.transcript_body)))
        assertTrue(row > surface.indexOfChild(activity.findViewById(R.id.terminal_scroll)))
    }

    @Test
    fun bodyModeLabelNamesTheBodyOnScreenAfterAnOverride() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("label:pane", agent = "claude")).create().start().resume().get()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("label:pane", "⏺ done\n", truncated = false, revision = 0),
                loading = false,
                transcript = listOf(TranscriptEntry("a", "2026-09-10T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = "done")))),
                transcriptAvailable = true,
            ),
        )
        activity.findViewById<View>(R.id.body_mode_switch).performClick()
        assertEquals(activity.getString(R.string.pane_body_showing_mirror_no_journal), activity.findViewById<TextView>(R.id.body_mode_label).text.toString())
        assertEquals(activity.getString(R.string.pane_body_switch_to_transcript), activity.findViewById<TextView>(R.id.body_mode_switch).text.toString())
    }

    @Test
    fun attachmentProgressSuccessAndRefusalUsePoliteAccessibleStatusRegion() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("attachment-status:pane")).create().get()
        val status = activity.findViewById<TextView>(R.id.composer_media_status)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
        val announce = PaneActivity::class.java.getDeclaredMethod("showComposerMediaMessage", String::class.java).apply { isAccessible = true }
        listOf(
            activity.getString(R.string.pane_attachment_preparing),
            activity.getString(R.string.pane_attachment_progress, 5, 10),
            activity.getString(R.string.pane_attachment_added, "notes.txt"),
            activity.getString(R.string.pane_attachment_unsupported),
        ).forEach { message ->
            announce.invoke(activity, message)
            assertEquals(message, status.text.toString())
            assertTrue(status.isVisibleForTest())
        }
    }

    private fun View.isVisibleForTest() = visibility == View.VISIBLE
}

private fun setDiagnosticsForTest(app: CollieApplication, recorder: DiagnosticsRecorder) {
    app.container.diagnostics = recorder
}
