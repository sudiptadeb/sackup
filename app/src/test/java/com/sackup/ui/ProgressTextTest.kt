package com.sackup.ui

import com.sackup.OnConnectMode
import com.sackup.service.BackupProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTextTest {

    @Test
    fun groupLabelShowsPositionOnlyInMultiGroupRuns() {
        assertEquals("Photos", groupLabel("Photos", 0, 1))
        assertEquals("Photos (2 of 3)", groupLabel("Photos", 1, 3))
        assertEquals("Music (3 of 3)", groupLabel(BackupProgress(groupName = "Music", groupIndex = 2, groupCount = 3)))
        assertEquals("", groupLabel(BackupProgress()))
    }

    @Test
    fun savedSoFarLineCountsFiles() {
        assertTrue(savedSoFarLine(1, 100L).startsWith("Saved so far: 1 file ("))
        assertTrue(savedSoFarLine(42, 100L).startsWith("Saved so far: 42 files ("))
        assertTrue(savedSoFarLine(0, 0L).startsWith("Saved so far: 0 files ("))
    }

    @Test
    fun successSentenceMentionsRunTotalsAcrossBackups() {
        assertEquals("Everything was already on the drive.", successSentence(0, 0L, 3))
        val single = successSentence(42, 1_200_000_000L, 1)
        assertTrue(single.startsWith("42 files are now safely on your USB drive ("))
        assertTrue(!single.contains("across"))
        val multi = successSentence(42, 1_200_000_000L, 3)
        assertTrue(multi.startsWith("42 files are now safely on your USB drive ("))
        assertTrue(multi.endsWith(") across 3 backups"))
    }

    @Test
    fun partialSentenceCoversNothingCopied() {
        assertEquals("Nothing was copied this time. Try running the backup again.", partialSentence(0, 0L, 2))
        assertTrue(partialSentence(5, 10L, 2).contains("across 2 backups"))
        assertTrue(!partialSentence(5, 10L, 1).contains("across"))
    }

    @Test
    fun onConnectModeLabelsArePlainLanguage() {
        assertEquals("Ask me", onConnectModeLabel(OnConnectMode.ASK))
        assertEquals("Back up automatically", onConnectModeLabel(OnConnectMode.AUTO))
        assertEquals("Do nothing", onConnectModeLabel(OnConnectMode.OFF))
    }
}
