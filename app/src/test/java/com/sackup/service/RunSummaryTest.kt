package com.sackup.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class RunSummaryTest {

    private fun r(id: Long, outcome: GroupOutcome, message: String = "") =
        GroupRunResult(groupId = id, groupName = "G$id", outcome = outcome, message = message)

    // ── summarizeRun ─────────────────────────────────────────────────────

    @Test
    fun allSuccessIsSuccess() {
        val s = summarizeRun(listOf(r(1, GroupOutcome.SUCCESS), r(2, GroupOutcome.SUCCESS)))
        assertEquals(BackupOutcome.SUCCESS, s.outcome)
        assertEquals("", s.errorMessage)
        assertEquals(2, s.groupsDone)
    }

    @Test
    fun skippedGroupsDoNotSpoilSuccess() {
        val s = summarizeRun(listOf(r(1, GroupOutcome.SKIPPED, "gone"), r(2, GroupOutcome.SUCCESS)))
        assertEquals(BackupOutcome.SUCCESS, s.outcome)
        assertEquals(1, s.groupsDone)
    }

    @Test
    fun anyPartialGroupMakesRunPartial() {
        val s = summarizeRun(listOf(r(1, GroupOutcome.SUCCESS), r(2, GroupOutcome.PARTIAL)))
        assertEquals(BackupOutcome.PARTIAL, s.outcome)
        assertEquals(2, s.groupsDone)
    }

    @Test
    fun laterScanFailureIsPartialNotError() {
        val s = summarizeRun(listOf(r(1, GroupOutcome.SUCCESS), r(2, GroupOutcome.FAILED, "Could not scan the drive")))
        assertEquals(BackupOutcome.PARTIAL, s.outcome)
        assertEquals("", s.errorMessage)
        assertEquals(1, s.groupsDone)
    }

    @Test
    fun firstScanFailureIsErrorWithItsMessage() {
        val s = summarizeRun(listOf(r(1, GroupOutcome.FAILED, "Could not scan the drive: boom")))
        assertEquals(BackupOutcome.ERROR, s.outcome)
        assertEquals("Could not scan the drive: boom", s.errorMessage)
        assertEquals(0, s.groupsDone)
    }

    @Test
    fun scanFailureAfterOnlySkippedGroupsIsError() {
        val s = summarizeRun(listOf(r(1, GroupOutcome.SKIPPED, "This backup group no longer exists"), r(2, GroupOutcome.FAILED, "scan")))
        assertEquals(BackupOutcome.ERROR, s.outcome)
        assertEquals("scan", s.errorMessage)  // the real failure wins over the skip reason
    }

    @Test
    fun everythingSkippedIsErrorWithSkipReason() {
        val s = summarizeRun(listOf(r(1, GroupOutcome.SKIPPED, "This backup group no longer exists")))
        assertEquals(BackupOutcome.ERROR, s.outcome)
        assertEquals("This backup group no longer exists", s.errorMessage)
    }

    @Test
    fun emptyRunIsError() {
        val s = summarizeRun(emptyList())
        assertEquals(BackupOutcome.ERROR, s.outcome)
        assertTrue(s.errorMessage.isNotEmpty())
    }

    @Test
    fun cancelWinsOverPartialAndSuccess() {
        val s = summarizeRun(listOf(r(1, GroupOutcome.PARTIAL), r(2, GroupOutcome.CANCELLED)))
        assertEquals(BackupOutcome.CANCELLED, s.outcome)
        assertEquals("", s.errorMessage)
        assertEquals(1, s.groupsDone)
    }

    @Test
    fun interruptedWinsOverEverythingAndCarriesPlainSentence() {
        val s = summarizeRun(listOf(r(1, GroupOutcome.SUCCESS), r(2, GroupOutcome.INTERRUPTED), r(3, GroupOutcome.CANCELLED)))
        assertEquals(BackupOutcome.INTERRUPTED, s.outcome)
        assertEquals(DRIVE_UNPLUGGED_MESSAGE, s.errorMessage)
        assertEquals("The USB drive was unplugged. Plug it back in to continue.", s.errorMessage)
    }

    // ── resultNotificationText ───────────────────────────────────────────

    private fun text(
        outcome: BackupOutcome,
        groupsDone: Int = 1,
        name: String = "Photos",
        copied: Int = 0,
        bytes: Long = 0L,
        failed: Int = 0,
        error: String = ""
    ) = resultNotificationText(outcome, groupsDone, name, copied, bytes, failed, error)

    @Test
    fun multiGroupSuccessSummarisesTheRun() {
        assertEquals(
            "3 backups done: 42 files backed up (1.20 GB)",
            text(BackupOutcome.SUCCESS, groupsDone = 3, copied = 42, bytes = (1.2 * 1024 * 1024 * 1024).toLong())
        )
        assertEquals("3 backups done: everything is already backed up", text(BackupOutcome.SUCCESS, groupsDone = 3))
    }

    @Test
    fun multiGroupPartialListsFailures() {
        assertEquals(
            "3 backups done: 42 files backed up (1.0 KB); 2 files failed",
            text(BackupOutcome.PARTIAL, groupsDone = 3, copied = 42, bytes = 1024, failed = 2)
        )
        assertEquals(
            "2 backups done: 1 file backed up (10 B); some files could not be backed up",
            text(BackupOutcome.PARTIAL, groupsDone = 2, copied = 1, bytes = 10, failed = 0)
        )
    }

    @Test
    fun singleGroupKeepsGroupNameWording() {
        assertEquals("Photos: 5 files backed up (5.0 KB)", text(BackupOutcome.SUCCESS, copied = 5, bytes = 5 * 1024))
        assertEquals("Photos: everything is already backed up", text(BackupOutcome.SUCCESS))
        assertEquals("Photos: 5 files backed up (5.0 KB); 1 file failed", text(BackupOutcome.PARTIAL, copied = 5, bytes = 5 * 1024, failed = 1))
        assertEquals("Backup: everything is already backed up", text(BackupOutcome.SUCCESS, name = ""))
    }

    @Test
    fun cancelledAndInterruptedMentionSavedFiles() {
        assertEquals("Backup stopped", text(BackupOutcome.CANCELLED))
        assertEquals("Backup stopped — 3 files were saved", text(BackupOutcome.CANCELLED, copied = 3))
        assertEquals("USB drive unplugged. Plug it back in to continue.", text(BackupOutcome.INTERRUPTED))
        assertEquals(
            "USB drive unplugged — 3 files were saved. Plug it back in to continue.",
            text(BackupOutcome.INTERRUPTED, groupsDone = 2, copied = 3)
        )
    }

    @Test
    fun errorUsesMessageOrFallback() {
        assertEquals("Backup failed: The drive is full", text(BackupOutcome.ERROR, error = "The drive is full"))
        assertEquals("Backup failed: something went wrong", text(BackupOutcome.ERROR))
        assertEquals("Backup failed: something went wrong", text(BackupOutcome.NONE))
    }

    // ── progressNotificationText ─────────────────────────────────────────

    @Test
    fun progressTextIsPrefixedOnlyForQueues() {
        assertEquals("Photos (2 of 3): Copying: a.jpg (1/9)", progressNotificationText("Copying: a.jpg (1/9)", "Photos", 1, 3))
        assertEquals("Copying: a.jpg (1/9)", progressNotificationText("Copying: a.jpg (1/9)", "Photos", 0, 1))
        assertEquals("Preparing backup...", progressNotificationText("Preparing backup...", "", 0, 3))
    }

    // ── isDriveVanishedError ─────────────────────────────────────────────

    @Test
    fun vanishedDriveIsRecognisedFromDocumentsContractExceptions() {
        assertTrue(isDriveVanishedError(FileNotFoundException("No root for 1234-5678")))
        assertTrue(isDriveVanishedError(SecurityException("Permission Denial: reading com.android.externalstorage")))
        assertTrue(isDriveVanishedError(IllegalArgumentException("Failed to determine if 1234-5678:DCIM is child of 1234-5678:")))
        // ...also when wrapped
        assertTrue(isDriveVanishedError(RuntimeException("query failed", FileNotFoundException("gone"))))
    }

    @Test
    fun otherErrorsAreNotBlamedOnAVanishedDrive() {
        assertFalse(isDriveVanishedError(IOException("ENOSPC")))
        assertFalse(isDriveVanishedError(IllegalStateException("boom")))
        // A missing phone file is a stale MediaStore row, not an unplugged drive
        assertFalse(isDriveVanishedError(SourceMissingException(FileNotFoundException("open failed: ENOENT"))))
        assertFalse(isDriveVanishedError(RuntimeException("x", SourceMissingException(FileNotFoundException("ENOENT")))))
    }

    @Test
    fun consecutiveFailureBackstopIsSmall() {
        assertEquals(10, MAX_CONSECUTIVE_FAILURES)
    }
}
