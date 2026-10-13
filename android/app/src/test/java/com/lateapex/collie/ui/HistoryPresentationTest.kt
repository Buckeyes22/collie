package com.lateapex.collie.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import com.lateapex.collie.network.TranscriptResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class HistoryPresentationTest {
    @Test
    fun preservesCallIdentityTruncationAndImageReferenceForRendererOwner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = TranscriptEntry(
            "u1", "2026-01-02T12:34:00Z", "assistant", listOf(
                TranscriptPart(
                    kind = "tool", name = "read", summary = "file", id = "call-7", truncated = true,
                    call = Json.parseToJsonElement("""{"kind":"read","path":"src/Main.kt","range":[1,20]}""") as JsonObject,
                    result = TranscriptResult("source", imageUrl = "https://private.example/media/1"),
                ),
            ),
        )

        val tool = HistoryPresentation.turn(entry, null, context.resources).parts.single() as HistoryPart.Tool
        assertEquals("call-7", tool.callId)
        assertTrue(tool.callTruncated)
        assertEquals("src/Main.kt", (tool.call as ToolCallPresentation.Read).path)
        assertEquals("source", tool.result)
        assertEquals("https://private.example/media/1", tool.resultImageUrl)
    }

    @Test
    fun datesAndTimesStayEnglishWhenTheDeviceLocaleIsNotEnglish() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val entry = TranscriptEntry("u1", "2026-01-02T12:34:00Z", "user", emptyList())
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val english = HistoryPresentation.turn(entry, null, resources)
            Locale.setDefault(Locale.CHINESE)
            val chineseDevice = HistoryPresentation.turn(entry, null, resources)
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkishDevice = HistoryPresentation.turn(entry, null, resources)

            assertEquals(english.day, chineseDevice.day)
            assertEquals(english.time, chineseDevice.time)
            assertEquals(english.day, turkishDevice.day)
            assertEquals(english.time, turkishDevice.time)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun mapsTranscriptSemanticsWithoutInterpretingItsText() {
        val turn = HistoryPresentation.turn(
            TranscriptEntry(
                uuid = "u1",
                ts = "not-a-time",
                role = "assistant",
                parts = listOf(
                    TranscriptPart(kind = "thinking", text = "<b>literal</b>", truncated = true),
                    TranscriptPart(
                        kind = "tool",
                        name = "shell",
                        summary = "ran command",
                        result = TranscriptResult("rm -rf /", truncated = true, isError = true),
                    ),
                ),
            ),
            agent = "Claude",
            resources = ApplicationProvider.getApplicationContext<Context>().resources,
        )

        assertEquals("Claude", turn.roleLabel)
        assertEquals("", turn.time)
        val prose = turn.parts[0] as HistoryPart.Prose
        assertEquals("<b>literal</b>", prose.text)
        assertTrue(prose.thinking)
        assertTrue(prose.truncated)
        val tool = turn.parts[1] as HistoryPart.Tool
        assertEquals("rm -rf /", tool.result)
        assertEquals(ToolResultStatus.FAILED, tool.resultStatus)
        assertTrue(tool.resultTruncated)
    }

    @Test
    fun preservesImageOnlyPartsAndToolResultImagesInTheirOriginalPositions() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val blob = "/api/blobs/${"ab".repeat(32)}"
        val turn = HistoryPresentation.turn(
            TranscriptEntry(
                "images", "2026-01-02T12:34:00Z", "assistant",
                listOf(
                    TranscriptPart(kind = "image", url = blob, mimeType = "image/png"),
                    TranscriptPart(kind = "tool", name = "capture", result = TranscriptResult("captured", imageUrl = blob)),
                ),
            ),
            "Pi", resources,
        )
        assertEquals(2, turn.parts.size)
        val image = turn.parts[0] as HistoryPart.Image
        assertEquals(blob, image.reference)
        assertEquals("image/png", image.mimeType)
        val tool = turn.parts[1] as HistoryPart.Tool
        assertEquals("captured", tool.result)
        assertEquals(blob, tool.image?.reference)
        assertTrue(tool.image?.description?.contains("capture") == true)
    }

    @Test
    fun sharedHistoryAndLiveRendererCreatesCardsForJournalAndExpandedToolImages() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Collie)
        val blob = "/api/blobs/${"ab".repeat(32)}"
        val entry = TranscriptEntry(
            "images", "2026-01-02T12:34:00Z", "assistant",
            listOf(
                TranscriptPart(kind = "image", url = blob, mimeType = "image/png"),
                TranscriptPart(kind = "tool", name = "capture", result = TranscriptResult("captured", imageUrl = blob)),
            ),
        )
        val turn = HistoryPresentation.turn(entry, "agent", context.resources)
        val expanded = mutableSetOf("images" to 1)
        val view = HistoryTurnRenderer(context, "agent", expanded, { _, _ -> }).turnView(turn, showHeader = false)
        val cards = descendants(view).filterIsInstance<AuthenticatedImageView>()

        assertEquals("image parts and expanded tool results use the authenticated image card", 2, cards.size)
        assertTrue(cards.all { it.contentDescription.toString().contains("image", ignoreCase = true) })

        val live = TranscriptBodyView(context)
        live.bind("agent", listOf(entry), hasMore = false)
        assertEquals(
            "the pane transcript binds journal image parts through the same card renderer",
            1,
            descendants(live.turnViewForTest("images")!!).filterIsInstance<AuthenticatedImageView>().size,
        )
    }

    @Test
    fun expandedToolShowsRealTextAndImageChildrenAndImageOnlyResultsCanOpen() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Collie)
        val blob = "/api/blobs/${"ab".repeat(32)}"
        val entry = TranscriptEntry(
            "tool-results", "2026-01-02T12:34:00Z", "assistant",
            listOf(
                TranscriptPart(kind = "tool", name = "text-and-image", result = TranscriptResult("visible result", imageUrl = blob)),
                TranscriptPart(kind = "tool", name = "image-only", result = TranscriptResult(text = null, imageUrl = blob)),
            ),
        )
        val turn = HistoryPresentation.turn(entry, "agent", context.resources)
        val expanded = mutableSetOf<Pair<String, Int>>()
        fun render(): View = HistoryTurnRenderer(context, "agent", expanded, { uuid, index ->
            val key = uuid to index
            if (!expanded.add(key)) expanded.remove(key)
        }).turnView(turn, showHeader = false)

        var view = render()
        val headers = descendants(view).filter { it.contentDescription?.toString()?.contains("collapsed", ignoreCase = true) == true }
        assertEquals(2, headers.size)
        assertTrue("text-plus-image header is a real expansion control", headers[0].performClick())
        assertTrue("actual header click expands the textual result", "tool-results" to 0 in expanded)
        view = render()
        val shownText = descendants(view).filterIsInstance<TextView>().first { it.text.toString() == "visible result" }
        assertEquals("text child is visible after actual tool expansion", View.VISIBLE, shownText.visibility)
        val textAndImageCard = descendants(view).filterIsInstance<AuthenticatedImageView>().first()
        assertEquals(View.VISIBLE, textAndImageCard.visibility)

        val imageOnlyHeader = descendants(view).filter { it.contentDescription?.toString()?.contains("collapsed", ignoreCase = true) == true }.first()
        assertTrue("image-only result header remains clickable", imageOnlyHeader.performClick())
        assertTrue("image-only result expands from its actual header", "tool-results" to 1 in expanded)
        view = render()
        val imageOnlyCard = descendants(view).filterIsInstance<AuthenticatedImageView>().last()
        assertEquals(View.VISIBLE, imageOnlyCard.visibility)
        assertTrue("expanded image-only result has no invented text", descendants(view).filterIsInstance<TextView>().none { it.text.toString().isEmpty() })
    }

    @Test
    fun olderPageMergeRetainsImageOnlyTurnContent() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val blob = "/api/blobs/${"cd".repeat(32)}"
        val older = TranscriptEntry(
            "older-image", "2026-01-01T00:00:00Z", "assistant",
            listOf(TranscriptPart(kind = "image", url = blob, mimeType = "image/webp")),
        )

        val merged = HistoryPresentation.mergeOlder(current = emptyList(), older = listOf(older))
        val projected = HistoryPresentation.turn(merged.single(), "Pi", resources)

        assertEquals(blob, (projected.parts.single() as HistoryPart.Image).reference)
    }

    @Test
    fun olderPagesPrependAndDeduplicateByUuid() {
        fun entry(id: String) = TranscriptEntry(id, "", "user", emptyList())
        val merged = HistoryPresentation.mergeOlder(
            current = listOf(entry("b"), entry("c")),
            older = listOf(entry("a"), entry("b")),
        )
        assertEquals(listOf("a", "b", "c"), merged.map { it.uuid })
        assertFalse(merged.isEmpty())
    }

    private fun descendants(root: View): List<View> = if (root !is ViewGroup) listOf(root) else buildList {
        add(root)
        repeat(root.childCount) { addAll(descendants(root.getChildAt(it))) }
    }

    @Test
    fun userTurnNavigationIsRelativeToAnyFocusedTurnAndWraps() {
        fun entry(id: String, role: String) = TranscriptEntry(id, "", role, emptyList())
        val entries = listOf(
            entry("u1", "user"),
            entry("a1", "assistant"),
            entry("u2", "user"),
            entry("a2", "assistant"),
        )

        assertEquals("u2", HistoryPresentation.stepUserTurn(entries, "a1", 1))
        assertEquals("u1", HistoryPresentation.stepUserTurn(entries, "a1", -1))
        assertEquals("u1", HistoryPresentation.stepUserTurn(entries, "a2", 1))
        assertEquals("u2", HistoryPresentation.stepUserTurn(entries, "u1", -1))
    }

    @Test
    fun parsesTheCanonicalSafeMarkdownBlockAndInlineSubset() {
        val blocks = HistoryPresentation.parseMarkdown(
            """# Heading with **weight**

> quoted *text*

- one
- two

| Name | Value |
| :--- | ---: |
| alpha | `1` |

```kotlin
println("safe")
```
""",
        )

        assertTrue(blocks[0] is HistoryMarkdownBlock.Heading)
        assertTrue(blocks[1] is HistoryMarkdownBlock.Quote)
        assertTrue(blocks[2] is HistoryMarkdownBlock.ListBlock)
        val table = blocks[3] as HistoryMarkdownBlock.Table
        assertEquals(listOf(HistoryTableAlignment.LEFT, HistoryTableAlignment.RIGHT), table.alignments)
        assertEquals("Name | Value\nalpha | 1", HistoryPresentation.markdownPlain(table))
        assertEquals("println(\"safe\")", (blocks[4] as HistoryMarkdownBlock.Code).text)
    }

    @Test
    fun permitsOnlyExplicitWebAndMailLinksAndLeavesUnsafeMarkupLiteral() {
        assertEquals("https://colliepwa.dev/docs", HistoryPresentation.safeHref("https://colliepwa.dev/docs"))
        assertEquals("mailto:hello@example.test", HistoryPresentation.safeHref("mailto:hello@example.test"))
        assertNull(HistoryPresentation.safeHref("javascript:alert(1)"))
        assertNull(HistoryPresentation.safeHref("data:text/html,bad"))
        assertNull(HistoryPresentation.safeHref("/relative"))

        val inline = HistoryPresentation.parseInline("[safe](https://example.test) [bad](javascript:alert)")
        assertTrue(inline.any { it is HistoryInline.Link && it.href == "https://example.test" })
        assertTrue(inline.any { it is HistoryInline.Text && "[bad](javascript:alert)" in it.text })
    }

    @Test
    fun searchesRenderedMarkdownBlocksAndToolHeadersButNotCollapsedToolResults() {
        val entries = listOf(
            TranscriptEntry(
                uuid = "u1",
                ts = "2026-01-01T00:00:00Z",
                role = "assistant",
                parts = listOf(
                    TranscriptPart(kind = "text", text = "# Needle heading\n\nplain needle"),
                    TranscriptPart(
                        kind = "tool",
                        name = "needle-tool",
                        summary = "summary",
                        result = TranscriptResult("hidden needle", truncated = false, isError = false),
                    ),
                ),
            ),
        )

        val hits = HistoryPresentation.search(entries, "needle")

        assertEquals(3, hits.size)
        assertEquals(
            setOf(HistorySearchRegion.PROSE, HistorySearchRegion.TOOL_HEADER),
            hits.map { it.key.region }.toSet(),
        )
        assertEquals(listOf(0, 1), hits.filter { it.key.region == HistorySearchRegion.PROSE }.map { it.key.blockIndex })
    }
}
