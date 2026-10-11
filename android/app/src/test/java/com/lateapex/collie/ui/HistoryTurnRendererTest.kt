package com.lateapex.collie.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import com.lateapex.collie.network.TranscriptResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryTurnRendererTest {
    @Test
    fun historicQuestionCardIsAccessibleAndItsOnlyClickExpandsReadOnlyDetails() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = TranscriptEntry(
            "u1", "2026-09-10T00:00:00Z", "assistant", listOf(
                TranscriptPart(
                    kind = "tool", name = "question", summary = "Which color?", id = "old-call",
                    call = Json.parseToJsonElement(
                        """{"kind":"question","name":"question","summary":"Which color?","questions":[{"header":"Color","question":"Pick one","multiple":false,"options":[{"label":"Blue","description":"Cool"},{"label":"Red"}]}]}""",
                    ) as JsonObject,
                    result = TranscriptResult("The user chose Blue", denied = true, isError = true),
                ),
            ),
        )
        var toggleCount = 0
        val turn = HistoryPresentation.turn(entry, "agent", context.resources)
        val root = HistoryTurnRenderer(context, "agent", mutableSetOf("u1" to 0), { _, _ -> toggleCount++ })
            .turnView(turn, showHeader = false)
        val texts = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
        val header = descendants(root).first { it.isClickable && it.contentDescription.toString().contains("expanded") }

        assertTrue(header.contentDescription.toString().contains("expanded"))
        val node = header.createAccessibilityNodeInfo()
        assertEquals(android.widget.Button::class.java.name, node.className.toString())
        assertTrue(node.isClickable)
        assertTrue(texts.any { it.contains("Pick one") })
        assertTrue(texts.any { it.contains("Blue") && it.contains("Cool") })
        assertTrue("Rendered text: $texts", texts.any { it.contains("old-call") })
        assertTrue(texts.any { it.contains("Denied by the operator") })
        header.performClick()
        assertEquals("historical card only changes disclosure state", 1, toggleCount)
    }

    @Test
    fun imageReferenceIsHandedToB04RendererOnlyAfterDisclosureOpens() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = TranscriptEntry(
            "u2", "2026-09-10T00:00:00Z", "assistant", listOf(
                TranscriptPart(kind = "tool", name = "view", summary = "image", result = TranscriptResult("caption", imageUrl = "https://example.test/image")),
            ),
        )
        var loads = 0
        val turn = HistoryPresentation.turn(entry, "agent", context.resources)

        HistoryTurnRenderer(context, "agent", mutableSetOf(), { _, _ -> }, renderToolImage = { loads++; null })
            .turnView(turn, showHeader = false)
        assertEquals(0, loads)
        HistoryTurnRenderer(context, "agent", mutableSetOf("u2" to 0), { _, _ -> }, renderToolImage = { loads++; null })
            .turnView(turn, showHeader = false)
        assertEquals(1, loads)
    }

    @Test
    fun commandAndDiffPayloadsUseSeparateSelectableMonospaceBlocks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = TranscriptEntry(
            "code-turn", "2026-09-10T00:00:00Z", "assistant", listOf(
                TranscriptPart(
                    kind = "tool", name = "execute", summary = "Run formatter",
                    call = Json.parseToJsonElement("""{"kind":"execute","description":"Formatting sources","command":"./gradlew ktfmtFormat","exitCode":0}""") as JsonObject,
                ),
                TranscriptPart(
                    kind = "tool", name = "edit", summary = "Update source",
                    call = Json.parseToJsonElement("""{"kind":"edit","path":"src/Main.kt","added":1,"removed":1,"diff":[{"header":"@@ -1 +1 @@","lines":["-old()","+new()"]}]}""") as JsonObject,
                ),
            ),
        )
        val turn = HistoryPresentation.turn(entry, "agent", context.resources)
        val root = HistoryTurnRenderer(context, "agent", mutableSetOf("code-turn" to 0, "code-turn" to 1), { _, _ -> })
            .turnView(turn, showHeader = false)
        val code = descendants(root).filterIsInstance<HistoryMonospaceTextView>()
            .filter { it.tag == HistoryTurnRenderer.TOOL_CODE_BLOCK_TAG }
        val text = descendants(root).filterIsInstance<TextView>()

        assertEquals(listOf("./gradlew ktfmtFormat", "@@ -1 +1 @@\n-old()\n+new()"), code.map { it.text.toString() })
        assertTrue(code.all {
            it.tag == HistoryTurnRenderer.TOOL_CODE_BLOCK_TAG && it.isTextSelectable && it.visibility == View.VISIBLE
        })
        assertTrue(code.all { it.typeface == android.graphics.Typeface.MONOSPACE })
        assertTrue(text.any { it !is HistoryMonospaceTextView && it.text.contains("Formatting sources") })
        assertTrue(text.any { it !is HistoryMonospaceTextView && it.text.contains("Edited src/Main.kt") })
    }

    @Test
    fun boundedUnknownCallShowsItsProjectionTruncationLabel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = TranscriptEntry(
            "bounded-call", "2026-09-10T00:00:00Z", "assistant", listOf(
                TranscriptPart(
                    kind = "tool", name = "future-tool", summary = "Unknown call",
                    call = Json.parseToJsonElement("{\"kind\":\"future\",\"payload\":\"${"x".repeat(20_000)}\"}") as JsonObject,
                ),
            ),
        )
        val turn = HistoryPresentation.turn(entry, "agent", context.resources)
        val root = HistoryTurnRenderer(context, "agent", mutableSetOf("bounded-call" to 0), { _, _ -> })
            .turnView(turn, showHeader = false)
        val texts = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }

        assertTrue(texts.any { it.contains("Call details truncated") })
        assertTrue(texts.any { it.contains("[truncated]") || it.contains("future") })
    }

    @Test
    fun imageOnlyToolResultUsesImageSeamWithoutAnEmptyTextBody() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val turn = HistoryTurn(
            uuid = "image-only",
            role = "assistant",
            roleLabel = "Agent",
            day = "",
            time = "",
            parts = listOf(
                HistoryPart.Tool(
                    name = "view", summary = "Image result", callId = null, call = null, callTruncated = false,
                    result = null, resultStatus = ToolResultStatus.COMPLETED, resultTruncated = false,
                    resultImageUrl = "content://image-reference",
                ),
            ),
        )
        var loads = 0
        val root = HistoryTurnRenderer(
            context, "agent", mutableSetOf("image-only" to 0), { _, _ -> },
            renderToolImage = { loads++; TextView(context).apply { text = "image preview"; tag = "rendered-image-preview" } },
        ).turnView(turn, showHeader = false)
        val renderedText = descendants(root).filterIsInstance<TextView>()

        assertEquals(1, loads)
        assertTrue(renderedText.any { it.text.toString() == "Completed" })
        assertTrue(renderedText.none { it.text.isEmpty() })
        assertTrue(descendants(root).any { it.tag == "rendered-image-preview" })
    }

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

    private fun descendants(root: View): List<View> = if (root !is ViewGroup) listOf(root) else buildList {
        add(root)
        for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }
}
