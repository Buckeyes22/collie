package com.lateapex.collie.ui

internal enum class PaneSwitcherRelease {
    TAP,
    OPEN,
    CANCEL,
}

/** Pure release decision shared by the switcher handle and its parity tests. */
internal object PaneSwitcherPullDecision {
    const val OPEN_DISTANCE_DP = 120f
    const val FLING_DP_PER_SECOND = 600f

    fun decide(
        travelDp: Float,
        touchSlopDp: Float,
        upwardVelocityDpPerSecond: Float,
    ): PaneSwitcherRelease {
        if (kotlin.math.abs(travelDp) <= touchSlopDp) return PaneSwitcherRelease.TAP
        if (travelDp >= OPEN_DISTANCE_DP) return PaneSwitcherRelease.OPEN
        return if (travelDp > touchSlopDp && upwardVelocityDpPerSecond >= FLING_DP_PER_SECOND) {
            PaneSwitcherRelease.OPEN
        } else {
            PaneSwitcherRelease.CANCEL
        }
    }
}
