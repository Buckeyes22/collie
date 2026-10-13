package com.lateapex.collie.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesModelsTest {
    @Test fun rootRelativePathRejectsTraversalAndEncodedSeparatorSpellings() {
        listOf("/etc/passwd", "../secret", "src/../../secret", "src\\..\\secret", ".git/config", "src//x", "a\u0000b").forEach {
            assertNull(it, RelativeFilePath.parse(it))
        }
        assertEquals("src/Main.kt", RelativeFilePath.parse("src/Main.kt")?.value)
        assertEquals("", RelativeFilePath.parse("", allowRoot = true)?.value)
    }

    @Test fun wireRefusesWrongIdentityUnlistedEntryAndOutOfBudgetLists() {
        val target = FilesTarget.Pane("w1:p1")
        val json = Json.parseToJsonElement("""{"paneId":"w1:p1","available":true,"root":"/srv/work","dir":"src","entries":[{"name":"Main.kt","kind":"file","size":3}],"truncated":false}""")
        val dir = RelativeFilePath.parse("src", allowRoot = true)!!
        val listing = FilesWire.answer(json, target, null, dir) as FilesAnswer.Listing
        assertEquals("Main.kt", listing.entries.single().name)
        assertNull(FilesWire.answer(json, FilesTarget.Pane("other"), null, dir))
        val traversal = Json.parseToJsonElement("""{"paneId":"w1:p1","available":true,"root":"/srv/work","dir":"src","entries":[{"name":"..","kind":"dir"}],"truncated":false}""")
        assertNull(FilesWire.answer(traversal, target, null, dir))
        assertFalse(FilesWire.answer(Json.parseToJsonElement("""{"paneId":"w1:p1","available":false,"reason":"secret-path"}"""), target, null, dir) is FilesAnswer.Unavailable)
    }

    @Test fun existenceAnswerCannotConfirmUnrequestedOrDuplicatePaths() {
        val requested = listOf(RelativeFilePath.parse("docs/a b.md")!!, RelativeFilePath.parse("src/λ.kt")!!)
        val valid = FilesWire.exists(Json.parseToJsonElement("""{"exists":["docs/a b.md"]}"""), requested)
        assertEquals(setOf("docs/a b.md"), valid?.exists)
        assertNull(FilesWire.exists(Json.parseToJsonElement("""{"exists":["/etc/passwd"]}"""), requested))
        assertNull(FilesWire.exists(Json.parseToJsonElement("""{"exists":["docs/a b.md","docs/a b.md"]}"""), requested))
    }

    @Test fun jsonBudgetPreflightStopsDeepAndWideValuesBeforeTreeConstruction() {
        assertTrue(FilesJsonBounds.accepts("""{"text":"braces } and [ brackets","items":[1,true,null]}"""))
        assertFalse(FilesJsonBounds.accepts("[".repeat(33) + "0" + "]".repeat(33), maxDepth = 32, maxNodes = 100))
        assertFalse(FilesJsonBounds.accepts("[" + (1..100).joinToString(",") + "]", maxDepth = 64, maxNodes = 40))
        assertFalse(FilesJsonBounds.accepts("{\"unterminated: ["))
    }
}
