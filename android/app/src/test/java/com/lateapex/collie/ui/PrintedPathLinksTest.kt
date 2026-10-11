package com.lateapex.collie.ui

import android.text.Spanned
import android.text.style.ClickableSpan
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class PrintedPathLinksTest {
    @Test fun editorSuffixAndLiteralColonFilenameNeedExactFilesystemExistence() {
        val source = "src/Main.kt:12"
        val references = PrintedPathLinks.extract(source, "/repo")
        assertTrue("the editor interpretation preserves its source line",
            references.any { it.path.value == "src/Main.kt" && it.line == 12 })
        assertTrue("a legal literal colon filename remains a separate candidate",
            references.any { it.path.value == "src/Main.kt:12" && it.line == null })
        fun spans(existing: Set<String>, onOpen: (PrintedFileReference) -> Unit = {}) =
            (PrintedPathLinks.confirmedText(source, references, existing, onOpen) as Spanned)
                .getSpans(0, source.length, ClickableSpan::class.java)
        assertTrue("confirming both different files must leave the ambiguous text plain",
            spans(setOf("src/Main.kt", "src/Main.kt:12")).isEmpty())
        var opened: PrintedFileReference? = null
        val sourceSpans = spans(setOf("src/Main.kt")) { opened = it }
        assertEquals("an exact existing source file receives one native span", 1, sourceSpans.size)
        sourceSpans.single().onClick(android.widget.TextView(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        assertEquals("src/Main.kt", opened?.path?.value)
        assertEquals(12, opened?.line)
        val literalSpans = spans(setOf("src/Main.kt:12")) { opened = it }
        assertEquals("an exact existing colon filename also receives one native span", 1, literalSpans.size)
        literalSpans.single().onClick(android.widget.TextView(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        assertEquals("src/Main.kt:12", opened?.path?.value)
        assertEquals(null, opened?.line)
    }

    @Test fun repeatedConfirmationReplacesOnlyOwnedSpansAndRetirementPreservesFormatting() {
        val text = android.text.SpannableString("src/Main.kt:7 website")
        val url = android.text.style.URLSpan("https://example.test/")
        text.setSpan(url, 14, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val style = android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
        text.setSpan(style, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val refs = PrintedPathLinks.extract(text.toString())
        val first = PrintedPathLinks.confirmedText(text, refs, setOf("src/Main.kt")) { }
        val second = PrintedPathLinks.confirmedText(first, refs, setOf("src/Main.kt")) { }
        assertEquals("one owned path plus the original URL, no accumulated confirmations", 2,
            (second as Spanned).getSpans(0, second.length, ClickableSpan::class.java).size)
        val retired = PrintedPathLinks.withoutConfirmedPaths(second) as Spanned
        assertEquals(1, retired.getSpans(0, retired.length, ClickableSpan::class.java).size)
        assertTrue(retired.getSpans(0, retired.length, android.text.style.URLSpan::class.java).single() === url)
        assertTrue(retired.getSpans(0, retired.length, android.text.style.StyleSpan::class.java).single() === style)
        assertEquals(text.toString(), retired.toString())
    }

    @Test fun extractsOnlyBoundedRootRelativePathsAndKeepsLineSuffixOutsidePath() {
        val refs = PrintedPathLinks.extract("Open src/Main.kt:42 and docs/naïve readme.md:7, but not https://host.test/src/Remote.kt, ../secret.kt or /etc/passwd")
        assertTrue(refs.any { it.path.value == "src/Main.kt" && it.line == 42 })
        assertTrue(refs.any { it.path.value == "docs/naïve readme.md" && it.line == 7 })
        assertTrue(refs.all { it.start < it.end })
    }

    @Test fun preservesRootSpacesAndColonNamesAndSuppressesConfirmedAmbiguity() {
        val source = "Open my file.kt:7 or config:prod.json:12"
        val refs = PrintedPathLinks.extract(source)
        assertTrue(refs.any { it.path.value == "my file.kt" && it.line == 7 })
        assertTrue(refs.any { it.path.value == "config:prod.json" && it.line == 12 })
        val onlyFullNameExists = PrintedPathLinks.confirmedText(source, refs, setOf("my file.kt", "config:prod.json")) { }
        val spans = (onlyFullNameExists as Spanned).getSpans(0, source.length, ClickableSpan::class.java)
        assertEquals("only exact confirmed filenames become links", 2, spans.size)
        val ambiguous = PrintedPathLinks.confirmedText(source, refs, setOf("my file.kt", "file.kt", "config:prod.json")) { }
        assertEquals("both existing space-separated interpretations remain plain", 1,
            (ambiguous as Spanned).getSpans(0, source.length, ClickableSpan::class.java).size)
        assertTrue("extensionless directory targets remain candidates", PrintedPathLinks.extract("Open docs/reports").any { it.path.value == "docs/reports" })
    }

    @Test fun capsCandidatesAndRefusesAbsoluteAndTraversalForms() {
        val source = (1..100).joinToString(" ") { "src/File$it.kt:$it" }
        assertEquals(64, PrintedPathLinks.extract(source).size)
        assertTrue(PrintedPathLinks.extract("../../secret.kt /var/log/key.kt C:\\private\\token.kt").isEmpty())
    }

    @Test fun onlyExistenceConfirmedReferencesReceiveNativeClickSpans() {
        val source = "See src/Main.kt:8 and missing/Nope.kt"
        val refs = PrintedPathLinks.extract(source)
        val unconfirmed = PrintedPathLinks.confirmedText(source, refs, emptySet()) { error("must not bind") }
        assertEquals("unchanged unconfirmed text stays an ordinary String", source, unconfirmed.toString())
        var opened: PrintedFileReference? = null
        val confirmed = PrintedPathLinks.confirmedText(source, refs, setOf("src/Main.kt")) { opened = it }
        val spans = (confirmed as Spanned).getSpans(0, source.length, ClickableSpan::class.java)
        assertEquals(1, spans.size)
        spans.single().onClick(android.widget.TextView(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        assertEquals("src/Main.kt", opened?.path?.value)
        assertEquals(8, opened?.line)
    }

    @Test fun absoluteWindowsAndPosixEvidenceMustMatchTheServerReturnedRootExactly() {
        val windows = PrintedPathLinks.extract(
            "Edit C:\\Work Tree\\src\\naïve Main.kt:27 or C:\\Other\\secret.kt",
            serverRoot = "c:/work tree",
        )
        assertEquals(listOf("src/naïve Main.kt"), windows.map { it.path.value })
        assertEquals(27, windows.single().line)
        val posix = PrintedPathLinks.extract("See /srv/project/src/Main.kt:4 and /etc/passwd", serverRoot = "/srv/project")
        assertEquals(listOf("src/Main.kt"), posix.map { it.path.value })
        assertEquals("an absolute path cannot also confirm its basename as relative evidence", emptyList<String>(),
            PrintedPathLinks.extract("See /srv/project/src/Main.kt:4", serverRoot = "/srv/project").map { it.path.value }.filter { it == "Main.kt" })
        assertTrue("an absolute path outside the selected root must not be rescanned as relative", PrintedPathLinks.extract("See /etc/passwd", serverRoot = "/srv/project").isEmpty())
        assertTrue(PrintedPathLinks.extract("/srv/project/src/Main.kt", serverRoot = "/other/root").isEmpty())
    }

    @Test fun completeAbsoluteExtensionlessAndExplicitQuotedRangesCannotBecomeSuffixGuesses() {
        val outside = "See /etc/passwd"
        assertTrue(PrintedPathLinks.hasAbsoluteCandidates(outside))
        assertTrue(PrintedPathLinks.extract("See /etc/passwd", serverRoot = "/srv/project").isEmpty())
        val inside = PrintedPathLinks.extract("Inspect /srv/project/build/output:19 and /etc/passwd", serverRoot = "/srv/project")
        assertEquals(listOf("build/output"), inside.map { it.path.value })
        assertEquals(19, inside.single().line)
        val quoted = PrintedPathLinks.extract("Open `my file.kt:7` and `docs`")
        assertTrue(quoted.any { it.path.value == "my file.kt" && it.line == 7 })
        assertTrue("explicit root-level folder references are supported", quoted.any { it.path.value == "docs" })
    }

    @Test fun absoluteEvidenceBeyondTheBatchBudgetNeverBecomesRelativeSuffixEvidence() {
        val prefix = (1..64).joinToString(" ") { "/outside/File$it.kt" }
        for (last in listOf("/outside/my file.kt", "`/outside/my file.kt:7`", "/outside/build/output", "C:\\outside\\my file.kt")) {
            val source = "$prefix $last"
            val references = PrintedPathLinks.extract(source, serverRoot = "/repo")
            assertTrue("absolute overflow remains inert: $last", references.isEmpty())
            val confirmed = PrintedPathLinks.confirmedText(source, references, setOf("file.kt", "my file.kt", "output")) { error("outside evidence cannot bind") }
            assertEquals(source, confirmed.toString())
            assertEquals("no span can admit a colliding in-root basename", 0, (confirmed as? Spanned)?.getSpans(0, confirmed.length, ClickableSpan::class.java)?.size ?: 0)
        }
        assertEquals("the ordinary relative admission budget still permits64", 64,
            PrintedPathLinks.extract((1..100).joinToString(" ") { "src/File$it.kt" }).size)
    }    @Test fun oversizedAbsoluteSourceNeverAdmitsACollidingRelativeBasename() {
        val long = "a".repeat(4096)
        for (source in listOf(
            "/outside/$long file.kt", "`/outside/$long file.kt:9`", "'/outside/$long/extensionless'",
            "C:\\outside\\$long file.kt", "\"C:\\outside\\$long naïve Main.kt:12\"",
            "/outside/${"a".repeat(4000)} ${"b".repeat(200)} file.kt",
        )) {
            val refs = PrintedPathLinks.extract(source, "/repo")
            assertTrue("oversized absolute evidence stays fully inert", refs.isEmpty())
            var callbacks = 0
            val confirmed = PrintedPathLinks.confirmedText(source, refs, setOf("file.kt", "Main.kt", "naïve Main.kt", "extensionless")) { callbacks++ }
            assertEquals(source, confirmed.toString())
            val spans = (confirmed as? Spanned)?.getSpans(0, confirmed.length, ClickableSpan::class.java).orEmpty()
            assertTrue(spans.isEmpty())
            spans.forEach { it.onClick(android.view.View(androidx.test.core.app.ApplicationProvider.getApplicationContext())) }
            assertEquals("colliding confirmed basename never becomes actionable", 0, callbacks)
        }
    }

}
