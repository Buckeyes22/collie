package com.lateapex.collie.ui

import com.lateapex.collie.network.DeviceAuthorization
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.UpdateInfo
import com.lateapex.collie.network.UpdatePeerLeg
import com.lateapex.collie.network.UpdateRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardShellPresentationTest {
    @Test
    fun activeRunAndFreshPeerStateOutrankRestartAndOffers() {
        val active = update(
            bridgeStale = true,
            available = true,
            run = run("staging", peers = listOf(UpdatePeerLeg("peer", "rolled-back", reason = "bad"))),
        )
        val activeView = DashboardUpdateRibbonPresentation.view(active, null, 2_000)
        assertEquals(DashboardRibbonKind.UPDATING, activeView.kind)
        assertEquals("building", activeView.reason)

        val failed = active.copy(run = run("done", updatedAt = 1_000, peers = listOf(
            UpdatePeerLeg("peer", "rolled-back", reason = "a very long reason that must stop cleanly at a word boundary"),
        )))
        val failedView = DashboardUpdateRibbonPresentation.view(failed, null, 2_000)
        assertEquals(DashboardRibbonKind.PEER_FAILED, failedView.kind)
        assertEquals("peer", failedView.peer)
        assertTrue(failedView.reason.endsWith("…"))
        assertTrue(failedView.reason.length <= DashboardUpdateRibbonPresentation.REASON_BUDGET + 1)
    }

    @Test
    fun restartAndOffersNavigateButOnlyOffersCanBeDismissed() {
        val restart = DashboardUpdateRibbonPresentation.view(update(bridgeStale = true), null, 0)
        assertEquals(DashboardRibbonKind.RESTART_REQUIRED, restart.kind)
        assertFalse(restart.dismissable)

        val offer = DashboardUpdateRibbonPresentation.view(update(available = true), null, 0)
        assertEquals(DashboardRibbonKind.AVAILABLE, offer.kind)
        assertTrue(offer.dismissable)
        assertEquals(
            DashboardRibbonKind.SILENT,
            DashboardUpdateRibbonPresentation.view(update(available = true), "1.2.0", 0).kind,
        )
    }

    @Test
    fun connectionEscalatesAtFourAndFifteenSecondsAndRedSurvivesWake() {
        val health = DashboardConnectionHealth(0)
        val failed = input(snapshotFailed = true)

        assertEquals(DashboardConnectionTone.SILENT, health.view(failed, 3_999).tone)
        assertEquals(DashboardConnectionTone.AMBER, health.view(failed, 4_000).tone)
        assertEquals(DashboardConnectionTone.RED, health.view(failed, 15_000).tone)
        health.markWake(20_000)
        assertEquals(DashboardConnectionTone.RED, health.view(failed, 20_001).tone)
    }

    @Test
    fun onlyRecoveryFromAVisibleBarFlashesGreenForOnePointEightSeconds() {
        val quiet = DashboardConnectionHealth(0)
        quiet.view(input(snapshotFailed = true), 2_000)
        quiet.markLive(2_000)
        assertEquals(DashboardConnectionTone.SILENT, quiet.view(input(), 2_001).tone)

        val visible = DashboardConnectionHealth(0)
        visible.view(input(snapshotFailed = true), 4_000)
        visible.markLive(5_000)
        assertEquals(DashboardConnectionTone.GREEN, visible.view(input(), 6_799).tone)
        assertEquals(DashboardConnectionTone.SILENT, visible.view(input(), 6_800).tone)
    }

    @Test
    fun stalledRequestJoinsTheSameSharedClockAndAuthIsImmediate() {
        val health = DashboardConnectionHealth(0)
        val request = input(requestStartedAt = 0)
        assertEquals(DashboardConnectionTone.SILENT, health.view(request, 2_499).tone)
        assertEquals(DashboardConnectionTone.SILENT, health.view(request, 3_000).tone)
        assertEquals(DashboardConnectionTone.AMBER, health.view(request, 4_000).tone)
        assertEquals(DashboardConnectionTone.AUTH, health.view(input(authError = true), 1).tone)
    }

    @Test
    fun disconnectedSnapshotNamesHerdrAsTheCause() {
        val health = DashboardConnectionHealth(0)
        val view = health.view(input(snapshot = snapshot("disconnected")), 15_000)
        assertEquals(DashboardConnectionTone.RED, view.tone)
        assertEquals(DashboardConnectionCause.HERDR_DOWN, view.cause)
    }

    private fun input(
        snapshot: SnapshotResponse? = snapshot("connected"),
        snapshotFailed: Boolean = false,
        authError: Boolean = false,
        requestStartedAt: Long? = null,
    ) = DashboardConnectionInput(true, snapshot, snapshotFailed, authError, requestStartedAt)

    private fun snapshot(bridge: String) = SnapshotResponse(
        bridge = bridge,
        device = DeviceAuthorization(false, null, true),
        agents = emptyList(),
        shellPanes = emptyList(),
        workspaces = emptyList(),
        tabs = emptyList(),
        ts = 0,
    )

    private fun update(
        bridgeStale: Boolean = false,
        available: Boolean = false,
        run: UpdateRun? = null,
    ) = UpdateInfo(
        current = "1.1.0",
        latest = "1.2.0",
        latestUrl = null,
        releaseAvailable = available,
        majorAvailable = null,
        majorUrl = null,
        bridgeStale = bridgeStale,
        checkedAt = 0,
        run = run,
    )

    private fun run(
        state: String,
        updatedAt: Long = 0,
        peers: List<UpdatePeerLeg>? = null,
    ) = UpdateRun(
        schema = 1,
        state = state,
        from = "1.1.0",
        to = "1.2.0",
        startedAt = 0,
        updatedAt = updatedAt,
        pid = 1,
        attempt = 1,
        peers = peers,
    )
}
