package com.sackup.service

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class DiffTest {

    private val fakeUri: Uri = Mockito.mock(Uri::class.java)

    private fun phone(
        name: String,
        size: Long,
        folder: String = "DCIM",
        sub: String = "",
        dateModified: Long = 1_700_000_000L
    ): PhoneFile {
        val drivePath = if (sub.isEmpty()) folder else "$folder/$sub"
        return PhoneFile(
            uri = fakeUri,
            name = name,
            size = size,
            dateModified = dateModified,
            phoneFolder = folder,
            phonePath = "$drivePath/",
            drivePath = drivePath
        )
    }

    private fun drive(vararg entries: Triple<String, String, Long>): Map<String, Map<String, DriveFileInfo>> {
        val cache = mutableMapOf<String, MutableMap<String, DriveFileInfo>>()
        for ((path, name, size) in entries) {
            cache.getOrPut(path) { mutableMapOf() }[name] = DriveFileInfo(size, "doc:$path/$name")
        }
        return cache
    }

    // ── computeDiff ───────────────────────────────────────────────────────

    @Test
    fun exactMatchIsSkipped() {
        val files = listOf(phone("a.jpg", 100), phone("b.jpg", 200))
        val cache = drive(Triple("DCIM", "a.jpg", 100L), Triple("DCIM", "b.jpg", 200L))

        val diff = computeDiff(listOf("DCIM"), files, cache)

        assertTrue(diff.filesToCopy.isEmpty())
        assertEquals(0L, diff.totalBytesToCopy)
        assertEquals(2, diff.alreadyOnDrive)
        assertTrue(diff.partialsOnDrive.isEmpty())
        val f = diff.perFolder.single()
        assertEquals(0, f.toCopy)
        assertEquals(2, f.alreadyOnDrive)
        assertEquals(300L, f.alreadyOnDriveSize)
        assertEquals(0, f.onDriveOnly)
        assertEquals(2, f.totalOnPhone)
        assertEquals(2, f.totalOnDrive)
    }

    @Test
    fun sizeMismatchIsCopiedAndReportedAsPartial() {
        val files = listOf(phone("v.mp4", 5000))
        val cache = drive(Triple("DCIM", "v.mp4", 1234L))

        val diff = computeDiff(listOf("DCIM"), files, cache)

        assertEquals(listOf("v.mp4"), diff.filesToCopy.map { it.name })
        assertEquals(5000L, diff.totalBytesToCopy)
        assertEquals(0, diff.alreadyOnDrive)
        assertEquals(1, diff.partialsOnDrive.size)
        val (pf, info) = diff.partialsOnDrive.single()
        assertEquals("v.mp4", pf.name)
        assertEquals(1234L, info.size)
        assertEquals("doc:DCIM/v.mp4", info.documentId)
        // The partial is also counted among the drive's files, but not as "drive only"
        val f = diff.perFolder.single()
        assertEquals(1, f.toCopy)
        assertEquals(0, f.onDriveOnly)
        assertEquals(1, f.totalOnDrive)
    }

    @Test
    fun newFileWithoutDriveCounterpartIsNotAPartial() {
        val diff = computeDiff(listOf("DCIM"), listOf(phone("new.jpg", 10)), drive())
        assertEquals(1, diff.filesToCopy.size)
        assertTrue(diff.partialsOnDrive.isEmpty())
    }

    @Test
    fun driveOnlyFilesAreCounted() {
        val files = listOf(phone("keep.jpg", 10))
        val cache = drive(
            Triple("DCIM", "keep.jpg", 10L),
            Triple("DCIM", "deleted.jpg", 70L),
            Triple("DCIM/Camera", "old.mp4", 30L)
        )

        val diff = computeDiff(listOf("DCIM"), files, cache)

        val f = diff.perFolder.single()
        assertEquals(2, f.onDriveOnly)
        assertEquals(100L, f.onDriveOnlySize)
        assertEquals(3, f.totalOnDrive)
        assertEquals(1, f.alreadyOnDrive)
        assertTrue(diff.filesToCopy.isEmpty())
    }

    @Test
    fun subfolderBelongsToItsTopLevelFolderButNotToLookalikes() {
        val files = listOf(
            phone("cam.jpg", 10, folder = "DCIM", sub = "Camera"),
            phone("other.jpg", 20, folder = "DCIM2")
        )
        val cache = drive(
            Triple("DCIM/Camera", "cam.jpg", 10L),
            Triple("DCIM2", "other.jpg", 20L),
            Triple("DCIM2", "stray.jpg", 5L)
        )

        val diff = computeDiff(listOf("DCIM", "DCIM2"), files, cache)

        val dcim = diff.perFolder.first { it.phoneFolder == "DCIM" }
        val dcim2 = diff.perFolder.first { it.phoneFolder == "DCIM2" }

        // "DCIM/Camera" is under "DCIM"; nothing from "DCIM2" leaks in
        assertEquals(1, dcim.totalOnDrive)
        assertEquals(1, dcim.alreadyOnDrive)
        assertEquals(0, dcim.onDriveOnly)
        assertEquals(1, dcim.totalOnPhone)

        assertEquals(2, dcim2.totalOnDrive)
        assertEquals(1, dcim2.alreadyOnDrive)
        assertEquals(1, dcim2.onDriveOnly)

        assertTrue(diff.filesToCopy.isEmpty())
        assertEquals(2, diff.alreadyOnDrive)
    }

    @Test
    fun emptyDriveCopiesEverything() {
        val files = listOf(phone("a.jpg", 1), phone("b.jpg", 2, sub = "Camera"))

        val diff = computeDiff(listOf("DCIM"), files, emptyMap())

        assertEquals(2, diff.filesToCopy.size)
        assertEquals(3L, diff.totalBytesToCopy)
        assertEquals(0, diff.alreadyOnDrive)
        val f = diff.perFolder.single()
        assertEquals(2, f.toCopy)
        assertEquals(3L, f.toCopySize)
        assertEquals(0, f.totalOnDrive)
        assertEquals(2, f.totalOnPhone)
    }

    @Test
    fun emptyPhoneCopiesNothingAndCountsDriveOnly() {
        val cache = drive(Triple("DCIM", "x.jpg", 9L))

        val diff = computeDiff(listOf("DCIM"), emptyList(), cache)

        assertTrue(diff.filesToCopy.isEmpty())
        assertEquals(0L, diff.totalBytesToCopy)
        assertEquals(0, diff.alreadyOnDrive)
        val f = diff.perFolder.single()
        assertEquals(0, f.totalOnPhone)
        assertEquals(1, f.onDriveOnly)
        assertEquals(9L, f.onDriveOnlySize)
    }

    @Test
    fun noFoldersYieldsEmptyResult() {
        val diff = computeDiff(emptyList(), listOf(phone("a.jpg", 1)), drive(Triple("DCIM", "a.jpg", 1L)))
        assertTrue(diff.perFolder.isEmpty())
        assertTrue(diff.filesToCopy.isEmpty())
        assertEquals(0, diff.alreadyOnDrive)
    }

    // ── buildManifestEntries ─────────────────────────────────────────────

    private fun snapshotOf(
        phoneFiles: List<PhoneFile>,
        cache: Map<String, Map<String, DriveFileInfo>>
    ): SnapshotResult {
        val diff = computeDiff(phoneFiles.map { it.phoneFolder }.distinct(), phoneFiles, cache)
        return SnapshotResult(
            filesToCopy = diff.filesToCopy,
            totalBytesToCopy = diff.totalBytesToCopy,
            alreadyOnDrive = diff.alreadyOnDrive,
            perFolder = diff.perFolder,
            allPhoneFiles = phoneFiles,
            driveFileCache = cache,
            dirDocIds = emptyMap(),
            partialsOnDrive = diff.partialsOnDrive
        )
    }

    @Test
    fun manifestContainsOnlyFilesConfirmedOnDrive() {
        val onDrive = phone("old.jpg", 10)
        val copied = phone("new.jpg", 20, sub = "Camera", dateModified = 42L)
        val failed = phone("bad.jpg", 30)
        val partial = phone("half.mp4", 40)
        val snapshot = snapshotOf(
            listOf(onDrive, copied, failed, partial),
            drive(Triple("DCIM", "old.jpg", 10L), Triple("DCIM", "half.mp4", 7L))
        )

        val entries = buildManifestEntries(7L, snapshot, setOf("DCIM/Camera|new.jpg"))

        assertEquals(setOf("old.jpg", "new.jpg"), entries.map { it.fileName }.toSet())
        assertTrue(entries.all { it.backupSuccess })
        assertTrue(entries.all { it.groupId == 7L })
        val e = entries.first { it.fileName == "new.jpg" }
        assertEquals(20L, e.fileSize)
        assertEquals("DCIM", e.phoneFolder)
        assertEquals("DCIM/Camera/", e.phonePath)
        assertEquals("DCIM/Camera", e.drivePath)
        assertEquals(42L, e.dateModified)
    }

    @Test
    fun manifestIsEmptyWhenNothingIsOnDrive() {
        val snapshot = snapshotOf(listOf(phone("a.jpg", 1)), emptyMap())
        assertTrue(buildManifestEntries(1L, snapshot, emptySet()).isEmpty())
    }

    @Test
    fun manifestSuccessFlagCanBeOverridden() {
        val snapshot = snapshotOf(listOf(phone("a.jpg", 1)), drive(Triple("DCIM", "a.jpg", 1L)))
        val entries = buildManifestEntries(1L, snapshot, emptySet(), backupSuccess = false)
        assertEquals(1, entries.size)
        assertFalse(entries.single().backupSuccess)
    }
}
