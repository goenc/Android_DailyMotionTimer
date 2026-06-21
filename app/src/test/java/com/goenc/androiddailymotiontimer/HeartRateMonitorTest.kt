package com.goenc.androiddailymotiontimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMonitorTest {
    @Test
    fun parsesEightBitHeartRate() {
        assertEquals(72, HeartRateMonitor.parseHeartRate(byteArrayOf(0x00, 72)))
    }

    @Test
    fun parsesSixteenBitHeartRate() {
        assertEquals(300, HeartRateMonitor.parseHeartRate(byteArrayOf(0x01, 0x2C, 0x01)))
    }

    @Test
    fun rejectsIncompleteMeasurement() {
        assertNull(HeartRateMonitor.parseHeartRate(byteArrayOf(0x01, 0x2C)))
    }
}
