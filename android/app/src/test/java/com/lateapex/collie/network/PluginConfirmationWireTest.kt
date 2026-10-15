package com.lateapex.collie.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginConfirmationWireTest {
    private val json = Json { encodeDefaults = false }
    @Test fun everyConfirmedPluginMutationSendsTheRequiredWireField() {
        val bodies = listOf(
            json.encodeToString(HostPluginLinkRequest.serializer(), HostPluginLinkRequest("request", "plan")),
            json.encodeToString(HostPluginInstallRequest.serializer(), HostPluginInstallRequest("request", "plan", "checksum")),
            json.encodeToString(HostPluginUninstallRequest.serializer(), HostPluginUninstallRequest("request", "plan")),
            json.encodeToString(HostPluginConfigPrepareRequest.serializer(), HostPluginConfigPrepareRequest("request", "plan")),
            json.encodeToString(HostPluginConfigApplyRequest.serializer(), HostPluginConfigApplyRequest("request", "plan", "file", "{}")),
        )
        bodies.forEach { body ->
            val fields = json.parseToJsonElement(body).jsonObject
            assertTrue("The bridge requires an explicit confirmed field: $body", fields.containsKey("confirmed"))
            assertTrue(fields.getValue("confirmed").jsonPrimitive.boolean)
            assertEquals("request", fields.getValue("requestId").jsonPrimitive.content)
        }
    }
    @Test fun explicitFalseIsNeverRewrittenAsConfirmed() {
        val body = json.encodeToString(HostPluginLinkRequest.serializer(), HostPluginLinkRequest("request", "plan", false))
        assertEquals(false, json.parseToJsonElement(body).jsonObject.getValue("confirmed").jsonPrimitive.boolean)
    }
}
