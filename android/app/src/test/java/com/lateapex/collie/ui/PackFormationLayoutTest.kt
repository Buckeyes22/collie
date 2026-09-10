package com.lateapex.collie.ui

import com.lateapex.collie.network.PackMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackFormationLayoutTest {
    @Test
    fun `lead and deputy form the centered spine and peers fan left first`() {
        val members = listOf(
            member("lead", lead = true),
            member("deputy"),
            member("peer-a"),
            member("peer-b"),
            member("peer-c"),
        )

        val nodes = PackFormationLayout.nodes(members, "deputy")

        assertEquals(PackFormationRole.LEAD, nodes[0].role)
        assertEquals(180f, nodes[0].x)
        assertEquals(58f, nodes[0].y)
        assertEquals(PackFormationRole.DEPUTY, nodes[1].role)
        assertEquals(180f, nodes[1].x)
        assertEquals(168f, nodes[1].y)
        assertEquals(112f, nodes[2].x)
        assertEquals(248f, nodes[3].x)
        assertTrue(nodes[4].x < nodes[2].x)
    }

    @Test
    fun `a missing deputy does not reserve an empty rank`() {
        val nodes = PackFormationLayout.nodes(listOf(member("lead", lead = true), member("peer")), "departed")

        assertEquals(2, nodes.size)
        assertEquals(PackFormationRole.PEER, nodes[1].role)
        assertEquals(154f, nodes[1].y)
        assertEquals(nodes.maxOf(PackFormationNode::y) + 70f, PackFormationLayout.height(nodes))
    }

    @Test
    fun `formation ring uses shared host freshness while loud protocol states win`() {
        val member = member("peer")

        assertEquals(PackRingState.LIVE, packRingState(member, DashboardHostState.LIVE))
        assertEquals(PackRingState.STALE, packRingState(member, DashboardHostState.STALE))
        assertEquals(PackRingState.UNKNOWN, packRingState(member, DashboardHostState.UNKNOWN))
        assertEquals(
            PackRingState.LOUD,
            packRingState(member.copy(health = "incompatible"), DashboardHostState.LIVE),
        )
        assertEquals(
            PackRingState.LOUD,
            packRingState(member.copy(health = "conflicted"), DashboardHostState.UNKNOWN),
        )
    }

    private fun member(id: String, lead: Boolean = false) = PackMember(
        id = id,
        name = id,
        isLead = lead,
        health = "reachable",
        lastSeenAt = 1,
        secretBehind = false,
        provisional = false,
    )
}
