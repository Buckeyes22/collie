package com.lateapex.collie.ui

import com.lateapex.collie.network.PreflightCheck
import com.lateapex.collie.network.UpdateCheckResponse
import com.lateapex.collie.network.UpdatePackMember
import com.lateapex.collie.network.UpdatePeerLeg
import com.lateapex.collie.network.UpdateRun

internal enum class NativeUpdateAction {
    UPDATE,
    UPDATE_PACK,
    RETRY_PACK,
    NONE,
}

internal data class NativeUpdatePeerRow(
    val name: String,
    val version: String?,
    val state: String,
    val reason: String?,
    val asOf: Long?,
    val rank: Int,
    val inFlight: Boolean,
)

/** Pure presentation rules shared with the web UpdateCard contract. */
internal object UpdateNativePresentation {
    val activeStates = setOf("preflight", "staging", "restarting", "verifying")
    private val failedPeerStates = setOf("rolled-back", "unreachable", "stuck", "interrupted")

    fun visibleRun(value: UpdateCheckResponse): UpdateRun? = value.run?.takeUnless { it.state == "idle" }

    fun hasActiveRun(value: UpdateCheckResponse?): Boolean = value?.run?.state in activeStates

    fun freshestRun(current: UpdateRun?, standby: UpdateRun): UpdateRun =
        if (current != null && current.updatedAt > standby.updatedAt) current else standby

    fun peerRows(
        pack: List<UpdatePackMember> = emptyList(),
        legs: List<UpdatePeerLeg> = emptyList(),
    ): List<NativeUpdatePeerRow> {
        val byName = linkedMapOf<String, NativeUpdatePeerRow>()
        pack.forEach { member ->
            val needsReason = member.verdict == "red" || member.verdict == "unknown"
            byName[member.name] = NativeUpdatePeerRow(
                name = member.name,
                version = member.version,
                state = member.verdict,
                reason = member.reasons.joinToString(" · ").takeIf { needsReason && it.isNotBlank() },
                asOf = member.asOf,
                rank = when (member.verdict) {
                    "red" -> 1
                    "unknown" -> 2
                    "amber" -> 3
                    else -> 5
                },
                inFlight = false,
            )
        }
        legs.forEach { leg ->
            val census = byName[leg.name]
            val failed = leg.state in failedPeerStates
            byName[leg.name] = NativeUpdatePeerRow(
                name = leg.name,
                version = leg.version ?: census?.version,
                state = leg.state,
                reason = leg.reason.takeIf { failed },
                asOf = leg.updatedAt ?: census?.asOf,
                rank = when {
                    failed -> 0
                    leg.state in activeStates || leg.state == "updating" -> 4
                    else -> 5
                },
                inFlight = leg.state in activeStates || leg.state == "updating",
            )
        }
        return byName.values.sortedWith(compareBy<NativeUpdatePeerRow> { it.rank }.thenBy { it.name })
    }

    fun peersBehind(value: UpdateCheckResponse): Int = value.pack.orEmpty().count {
        value.current.isNotEmpty() && it.version != null && it.version != value.current
    }

    fun peersFailed(value: UpdateCheckResponse): Int = visibleRun(value)?.peers.orEmpty().count {
        it.state in failedPeerStates
    }

    fun action(value: UpdateCheckResponse): NativeUpdateAction {
        val hasPeers = value.pack.orEmpty().isNotEmpty() || visibleRun(value)?.peers.orEmpty().isNotEmpty()
        return when {
            value.releaseAvailable && hasPeers -> NativeUpdateAction.UPDATE_PACK
            value.releaseAvailable -> NativeUpdateAction.UPDATE
            peersBehind(value) > 0 || peersFailed(value) > 0 -> NativeUpdateAction.RETRY_PACK
            else -> NativeUpdateAction.NONE
        }
    }

    fun actionTarget(value: UpdateCheckResponse, action: NativeUpdateAction = action(value)): String? = when (action) {
        NativeUpdateAction.UPDATE, NativeUpdateAction.UPDATE_PACK -> value.latest ?: value.current.takeIf { it.isNotEmpty() }
        NativeUpdateAction.RETRY_PACK -> value.current.takeIf { it.isNotEmpty() }
        NativeUpdateAction.NONE -> null
    }

    fun redCheck(value: UpdateCheckResponse): PreflightCheck? = value.preflight?.checks?.firstOrNull {
        it.verdict == "red"
    }

    fun canStart(
        value: UpdateCheckResponse,
        busy: Boolean,
        writeAuthorized: Boolean,
        action: NativeUpdateAction = action(value),
    ): Boolean {
        if (!writeAuthorized || busy || value.run?.state in activeStates || action == NativeUpdateAction.NONE) return false
        if (action == NativeUpdateAction.RETRY_PACK) return true
        return value.preflight != null && redCheck(value) == null
    }

    fun canStartMajor(value: UpdateCheckResponse, busy: Boolean, writeAuthorized: Boolean): Boolean =
        writeAuthorized && !busy && value.majorAvailable != null && value.run?.state !in activeStates &&
            value.preflight != null && redCheck(value) == null

    fun isUpToDate(value: UpdateCheckResponse): Boolean =
        !value.releaseAvailable && value.majorAvailable == null && value.latest != null &&
            visibleRun(value) == null && peersBehind(value) == 0

    fun worstPreflight(checks: List<PreflightCheck>): String = when {
        checks.any { it.verdict == "red" } -> "red"
        checks.any { it.verdict == "amber" } -> "amber"
        else -> "green"
    }

    fun shouldOpenPreflight(value: UpdateCheckResponse): Boolean {
        val checks = value.preflight?.checks.orEmpty()
        return value.releaseAvailable || value.majorAvailable != null || worstPreflight(checks) == "red"
    }
}

/** Compatibility facade retained for existing route tests and call sites. */
internal object UpdateActionGate {
    fun target(value: UpdateCheckResponse): String? = UpdateNativePresentation.actionTarget(value)

    fun canStart(value: UpdateCheckResponse, busy: Boolean, writeAuthorized: Boolean): Boolean =
        UpdateNativePresentation.canStart(value, busy, writeAuthorized) && value.preflight?.verdict != "red"

    fun visibleRun(value: UpdateCheckResponse): UpdateRun? = UpdateNativePresentation.visibleRun(value)

    fun hasActiveRun(value: UpdateCheckResponse?): Boolean = UpdateNativePresentation.hasActiveRun(value)

    fun freshestRun(current: UpdateRun?, standby: UpdateRun): UpdateRun =
        UpdateNativePresentation.freshestRun(current, standby)
}
