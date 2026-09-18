package com.lateapex.collie.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrWatchdogTest {
    @Test
    fun notBlockedWhenTheLastAckIsWithinTheTimeout() {
        assertFalse(AnrWatchdog.isBlocked(lastAckAt = 1_000L, now = 4_000L, timeoutMs = 5_000L))
    }

    @Test
    fun blockedOnceTheLastAckIsOlderThanTheTimeout() {
        assertTrue(AnrWatchdog.isBlocked(lastAckAt = 1_000L, now = 7_000L, timeoutMs = 5_000L))
    }

    @Test
    fun exactlyAtTheTimeoutBoundaryIsNotYetBlocked() {
        assertFalse(AnrWatchdog.isBlocked(lastAckAt = 1_000L, now = 6_000L, timeoutMs = 5_000L))
    }
}
