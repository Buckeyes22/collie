package com.lateapex.collie.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryTurnRendererTest {
    @Test
    fun rendersAUserTurnWithCardBackgroundAndProse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val turn = HistoryPresentation.turn(
            TranscriptEntry("u1", "2026-09-10T00:00:00Z", "user", listOf(TranscriptPart(kind = "text", text = "hello"))),
            agent = "claude", resources = context.resources,
        )
        val view = HistoryTurnRenderer(context, "claude", mutableSetOf(), { _, _ -> }).turnView(turn, showHeader = true)
        val content = (view as ViewGroup).getChildAt(0) as ViewGroup
        assertNotNull(content.background)
        val texts = generateSequence(0) { it + 1 }.take(content.childCount).map { content.getChildAt(it) }
            .filterIsInstance<ViewGroup>().flatMap { g -> (0 until g.childCount).map { g.getChildAt(it) } }
            .filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(texts.any { it.contains("hello") })
    }
}
