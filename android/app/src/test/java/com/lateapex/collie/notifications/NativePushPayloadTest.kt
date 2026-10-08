package com.lateapex.collie.notifications

import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class NativePushPayloadTest {
    private val cases = Json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("native-push-v1.fixtures.json")!!.bufferedReader().use { it.readText() },
    ).jsonArray

    @Test
    fun sharedWireCorpusMatchesServerDecoder() {
        for (case in cases) {
            val c = case.jsonObject
            val decoded = NativePushPayload.decode(c.getValue("payload").toString(), "registration_demo", 1000, c["lastSequence"]?.jsonPrimitive?.long ?: 0)
            assertEquals(c.getValue("name").jsonPrimitive.content, c.getValue("accept").jsonPrimitive.boolean, decoded != null)
            if (c.getValue("name").jsonPrimitive.content == "silent default") assertEquals(false, decoded!!.renotify)
        }
    }

    @Test
    fun addressesTheExactHostSessionAndPaneWithoutAnExternalUrl() {
        val decoded = NativePushPayload.decode(cases[0].jsonObject.getValue("payload").toString(), "registration_demo", 1000)!!
        assertEquals(NativePushPayload.Target.Pane(PaneAddress(Scope(host = "peer", session = "work"), "w1:p2")), decoded.target)
        assertEquals("blocked", decoded.kind)
        assertEquals(4L, decoded.sequence)
        assertFalse(decoded.toString().contains(decoded.title!!))
        assertFalse(decoded.toString().contains(decoded.body!!))
    }

    @Test
    fun rejectsMalformedOversizedAndInvalidReceiverContext() {
        val raw = cases[0].jsonObject.getValue("payload").toString()
        assertNull(NativePushPayload.decode("{", "registration_demo", 1000))
        assertNull(NativePushPayload.decode(" ".repeat(4096) + raw, "registration_demo", 1000))
        assertNull(NativePushPayload.decode(raw, "registration_demo", -1))
        assertNull(NativePushPayload.decode(raw, "registration_demo", 1000, -1))
        assertNull(NativePushPayload.decode(raw, "", 1000))
    }
}
