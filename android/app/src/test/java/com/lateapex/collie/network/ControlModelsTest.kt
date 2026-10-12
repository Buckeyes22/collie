package com.lateapex.collie.network

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ControlModelsTest {
    @Test fun newCapabilitiesRequirePositiveDeclarations() {
        val json = Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString<ControlCapabilityResponse>("{}").supportsNew("controlOperations"))
        assertFalse(ControlCapabilityResponse(mapOf("controlOperations" to false)).supportsNew("controlOperations"))
        assertTrue(ControlCapabilityResponse(mapOf("controlOperations" to true)).supportsNew("controlOperations"))
        assertEquals(CapabilityState.Unknown, ControlCapabilityResponse().state("controlOperations"))
        assertFalse(HostControlResponse(provider = "herdr").supportsNew("plugins"))
    }
    @Test fun matchingStatusAndProseCannotRetirePairing() {
        for (status in listOf(401, 403)) {
            assertFalse(ApiFailure.Http(status, "device expired", "pairing.expired").retiresPairing())
            assertFalse(ApiFailure.Http(status, "device expired", "pairing.expired", source = FailureSource.GATEWAY, factor = FailureFactor.PAIRING).retiresPairing())
            assertFalse(ApiFailure.Http(status, "device expired", "pairing.expired", source = FailureSource.COLLIE, factor = FailureFactor.IDENTITY).retiresPairing())
            assertTrue(ApiFailure.Http(status, "unpaired", "pairing.expired", source = FailureSource.COLLIE, factor = FailureFactor.PAIRING).retiresPairing())
        }
    }
    @Test fun operationSchemaAndClosedStateRefuseAmbiguousReceipts() {
        val pending = ControlOperation(1, "intent", "pending", "movePane", "a".repeat(64), 1, 2)
        assertTrue(pending.validFor("intent"))
        assertTrue(pending.copy(state = "unknown").validFor("intent"))
        assertFalse(pending.copy(state = "retry").validFor("intent"))
        assertFalse(pending.copy(schema = 2).validFor("intent"))
        assertFalse(pending.copy(updatedAt = 0).validFor("intent"))
        assertFalse(pending.validFor("another"))
    }
    @Test fun paneMoveResultIsClosedAndBoundToSucceededOperation() {
        val result = PaneMoveResult("pane-move", "pane_new", "pane_old", "tab_old", "space_old")
        val move = ControlOperation(1, "move-1", "succeeded", "paneMove", "b".repeat(64), 10, 11, result = result)
        assertTrue(move.validFor("move-1"))
        assertFalse(move.copy(result = result.copy(paneId = "../other")).validFor("move-1"))
        assertFalse(move.copy(state = "unknown").validFor("move-1"))
        assertFalse(move.copy(result = null).validFor("move-1"))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(result, json.decodeFromString<ControlOperation>(json.encodeToString(ControlOperation.serializer(), move)).result)
    }

    @Test fun removalReceiptBindsTheExactWorkspaceRequestForceAndOwnerTarget() {
        val workspace = "space-7"
        val requestId = "remove-19"
        val target = MessageDigest.getInstance("SHA-256").digest("worktree:$workspace".toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val now = System.currentTimeMillis()
        val result = WorktreeRemoveResult("worktree-remove", workspace, "/repo/.worktrees/topic", "topic", false)
        val operation = ControlOperation(1, requestId, "succeeded", "worktreeRemove", target, now, now, result = result)
        val response = WorktreeRemoveResponse(true, "worktree_removed", workspace, "/repo/.worktrees/topic", "topic", false, operation)
        assertTrue(response.validFor(workspace, false, requestId))
        assertFalse(response.validFor(workspace, false, "another-request"))
        assertFalse(response.validFor(workspace, true, requestId))
        assertFalse(response.copy(operation = operation.copy(targetIdentity = "f".repeat(64))).validFor(workspace, false, requestId))
        assertFalse(response.copy(path = "relative/path").validFor(workspace, false, requestId))
    }

    @Test fun paneLayoutPlansBindRequestedPaneActionAndClosedEffects() {
        val layout = PaneLayoutRead("layout", LayoutSnapshot("space", "tab", false, LayoutRect(0, 0, 80, 24), "pane:one",
            listOf(LayoutPane("pane:one", true, LayoutRect(0, 0, 80, 24))), emptyList()))
        val action = Json.parseToJsonElement("""{"kind":"split","direction":"right","ratio":0.5}""").jsonObject
        val effects = Json.parseToJsonElement("""{"verb":"split","targetPaneId":"pane:one","direction":"right","ratio":0.5,"focusChanges":false}""").jsonObject
        val plan = PaneLayoutPlanResponse(true, "plan-one", System.currentTimeMillis() + 10_000, "split", effects, layout)
        assertTrue(plan.validForPane("pane:one", action))
        assertFalse(plan.validForPane("pane:other", action))
        assertFalse(plan.copy(effects = Json.parseToJsonElement("""{"verb":"split","targetPaneId":"pane:other","direction":"right","ratio":0.5,"focusChanges":false}""").jsonObject).validForPane("pane:one", action))
        assertFalse(plan.copy(effects = Json.parseToJsonElement("""{"verb":"split","targetPaneId":"pane:one","direction":"left","ratio":0.5,"focusChanges":false}""").jsonObject).validForPane("pane:one", action))
        assertFalse(plan.copy(effects = Json.parseToJsonElement("""{"verb":"split","targetPaneId":"pane:one","direction":"right","ratio":0.5,"focusChanges":false,"sourcePaneId":"forged"}""").jsonObject).validForPane("pane:one", action))
        assertFalse(plan.copy(layout = layout.copy(layout = layout.layout.copy(panes = listOf(LayoutPane("pane:other", true, LayoutRect(0, 0, 80, 24))), focusedPaneId = "pane:other"))).validForPane("pane:one", action))
    }    @Test fun nullablePlanEffectsRejectWrongPrimitiveTypesWithoutLosingDefaultPositives() {
        val geometry = PaneLayoutRead("layout", LayoutSnapshot("space", "tab", false, LayoutRect(0, 0, 80, 24), "pane",
            listOf(LayoutPane("pane", true, LayoutRect(0, 0, 40, 24)), LayoutPane("other", false, LayoutRect(40, 0, 40, 24))), emptyList()))
        for ((kind, field) in listOf("split" to "ratio", "resize" to "amount")) {
            val action = Json.parseToJsonElement("""{"kind":"$kind","direction":"right"}""").jsonObject
            val base = if (kind == "split") """{"verb":"split","targetPaneId":"pane","direction":"right","ratio":null,"focusChanges":false}""" else
                """{"verb":"resize","targetPaneId":"pane","direction":"right","amount":null}"""
            val plan = PaneLayoutPlanResponse(true, "plan", System.currentTimeMillis() + 10_000, kind, Json.parseToJsonElement(base).jsonObject, geometry)
            assertTrue(plan.validForPane("pane", action))
            for (bad in listOf("\"bad\"", "true", "{}", "[]", "\"0.5\"")) {
                assertFalse(plan.copy(effects = Json.parseToJsonElement(base.replace("\"$field\":null", "\"$field\":$bad")).jsonObject).validForPane("pane", action))
            }
        }
        val swapAction = Json.parseToJsonElement("""{"kind":"swap","sourcePaneId":"pane","targetPaneId":"other"}""").jsonObject
        val swap = PaneLayoutPlanResponse(true, "plan", System.currentTimeMillis() + 10_000, "swap",
            Json.parseToJsonElement("""{"verb":"swap","sourcePaneId":"pane","targetPaneId":"other","direction":null}""").jsonObject, geometry)
        assertTrue(swap.validForPane("pane", swapAction))
        assertFalse(swap.copy(effects = Json.parseToJsonElement("""{"verb":"swap","sourcePaneId":"pane","targetPaneId":"other","direction":true}""").jsonObject).validForPane("pane", swapAction))
    }


    @Test fun removalClosedUnionRejectsScalarCoercionExtrasAndEveryEnvelopeMismatch() {
        val json = Json { ignoreUnknownKeys = true }
        val now = System.currentTimeMillis()
        val target = MessageDigest.getInstance("SHA-256").digest("worktree:space".toByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val result = WorktreeRemoveResult("worktree-remove", "space", "/repo/topic", "topic", false)
        val operation = ControlOperation(1, "remove", "succeeded", "worktreeRemove", target, now, now, result = result)
        val good = WorktreeRemoveResponse(true, "worktree_removed", "space", "/repo/topic", "topic", false, operation)
        val raw = json.encodeToString(WorktreeRemoveResponse.serializer(), good)
        assertTrue(json.decodeFromString<WorktreeRemoveResponse>(raw).validFor("space", false, "remove"))
        for (wrong in listOf(
            raw.replace("\"workspaceId\":\"space\"", "\"workspaceId\":17"),
            raw.replace("\"path\":\"/repo/topic\"", "\"path\":17"),
            raw.replace("\"branch\":\"topic\"", "\"branch\":true"),
            raw.replace("\"forced\":false", "\"forced\":\"false\""),
            raw.replace("\"at\":$now", "\"at\":\"$now\""),
            raw.replace("\"kind\":\"worktree-remove\"", "\"kind\":\"worktree-remove\",\"vendor\":1"),
        )) assertThrows(kotlinx.serialization.SerializationException::class.java) { json.decodeFromString<WorktreeRemoveResponse>(wrong) }
        for (bad in listOf(good.copy(workspaceId = "other"), good.copy(path = "/repo/other"), good.copy(branch = "other"), good.copy(forced = true),
            good.copy(operation = operation.copy(result = result.copy(workspaceId = "other"))), good.copy(operation = operation.copy(result = result.copy(path = "/repo/other"))),
            good.copy(operation = operation.copy(result = result.copy(branch = "other"))), good.copy(operation = operation.copy(result = result.copy(forced = true)))))
            assertFalse(bad.validFor("space", false, "remove"))
        val refused = WorktreeRemoveResponse(false, operation = operation.copy(state = "refused", result = null, code = "worktree.stale_worktree_operation"), code = "worktree.stale_worktree_operation", error = "The target changed")
        assertTrue(refused.knownRefusalFor("space", "remove"))
        assertFalse(refused.copy(operation = refused.operation!!.copy(state = "unknown")).knownRefusalFor("space", "remove"))
    }

}
