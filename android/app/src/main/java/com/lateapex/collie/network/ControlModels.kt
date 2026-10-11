package com.lateapex.collie.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

enum class FailureSource { COLLIE, GATEWAY, UNKNOWN }
enum class FailureFactor { PAIRING, IDENTITY, DEVICE, SCOPE, CAPABILITY, OPERATION, UNKNOWN }

sealed interface CapabilityState {
    data object Unknown : CapabilityState
    data object Available : CapabilityState
    data class Unavailable(val reason: String?) : CapabilityState
    data object Forbidden : CapabilityState
}

@Serializable
data class ControlCapabilityResponse(val capabilities: Map<String, Boolean> = emptyMap()) {
    fun supportsNew(name: String): Boolean = capabilities[name] == true
    fun state(name: String): CapabilityState = when (capabilities[name]) {
        true -> CapabilityState.Available
        false -> CapabilityState.Unavailable(null)
        null -> CapabilityState.Unknown
    }
}
@Serializable
data class HostControlResponse(
    val provider: String = "none",
    val capabilities: Map<String, Boolean> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
) {
    fun supportsNew(name: String): Boolean = capabilities[name] == true
}

/** Open display codes, closed state/schema acceptance. Unknown states never authorize a repeat. */
@Serializable
data class ControlOperation(
    val schema: Int,
    val requestId: String,
    val state: String,
    val capability: String,
    val targetIdentity: String,
    val at: Long,
    val updatedAt: Long,
    val code: String? = null,
    val result: PaneMoveResult? = null,
) {
    fun validFor(id: String): Boolean = schema == 1 && requestId == id &&
        state in setOf("pending", "succeeded", "refused", "unknown") &&
        capability.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) && targetIdentity.matches(Regex("[a-f0-9]{64}")) &&
        at >= 0 && updatedAt >= at && updatedAt <= System.currentTimeMillis() + 60_000 &&
        (result == null || (result.kind == "pane-move" && listOf(result.paneId, result.previousPaneId, result.previousTabId, result.previousWorkspaceId).all { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) })) &&
        (result == null || state == "succeeded") &&
        (capability != "paneMove" || state != "succeeded" || result?.kind == "pane-move")
}
@Serializable
data class ControlOperationResponse(val ok: Boolean, val operation: ControlOperation)

/** Only a declared response source/factor/code can retire Collie pairing, never server prose. */
fun ApiFailure.Http.retiresPairing(): Boolean = status in setOf(401, 403) &&
    source == FailureSource.COLLIE && factor == FailureFactor.PAIRING &&
    code in setOf("pairing.expired", "pairing.revoked", "pairing.unpaired")

@kotlinx.serialization.Serializable
data class LayoutRect(val x: Int, val y: Int, val width: Int, val height: Int)
@kotlinx.serialization.Serializable
data class LayoutPane(val paneId: String, val focused: Boolean, val rect: LayoutRect)
@kotlinx.serialization.Serializable
data class LayoutSplit(val id: String, val direction: String, val ratio: Double, val rect: LayoutRect)
@kotlinx.serialization.Serializable
data class LayoutSnapshot(
    val workspaceId: String, val tabId: String, val zoomed: Boolean, val area: LayoutRect,
    val focusedPaneId: String, val panes: List<LayoutPane>, val splits: List<LayoutSplit>,
)
fun LayoutSnapshot.valid(): Boolean =
    workspaceId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && tabId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) &&
        area.valid() && focusedPaneId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && panes.size in 1..64 && splits.size <= 63 &&
        panes.all { it.paneId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && it.rect.valid() } &&
        panes.map { it.paneId }.distinct().size == panes.size && panes.any { it.paneId == focusedPaneId } &&
        splits.all { it.id.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && it.direction in setOf("right", "down") && it.ratio.isFinite() && it.ratio > 0.0 && it.ratio < 1.0 && it.rect.valid() }
private fun LayoutRect.valid(): Boolean = x >= 0 && y >= 0 && width in 1..100_000 && height in 1..100_000 && x.toLong() + width <= 100_000 && y.toLong() + height <= 100_000

fun PaneLayoutRead.validForPane(paneId: String, expectedKind: String? = null): Boolean =
    (expectedKind == null || kind == expectedKind) && layout.valid() && layout.panes.any { it.paneId == paneId } &&
        when (kind) {
            "layout" -> true
            "edges" -> this.paneId == paneId && left != null && right != null && up != null && down != null
            "neighbor" -> this.paneId == paneId && direction in setOf("left", "right", "up", "down") &&
                (neighborPaneId == null || layout.panes.any { it.paneId == neighborPaneId })
            else -> false
        }

fun PaneLayoutPlanResponse.validForPane(paneId: String, action: JsonObject): Boolean {
    val kind = (action["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
    if (!ok || this.action != kind || !planToken.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) ||
        expiresAt !in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 60_000) ||
        !layout.validForPane(paneId, "layout")) return false
    fun text(name: String): String? = (effects[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun bool(name: String): Boolean? = (effects[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    fun number(name: String): Double? = (effects[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    fun actionText(name: String): String? = (action[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun exactEffects(vararg names: String): Boolean = effects.keys == names.toSet()
    fun nullableNumber(name: String): Boolean = effects[name] == JsonNull ||
        ((effects[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.isFinite() == true)
    fun nullableText(name: String): Boolean = effects[name] == JsonNull ||
        ((effects[name] as? JsonPrimitive)?.isString == true)
    fun nullableActionNumber(name: String): Boolean = action[name] == null || action[name] == JsonNull ||
        ((action[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.isFinite() == true)
    fun nullableActionText(name: String): Boolean = action[name] == null || action[name] == JsonNull ||
        ((action[name] as? JsonPrimitive)?.isString == true)
    return when (kind) {
        "split" -> exactEffects("verb", "targetPaneId", "direction", "ratio", "focusChanges") && text("verb") == "split" && text("targetPaneId") == paneId && text("direction") == actionText("direction") &&
            nullableNumber("ratio") && nullableActionNumber("ratio") && number("ratio") == ((action["ratio"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull) && bool("focusChanges") == false
        "resize" -> exactEffects("verb", "targetPaneId", "direction", "amount") && text("verb") == "resize" && text("targetPaneId") == paneId && text("direction") == actionText("direction") &&
            nullableNumber("amount") && nullableActionNumber("amount") && number("amount") == ((action["amount"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull)
        "zoom" -> exactEffects("verb", "targetPaneId", "mode", "focusChanges") && text("verb") == (if (actionText("mode") == "toggle") "toggle-zoom" else "${actionText("mode")}-zoom") && text("targetPaneId") == paneId && text("mode") == actionText("mode") && bool("focusChanges") == false
        "swap" -> run {
            val source = actionText("sourcePaneId").takeUnless { it.isNullOrEmpty() } ?: paneId
            val target = text("targetPaneId")
            exactEffects("verb", "sourcePaneId", "targetPaneId", "direction") && text("verb") == "swap" && text("sourcePaneId") == source &&
                (actionText("targetPaneId") == null || target == actionText("targetPaneId")) &&
                nullableText("direction") && nullableActionText("direction") && text("direction") == actionText("direction") && target != null && target != source && layout.layout.panes.any { it.paneId == target }
        }
        "focus-direction" -> exactEffects("verb", "sourcePaneId", "direction", "focusChanges") && text("verb") == "focus" && text("sourcePaneId") == paneId && text("direction") == actionText("direction") && bool("focusChanges") == true
        "move" -> exactEffects("verb", "sourcePaneId", "destination", "focusChanges", "mayCloseSourceTab") && text("verb") == "move" && text("sourcePaneId") == paneId &&
            text("destination") == ((action["destination"] as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content && bool("focusChanges") == false && bool("mayCloseSourceTab") != null
        else -> false
    }
}
@kotlinx.serialization.Serializable
data class PaneLayoutRead(
    val kind: String, val layout: LayoutSnapshot, val paneId: String? = null,
    val direction: String? = null, val neighborPaneId: String? = null, val left: Boolean? = null,
    val right: Boolean? = null, val up: Boolean? = null, val down: Boolean? = null,
)
@kotlinx.serialization.Serializable
data class PaneLayoutReadResponse(val ok: Boolean, val result: PaneLayoutRead)
@kotlinx.serialization.Serializable
data class PaneLayoutPlanResponse(
    val ok: Boolean, val planToken: String, val expiresAt: Long, val action: String,
    val effects: kotlinx.serialization.json.JsonObject, val layout: PaneLayoutRead,
)
@kotlinx.serialization.Serializable
data class PaneMoveResult(
    val kind: String, val paneId: String, val previousPaneId: String,
    val previousTabId: String, val previousWorkspaceId: String,
)
@kotlinx.serialization.Serializable
data class PaneLayoutOperationResponse(
    val ok: Boolean, val code: String? = null, val operation: ControlOperation? = null,
    val move: PaneMoveResult? = null,
)
