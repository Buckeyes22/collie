package com.lateapex.collie.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import com.lateapex.collie.network.TranscriptResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptBodyViewTest {
    @Test
    fun anEmptyTranscriptShowsTheDeclaredQueueActionAndKeepsAttemptDisclosureVisible() {
        val context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Collie,
        )
        val view = TranscriptBodyView(context)
        val action = QueuedSendNow(
            generation = 7,
            revision = 12,
            queuedDigest = "a".repeat(64),
            occupant = "b".repeat(64),
            queued = listOf("next message"),
            keys = listOf("ctrl+Enter"),
        )
        var tapped: QueuedSendNow? = null
        view.onSendQueuedNow = { tapped = it }

        view.bind("claude", emptyList(), hasMore = false, queued = action.queued, sendNow = action, sendNowEnabled = true)

        val button = view.findViewById<android.widget.Button>(R.id.transcript_send_queued_now)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(button.isEnabled)
        button.performClick()
        assertSame(action, tapped)

        view.bind(
            "claude", emptyList(), hasMore = false, queued = action.queued, sendNow = action,
            sendNowEnabled = false, sendNowAttempted = true,
        )
        assertFalse(button.isEnabled)
        assertEquals(context.getString(R.string.pane_transcript_send_check_queue), button.text.toString())
    }

    @Test fun replacingTranscriptTurnsKeepsQueuedRowsAndUsesOnlyTheCurrentSendAction() {
        val context = android.view.ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Collie)
        val view = TranscriptBodyView(context)
        val old = QueuedSendNow(7, 12, "a".repeat(64), "b".repeat(64), listOf("pending owned queue"), listOf("ctrl+Enter"))
        val current = old.copy(revision = 13)
        val taps = mutableListOf<QueuedSendNow>()
        view.onSendQueuedNow = { taps += it }
        view.bind("claude", listOf(entry(0)), true, old.queued, sendNow = old, sendNowEnabled = true)
        val oldTurn = view.turnViewForTest("e0")!!
        val button = view.findViewById<android.widget.Button>(R.id.transcript_send_queued_now)
        view.bind("claude", listOf(entry(1)), true, current.queued, sendNow = current, sendNowEnabled = true)
        assertEquals(null, oldTurn.parent)
        assertSame("A replaced turn keeps the reachable queue action", button, view.findViewById(R.id.transcript_send_queued_now))
        fun textNodes(node: View): List<String> = when (node) {
            is android.widget.TextView -> listOf(node.text.toString())
            is ViewGroup -> (0 until node.childCount).flatMap { textNodes(node.getChildAt(it)) }
            else -> emptyList()
        }
        assertTrue("A transcript reset must retain the visible queued message", textNodes(view).any { it.contains("pending owned queue") })
        assertTrue(button.isEnabled)
        button.performClick()
        assertEquals(1, taps.size)
        assertSame("A delayed retained button cannot dispatch its old recipe", current, taps.single())
    }

    @Test
    fun bindAddsOneViewPerUuidAndReusesOnRebind() {
        val context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Collie,
        )
        val view = TranscriptBodyView(context)
        val a = TranscriptEntry("a", "2026-09-10T00:00:00Z", "user", listOf(TranscriptPart("text", text = "hi")))
        val b = TranscriptEntry("b", "2026-09-10T00:00:01Z", "assistant", listOf(TranscriptPart("text", text = "yo")))
        view.bind("claude", listOf(a), hasMore = false)
        val first = view.turnViewForTest("a")
        view.bind("claude", listOf(a, b), hasMore = true)
        assertSame(first, view.turnViewForTest("a"))
        assertEquals(2, view.turnCountForTest())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.transcript_load_older).visibility)
    }

    @Test fun onlyChangedTurnBatchesResetDiscoveryAndAllRetainedTurnsAreRechecked() {
        val context = android.view.ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Collie)
        val view = TranscriptBodyView(context)
        var batches = 0
        view.onFileReferenceBatchStarted = { batches++ }
        val a = entry(0)
        view.bind("claude", listOf(a), false)
        val retained = view.turnViewForTest(a.uuid)
        assertEquals(1, batches)
        view.bind("claude", listOf(a), true, listOf("queue only"))
        assertEquals("supporting/queued state does not reset a retained batch", 1, batches)
        assertSame(retained, view.turnViewForTest(a.uuid))
        view.bind("claude", listOf(a, entry(1)), true)
        assertEquals("an actual added turn starts a new bounded batch", 2, batches)
        assertSame(retained, view.turnViewForTest(a.uuid))
        view.bind("claude", listOf(a.copy(parts = listOf(TranscriptPart("text", text = "changed"))), entry(1)), true)
        assertEquals("an actual replaced turn starts a new bounded batch", 3, batches)
    }

    @Test fun drawnTranscriptPathTextIsReportedWithTheExactAcceptedReadActor() {
        val context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Collie,
        )
        val app = ApplicationProvider.getApplicationContext<com.lateapex.collie.CollieApplication>()
        val initialConnection = com.lateapex.collie.domain.Connection(com.lateapex.collie.domain.CollieOrigin("https://transcript-files.fixture/"), "phone", "token")
        val repository = com.lateapex.collie.data.CollieRepository(
            app.container.api,
            object : com.lateapex.collie.data.ConnectionStore {
                override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(initialConnection)
                override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
                override fun clear() { connection.value = null }
            },
            app.container.originValidator,
        )
        val scope = com.lateapex.collie.domain.Scope("peer", "work")
        repository.selectSubject(scope, "w1:p1")
        val actor = requireNotNull(repository.readContext(scope, "w1:p1"))
        val view = TranscriptBodyView(context)
        var delivered: Triple<android.widget.TextView, CharSequence, com.lateapex.collie.data.ScopedReadContext>? = null
        view.onDisplayedFileReferences = { textView, text, readActor -> delivered = Triple(textView, text, readActor) }
        val entry = TranscriptEntry("path-turn", "2026-09-10T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = "Open src/Main.kt:42")))
        view.bind("claude", listOf(entry), hasMore = false, readContext = actor)
        assertEquals("the callback comes from a visible transcript text node", "Open src/Main.kt:42", delivered?.second?.toString())
        assertSame("existence confirmation receives the exact actor that admitted this transcript", actor, delivered?.third)
        assertTrue(delivered?.first?.text?.contains("src/Main.kt") == true)
        val reported = mutableListOf<Pair<String, com.lateapex.collie.data.ScopedReadContext>>()
        view.onDisplayedFileReferences = { _, text, readActor -> reported += text.toString() to readActor }
        view.bind("claude", listOf(entry, entry.copy(uuid = "another-path", parts = listOf(TranscriptPart("text", text = "docs/other.kt")))), false, readContext = actor)
        assertEquals("the new batch rechecks retained and new paths", setOf("Open src/Main.kt:42", "docs/other.kt"), reported.map { it.first }.toSet())
        assertTrue(reported.all { it.second === actor })
        view.onDisplayedFileReferences = { textView, text, readActor -> delivered = Triple(textView, text, readActor) }
        for ((index, path) in listOf("/srv/project/src/Main.kt:4", "C:\\Work Tree\\src\\Main.kt:9").withIndex()) {
            delivered = null
            view.bind("claude", listOf(TranscriptEntry("absolute-$index", "2026-09-10T00:00:00Z", "assistant",
                listOf(TranscriptPart("text", text = path)))), hasMore = false, readContext = actor)
            assertEquals("rootless absolute evidence reaches discovery, not navigation", path, delivered?.second?.toString())
            assertSame(actor, delivered?.third)
            assertTrue("discovery still needs authoritative root evidence", PrintedPathLinks.extract(path).isEmpty())
        }
    }

    @Test
    fun olderEntriesPrependAboveTheExistingTurns() {
        val context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Collie,
        )
        val view = TranscriptBodyView(context)
        val a = TranscriptEntry("a", "2026-09-10T00:00:00Z", "user", listOf(TranscriptPart("text", text = "hi")))
        val b = TranscriptEntry("b", "2026-09-10T00:00:01Z", "assistant", listOf(TranscriptPart("text", text = "yo")))
        view.bind("claude", listOf(b), hasMore = true)
        val bView = view.turnViewForTest("b")
        view.bind("claude", listOf(a, b), hasMore = true)

        val container = (view.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        assertEquals(5, container.childCount)
        assertEquals(R.id.transcript_load_older, container.getChildAt(0).id)
        assertSame(view.turnViewForTest("a"), container.getChildAt(1))
        assertSame(bView, container.getChildAt(2))
        assertTrue(container.getChildAt(3) is android.widget.LinearLayout)
        assertSame(view.findViewById<View>(R.id.transcript_send_queued_now), container.getChildAt(4))
    }

    @Test
    fun appendingNewTurnsWhileScrolledUpDoesNotMoveTheReader() {
        // S25 Ultra, 2026-09-10: scrolled to the top, every poll that appended a turn shoved the
        // view down by the new turn's height and "Load older" slid out of reach.
        val view = laidOutBody()
        val entries = (0 until 60).map(::entry)
        view.bind("claude", entries, hasMore = true)
        layout(view)
        val scroll = view.getChildAt(0) as android.widget.ScrollView
        scroll.scrollTo(0, 0)
        val anchor = view.turnViewForTest("e2")!!
        val offset = anchor.top - scroll.scrollY

        view.bind("claude", entries + entry(60) + entry(61), hasMore = true)
        layout(view)

        assertEquals("scrollY=${scroll.scrollY}", offset, anchor.top - scroll.scrollY)
        assertEquals(0, scroll.scrollY)
    }

    @Test
    fun loadingOlderTurnsKeepsTheReadersPlace() {
        val view = laidOutBody()
        val entries = (10 until 40).map(::entry)
        view.bind("claude", entries, hasMore = true)
        layout(view)
        val scroll = view.getChildAt(0) as android.widget.ScrollView
        val anchor = view.turnViewForTest("e12")!!
        scroll.scrollTo(0, anchor.top)
        val offset = anchor.top - scroll.scrollY

        view.bind("claude", (0 until 10).map(::entry) + entries, hasMore = true)
        layout(view)

        assertEquals(offset, anchor.top - scroll.scrollY)
    }

    @Test
    fun focusLandingOnTheOldestTurnDoesNotScrollToTheTop() {
        // Opening a busy agent's pane: the mirror shows first and holds focus, then the
        // transcript replaces it and Android hands focus to the first selectable turn, the
        // oldest. The scroll view revealed it and the reader landed at the top of the session
        // (S25 Ultra, 2026-09-11).
        val view = laidOutBody()
        view.bind("claude", (0 until 60).map(::entry), hasMore = true)
        layout(view)
        val scroll = view.getChildAt(0) as android.widget.ScrollView
        val bottom = scroll.scrollY
        assertTrue("starts at the bottom", bottom > 0)
        val oldest = generateSequence(listOf<View>(view.turnViewForTest("e0")!!)) { level ->
            level.filterIsInstance<ViewGroup>().flatMap { g -> (0 until g.childCount).map(g::getChildAt) }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<android.widget.TextView>().first { it.isTextSelectable }
        oldest.requestFocus()
        layout(view)
        assertEquals(bottom, scroll.scrollY)
    }

    @Test
    fun theFirstTranscriptShownAfterTheMirrorLandsOnTheNewestTurn() {
        // S25 Ultra, 2026-09-18: opening a busy pane, the body is hidden while the transcript is
        // pending, then shown and bound in the same render. It landed on "Load older" at the top.
        val view = laidOutBody()
        val placements = mutableListOf<Map<String, Any?>>()
        view.onPlaced = { placements += it }
        view.visibility = View.GONE
        layout(view)

        view.visibility = View.VISIBLE
        view.bind("claude", (0 until 60).map(::entry), hasMore = true)
        layout(view)
        view.bind("claude", (0 until 60).map(::entry), hasMore = true)
        layout(view)

        val scroll = view.getChildAt(0) as android.widget.ScrollView
        val content = scroll.getChildAt(0).height
        assertTrue("content $content taller than the viewport", content > scroll.height)
        assertEquals(content - scroll.height, scroll.scrollY)
        // Placing from inside the scroll view's own first layout lost the race on a phone: its
        // first-layout pass wrote the offset back to 0 with no scroll callback. Place only once
        // it has been laid out.
        assertEquals(true, placements.first()["viewLaidOut"])
    }

    @Test
    fun liveUpsertsRebuildChangedTurnsAndKeepUnchangedCards() {
        val view = laidOutBody()
        val first = entry(0)
        val partial = entry(1)
        view.bind("claude", listOf(first, partial), false)
        val firstView = view.turnViewForTest(first.uuid)
        val partialView = view.turnViewForTest(partial.uuid)
        val finished = partial.copy(parts = listOf(TranscriptPart("text", text = "finished answer")))
        view.bind("claude", listOf(first, finished), false, listOf("next message"))
        assertSame(firstView, view.turnViewForTest(first.uuid))
        assertNotSame(partialView, view.turnViewForTest(partial.uuid))
        assertEquals(2, view.turnCountForTest())
        assertTrue(texts(view).any { it.contains("finished answer") })
        assertTrue(texts(view).any { it == "Queued: next message" })
        view.bind("claude", listOf(first, finished), false, emptyList())
        assertTrue(texts(view).none { it.contains("Queued:") })
    }

    @Test
    fun sameUuidToolRevisionReplacesPendingContentAndRetainsDisclosure() {
        val view = laidOutBody()
        val pendingPart = TranscriptPart(
            kind = "tool", name = "execute", summary = "Waiting for result", id = "call-1",
            call = Json.parseToJsonElement("""{"kind":"execute","command":"dangerous-looking but inert"}""") as JsonObject,
        )
        val pending = TranscriptEntry("live-tool", "2026-09-10T00:00:00Z", "assistant", listOf(pendingPart), seq = 1)
        view.bind("claude", listOf(pending), hasMore = false)

        var row = view.turnViewForTest("live-tool")!!
        val disclosure = descendants(row).first {
            it.isClickable && it.contentDescription?.toString().orEmpty().contains("collapsed")
        }
        disclosure.performClick()
        row = view.turnViewForTest("live-tool")!!
        val expandedPendingRow = row
        assertTrue(descendants(row).any { it.contentDescription?.toString().orEmpty().contains("expanded") })
        assertTrue(texts(row).any { it.contains("dangerous-looking but inert") })

        val completed = pending.copy(
            seq = 2,
            parts = listOf(pendingPart.copy(
                summary = "Completed successfully",
                result = TranscriptResult("Result revision two"),
            )),
        )
        view.bind("claude", listOf(completed), hasMore = false)
        row = view.turnViewForTest("live-tool")!!
        assertNotSame(expandedPendingRow, row)
        val completedRow = row
        assertEquals(1, view.turnCountForTest())
        assertTrue(descendants(row).any { it.contentDescription?.toString().orEmpty().contains("expanded") })
        assertTrue(texts(row).any { it.contains("Completed") })
        assertTrue(texts(row).any { it.contains("Result revision two") })
        assertTrue(textViews(row).any { it.text.contains("Result revision two") && it.isShown })
        assertTrue(texts(row).none { it.contains("Waiting for result") })

        val deniedAndTruncated = completed.copy(
            seq = 3,
            parts = listOf(completed.parts.single().copy(
                summary = "Denied with bounded output",
                result = TranscriptResult("Operator policy denied this action", truncated = true, isError = true, denied = true),
            )),
        )
        view.bind("claude", listOf(deniedAndTruncated), hasMore = false)
        row = view.turnViewForTest("live-tool")!!
        assertNotSame(completedRow, row)
        val updatedTexts = texts(row)

        assertEquals(1, view.turnCountForTest())
        assertTrue(descendants(row).any { it.contentDescription?.toString().orEmpty().contains("expanded") })
        assertTrue(updatedTexts.any { it.contains("Denied with bounded output") })
        assertTrue(updatedTexts.any { it.contains("Denied by the operator") })
        assertTrue(updatedTexts.any { it.contains("Operator policy denied this action") })
        assertTrue(updatedTexts.any { it.contains("Output truncated") })
        assertTrue(textViews(row).any { it.text.contains("Operator policy denied this action") && it.isShown })
        assertTrue(updatedTexts.none { it.contains("Waiting for result") || it.contains("Result revision two") })
    }

    private fun descendants(root: View): List<View> = if (root !is ViewGroup) listOf(root) else buildList {
        add(root)
        for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    private fun textViews(view: View): List<android.widget.TextView> = buildList {
        if (view is android.widget.TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(textViews(view.getChildAt(index)))
    }

    private fun texts(view: View): List<String> = buildList {
        if (view is android.widget.TextView) add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(texts(view.getChildAt(index)))
    }

    /** Hosted in a real window so posted work runs after layout, in the order a phone runs it. */
    private fun laidOutBody(): TranscriptBodyView {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = TranscriptBodyView(android.view.ContextThemeWrapper(activity, R.style.Theme_Collie))
        activity.setContentView(
            android.widget.FrameLayout(activity).apply {
                addView(view, android.widget.FrameLayout.LayoutParams(1080, 1600))
            },
        )
        layout(view)
        return view
    }

    @Suppress("UNUSED_PARAMETER")
    private fun layout(view: View) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
    }

    private fun entry(index: Int) = TranscriptEntry(
        "e$index",
        "2026-09-10T00:00:00Z",
        if (index % 2 == 0) "user" else "assistant",
        listOf(TranscriptPart("text", text = "turn $index with enough words to take a line or two of height")),
    )
}
