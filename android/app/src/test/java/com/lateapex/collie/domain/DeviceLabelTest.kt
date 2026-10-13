package com.lateapex.collie.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceLabelTest {
    @Test
    fun labelMatchesTheBridgeNormalizationAndUtf16Limit() {
        assertEquals(48, DeviceLabel.MAX_CHARS)
        assertEquals("my phone", DeviceLabel.normalize(" \tmy\r\n\tphone \n"))
        assertEquals(48, DeviceLabel.normalize("\uD83D\uDCF1".repeat(24)).length)
        assertEquals(50, DeviceLabel.normalize("\uD83D\uDCF1".repeat(25)).length)
    }
}
