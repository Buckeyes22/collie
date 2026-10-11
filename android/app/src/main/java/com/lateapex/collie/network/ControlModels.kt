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
        (result == null || (result.kind == "pane-move" && listOf(result.paneId, result.previousPaneId, result.previousTabId, result.previousWorkspaceId).all { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) } &&
            (result.transfer == null || result.transfer.valid()))) &&
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
    val previousTabId: String, val previousWorkspaceId: String, val transfer: PaneTransferResult? = null,
)
@kotlinx.serialization.Serializable
data class PaneTransferResult(
    val schema: Int, val changed: Boolean, val targetTabId: String, val targetWorkspaceId: String, val processWitness: String,
    val createdTabId: String? = null, val createdWorkspaceId: String? = null, val closedTabId: String? = null, val closedWorkspaceId: String? = null,
)
private fun PaneTransferResult.valid(): Boolean = schema == 1 && changed && targetTabId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) &&
    targetWorkspaceId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && processWitness.matches(Regex("[a-f0-9]{64}")) &&
    listOf(createdTabId, createdWorkspaceId, closedTabId, closedWorkspaceId).all { it == null || it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) }
@kotlinx.serialization.Serializable
data class PaneTransferMemberDto(val paneId: String, val witness: String)
@kotlinx.serialization.Serializable
data class PaneTransferDestinationDto(
    val type: String, val workspaceId: String? = null, val label: String? = null, val tabLabel: String? = null,
)
@kotlinx.serialization.Serializable
data class PaneTransferRecipeStepDto(val direction: String, val ratio: Double)
@kotlinx.serialization.Serializable
data class PaneTransferSourceDto(val workspaceId: String, val tabId: String, val layout: LayoutSnapshot, val members: List<PaneTransferMemberDto>)
@kotlinx.serialization.Serializable
data class PaneTransferEffectsDto(
    val createsDestinationWithFirstExistingPane: Boolean, val preservesExistingProcesses: Boolean, val reflow: Boolean,
    val mayCloseEmptySourceTab: Boolean, val mayCloseEmptySourceWorkspace: Boolean, val mayChangeFocusWhenSourceEmpties: Boolean,
    val createsNoReplacementShell: Boolean, val closesNoBranchOrWorkspaceGroup: Boolean,
)
@kotlinx.serialization.Serializable
data class PaneTransferPlanResponse(
    val ok: Boolean, val schema: Int, val source: PaneTransferSourceDto, val destination: PaneTransferDestinationDto,
    val recipe: List<PaneTransferRecipeStepDto>, val effects: PaneTransferEffectsDto,
) {
    fun validForTab(tabId: String): Boolean = ok && schema == 1 && source.tabId == tabId && source.workspaceId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) &&
        source.layout.valid() && source.layout.tabId == tabId && source.layout.workspaceId == source.workspaceId && source.members.size in 1..64 &&
        source.members.map { it.paneId }.distinct().size == source.members.size && source.members.map { it.witness }.distinct().size == source.members.size &&
        source.members.all { it.paneId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) && it.witness.matches(Regex("[a-f0-9]{64}")) } &&
        source.layout.panes.map { it.paneId }.toSet() == source.members.map { it.paneId }.toSet() && recipe.size == source.members.size - 1 &&
        recipe.all { it.direction in setOf("right", "down") && it.ratio.isFinite() && it.ratio > 0.0 && it.ratio < 1.0 } &&
        (destination.type == "new_tab" && destination.workspaceId?.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) == true && destination.workspaceId != source.workspaceId && destination.tabLabel == null &&
            (destination.label == null || destination.label.length <= 128) ||
            destination.type == "new_workspace" && destination.workspaceId == null && !destination.label.isNullOrBlank() && destination.label.length <= 128 &&
                !destination.tabLabel.isNullOrBlank() && destination.tabLabel.length <= 128) &&
        effects.createsDestinationWithFirstExistingPane && effects.preservesExistingProcesses && effects.reflow && effects.mayCloseEmptySourceWorkspace == false &&
        effects.mayCloseEmptySourceTab == (source.members.size == 1) && effects.mayChangeFocusWhenSourceEmpties &&
        effects.createsNoReplacementShell && effects.closesNoBranchOrWorkspaceGroup
}
@Serializable
data class PaneTransferDestinationGuardDto(val workspaceId: String, val tabId: String, val members: List<PaneTransferMemberDto>)
@Serializable
data class PaneTransferGuardDto(
    val schema: Int, val transferId: String, val sourceWorkspaceId: String, val sourceTabId: String,
    val remainingSource: List<PaneTransferMemberDto>, val destination: PaneTransferDestinationGuardDto? = null,
)
@kotlinx.serialization.Serializable
data class PaneLayoutOperationResponse(
    val ok: Boolean, val code: String? = null, val operation: ControlOperation? = null,
    val move: PaneMoveResult? = null,
)

/** Closed, typed workspace/tab organization intents; no caller can supply an arbitrary RPC name. */
sealed interface OrganizationAction {
    val kind: String
    val capability: String
    val path: List<String>
    val payload: JsonObject
    data class RenameWorkspace(val workspaceId: String, val label: String) : OrganizationAction {
        override val kind = "workspace-rename"
        override val capability = "workspaceRename"
        override val path = listOf("workspace", workspaceId, "rename")
        override val payload = kotlinx.serialization.json.buildJsonObject { put("label", JsonPrimitive(label)) }
    }
    data class CloseWorkspace(val workspaceId: String, val closeGroup: Boolean) : OrganizationAction {
        override val kind = "workspace-close"
        override val capability = "workspaceClose"
        override val path = listOf("workspace", workspaceId, "close")
        override val payload = kotlinx.serialization.json.buildJsonObject { put("closeGroup", JsonPrimitive(closeGroup)) }
    }
    data class MoveWorkspace(val workspaceId: String, val insertIndex: Int) : OrganizationAction {
        override val kind = "workspace-move"
        override val capability = "workspaceMove"
        override val path = listOf("workspace", workspaceId, "move")
        override val payload = kotlinx.serialization.json.buildJsonObject { put("insertIndex", JsonPrimitive(insertIndex)) }
    }
    data class ReorderWorkspaces(val workspaceIds: List<String>, val beforeWorkspaceId: String?) : OrganizationAction {
        override val kind = "workspace-reorder"
        override val capability = "workspaceMoveBlock"
        override val path = listOf("workspaces", "reorder")
        override val payload = kotlinx.serialization.json.buildJsonObject {
            put("workspaceIds", kotlinx.serialization.json.JsonArray(workspaceIds.map(::JsonPrimitive)))
            put("beforeWorkspaceId", beforeWorkspaceId?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
    data class FocusWorkspace(val workspaceId: String) : OrganizationAction {
        override val kind = "workspace-focus"
        override val capability = "workspaceFocus"
        override val path = listOf("workspace", workspaceId, "focus")
        override val payload = kotlinx.serialization.json.buildJsonObject { }
    }
    data class MoveTab(val tabId: String, val insertIndex: Int) : OrganizationAction {
        override val kind = "tab-move"
        override val capability = "tabMove"
        override val path = listOf("tab", tabId, "move")
        override val payload = kotlinx.serialization.json.buildJsonObject { put("insertIndex", JsonPrimitive(insertIndex)) }
    }
    data class FocusTab(val tabId: String) : OrganizationAction {
        override val kind = "tab-focus"
        override val capability = "tabFocus"
        override val path = listOf("tab", tabId, "focus")
        override val payload = kotlinx.serialization.json.buildJsonObject { }
    }
    data class RenameTab(val tabId: String, val label: String) : OrganizationAction {
        override val kind = "tab-rename"
        override val capability = "renameTab"
        override val path = listOf("tab", tabId, "rename")
        override val payload = kotlinx.serialization.json.buildJsonObject { put("label", JsonPrimitive(label)) }
    }
    data class CloseTab(val tabId: String) : OrganizationAction {
        override val kind = "tab-close"
        override val capability = "closeTab"
        override val path = listOf("tab", tabId, "close")
        override val payload = kotlinx.serialization.json.buildJsonObject { }
    }
}

private fun OrganizationAction.expectedAction(): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("kind", JsonPrimitive(kind))
    when (this@expectedAction) {
        is OrganizationAction.RenameWorkspace -> { put("workspaceId", JsonPrimitive(workspaceId)); put("label", JsonPrimitive(label)) }
        is OrganizationAction.CloseWorkspace -> { put("workspaceId", JsonPrimitive(workspaceId)); put("closeGroup", JsonPrimitive(closeGroup)) }
        is OrganizationAction.MoveWorkspace -> { put("workspaceId", JsonPrimitive(workspaceId)); put("insertIndex", JsonPrimitive(insertIndex)) }
        is OrganizationAction.ReorderWorkspaces -> {
            put("workspaceIds", kotlinx.serialization.json.JsonArray(workspaceIds.map(::JsonPrimitive)))
            put("beforeWorkspaceId", beforeWorkspaceId?.let(::JsonPrimitive) ?: JsonNull)
        }
        is OrganizationAction.FocusWorkspace -> put("workspaceId", JsonPrimitive(workspaceId))
        is OrganizationAction.MoveTab -> { put("tabId", JsonPrimitive(tabId)); put("insertIndex", JsonPrimitive(insertIndex)) }
        is OrganizationAction.FocusTab -> put("tabId", JsonPrimitive(tabId))
        is OrganizationAction.RenameTab -> { put("tabId", JsonPrimitive(tabId)); put("label", JsonPrimitive(label)) }
        is OrganizationAction.CloseTab -> put("tabId", JsonPrimitive(tabId))
    }
}

@Serializable
data class OrganizationPlanResponse(
    val ok: Boolean,
    val planToken: String,
    val expiresAt: Long,
    val action: JsonObject,
    val effects: JsonObject,
    val workspaceOrder: List<String> = emptyList(),
    val tabOrder: List<String> = emptyList(),
)
fun OrganizationPlanResponse.validFor(action: OrganizationAction): Boolean = ok &&
    planToken.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) &&
    expiresAt in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 60_000) &&
    this.action == action.expectedAction() &&
    workspaceOrder.size <= 256 && workspaceOrder.distinct().size == workspaceOrder.size &&
    tabOrder.size <= 256 && tabOrder.distinct().size == tabOrder.size && effects.validFor(action)

private val ORGANIZATION_ID = Regex("[A-Za-z0-9_.:-]{1,128}")
private fun kotlinx.serialization.json.JsonElement.stringValue(maxLength: Int = 256, allowEmpty: Boolean = false): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { (allowEmpty || it.isNotEmpty()) && it.length <= maxLength }
private fun kotlinx.serialization.json.JsonElement.booleanValue(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private fun kotlinx.serialization.json.JsonObject.stringList(name: String, maxRows: Int, allowEmpty: Boolean = false): List<String>? {
    val rows = this[name] as? kotlinx.serialization.json.JsonArray ?: return null
    if (rows.size > maxRows) return null
    return rows.map { it.stringValue(128, allowEmpty) ?: return null }
}
private fun kotlinx.serialization.json.JsonObject.paneRows(name: String, workspaceIds: Set<String>? = null): Boolean {
    val rows = this[name] as? kotlinx.serialization.json.JsonArray ?: return false
    if (rows.size > 2_048) return false
    return rows.all { row ->
        val pane = row as? JsonObject ?: return@all false
        val paneId = pane["paneId"]?.stringValue(128) ?: return@all false
        val label = pane["label"]?.stringValue(256) ?: return@all false
        if (workspaceIds == null) {
            pane.keys == setOf("paneId", "label") && ORGANIZATION_ID.matches(paneId) && label.length <= 256
        } else {
            val workspaceId = pane["workspaceId"]?.stringValue(128) ?: return@all false
            val tabId = pane["tabId"]?.stringValue(128) ?: return@all false
            pane.keys == setOf("paneId", "workspaceId", "tabId", "label") && ORGANIZATION_ID.matches(paneId) &&
                ORGANIZATION_ID.matches(workspaceId) && ORGANIZATION_ID.matches(tabId) && workspaceId in workspaceIds && label.length <= 256
        }
    }
}
private fun JsonObject.validFor(action: OrganizationAction): Boolean {
    return when (action) {
        is OrganizationAction.CloseWorkspace -> {
            val ids = stringList("workspaceIds", 256) ?: return false
            val preview = stringList("groupPreviewIds", 256) ?: return false
            val labels = stringList("workspaceLabels", 256, allowEmpty = true) ?: return false
            val groupRequired = this["groupCloseRequired"]?.booleanValue() ?: return false
            val deletes = this["deletesCheckouts"]?.booleanValue() ?: return false
            val expectedKeys = setOf("kind", "workspaceIds", "workspaceLabels", "panes", "groupPreviewIds", "groupCloseRequired", "deletesCheckouts")
            this["kind"]?.stringValue(32) == "close" && keys == expectedKeys && action.workspaceId in preview &&
                preview.distinct().size == preview.size && ids.distinct().size == ids.size && ids.isNotEmpty() &&
                (if (action.closeGroup) ids == preview else ids == listOf(action.workspaceId)) && labels.size == ids.size &&
                groupRequired == (preview.size > 1) && !deletes && paneRows("panes", ids.toSet())
        }
        is OrganizationAction.CloseTab -> {
            val expectedKeys = setOf("kind", "tabId", "workspaceId", "panes", "closesProcesses")
            val workspaceId = this["workspaceId"]?.stringValue(128) ?: return false
            val closesProcesses = this["closesProcesses"]?.booleanValue() ?: return false
            this["kind"]?.stringValue(32) == action.kind && keys == expectedKeys &&
                this["tabId"]?.stringValue(128) == action.tabId && ORGANIZATION_ID.matches(workspaceId) &&
                closesProcesses && paneRows("panes")
        }
        else -> true
    }
}

@Serializable
data class OrganizationOperationResponse(
    val ok: Boolean,
    val code: String? = null,
    val operation: ControlOperation? = null,
)
fun OrganizationOperationResponse.validFor(requestId: String, action: OrganizationAction): Boolean {
    val receipt = operation ?: return false
    return receipt.validFor(requestId) && receipt.capability == action.capability &&
        if (ok) receipt.state == "succeeded" && receipt.result == null
        else receipt.state in setOf("pending", "unknown") && code == "organization.outcome_unknown" && receipt.result == null
}
