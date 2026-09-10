package com.lateapex.collie.ui

import com.lateapex.collie.network.DeviceAuthorization
import com.lateapex.collie.network.SnapshotResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardShellPresentationTest {
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

}
