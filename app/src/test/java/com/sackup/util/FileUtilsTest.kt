package com.sackup.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilsTest {

    @Test
    fun formatBytes_zero() {
        assertEquals("0 B", formatBytes(0))
    }

    @Test
    fun formatBytes_justBelowOneKilobyte() {
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun formatBytes_exactlyOneKilobyte() {
        assertEquals("1.0 KB", formatBytes(1024))
    }

    @Test
    fun formatBytes_exactlyOneMegabyte() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
    }

    @Test
    fun formatBytes_exactlyOneGigabyte() {
        assertEquals("1.00 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun formatBytes_fractionalValues() {
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("4.20 GB", formatBytes((4.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatDuration_underOneMinute() {
        assertEquals("59s", formatDuration(59_000))
    }

    @Test
    fun formatDuration_exactlyOneMinute() {
        assertEquals("1m 0s", formatDuration(60_000))
    }

    @Test
    fun formatDuration_justUnderOneHour() {
        assertEquals("59m 59s", formatDuration(3_599_000))
    }

    @Test
    fun formatDuration_exactlyOneHour() {
        assertEquals("1h 0m", formatDuration(3_600_000))
    }

    @Test
    fun formatDuration_subSecondRoundsDownToZero() {
        assertEquals("0s", formatDuration(999))
    }
}
