package com.lateapex.collie.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HardeningLifecyclePollingBackoffTest {
    @Test
    fun failuresDoubleDelayUntilCapAndSuccessResetsIt() {
        val backoff = PollingBackoff(maxDelayMs = 1_000L)

        assertEquals(100L, backoff.delayAfter(100L))
        backoff.failed()
        assertEquals(200L, backoff.delayAfter(100L))
        backoff.failed()
        assertEquals(400L, backoff.delayAfter(100L))
        backoff.failed()
        assertEquals(800L, backoff.delayAfter(100L))
        backoff.failed()
        assertEquals(1_000L, backoff.delayAfter(100L))
        backoff.failed()
        assertEquals(1_000L, backoff.delayAfter(100L))

        backoff.succeeded()
        assertEquals(100L, backoff.delayAfter(100L))
    }

    @Test
    fun largeInputsClampWithoutOverflowAndNonPositiveBaseUsesOneMillisecond() {
        val backoff = PollingBackoff(maxDelayMs = Long.MAX_VALUE)
        backoff.failed()

        assertEquals(Long.MAX_VALUE, backoff.delayAfter(Long.MAX_VALUE / 2 + 1))
        assertEquals(1L, PollingBackoff().delayAfter(0L))
        assertEquals(1L, PollingBackoff().delayAfter(-100L))
    }
}
