package com.lateapex.collie.network

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationActionTest {
    @Test
    fun actionsEncodeOnlyTheirClosedRouteAndPayload() {
        val rename = OrganizationAction.RenameWorkspace("space:1", "Review")
        assertEquals(listOf("workspace", "space:1", "rename"), rename.path)
        assertEquals("workspace-rename", rename.kind)
        assertEquals("Review", rename.payload["label"]?.let { (it as JsonPrimitive).content })

        val move = OrganizationAction.MoveTab("tab-2", 3)
        assertEquals(listOf("tab", "tab-2", "move"), move.path)
        assertEquals("tab-move", move.kind)
        assertEquals(3, (move.payload["insertIndex"] as JsonPrimitive).int)

        val block = OrganizationAction.ReorderWorkspaces(listOf("space:2", "space:3"), null)
        assertEquals("workspaces", block.path.first())
        assertEquals(listOf("space:2", "space:3"), (block.payload["workspaceIds"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(JsonNull, block.payload["beforeWorkspaceId"])
    }

    @Test
    fun planMustMatchExactActionTargetAndPayloadBeforeItCanBeApplied() {
        val action = OrganizationAction.CloseWorkspace("space-7", closeGroup = true)
        val response = OrganizationPlanResponse(
            ok = true,
            planToken = "plan-1",
            expiresAt = System.currentTimeMillis() + 30_000,
            action = buildJsonObject {
                put("kind", JsonPrimitive(action.kind))
                put("workspaceId", JsonPrimitive("space-7"))
                put("closeGroup", JsonPrimitive(true))
            },
            effects = buildJsonObject {
                put("kind", JsonPrimitive("close"))
                put("workspaceIds", JsonArray(listOf(JsonPrimitive("space-7"), JsonPrimitive("space-8"))))
                put("workspaceLabels", JsonArray(listOf(JsonPrimitive("Review"), JsonPrimitive("Linked"))))
                put("panes", JsonArray(emptyList()))
                put("groupPreviewIds", JsonArray(listOf(JsonPrimitive("space-7"), JsonPrimitive("space-8"))))
                put("groupCloseRequired", JsonPrimitive(true))
                put("deletesCheckouts", JsonPrimitive(false))
            },
            workspaceOrder = listOf("space-7", "space-8"),
        )

        assertTrue(response.validFor(action))
        assertFalse(response.validFor(OrganizationAction.CloseWorkspace("space-8", closeGroup = true)))
        assertFalse(response.validFor(OrganizationAction.CloseWorkspace("space-7", closeGroup = false)))
        assertFalse(response.copy(action = buildJsonObject {
            put("kind", JsonPrimitive(action.kind))
            put("workspaceId", JsonPrimitive("space-7"))
            put("closeGroup", JsonPrimitive(true))
            put("unreviewed", JsonPrimitive(true))
        }).validFor(action))
        assertFalse(response.copy(workspaceOrder = List(257) { "space-$it" }).validFor(action))
        assertFalse(response.copy(effects = JsonObject(response.effects + ("deletesCheckouts" to JsonPrimitive(true)))).validFor(action))
        assertFalse(response.copy(effects = JsonObject(response.effects + ("unreviewed" to JsonPrimitive(true)))).validFor(action))
    }
}
