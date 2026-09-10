package com.lateapex.collie.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedConfirmationTest {
    @Test
    fun confirmsOnlyTheSameValueWithinTheWindowAndConsumesIt() {
        var now = 100L
        val confirmation = TimedConfirmation<String>(3_000L) { now }

        assertFalse(confirmation.confirm("first"))
        assertTrue(confirmation.isPending("first"))
        assertFalse(confirmation.confirm("changed"))
        assertFalse(confirmation.isPending("first"))
        assertTrue(confirmation.confirm("changed"))
        assertFalse(confirmation.confirm("changed"))
        now += 3_000L
        assertFalse(confirmation.confirm("changed"))
    }

    @Test
    fun resetDisarmsAnExistingConfirmation() {
        val confirmation = TimedConfirmation<String>(now = { 0L })
        assertFalse(confirmation.confirm("send"))
        confirmation.reset()
        assertFalse(confirmation.confirm("send"))
    }
}
