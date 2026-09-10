package com.lateapex.collie.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneSwitcherPullDecisionTest {
    @Test
    fun tapWithinTouchSlopUsesTheHandleClickFallback() {
        assertEquals(PaneSwitcherRelease.TAP, PaneSwitcherPullDecision.decide(6f, 6f, 0f))
        assertEquals(PaneSwitcherRelease.TAP, PaneSwitcherPullDecision.decide(-6f, 6f, 2_000f))
    }

    @Test
    fun thresholdPullOpensRegardlessOfVelocity() {
        assertEquals(PaneSwitcherRelease.OPEN, PaneSwitcherPullDecision.decide(120f, 6f, 0f))
    }

    @Test
    fun shortUpwardFlingOpensOnlyAfterTouchSlop() {
        assertEquals(PaneSwitcherRelease.OPEN, PaneSwitcherPullDecision.decide(7f, 6f, 600f))
        assertEquals(PaneSwitcherRelease.TAP, PaneSwitcherPullDecision.decide(6f, 6f, 2_000f))
    }

    @Test
    fun slowShortOrDownwardDragsCancel() {
        assertEquals(PaneSwitcherRelease.CANCEL, PaneSwitcherPullDecision.decide(119f, 6f, 599f))
        assertEquals(PaneSwitcherRelease.CANCEL, PaneSwitcherPullDecision.decide(-40f, 6f, 2_000f))
    }
}
