package com.lateapex.collie.network

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

}
