package com.lateapex.collie.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import com.lateapex.collie.network.TranscriptResult
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
        assertTrue(tool.resultIsError)
        assertTrue(tool.resultTruncated)
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
