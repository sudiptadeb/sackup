package com.sackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveArrivalHelpersTest {

    @Test
    fun interruptedGroupsTakePriorityOverEverything() {
        assertEquals(listOf(2L, 3L), groupIdsToRun(listOf(2L, 3L), listOf(1L, 2L, 3L)))
    }

    @Test
    fun everythingRunsWhenNothingWasInterrupted() {
        assertEquals(listOf(1L, 2L, 3L), groupIdsToRun(emptyList(), listOf(1L, 2L, 3L)))
    }

    @Test
    fun deletedInterruptedGroupsAreIgnored() {
        // The interrupted group no longer exists → fall back to all groups.
        assertEquals(listOf(1L, 2L), groupIdsToRun(listOf(9L), listOf(1L, 2L)))
        // One of two still exists → only that one.
        assertEquals(listOf(2L), groupIdsToRun(listOf(9L, 2L), listOf(1L, 2L)))
    }

    @Test
    fun noGroupsMeansNothingToRun() {
        assertEquals(emptyList<Long>(), groupIdsToRun(listOf(1L), emptyList()))
    }

    @Test
    fun driveArrivalIntentIsRecognised() {
        assertTrue(isDriveArrivalIntent("android.hardware.usb.action.USB_DEVICE_ATTACHED", false))
        assertTrue(isDriveArrivalIntent("android.intent.action.MAIN", true))
        assertTrue(isDriveArrivalIntent(null, true))
        assertFalse(isDriveArrivalIntent("android.intent.action.MAIN", false))
        assertFalse(isDriveArrivalIntent(null, false))
    }

    @Test
    fun onConnectModeParsesWithAskAsDefault() {
        assertEquals(OnConnectMode.AUTO, ConnectPrefs.parse("AUTO"))
        assertEquals(OnConnectMode.OFF, ConnectPrefs.parse("OFF"))
        assertEquals(OnConnectMode.ASK, ConnectPrefs.parse("ASK"))
        assertEquals(OnConnectMode.ASK, ConnectPrefs.parse(null))
        assertEquals(OnConnectMode.ASK, ConnectPrefs.parse("garbage"))
        assertEquals(OnConnectMode.ASK, ConnectPrefs.parse("auto"))   // stored as the enum name
    }
}
