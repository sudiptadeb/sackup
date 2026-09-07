package com.sackup.util

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DriveVolumesTest {

    private fun mountUri(path: String?): Uri = mock(Uri::class.java).also { `when`(it.path).thenReturn(path) }

    @Test
    fun volumeIdOfDocumentId_extractsPartBeforeColon() {
        assertEquals("1234-5678", DriveVolumes.volumeIdOfDocumentId("1234-5678:Backups"))
        assertEquals("1234-5678", DriveVolumes.volumeIdOfDocumentId("1234-5678:"))
        assertEquals("primary", DriveVolumes.volumeIdOfDocumentId("primary:DCIM"))
    }

    @Test
    fun volumeIdOfDocumentId_nullForMissingOrEmpty() {
        assertNull(DriveVolumes.volumeIdOfDocumentId(null))
        assertNull(DriveVolumes.volumeIdOfDocumentId(""))
        assertNull(DriveVolumes.volumeIdOfDocumentId(":Backups"))
        assertNull(DriveVolumes.volumeIdOfDocumentId("no-colon"))
    }

    @Test
    fun volumeIdOfMountPath_takesLastSegment() {
        assertEquals("1234-5678", DriveVolumes.volumeIdOfMountPath(mountUri("/storage/1234-5678")))
        assertEquals("1234-5678", DriveVolumes.volumeIdOfMountPath(mountUri("/storage/1234-5678/")))
        assertNull(DriveVolumes.volumeIdOfMountPath(mountUri(null)))
        assertNull(DriveVolumes.volumeIdOfMountPath(mountUri("/")))
        assertNull(DriveVolumes.volumeIdOfMountPath(null))
    }

    @Test
    fun eventConcernsDrive_unknownSidesErrOnYes() {
        // No saved drive: cannot rule the event out.
        assertTrue(DriveVolumes.eventConcernsDrive(mountUri("/storage/1234-5678"), null))
        // Event with no usable path: cannot rule it out either.
        assertTrue(DriveVolumes.eventConcernsDrive(mountUri(null), null))
    }
}
