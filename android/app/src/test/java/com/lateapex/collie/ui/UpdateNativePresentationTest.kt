package com.lateapex.collie.ui

import com.lateapex.collie.network.PreflightCheck
import com.lateapex.collie.network.PreflightReport
import com.lateapex.collie.network.UpdateCheckResponse
import com.lateapex.collie.network.UpdatePackMember
import com.lateapex.collie.network.UpdatePeerLeg
import com.lateapex.collie.network.UpdateRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNativePresentationTest {
    @Test
    fun `peer legs override census and rows sort worst first`() {
        val value = state().copy(
            pack = listOf(
                member("healthy", "green", "1.6.0"),
                member("unknown", "unknown", null, listOf("timed out")),
                member("moving", "red", "1.5.0", listOf("old preflight")),
            ),
            run = run("verifying").copy(peers = listOf(
                leg("moving", "updating", "1.6.0"),
                leg("failed", "rolled-back", "1.5.0", "doctor failed"),
            )),
        )

        val rows = UpdateNativePresentation.peerRows(value.pack.orEmpty(), value.run?.peers.orEmpty())

        assertEquals(listOf("failed", "unknown", "moving", "healthy"), rows.map { it.name })
        assertEquals("doctor failed", rows.first().reason)
        assertTrue(rows.first { it.name == "moving" }.inFlight)
        assertNull(rows.first { it.name == "moving" }.reason)
    }

    @Test
    fun `action prioritizes release then peer recovery`() {
        val solo = state()
        val pack = solo.copy(pack = listOf(member("peer", "green", "1.5.0")))
        val currentWithBehindPeer = pack.copy(releaseAvailable = false, latest = "1.6.0", current = "1.6.0")
        val currentWithFailedPeer = currentWithBehindPeer.copy(
            pack = emptyList(),
            run = run("rolled-back").copy(peers = listOf(leg("peer", "interrupted", "1.5.0"))),
        )

        assertEquals(NativeUpdateAction.UPDATE, UpdateNativePresentation.action(solo))
        assertEquals(NativeUpdateAction.UPDATE_PACK, UpdateNativePresentation.action(pack))
        assertEquals(NativeUpdateAction.RETRY_PACK, UpdateNativePresentation.action(currentWithBehindPeer))
        assertEquals(NativeUpdateAction.RETRY_PACK, UpdateNativePresentation.action(currentWithFailedPeer))
        assertEquals("1.6.0", UpdateNativePresentation.actionTarget(currentWithBehindPeer))
    }

    @Test
    fun `preflight blocks ordinary and major actions but not peers-only retry`() {
        val red = state().copy(preflight = report("red"))
        val retry = red.copy(releaseAvailable = false, current = "1.6.0", pack = listOf(member("peer", "red", "1.5.0")))

        assertFalse(UpdateNativePresentation.canStart(red, busy = false, writeAuthorized = true))
        assertFalse(UpdateNativePresentation.canStartMajor(red.copy(majorAvailable = "2.0.0"), false, true))
        assertTrue(UpdateNativePresentation.canStart(retry, busy = false, writeAuthorized = true))
        assertFalse(UpdateNativePresentation.canStart(retry, busy = true, writeAuthorized = true))
        assertFalse(UpdateNativePresentation.canStart(retry, busy = false, writeAuthorized = false))
    }

    @Test
    fun `preflight opens only for action or red and reports worst row`() {
        val amber = state().copy(releaseAvailable = false, preflight = report("amber"))
        val red = amber.copy(preflight = report("red"))

        assertFalse(UpdateNativePresentation.shouldOpenPreflight(amber))
        assertTrue(UpdateNativePresentation.shouldOpenPreflight(red))
        assertEquals("red", UpdateNativePresentation.worstPreflight(red.preflight!!.checks))
        assertTrue(UpdateNativePresentation.shouldOpenPreflight(state()))
    }

    @Test
    fun `run visibility freshness and complete state match card contract`() {
        val idle = state().copy(releaseAvailable = false, latest = "1.5.0", run = run("idle"))
        val active = idle.copy(run = run("restarting"))

        assertNull(UpdateNativePresentation.visibleRun(idle))
        assertTrue(UpdateNativePresentation.hasActiveRun(active))
        assertEquals("verifying", UpdateNativePresentation.freshestRun(run("staging"), run("verifying").copy(updatedAt = 2)).state)
        assertTrue(UpdateNativePresentation.isUpToDate(idle))
        assertFalse(UpdateNativePresentation.isUpToDate(active))
    }

    private fun state() = UpdateCheckResponse(
        current = "1.5.0",
        latest = "1.6.0",
        latestUrl = null,
        releaseAvailable = true,
        majorAvailable = null,
        majorUrl = null,
        bridgeStale = false,
        checkedAt = 1,
        newerVersions = listOf("1.5.1", "1.6.0"),
        preflight = report("green"),
    )

    private fun report(verdict: String) = PreflightReport(
        schema = 1,
        verdict = verdict,
        checks = listOf(PreflightCheck("doctor", verdict, "$verdict result", "collie doctor")),
    )

    private fun member(name: String, verdict: String, version: String?, reasons: List<String> = emptyList()) =
        UpdatePackMember(name, version, verdict, reasons, 10)

    private fun leg(name: String, state: String, version: String?, reason: String? = null) =
        UpdatePeerLeg(name, state, version, reason, 20)

    private fun run(state: String) = UpdateRun(
        schema = 1,
        state = state,
        from = "1.5.0",
        to = "1.6.0",
        startedAt = 1,
        updatedAt = 1,
        pid = 1,
        attempt = 1,
    )
}
