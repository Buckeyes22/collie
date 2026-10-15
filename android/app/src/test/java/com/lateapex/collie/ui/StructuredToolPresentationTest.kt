package com.lateapex.collie.ui

import com.lateapex.collie.network.TranscriptPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredToolPresentationTest {
    @Test
    fun projectsEveryDeclaredCallKindIntoTypedInertDetails() {
        fun part(json: String, name: String = "tool", summary: String = "summary") = TranscriptPart(
            kind = "tool", name = name, summary = summary,
            call = Json.parseToJsonElement(json) as JsonObject,
        )

        assertEquals("/tmp/a", (StructuredToolPresentation.call(part("""{"kind":"edit","path":"/tmp/a","added":2,"removed":1,"created":true,"diff":[{"header":"@@","lines":["+a"]}]}""")) as ToolCallPresentation.Edit).path)
        assertEquals(2, (StructuredToolPresentation.call(part("""{"kind":"edit","path":"a","added":2,"removed":1,"diff":[]}""")) as ToolCallPresentation.Edit).added)
        assertEquals(7, (StructuredToolPresentation.call(part("""{"kind":"execute","command":"printf ok","description":"say ok","exitCode":7}""")) as ToolCallPresentation.Execute).exitCode)
        assertEquals(listOf(2, 4), (StructuredToolPresentation.call(part("""{"kind":"read","path":"a","range":[2,4]}""")) as ToolCallPresentation.Read).range)
        assertEquals(3, (StructuredToolPresentation.call(part("""{"kind":"search","query":"needle","where":"src","hits":3}""")) as ToolCallPresentation.Search).hits)
        assertEquals("https://example.test", (StructuredToolPresentation.call(part("""{"kind":"fetch","url":"https://example.test"}""")) as ToolCallPresentation.Fetch).url)
        assertEquals("a", (StructuredToolPresentation.call(part("""{"kind":"delete","path":"a"}""")) as ToolCallPresentation.Delete).path)
        assertEquals("b", (StructuredToolPresentation.call(part("""{"kind":"move","path":"a","to":"b"}""")) as ToolCallPresentation.Move).to)
        assertEquals("reviewer", (StructuredToolPresentation.call(part("""{"kind":"task","agent":"reviewer","summary":"review code"}""")) as ToolCallPresentation.Task).agent)
    }

    @Test
    fun questionDetailsKeepOptionsAnswersAndRemainDataWithoutActions() {
        val part = TranscriptPart(
            kind = "tool", name = "question", summary = "Which color?",
            id = "historic-call-1",
            call = Json.parseToJsonElement(
                """{"kind":"question","name":"question","summary":"Which color?","questions":[{"header":"Color","question":"Pick a color","multiple":true,"options":[{"label":"Blue","description":"Cool"},{"label":"Red"}]}],"answers":[["Blue"]]}""",
            ) as JsonObject,
        )

        val call = StructuredToolPresentation.call(part) as ToolCallPresentation.Question
        assertEquals("Pick a color", call.questions.single().question)
        assertEquals("Cool", call.questions.single().options.first().description)
        assertTrue(call.questions.single().multiple)
        assertEquals(listOf("Blue"), call.answers?.single())
        // This projection exposes only transcript data; the historic call ID is not an action API.
        assertEquals("historic-call-1", part.id)
    }

    @Test
    fun deniedFailureAndTruncationAreDistinctAndDenialWinsOverErrorFlag() {
        val denied = TranscriptPart("tool", result = com.lateapex.collie.network.TranscriptResult("refused", isError = true, denied = true))
        val failed = TranscriptPart("tool", result = com.lateapex.collie.network.TranscriptResult("failed", isError = true))
        val completed = TranscriptPart("tool", result = com.lateapex.collie.network.TranscriptResult("partial", truncated = true))

        assertEquals(ToolResultStatus.DENIED, StructuredToolPresentation.resultStatus(denied))
        assertEquals(ToolResultStatus.FAILED, StructuredToolPresentation.resultStatus(failed))
        assertEquals(ToolResultStatus.COMPLETED, StructuredToolPresentation.resultStatus(completed))
        assertTrue(completed.result!!.truncated)
    }

    @Test
    fun malformedUnknownAndLargeNestedCallsStayBoundedInertText() {
        val unknown = TranscriptPart(
            kind = "tool", name = "new_vendor_tool", summary = "safe fallback",
            call = Json.parseToJsonElement("""{"kind":"future","nested":{"value":"${"x".repeat(6000)}"}}""") as JsonObject,
        )
        val malformed = TranscriptPart(kind = "tool", name = "unknown", summary = "still readable", call = JsonObject(emptyMap()))
        val unknownView = StructuredToolPresentation.call(unknown) as ToolCallPresentation.Other
        val malformedView = StructuredToolPresentation.call(malformed) as ToolCallPresentation.Other

        assertEquals("new_vendor_tool", unknownView.name)
        assertEquals("safe fallback", unknownView.summary)
        assertTrue(unknownView.inertJson!!.length <= 4_000)
        assertEquals("still readable", malformedView.summary)
        assertNotNull(malformedView.inertJson)
        assertNull(StructuredToolPresentation.call(TranscriptPart("tool")))
    }

    @Test
    fun fallbackProjectionStopsAtDepthNodeAndOutputBudgets() {
        val deep = Json.parseToJsonElement(buildString {
            repeat(40) { append("{\"child\":") }
            append("\"leaf\"")
            repeat(40) { append('}') }
        })
        val deepRaw = buildJsonObject {
            put("kind", "future")
            put("nested", deep)
        }
        val deepFallback = StructuredToolPresentation.call(
            TranscriptPart(kind = "tool", name = "future", call = deepRaw),
        ) as ToolCallPresentation.Other
        assertTrue(deepFallback.inertJson!!.length <= StructuredToolPresentation.MAX_JSON)
        assertTrue(deepFallback.projectionNodes <= StructuredToolPresentation.MAX_JSON_NODES)
        assertTrue(deepFallback.projectionTruncated)
        assertTrue(deepFallback.inertJson.contains("depth limit"))

        val manySiblings = buildJsonObject {
            put("kind", "future")
            repeat(2_000) { put("field-$it", it) }
        }
        val bounded = StructuredToolPresentation.call(
            TranscriptPart(kind = "tool", name = "future", call = manySiblings),
        ) as ToolCallPresentation.Other
        assertTrue(bounded.inertJson!!.length <= StructuredToolPresentation.MAX_JSON)
        assertTrue(bounded.projectionNodes <= StructuredToolPresentation.MAX_JSON_NODES)
        assertTrue(bounded.projectionTruncated)
        assertTrue(bounded.inertJson.contains("node limit"))

        val hugeLeaf = Json.parseToJsonElement("{\"kind\":\"future\",\"value\":\"${"x".repeat(20_000)}\"}") as JsonObject
        val outputBounded = StructuredToolPresentation.call(TranscriptPart("tool", call = hugeLeaf)) as ToolCallPresentation.Other
        assertEquals(StructuredToolPresentation.MAX_JSON, outputBounded.inertJson!!.length)
        assertTrue(outputBounded.projectionTruncated)
    }

    @Test
    fun diffTraversalStopsAtAggregateOutputBudget() {
        val hugeDiff = Json.parseToJsonElement(
            """{"kind":"edit","path":"a","added":1,"removed":0,"diff":[{"header":"@@","lines":[${(0 until 80).joinToString(",") { "\"${"z".repeat(4000)}\"" }}]},{"header":"later hunk","lines":["must not be visited"]}]}""",
        ) as JsonObject
        val edit = StructuredToolPresentation.call(TranscriptPart("tool", call = hugeDiff)) as ToolCallPresentation.Edit
        assertTrue(edit.diff.sumOf(String::length) <= 8_000)
        assertTrue(edit.diff.sumOf(String::length) >= 7_900)
        assertTrue(edit.diff.none { it.contains("must not be visited") })
    }
}
