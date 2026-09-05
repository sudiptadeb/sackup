package com.sackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityHelpersTest {

    @Test
    fun primaryStorageTreeIsDetected() {
        assertTrue(isPrimaryStorageTree("primary:"))
        assertTrue(isPrimaryStorageTree("primary:DCIM/Camera"))
    }

    @Test
    fun usbAndUnknownTreesAreNotPrimary() {
        assertFalse(isPrimaryStorageTree("1A2B-3C4D:"))
        assertFalse(isPrimaryStorageTree("1A2B-3C4D:Backups"))
        assertFalse(isPrimaryStorageTree("Primary:"))   // case-sensitive, matches the provider
        assertFalse(isPrimaryStorageTree(""))
        assertFalse(isPrimaryStorageTree(null))
    }

    @Test
    fun deleteResultMessageCoversAllOutcomes() {
        assertEquals("", deleteResultMessage(0, 0))
        assertEquals("Deleted 1 file", deleteResultMessage(1, 1))
        assertEquals("Deleted 12 files", deleteResultMessage(12, 12))
        assertEquals("Deleted 3 of 10 files", deleteResultMessage(3, 10))
        assertTrue(deleteResultMessage(0, 5).startsWith("No files were deleted"))
    }

    @Test
    fun deleteBatchSizeStaysUnderBinderLimit() {
        // Each URI costs a few hundred bytes in the Binder transaction; 400 keeps well under 1 MB.
        assertTrue(DELETE_BATCH_SIZE in 1..1000)
    }
}
