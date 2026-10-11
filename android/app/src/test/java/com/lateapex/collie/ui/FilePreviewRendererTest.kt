package com.lateapex.collie.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.lateapex.collie.network.RelativeFilePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilePreviewRendererTest {
    @Test fun jsonProjectionIsIterativeAndBoundsDepthNodesAndOutput() {
        val shallow = FilePreviewRenderer.json("""{"a":[1,2],"b":true}""")
        assertTrue(shallow.text.contains("a:"))
        assertTrue(shallow.text.contains("[1]"))
        val deep = FilePreviewRenderer.json("[".repeat(100) + "0" + "]".repeat(100))
        assertTrue(deep.unsupported.isNotEmpty())
        val many = FilePreviewRenderer.json("[" + (0..6_000).joinToString(",") + "]")
        assertTrue(many.unsupported.isNotEmpty())
    }

    @Test fun htmlSafelistRemovesActiveAndRemoteElementsWhileKeepingText() {
        val preview = FilePreviewRenderer.html("""<h1>Hello</h1><script>steal()</script><form><input value="x"></form><iframe src="https://evil.test">frame</iframe><p>safe <b>text</b><a href="javascript:alert(1)">bad link</a></p><img src="https://evil.test/x.png">""")
        assertTrue(preview.text.contains("Hello"))
        assertTrue(preview.text.contains("safe text"))
        assertFalse(preview.text.contains("steal()"))
        assertFalse(preview.text.contains("evil.test"))
        assertFalse(preview.text.contains("javascript:"))
        assertTrue(preview.unsupported.isNotEmpty())
    }

    @Test fun htmlLocalRasterReferencesAreRootContainedAndRemoteImagesStayInert() {
        val preview = FilePreviewRenderer.project(
            "docs/page.html",
            "<p>before</p><img src='../images/a diagram.png' alt='Local'><img src='https://example.test/remote.png'><img src='../../outside.png'>",
        )
        assertEquals(listOf("images/a diagram.png"), preview.images.map { it.path.value })
        assertEquals("Local", preview.images.single().alt)
        assertFalse(preview.text.contains("example.test"))
        assertFalse(preview.text.contains("outside.png"))
    }

    @Test fun htmlBuildsNativeBlocksAndOnlyTakesLinksFromTheCleanedDom() {
        val preview = FilePreviewRenderer.project(
            "docs/index.html",
            """<h1>Guide</h1><p>Safe paragraph</p><ul><li>first</li><li>second</li></ul><table><tr><th>Key</th><th>Value</th></tr><tr><td>a</td><td>b</td></tr></table><a href="../src/Main.kt">Source</a><script><a href="../../secret.kt">bad</a></script>""",
        )
        assertTrue(preview.markdownBlocks.any { it is HistoryMarkdownBlock.Heading })
        assertTrue(preview.markdownBlocks.any { it is HistoryMarkdownBlock.ListBlock })
        assertTrue(preview.markdownBlocks.any { it is HistoryMarkdownBlock.Table })
        assertEquals(listOf("src/Main.kt"), preview.links.map { it.path.value })
    }

    @Test fun htmlCleanerPreservesOnlyContainedRelativeLinksForNativeAdmission() {
        val preview = FilePreviewRenderer.project(
            "docs/page.html",
            """<a href="../src/Main.kt#part">local</a><a href="#top">anchor</a><a href="https://example.test/docs">external</a><a href="javascript:alert(1)">unsafe</a><a href="../../secret">escape</a>""",
        )
        assertTrue(preview.links.any { it.path.value == "src/Main.kt" && it.anchor == "part" })
        assertTrue(preview.links.any { it.path.value == "docs/page.html" && it.anchor == "top" })
        assertFalse(preview.links.any { it.path.value.contains("secret") || it.path.value.contains("example") })
        assertFalse(preview.text.contains("javascript:"))
    }

    @Test fun markdownImagesAreAtMostTwentyAndStayInsideDocumentRoot() {
        val document = RelativeFilePath.parse("docs/readme.md")!!
        val source = """![ok](img/a.png) ![remote](https://example.test/a.png) ![escape](../../etc/passwd) ![data](data:image/png;base64,AAAA)"""
        val refs = FilePreviewRenderer.markdownImages(source, document)
        assertEquals(listOf("docs/img/a.png"), refs.map { it.path.value })
        val many = (1..30).joinToString(" ") { "![x](img/$it.png)" }
        assertEquals(20, FilePreviewRenderer.markdownImages(many, document).size)
        assertEquals("extensionless image candidates remain for authenticated response sniffing", listOf("docs/img/diagram"),
            FilePreviewRenderer.markdownImages("![x](img/diagram)", document).map { it.path.value })
    }

    @Test fun markdownAndHtmlKeepSameDocumentHeadingTargetsAndResolveLocalFiles() {
        val markdown = FilePreviewRenderer.project(
            "docs/readme.md",
            "## Getting Started\n\n[Jump](#getting-started) [Source](src/Main.kt) [web](https://example.test) [escape](../../secret.md)",
        )
        assertTrue(markdown.links.any { it.anchor == "getting-started" && it.path.value == "docs/readme.md" })
        assertTrue(markdown.links.any { it.path.value == "docs/src/Main.kt" && it.anchor == null })
        assertFalse(markdown.links.any { it.path.value.contains("secret") || it.path.value.contains("example") })
        val html = FilePreviewRenderer.project("docs/page.html", """<h2>Overview</h2><a href="#overview">top</a><a href="src/Main.kt">source</a>""")
        assertTrue(html.links.any { it.anchor == "overview" && it.path.value == "docs/page.html" })
        assertTrue(html.links.any { it.path.value == "docs/src/Main.kt" })
    }

    @Test fun csvAndSvgHaveBoundedNativeProjectionsAndRefuseExternalEntities() {
        val csv = FilePreviewRenderer.csv("name,value\r\n\"alpha,beta\",1\r\n\"multi\nline\",\"a \"\"quoted\"\" value\"\r\n\"\",3")
        assertEquals(PreviewFormat.CSV, csv.format)
        assertTrue(csv.text.contains("alpha,beta  |  1"))
        assertTrue(csv.text.contains("multi line  |  a \"quoted\" value"))
        assertTrue(csv.text.contains("  |  3"))
        val singleEmptyQuotedCell = FilePreviewRenderer.csv("\"\"")
        assertEquals("", singleEmptyQuotedCell.text)
        assertTrue("a valid empty quoted cell remains one table cell", singleEmptyQuotedCell.markdownBlocks.single() is HistoryMarkdownBlock.Table)
        assertTrue(csv.unsupported.isEmpty())
        val malformed = FilePreviewRenderer.csv("a,\"unterminated")
        assertTrue(malformed.unsupported.contains(PreviewNotice.CSV_MALFORMED))
        val overColumns = FilePreviewRenderer.csv((1..40).joinToString(",") { "c$it" })
        assertTrue(overColumns.unsupported.contains(PreviewNotice.TABLE_BOUNDED))
        val svg = FilePreviewRenderer.svg("""<svg width="100" height="60"><rect x="1" y="2" width="30" height="40" fill="#ff0000"/><circle cx="50" cy="25" r="10" fill="blue"/></svg>""")
        assertNotNull(svg.svg)
        val unsafe = FilePreviewRenderer.svg("""<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg><text>&x;</text></svg>""")
        assertNull(unsafe.svg)
        assertTrue(unsafe.unsupported.isNotEmpty())
        val pathBomb = FilePreviewRenderer.svg("""<svg><path d="${"L 1 1 ".repeat(25_000)}"/></svg>""")
        assertNull(pathBomb.svg)
    }

    @Test fun svgUsesThePinnedRendererForViewBoxTransformAndInheritedFill() {
        val drawing = FilePreviewRenderer.svg(
            """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 10 10"><g transform="translate(5 0)" fill="#ff0000"><rect x="0" y="0" width="2" height="2"/></g></svg>""",
        ).svg
        assertNotNull("ordinary viewBox, transform and inherited fill remain supported", drawing)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = FilePreviewRenderer.createSvgView(context, drawing!!).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 200, 200)
        }
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val colored = (0 until bitmap.width).any { x -> (0 until bitmap.height).any { y ->
            val pixel = bitmap.getPixel(x, y)
            android.graphics.Color.red(pixel) > 200 && android.graphics.Color.green(pixel) < 80 && android.graphics.Color.blue(pixel) < 80
        } }
        assertTrue("SVG renderer must paint the supported transformed shape", colored)
        assertNull(FilePreviewRenderer.svg("""<svg width="NaN" height="80"><rect width="1" height="1"/></svg>""").svg)
        assertNull(FilePreviewRenderer.svg("""<svg><rect width="1" height="1" style="fill:url(https://bad.test/x)"/></svg>""").svg)
        assertNull(FilePreviewRenderer.svg("""<svg><image href="data:image/png;base64,AAAA"/></svg>""").svg)
        val transformBomb = """<svg><g transform="${"translate(5 0) ".repeat(65)}"><rect width="1" height="1"/></g></svg>"""
        assertNull("large transform programs are refused before the renderer sees them", FilePreviewRenderer.svg(transformBomb).svg)
        val identitySkewScaleBomb = """<svg><g transform="skewX(0) ${"scale(100) ".repeat(20)}"><rect width="1" height="1"/></g></svg>"""
        val alternatingShearBomb = """<svg><g transform="${"skewX(80) skewY(80) ".repeat(16)}"><rect width="1" height="1"/></g></svg>"""
        assertNull("identity skew must not hide composed scale growth", FilePreviewRenderer.svg(identitySkewScaleBomb).svg)
        assertNull("alternating shears must remain within the composed affine bound", FilePreviewRenderer.svg(alternatingShearBomb).svg)
        assertTrue("translated geometry is at the expected viewport coordinate", android.graphics.Color.red(bitmap.getPixel(108, 8)) > 200)
        assertEquals("the region outside the expected transformed geometry stays clear", 0, android.graphics.Color.alpha(bitmap.getPixel(150, 50)))
    }

    @Test fun inheritedEffectiveStrokeIsBoundedBeforeParserAndOrdinaryPaintStillDraws() {
        for (attributes in listOf("", "stroke-linecap='square' stroke-linejoin='round'", "stroke-linejoin='bevel'")) {
            val source = "<svg><g stroke='red' stroke-width='1000000' $attributes><rect transform='scale(100)' width='1' height='1'/></g></svg>"
            assertNull("inherited stroke exceeds transformed bounds: $attributes", FilePreviewRenderer.svg(source).svg)
        }
        assertNull("inherited miter limit contributes to paint extent", FilePreviewRenderer.svg(
            "<svg><g stroke='red' stroke-width='100' stroke-miterlimit='1000000'><path d='M 0 0 L 1 0 L 1 1'/></g></svg>",
        ).svg)
        assertNull("unsupported caps cannot silently revert to an unbounded inherited style", FilePreviewRenderer.svg(
            "<svg><path stroke='red' stroke-linecap='invalid' d='M 0 0 L 1 1'/></svg>",
        ).svg)
        val drawing = requireNotNull(FilePreviewRenderer.svg(
            "<svg width='100' height='100' viewBox='0 0 50 50'><g stroke='red' stroke-width='2' stroke-linecap='round' stroke-linejoin='bevel' fill='none' transform='translate(3 0)'><rect x='20' y='20' width='20' height='20'/></g></svg>",
        ).svg)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = FilePreviewRenderer.createSvgView(app, drawing)
        view.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 100, 100)
        val pixels = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(pixels))
        assertTrue("inherited width/paint follows composed viewBox and transform", android.graphics.Color.red(pixels.getPixel(46, 60)) > 200)
        assertEquals("inherited fill none preserves the unpainted interior", 0, android.graphics.Color.alpha(pixels.getPixel(60, 60)))
        assertEquals("ordinary stroke stays within its transformed boundary", 0, android.graphics.Color.alpha(pixels.getPixel(30, 60)))
        pixels.recycle()
    }

    @Test fun svgTextIsExplicitlyUnsupportedBeforeInheritedFontGeometryCanReachTheRenderer() {
        for (source in listOf(
            "<svg><text x='10' y='20'>ordinary text</text></svg>",
            "<svg><g transform='scale(100)' font-size='1000000'><text x='1' y='1'>AAAA</text></g></svg>",
            "<svg><g font-size='1000000'><text><tspan>${"A".repeat(99_999)}</tspan></text></g></svg>",
        )) {
            val preview = FilePreviewRenderer.svg(source)
            assertNull("unsupported text is refused before renderer allocation", preview.svg)
            assertTrue(preview.unsupported.contains(PreviewNotice.SVG_REFUSED))
        }
        assertNotNull("supported bounded inherited paint stays available", FilePreviewRenderer.svg(
            "<svg><g fill='red' transform='translate(5 0)'><rect width='20' height='20'/></g></svg>",
        ).svg)
    }

}
