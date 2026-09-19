package com.lateapex.collie.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptBodyViewTest {
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
        assertEquals(3, container.childCount)
        assertEquals(R.id.transcript_load_older, container.getChildAt(0).id)
        assertSame(view.turnViewForTest("a"), container.getChildAt(1))
        assertSame(bView, container.getChildAt(2))
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
