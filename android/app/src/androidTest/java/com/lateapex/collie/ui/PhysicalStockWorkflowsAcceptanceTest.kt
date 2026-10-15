package com.lateapex.collie.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ChangeCommitHistoryState
import com.lateapex.collie.network.ChangesListState
import com.lateapex.collie.network.ChangesLookup
import com.lateapex.collie.network.ChangesTarget
import com.lateapex.collie.network.FilesAnswer
import com.lateapex.collie.network.FilesTarget
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.RelativeFilePath
import com.lateapex.collie.ui.terminal.TerminalPlainText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matcher
import org.hamcrest.Matchers.any
import java.util.UUID

/** Opt-in real HTTPS/native UI acceptance. No fixture API, reflection, or direct mutation API. */
@RunWith(AndroidJUnit4::class)
class PhysicalStockWorkflowsAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private data class Live(val app: CollieApplication, val repository: CollieRepository,
        val address: PaneAddress, val actor: ScopedReadContext, val root: String)

    /**
     * Supply liveOrigin, livePaneId, liveRepoPath; optional liveHost/liveSession stay exact.
     * The shell must already be in the explicitly supplied disposable dated Git root.
     * Commit acceptance.txt containing BASE, then append CHANGE; list a valid acceptance.png.
     */
    private fun withLive(test: (Live) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val originText = args.getString("liveOrigin")
        assumeTrue("liveOrigin argument was not supplied; this physical test is opt-in", originText != null)
        assertFalse("Supplied liveOrigin must not be blank", originText.isNullOrBlank())
        fun required(name: String): String {
            val value = args.getString(name)?.trim()
            assertTrue("Supplied liveOrigin requires an explicit $name", !value.isNullOrEmpty())
            return requireNotNull(value)
        }
        val pane = required("livePaneId")
        assertTrue("livePaneId must be a bounded explicit pane identifier", pane.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val root = required("liveRepoPath")
        assertTrue("Only the disposable physical acceptance Git root is allowed", root.matches(PhysicalFixturePaths.datedRootPattern))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val repository = app.container.repository
        val connection = app.container.connectionStore.connection.value
        assertTrue("Supplied liveOrigin requires its existing installed pairing", connection?.isPaired == true)
        assertEquals("Installed pairing must match the exact supplied origin and mount", OriginValidator().requireValid(requireNotNull(originText)), connection?.origin)
        val address = PaneAddress(Scope(args.getString("liveHost")?.takeIf(String::isNotBlank),
            args.getString("liveSession")?.takeIf(String::isNotBlank)), pane)
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        try {
            instrumentation.runOnMainSync { repository.selectSubject(address.scope, address.paneId) }
            val actor = repository.readContext(address.scope, address.paneId)
            assertNotNull("Existing pairing must admit the exact live read actor", actor)
            val captured = requireNotNull(actor)
            val result = runBlocking { repository.snapshot(address.scope, captured) }
            assertTrue("Live snapshot must succeed under its original actor", result is ApiResult.Success)
            assertTrue("Live snapshot actor must remain current", repository.isCurrent(captured))
            val snapshot = (result as ApiResult.Success).value
            val target = (snapshot.agents + snapshot.shellPanes).singleOrNull {
                it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
            }
            assertNotNull("Explicit disposable pane must be present in the live snapshot", target)
            assertEquals("Disposable shell must already be in the exact fixture Git root", root, target?.cwd)
            assertTrue("Only a shell pane may receive the harmless printf acceptance command", target?.agent == "shell" || target?.kind == "shell")
            test(Live(app, repository, address, captured, root))
        } finally {
            instrumentation.runOnMainSync { repository.selectSubject(priorScope, priorSubject) }
            assertTrue("Acceptance must preserve the installed pairing", app.container.connectionStore.connection.value === connection)
        }
    }

    @Test fun liveShellNativeReplyPreservesUnicodeOutputAndFindMatches() = withLive { live ->
        val marker = "COLLIE_PHYSICAL_${UUID.randomUUID().toString().take(8)} 漢字 e\u0301 👨‍💻"
        val command = "printf '\\n%s\\n' '$marker'; pwd"
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
            await(scenario, "live native reply admission") { activity ->
                activity.findViewById<View>(R.id.send_button).isEnabled &&
                    activity.findViewById<EditText>(R.id.reply_input).isEnabled &&
                    activity.findViewById<TextView>(R.id.terminal_text).let { it.isShown && it.text.isNotEmpty() }
            }
            onView(withId(R.id.reply_input)).perform(replaceText(command))
            onView(withId(R.id.send_button)).perform(click())
            await(scenario, "standalone Unicode stdout and fixture working directory") { activity ->
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                val lines = mirror.text.toString().lineSequence().map(String::trim).toList()
                mirror.isShown && marker in lines && live.root in lines
            }
            assertTrue("UI writes and verification must retain the original paired actor", live.repository.isCurrent(live.actor))
            val actual = runBlocking { live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor) }
            assertTrue("Original-actor production pane read must confirm stdout", actual is ApiResult.Success)
            assertTrue("Native Unicode stdout must also exist in the actual stock pane", TerminalPlainText.strip((actual as ApiResult.Success).value.pane.text).lineSequence().map(String::trim).any { it == marker })
            assertTrue("Original actor must still own the real pane after verification", live.repository.isCurrent(live.actor))
            onView(withId(R.id.refresh_button)).perform(click())
            onView(withText(live.app.getString(R.string.pane_action_find))).perform(click())
            onView(withId(R.id.find_query)).perform(replaceText(marker))
            await(scenario, "native output Find result") { activity ->
                val count = activity.findViewById<TextView>(R.id.find_count).text.toString()
                count.matches(Regex("[1-9][0-9]*/[1-9][0-9]*"))
            }
            onView(withId(R.id.find_close)).perform(click())
        }
    }

    @Test fun liveFilesOpensListedUnicodeSourceAndLoadsTheActualRaster() = withLive { live ->
        val target = FilesTarget.Pane(live.address.paneId)
        val response = runBlocking { live.repository.files(target, live.address.scope, dir = requireNotNull(RelativeFilePath.parse("", allowRoot = true)), expectedContext = live.actor) }
        assertTrue("Live Files listing must succeed under the original actor", response is ApiResult.Success)
        val listing = (response as ApiResult.Success).value as? FilesAnswer.Listing
        assertNotNull("The live root must be a directory listing", listing)
        assertEquals(live.root, listing?.root)
        assertTrue("Fixture text and PNG must both be listed", listing!!.entries.map { it.name }.containsAll(listOf(TEXT, IMAGE)))
        assertTrue(live.repository.isCurrent(live.actor))
        var filesActivity: FilesActivity? = null
        try {
            ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { pane ->
                await(pane, "native Files discovery admission") { it.findViewById<View>(R.id.files_button).isEnabled }
                onView(withId(R.id.files_button)).perform(click())
                filesActivity = awaitFiles("native listed fixture source") { fileRow(it, TEXT) != null }
                instrumentation.runOnMainSync { assertTrue("Listed text row must invoke its native handler", fileRow(filesActivity!!, TEXT)!!.performClick()) }
                awaitFiles("byte-faithful Unicode Files source") { activity ->
                    visibleText(activity.findViewById(R.id.files_source_content)).any { BASE in it && CHANGE in it }
                }
                instrumentation.runOnMainSync {
                    assertEquals(TEXT, filesActivity!!.findViewById<TextView>(R.id.files_title).text.toString())
                    assertTouchableInsideSystemInsets(filesActivity!!, R.id.files_back)
                }
                onView(withId(R.id.files_back)).perform(click())
                awaitFiles("returned native listing") { activity -> fileRow(activity, IMAGE) != null }
                instrumentation.runOnMainSync { assertTrue("Listed raster row must invoke its native handler", fileRow(filesActivity!!, IMAGE)!!.performClick()) }
                awaitFiles("actual authenticated PNG bitmap display") { activity ->
                    descendants(activity.findViewById(R.id.files_source_content)).filterIsInstance<ImageView>()
                        .any { it.isShown && it.drawable != null && it.drawable.intrinsicWidth > 0 && it.drawable.intrinsicHeight > 0 }
                }
                instrumentation.runOnMainSync { assertEquals(IMAGE, filesActivity!!.findViewById<TextView>(R.id.files_title).text.toString()) }
                assertTrue("Raster must remain owned by the exact paired read actor", live.repository.isCurrent(live.actor))
            }
        } finally { instrumentation.runOnMainSync { filesActivity?.finish() } }
    }

    @Test fun liveChangesOpensListedHarmlessDiffAndCurrentSourceHandoff() = withLive { live ->
        val response = runBlocking { live.repository.changes(ChangesTarget.Pane(live.address.paneId), live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("Live Changes listing must succeed under the original actor", response is ApiResult.Success)
        val answer = (response as ApiResult.Success).value
        val available = answer.state as? ChangesListState.Available
        assertNotNull("Disposable Git fixture must report actual repository changes", available)
        assertEquals("Changes must retain the exact disposable repository root", live.root, available?.root)
        assertTrue("Harmless modified source must be Git-listed", available!!.repos.any { repo -> repo.files.any { it.path == TEXT } })
        assertTrue(live.repository.isCurrent(live.actor))
        var sourceActivity: FilesActivity? = null
        try {
            ActivityScenario.launch<ChangesActivity>(ChangesActivity.intentForPane(live.app, live.address.paneId, live.address.scope, "Disposable acceptance Git")).use { scenario ->
                await(scenario, "native listed harmless Git change") { activity -> changeButton(activity) != null }
                scenario.onActivity { assertTrue("Git-listed file must invoke the native diff handler", changeButton(it)!!.performClick()) }
                await(scenario, "actual harmless Unicode diff addition") { activity ->
                    visibleText(activity.findViewById(R.id.changes_content)).any { "+$CHANGE" in it }
                }
                onView(withText(live.app.getString(R.string.changes_open_current_source))).perform(click())
                val deadline = SystemClock.uptimeMillis() + TIMEOUT
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    instrumentation.runOnMainSync {
                        sourceActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<FilesActivity>().singleOrNull()
                        ready = sourceActivity?.let { visibleText(it.findViewById(R.id.files_source_content)).any { text -> BASE in text && CHANGE in text } } == true
                    }
                    if (ready) break
                    SystemClock.sleep(100)
                }
                assertNotNull("Current-source handoff must open the real native Files Activity", sourceActivity)
                instrumentation.runOnMainSync {
                    assertTrue("Current-source handoff must render the listed fixture bytes", visibleText(sourceActivity!!.findViewById(R.id.files_source_content)).any { BASE in it && CHANGE in it })
                }
            }
        } finally { instrumentation.runOnMainSync { sourceActivity?.finish() } }
    }

    @Test fun liveChangesLastCommitReadsOwnedHeadAndItsCommittedFileDiff() = withLive { live ->
        val target = ChangesTarget.Pane(live.address.paneId)
        val lookup = ChangesLookup()
        val listed = runBlocking { live.repository.changes(target, live.address.scope, lookup, live.actor) }
        assertTrue("Owned fixture Changes listing must succeed under its original actor", listed is ApiResult.Success)
        val before = (listed as ApiResult.Success).value
        val available = before.state as? ChangesListState.Available
        assertNotNull("Owned fixture must expose its actual Git repository", available)
        assertEquals("Last commit acceptance is scoped to the exact disposable Git root", live.root, available?.root)
        val repo = available!!.repos.singleOrNull { it.relPath == "." }
        assertNotNull("The fixture root must be the listed repository", repo)
        assertTrue("The committed fixture source must now be listed as an uncommitted modification",
            repo!!.files.any { it.path == TEXT })

        val historyResult = runBlocking {
            live.repository.changeCommitHistory(target, live.address.scope, lookup, ".", head = null,
                offset = 0, limit = 1, expectedContext = live.actor)
        }
        assertTrue("Read-only Git history must return the actual owned repository HEAD", historyResult is ApiResult.Success)
        val history = ((historyResult as ApiResult.Success).value.state as? ChangeCommitHistoryState.Available)?.page
        assertNotNull("The disposable fixture must have its committed baseline", history)
        assertEquals("The server's HEAD must equal its newest returned commit", history!!.headHash, history.commits.single().hash)
        val head = history.commits.single()

        var changesActivity: ChangesActivity? = null
        try {
            ActivityScenario.launch<ChangesActivity>(ChangesActivity.intentForPane(
                live.app, live.address.paneId, live.address.scope, "Disposable acceptance Git",
            )).use { scenario ->
                changesActivity = awaitChanges("native Changes repository and Last commit control") {
                    changeAction(it, live.app.getString(R.string.changes_last_commit)) != null
                }
                instrumentation.runOnMainSync {
                    val button = changeAction(requireNotNull(changesActivity), live.app.getString(R.string.changes_last_commit))
                    assertNotNull("Last commit must be reachable through the native Changes screen", button)
                    assertTrue(requireNotNull(button).performClick())
                }
                awaitChanges("native Last commit detail from the exact Git HEAD") { activity ->
                    visibleText(activity.findViewById(R.id.changes_content)).any {
                        head.subject in it && head.shortHash in it
                    }
                }
                instrumentation.runOnMainSync {
                    val file = changeButton(requireNotNull(changesActivity))
                    assertNotNull("The actual HEAD detail must list its committed fixture source", file)
                    assertTrue(requireNotNull(file).performClick())
                }
                awaitChanges("native read-only diff for the committed fixture source") { activity ->
                    val body = visibleText(activity.findViewById(R.id.changes_content)).joinToString("\n")
                    "+$BASE" in body && "+$CHANGE" !in body
                }
            }
        } finally { instrumentation.runOnMainSync { changesActivity?.finish() } }

        val after = runBlocking { live.repository.changes(target, live.address.scope, lookup, live.actor) }
        assertTrue("Read-only Last commit inspection must retain its original actor", live.repository.isCurrent(live.actor))
        assertTrue("Read-only Last commit inspection must leave Changes readable", after is ApiResult.Success)
        assertEquals("Opening HEAD and its committed diff must not alter the repository's live Changes state",
            before.state, (after as ApiResult.Success).value.state)
    }

    @Test fun liveMirrorCopiesSelectedTextAndOpensOnlyConfirmedFileReadOnly() = withLive { live ->
        val marker = "COLLIE_COPY_${UUID.randomUUID().toString().take(8)}"
        val printedPath = "${live.root}/$TEXT"
        val command = "printf '%s\\n' '$marker' 'Open $printedPath'; pwd"
        val target = ChangesTarget.Pane(live.address.paneId)
        val beforeChanges = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("Repository read before mirror interaction must succeed", beforeChanges is ApiResult.Success)
        var readOnlyBaseline: PaneReadResponse? = null

        var filesActivity: FilesActivity? = null
        try {
            ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
                await(scenario, "native live mirror and reply admission") { activity ->
                    activity.findViewById<View>(R.id.send_button).isEnabled &&
                        activity.findViewById<EditText>(R.id.reply_input).isEnabled &&
                        activity.findViewById<TextView>(R.id.terminal_text).isShown
                }
                onView(withId(R.id.reply_input)).perform(replaceText(command))
                onView(withId(R.id.send_button)).perform(click())
                await(scenario, "actual mirror output and confirmed printed source link") { activity ->
                    val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                    mirror.text.toString().contains(marker) && mirror.text.toString().contains(printedPath) &&
                        clickableSpan(mirror, printedPath) != null
                }
                val beforeReadOnlyActions = runBlocking {
                    live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
                }
                assertTrue("The actual live output must be captured before exercising mirror read actions",
                    beforeReadOnlyActions is ApiResult.Success)
                readOnlyBaseline = (beforeReadOnlyActions as ApiResult.Success).value.pane

                onView(withId(R.id.terminal_text)).perform(longPressText(marker))
                onView(withText(android.R.string.copy)).perform(click())
                val clipboard = live.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertTrue("Native selectable mirror text must copy the selected live output",
                    clipboard.primaryClip?.getItemAt(0)?.coerceToText(live.app)?.toString()?.contains(marker) == true)

                onView(withId(R.id.reply_input)).perform(click())
                onView(withId(R.id.terminal_text)).perform(tapPrintedPath(printedPath))
                filesActivity = awaitFiles("confirmed printed path opens the native Files reader") { activity ->
                    activity.findViewById<TextView>(R.id.files_title).text.toString() == TEXT &&
                        visibleText(activity.findViewById(R.id.files_source_content)).any { BASE in it && CHANGE in it }
                }
                instrumentation.runOnMainSync {
                    val content = requireNotNull(filesActivity).findViewById<View>(R.id.files_source_content)
                    assertTrue("Printed-path handoff must use the read-only Files presentation",
                        descendants(content).none { it is EditText })
                }
            }
        } finally { instrumentation.runOnMainSync { filesActivity?.finish() } }

        val pane = runBlocking {
            live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
        }
        assertTrue("Copy and confirmed-file navigation must retain the exact live pane actor", live.repository.isCurrent(live.actor))
        assertTrue("Copy and confirmed-file navigation must leave the actual shell readable", pane is ApiResult.Success)
        assertEquals("Copy and Files navigation must leave the real terminal output and revision unchanged",
            readOnlyBaseline,
            (pane as ApiResult.Success).value.pane)
        val afterChanges = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("Mirror and Files reads must leave the repository readable", afterChanges is ApiResult.Success)
        assertEquals("Mirror copying and read-only printed-path navigation must not change Git state",
            (beforeChanges as ApiResult.Success).value.state, (afterChanges as ApiResult.Success).value.state)
        val mirror = TerminalPlainText.strip((pane as ApiResult.Success).value.pane.text)
        assertTrue("The exact native shell's actual output must retain the copy marker", marker in mirror)
        assertTrue("The exact native shell's actual output must retain the printed file reference", printedPath in mirror)
    }

    @Test fun liveLoadOlderScrollbackPreservesTheVisibleMirrorLine() = withLive { live ->
        // A bounded harmless printf creates enough real terminal scrollback to expose the
        // production Load older action. It writes no files and uses no fixture/test API.
        val command = buildString {
            append("printf '%s\\n'")
            repeat(650) { index -> append(" 'SCROLL_").append(index.toString().padStart(4, '0')).append("'") }
        }
        val target = ChangesTarget.Pane(live.address.paneId)
        val beforeChanges = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
        assertTrue("Repository state before scrollback read must be available", beforeChanges is ApiResult.Success)
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
            await(scenario, "native scrollback reader admission") { activity ->
                activity.findViewById<View>(R.id.send_button).isEnabled &&
                    activity.findViewById<EditText>(R.id.reply_input).isEnabled
            }
            onView(withId(R.id.reply_input)).perform(replaceText(command))
            onView(withId(R.id.send_button)).perform(click())
            await(scenario, "actual terminal output reaches the bounded initial window") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                state.pane?.text?.contains("SCROLL_0649") == true && state.loadedLines >= 600
            }
            val beforeReadOnlyGrowth = runBlocking {
                live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
            }
            assertTrue("The exact shell mirror must remain readable before loading older rows", beforeReadOnlyGrowth is ApiResult.Success)

            var anchor = ""
            var anchorY = 0
            scenario.onActivity { activity ->
                val viewModel = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java]
                val state = viewModel.state.value
                val summary = state.panes.singleOrNull {
                    it.paneId == live.address.paneId && it.host == live.address.scope.host && it.session == live.address.scope.session
                }
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                val scroll = activity.findViewById<android.widget.ScrollView>(R.id.terminal_scroll)
                val reportedDepth = listOfNotNull(summary?.readableLines, state.pane?.readableLines).maxOrNull() ?: 0
                val backendSupportsOlder = reportedDepth > state.requestedLines &&
                    state.mux?.supports("gridScrollback") != false && PaneScrollbackWindow.maxLines(state.mux) > state.requestedLines
                assumeTrue("The supplied shell backend has no deeper scrollback window to accept", backendSupportsOlder)
                val button = activity.findViewById<View>(R.id.pane_buffer_action)
                assertTrue("A backend-reported deeper scrollback window must expose the native Load older control",
                    button.isShown && button.isEnabled)
                assertEquals("The visible control must load terminal rows, not open agent history",
                    activity.getString(R.string.pane_scrollback_load_older), (button as TextView).text.toString())
                scroll.scrollTo(0, 0)
                val layout = requireNotNull(mirror.layout)
                val text = mirror.text.toString()
                val location = IntArray(2).also(mirror::getLocationOnScreen)
                val scrollLocation = IntArray(2).also(scroll::getLocationOnScreen)
                for (line in 0 until layout.lineCount) {
                    val start = layout.getLineStart(line)
                    val end = layout.getLineEnd(line).coerceAtMost(text.length)
                    val candidate = text.substring(start, end).trim()
                    if (candidate.matches(Regex("SCROLL_[0-9]{4}"))) {
                        val top = location[1] + mirror.paddingTop + layout.getLineTop(line) - mirror.scrollY
                        val bottom = location[1] + mirror.paddingTop + layout.getLineBottom(line) - mirror.scrollY
                        if (bottom > scrollLocation[1] && top < scrollLocation[1] + scroll.height) {
                            anchor = candidate
                            anchorY = top
                            break
                        }
                    }
                }
                assertTrue("Initial 600-line mirror window must contain a visible unique scrollback row", anchor.isNotEmpty())
                assertTrue("The native Load older control must be available for this readable pane", button.performClick())
            }
            await(scenario, "the native read window grows while the mirror viewport remains anchored") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                state.loadedLines > 600 && !state.loadingOlder && state.pane?.text?.contains(anchor) == true
            }
            scenario.onActivity { activity ->
                val mirror = activity.findViewById<TextView>(R.id.terminal_text)
                val lines = mirror.text.toString().lineSequence().toList()
                val line = lines.indexOfFirst { it.trim() == anchor }
                assertTrue("The preserved scrollback anchor must remain in the expanded mirror", line >= 0)
                val layout = requireNotNull(mirror.layout)
                val offset = lines.take(line).sumOf { it.length + 1 }
                val lineIndex = layout.getLineForOffset(offset.coerceAtMost(mirror.text.length))
                val location = IntArray(2).also(mirror::getLocationOnScreen)
                val restoredY = location[1] + mirror.paddingTop + layout.getLineTop(lineIndex) - mirror.scrollY
                assertTrue("Loading older rows must preserve the visible mirror row within one line-height",
                    kotlin.math.abs(restoredY - anchorY) <= mirror.lineHeight)
                assertTrue("Scrollback expansion must retain the original live pane actor", live.repository.isCurrent(live.actor))
            }
            val afterReadOnlyGrowth = runBlocking {
                live.repository.readPane(live.address, conditional = false, markSeen = false, expectedContext = live.actor)
            }
            assertTrue("The exact shell mirror must remain readable after loading older rows", afterReadOnlyGrowth is ApiResult.Success)
            assertEquals("Reading older scrollback must preserve terminal contents and revision",
                (beforeReadOnlyGrowth as ApiResult.Success).value.pane,
                (afterReadOnlyGrowth as ApiResult.Success).value.pane)
            val target = ChangesTarget.Pane(live.address.paneId)
            val repoAfter = runBlocking { live.repository.changes(target, live.address.scope, ChangesLookup(), live.actor) }
            assertTrue("Scrollback reads must leave the fixture Git repository readable", repoAfter is ApiResult.Success)
            assertEquals("Loading deeper terminal rows must leave the repository's Git state unchanged",
                (beforeChanges as ApiResult.Success).value.state, (repoAfter as ApiResult.Success).value.state)
            assertTrue("Scrollback reads must retain the exact live pane actor", live.repository.isCurrent(live.actor))
        }
    }

    private fun paneIntent(live: Live) = Intent(live.app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, live.address.paneId).putExtra(PaneActivity.EXTRA_AGENT, "shell")
        .putExtra(PaneActivity.EXTRA_HOST, live.address.scope.host).putExtra(PaneActivity.EXTRA_SESSION, live.address.scope.session)
        .putExtra(PaneActivity.EXTRA_TITLE, "Disposable stock acceptance shell")

    private fun awaitFiles(checkpoint: String, ready: (FilesActivity) -> Boolean): FilesActivity {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            var matched: FilesActivity? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<FilesActivity>().singleOrNull()
                if (activity != null && ready(activity)) matched = activity
            }
            matched?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical native checkpoint timed out: $checkpoint")
    }

    private fun awaitChanges(checkpoint: String, ready: (ChangesActivity) -> Boolean): ChangesActivity {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            var matched: ChangesActivity? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ChangesActivity>().singleOrNull()
                if (activity != null && ready(activity)) matched = activity
            }
            matched?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical native checkpoint timed out: $checkpoint")
    }

    private fun <T : Activity> await(scenario: ActivityScenario<T>, checkpoint: String, ready: (T) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { matched = ready(it) }
            if (matched) return
            SystemClock.sleep(100)
        }
        fail("Physical native checkpoint timed out: $checkpoint")
    }
    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }
    private fun visibleText(root: View) = descendants(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun fileRow(activity: FilesActivity, name: String): TextView? = descendants(activity.findViewById(R.id.files_list))
        .filterIsInstance<TextView>().singleOrNull { it.isShown && it.isClickable && it.contentDescription?.toString() == activity.getString(R.string.files_open_file, name) }
    private fun changeButton(activity: ChangesActivity): TextView? = descendants(activity.findViewById(R.id.changes_content))
        .filterIsInstance<TextView>().singleOrNull { it.isShown && it.isClickable && it.text.toString().contains("  $TEXT  ") }
    private fun changeAction(activity: ChangesActivity, label: String): TextView? = descendants(activity.findViewById(R.id.changes_content))
        .filterIsInstance<TextView>().singleOrNull { it.isShown && it.isClickable && it.text.toString() == label }
    private fun clickableSpan(view: TextView, value: String): ClickableSpan? {
        val start = view.text.toString().indexOf(value)
        if (start < 0) return null
        val spanned = view.text as? Spanned ?: return null
        return spanned.getSpans(start, start + value.length, ClickableSpan::class.java)
            ?.firstOrNull { spanned.getSpanStart(it) <= start && spanned.getSpanEnd(it) >= start + value.length }
    }

    private fun longPressText(token: String): ViewAction = textGesture(token, longPress = true)
    private fun tapPrintedPath(path: String): ViewAction = textGesture(path, longPress = false)

    /** Inject an ordinary touch at the requested text span so this remains a native UI exercise. */
    private fun textGesture(token: String, longPress: Boolean): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = any(View::class.java)
        override fun getDescription(): String = if (longPress) "Long press native mirror text $token" else "Tap confirmed native mirror path $token"
        override fun perform(uiController: UiController, view: View) {
            val textView = view as? TextView ?: throw AssertionError("Mirror gesture target must be a native TextView")
            val text = textView.text
            val start = text.toString().indexOf(token)
            if (start < 0) throw AssertionError("Requested text is absent from the native mirror: $token")
            if (!longPress && clickableSpan(textView, token) == null) {
                throw AssertionError("Only a currently confirmed Files reference may be opened: $token")
            }
            val layout = requireNotNull(textView.layout) { "Native mirror text must be laid out before the touch" }
            val offset = start + token.length / 2
            val line = layout.getLineForOffset(offset)
            val x = textView.compoundPaddingLeft + layout.getPrimaryHorizontal(offset) - textView.scrollX
            val y = textView.compoundPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f - textView.scrollY
            if (x < 0f || x >= textView.width || y < 0f || y >= textView.height) {
                throw AssertionError("Requested native text point is outside the TextView; refusing to touch a different row: $token")
            }
            val viewPosition = IntArray(2).also(textView::getLocationOnScreen)
            val screenX = (viewPosition[0] + x).toInt()
            val screenY = (viewPosition[1] + y).toInt()
            val visible = Rect()
            if (!textView.getGlobalVisibleRect(visible) || !visible.contains(screenX, screenY)) {
                throw AssertionError("Requested native text point is not actually visible; refusing to touch a clipped row: $token")
            }
            val decor = textView.rootView
            val insets = ViewCompat.getRootWindowInsets(decor)
                ?: throw AssertionError("Native mirror gesture needs the live window's actual system insets")
            val safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val decorPosition = IntArray(2).also(decor::getLocationOnScreen)
            val safe = Rect(decorPosition[0] + safeInsets.left, decorPosition[1] + safeInsets.top,
                decorPosition[0] + decor.width - safeInsets.right, decorPosition[1] + decor.height - safeInsets.bottom)
            if (!safe.contains(screenX, screenY)) {
                throw AssertionError("Requested native text point is outside the real system-safe content area: $token")
            }
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            try {
                textView.dispatchTouchEvent(down)
                if (longPress) uiController.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
                try { textView.dispatchTouchEvent(up) } finally { up.recycle() }
            } finally { down.recycle() }
        }
    }

    /** A handler invocation cannot prove the physical tap survives edge-to-edge system bars. */
    private fun assertTouchableInsideSystemInsets(activity: Activity, viewId: Int) {
        val button = activity.findViewById<View>(viewId)
        assertTrue("Native Back must be shown, enabled, and clickable", button.isShown && button.isEnabled && button.isClickable)
        val decor = activity.window.decorView
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor)) { "Physical window must have actual system insets" }
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val windowPosition = IntArray(2).also(decor::getLocationOnScreen)
        val safe = Rect(windowPosition[0] + insets.left, windowPosition[1] + insets.top,
            windowPosition[0] + decor.width - insets.right, windowPosition[1] + decor.height - insets.bottom)
        val position = IntArray(2).also(button::getLocationOnScreen)
        val centerX = position[0] + button.width / 2
        val centerY = position[1] + button.height / 2
        val visible = Rect()
        assertTrue("Native Back tap must be visible in the physical window", button.getGlobalVisibleRect(visible) && visible.contains(centerX, centerY))
        assertTrue("Native Back tap must be inside actual system-bar/cutout insets", safe.contains(centerX, centerY))
    }

    private companion object {
        const val TIMEOUT = 30_000L
        const val TEXT = "acceptance.txt"
        const val IMAGE = "acceptance.png"
        const val BASE = "COLLIE_FIXTURE_BASE 漢字 e\u0301 👨‍💻"
        const val CHANGE = "COLLIE_FIXTURE_CHANGE λ"
    }
}
