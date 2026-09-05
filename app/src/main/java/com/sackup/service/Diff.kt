package com.sackup.service

import com.sackup.data.ManifestEntry

// ── Pure diff / manifest helpers ─────────────────────────────────────────────
// No Android or I/O dependencies so they can be unit-tested on the JVM.

/** Result of comparing the phone's files against what is on the drive. */
data class DiffResult(
    val filesToCopy: List<PhoneFile>,
    val totalBytesToCopy: Long,
    val alreadyOnDrive: Int,
    val perFolder: List<FolderDiff>,
    /**
     * Phone files that exist on the drive under the same path/name but with a different size —
     * i.e. an interrupted earlier copy. They are also included in [filesToCopy]; the caller must
     * remove the stale drive document before copying so the new file keeps its proper name.
     */
    val partialsOnDrive: List<Pair<PhoneFile, DriveFileInfo>>
)

/** "drivePath|name" — the identity of a file on the drive. */
internal fun driveKey(drivePath: String, name: String): String = "$drivePath|$name"

internal fun PhoneFile.driveKey(): String = driveKey(drivePath, name)

/**
 * Compare [phoneFiles] against [driveFileCache] (drivePath → (fileName → info)) and decide what
 * needs copying. Pure: never touches the drive or MediaStore.
 *
 * A phone file is "already on the drive" when a drive file with the same drivePath and name has
 * exactly the same size. Drive files under a top-level folder ("DCIM", "DCIM/Camera", ...) are
 * attributed to that folder; "DCIM2" is not under "DCIM".
 */
fun computeDiff(
    phoneFolders: List<String>,
    phoneFiles: List<PhoneFile>,
    driveFileCache: Map<String, Map<String, DriveFileInfo>>
): DiffResult {
    val filesToCopy = mutableListOf<PhoneFile>()
    val partials = mutableListOf<Pair<PhoneFile, DriveFileInfo>>()
    val perFolder = mutableListOf<FolderDiff>()
    var totalAlreadyOnDrive = 0

    for (folderPath in phoneFolders) {
        val phoneFolderFiles = phoneFiles.filter { it.phoneFolder == folderPath }

        // Every drive file that lives under this top-level folder
        val driveKeys = mutableMapOf<String, DriveFileInfo>()
        for ((drivePath, files) in driveFileCache) {
            if (drivePath == folderPath || drivePath.startsWith("$folderPath/")) {
                for ((name, info) in files) driveKeys[driveKey(drivePath, name)] = info
            }
        }

        val phoneKeys = phoneFolderFiles.mapTo(HashSet()) { it.driveKey() }

        var toCopy = 0; var toCopySize = 0L
        var onDrive = 0; var onDriveSize = 0L

        for (pf in phoneFolderFiles) {
            val driveInfo = driveKeys[pf.driveKey()]
            if (driveInfo != null && driveInfo.size == pf.size) {
                onDrive++; onDriveSize += pf.size
            } else {
                if (driveInfo != null) partials.add(pf to driveInfo)
                toCopy++; toCopySize += pf.size
                filesToCopy.add(pf)
            }
        }

        var driveOnly = 0; var driveOnlySize = 0L
        for ((key, info) in driveKeys) {
            if (key !in phoneKeys) { driveOnly++; driveOnlySize += info.size }
        }

        totalAlreadyOnDrive += onDrive

        perFolder.add(
            FolderDiff(
                phoneFolder = folderPath,
                toCopy = toCopy,
                toCopySize = toCopySize,
                alreadyOnDrive = onDrive,
                alreadyOnDriveSize = onDriveSize,
                onDriveOnly = driveOnly,
                onDriveOnlySize = driveOnlySize,
                totalOnPhone = phoneFolderFiles.size,
                totalOnDrive = driveKeys.size
            )
        )
    }

    return DiffResult(
        filesToCopy = filesToCopy,
        totalBytesToCopy = filesToCopy.sumOf { it.size },
        alreadyOnDrive = totalAlreadyOnDrive,
        perFolder = perFolder,
        partialsOnDrive = partials
    )
}

/**
 * Build the manifest for a group: exactly the phone files that are confirmed to be on the drive —
 * those that already matched at scan time plus those in [copiedFiles] ("drivePath|name" keys of
 * files that were copied and verified during this run).
 *
 * Every entry written is a file known to be on the drive, so [backupSuccess] defaults to true;
 * a failure of some other file must not lock Free Up Space for the files that did make it.
 */
fun buildManifestEntries(
    groupId: Long,
    snapshot: SnapshotResult,
    copiedFiles: Set<String>,
    backupSuccess: Boolean = true
): List<ManifestEntry> {
    val entries = mutableListOf<ManifestEntry>()
    val now = System.currentTimeMillis()

    for (pf in snapshot.allPhoneFiles) {
        val driveInfo = snapshot.driveFileCache[pf.drivePath]?.get(pf.name)
        val wasOnDrive = driveInfo != null && driveInfo.size == pf.size
        val wasCopied = pf.driveKey() in copiedFiles

        if (wasOnDrive || wasCopied) {
            entries.add(
                ManifestEntry(
                    groupId = groupId,
                    fileName = pf.name,
                    fileSize = pf.size,
                    phoneFolder = pf.phoneFolder,
                    phonePath = pf.phonePath,
                    drivePath = pf.drivePath,
                    dateModified = pf.dateModified,
                    backupSuccess = backupSuccess,
                    manifestTimestamp = now
                )
            )
        }
    }
    return entries
}
