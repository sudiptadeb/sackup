package com.sackup.service

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.sackup.data.ManifestEntry
import com.sackup.util.formatBytes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

// ── Data classes ──────────────────────────────────────────────────────────────

data class PhoneFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateModified: Long,   // epoch seconds
    val phoneFolder: String,  // top-level e.g. "DCIM"
    val phonePath: String,    // MediaStore RELATIVE_PATH e.g. "DCIM/Camera/"
    val drivePath: String     // mapped path on drive e.g. "DCIM/Camera"
)

data class CopyJob(
    val phone: PhoneFile,
    val destParentDocId: String
)

data class FolderDiff(
    val phoneFolder: String,
    val toCopy: Int,
    val toCopySize: Long,
    val alreadyOnDrive: Int,
    val alreadyOnDriveSize: Long,
    val onDriveOnly: Int,        // deleted from phone
    val onDriveOnlySize: Long,
    val totalOnPhone: Int,
    val totalOnDrive: Int
)

data class DriveFileInfo(
    val size: Long,
    val documentId: String
)

data class SnapshotResult(
    val filesToCopy: List<PhoneFile>,
    val totalBytesToCopy: Long,
    val alreadyOnDrive: Int,
    val perFolder: List<FolderDiff>,
    // Cached for manifest rebuild
    val allPhoneFiles: List<PhoneFile>,
    val driveFileCache: Map<String, Map<String, DriveFileInfo>>,  // drivePath → (name → info)
    val dirDocIds: Map<String, String>                             // drivePath → documentId
)

data class CopyResult(
    val copiedCount: Int,
    val copiedSize: Long,
    val failedCount: Int,
    val failedFiles: List<String>
)

// ── Engine ────────────────────────────────────────────────────────────────────

class BackupEngine(private val resolver: ContentResolver) {

    companion object {
        const val BUFFER_SIZE = 4 * 1024 * 1024  // 4MB
        const val WORKER_COUNT = 4
    }

    // ── Phase 1: Snapshot & Diff ──────────────────────────────────────────

    fun snapshot(
        phoneFolders: List<String>,
        treeUri: Uri,
        groupDriveFolder: String,
        syncTimestamp: Long = Long.MAX_VALUE  // only include files with DATE_MODIFIED <= this
    ): SnapshotResult {
        // 1. Get or find the group's drive folder doc ID
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val groupDirDocId = findChildDocId(treeUri, rootDocId, groupDriveFolder)

        // 2. Scan drive using DocumentsContract cursors (fast)
        val driveFileCache = mutableMapOf<String, MutableMap<String, DriveFileInfo>>()
        val dirDocIds = mutableMapOf<String, String>()

        if (groupDirDocId != null) {
            for (folderPath in phoneFolders) {
                val topName = folderPath.replace("/", "_")
                val topDocId = findChildDocId(treeUri, groupDirDocId, topName)
                if (topDocId != null) {
                    dirDocIds[topName] = topDocId
                    scanDriveCursor(treeUri, topDocId, topName, driveFileCache, dirDocIds)
                }
            }
        }

        // 3. Query phone files from MediaStore with timestamp filter
        val allPhoneFiles = mutableListOf<PhoneFile>()
        for (folderPath in phoneFolders) {
            val topName = folderPath.replace("/", "_")
            allPhoneFiles.addAll(queryPhoneFiles(folderPath, topName, syncTimestamp))
        }

        // 4. Diff per folder
        val filesToCopy = mutableListOf<PhoneFile>()
        var totalAlreadyOnDrive = 0
        val perFolder = mutableListOf<FolderDiff>()

        for (folderPath in phoneFolders) {
            val topName = folderPath.replace("/", "_")
            val phoneFolderFiles = allPhoneFiles.filter { it.phoneFolder == folderPath }

            // Collect all drive files under this top folder
            val driveKeys = mutableMapOf<String, DriveFileInfo>() // "drivePath|name" → info
            for ((drivePath, files) in driveFileCache) {
                if (drivePath == topName || drivePath.startsWith("$topName/")) {
                    for ((name, info) in files) {
                        driveKeys["$drivePath|$name"] = info
                    }
                }
            }

            val phoneKeyMap = phoneFolderFiles.associateBy { "${it.drivePath}|${it.name}" }

            var toCopy = 0; var toCopySize = 0L
            var onDrive = 0; var onDriveSize = 0L

            for (pf in phoneFolderFiles) {
                val key = "${pf.drivePath}|${pf.name}"
                val driveInfo = driveKeys[key]
                if (driveInfo != null && driveInfo.size == pf.size) {
                    // Full match — file is safely on drive
                    onDrive++; onDriveSize += pf.size
                } else {
                    if (driveInfo != null && driveInfo.size != pf.size) {
                        // Partial file on drive — delete it so copy can overwrite cleanly
                        try {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, driveInfo.documentId)
                            DocumentsContract.deleteDocument(resolver, docUri)
                            // Remove from cache so it's not counted
                            driveFileCache[pf.drivePath]?.remove(pf.name)
                        } catch (_: Exception) {}
                    }
                    toCopy++; toCopySize += pf.size
                    filesToCopy.add(pf)
                }
            }

            // Drive-only files (deleted from phone)
            var driveOnly = 0; var driveOnlySize = 0L
            for ((key, info) in driveKeys) {
                if (key !in phoneKeyMap) {
                    driveOnly++; driveOnlySize += info.size
                }
            }

            totalAlreadyOnDrive += onDrive

            val totalDriveFiles = driveKeys.size

            perFolder.add(FolderDiff(
                phoneFolder = folderPath,
                toCopy = toCopy,
                toCopySize = toCopySize,
                alreadyOnDrive = onDrive,
                alreadyOnDriveSize = onDriveSize,
                onDriveOnly = driveOnly,
                onDriveOnlySize = driveOnlySize,
                totalOnPhone = phoneFolderFiles.size,
                totalOnDrive = totalDriveFiles
            ))
        }

        return SnapshotResult(
            filesToCopy = filesToCopy,
            totalBytesToCopy = filesToCopy.sumOf { it.size },
            alreadyOnDrive = totalAlreadyOnDrive,
            perFolder = perFolder,
            allPhoneFiles = allPhoneFiles,
            driveFileCache = driveFileCache,
            dirDocIds = dirDocIds
        )
    }

    // ── Phase 2: Parallel Copy ────────────────────────────────────────────

    suspend fun parallelCopy(
        snapshot: SnapshotResult,
        treeUri: Uri,
        groupDriveFolder: String,
        phoneFolders: List<String>,
        isCancelled: () -> Boolean,
        onProgress: (completed: Int, fileName: String, copiedBytes: Long, speed: Long) -> Unit
    ): CopyResult {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val groupDirDocId = findChildDocId(treeUri, rootDocId, groupDriveFolder)
            ?: createChildDir(treeUri, rootDocId, groupDriveFolder)
            ?: return CopyResult(0, 0, snapshot.filesToCopy.size,
                listOf("Could not create drive folder '$groupDriveFolder'"))

        // Pre-create all needed directories and collect doc IDs
        val dirDocIds = snapshot.dirDocIds.toMutableMap()
        val neededPaths = snapshot.filesToCopy.map { it.drivePath }.toSet()
        for (path in neededPaths) {
            if (path !in dirDocIds) {
                ensureDirPath(treeUri, groupDirDocId, path, dirDocIds)
            }
        }

        // Build CopyJobs
        val jobs = snapshot.filesToCopy.mapNotNull { pf ->
            val parentDocId = dirDocIds[pf.drivePath] ?: return@mapNotNull null
            CopyJob(phone = pf, destParentDocId = parentDocId)
        }

        if (jobs.isEmpty()) return CopyResult(0, 0, 0, emptyList())

        // Parallel copy with workers
        val channel = Channel<CopyJob>(Channel.UNLIMITED)
        val completedCount = AtomicInteger(0)
        val copiedBytes = AtomicLong(0)
        val failedCount = AtomicInteger(0)
        val errors = ConcurrentLinkedQueue<String>()
        val startTime = System.currentTimeMillis()

        for (job in jobs) channel.send(job)
        channel.close()

        coroutineScope {
            repeat(WORKER_COUNT) {
                launch(Dispatchers.IO) {
                    for (job in channel) {
                        if (isCancelled()) {
                            // Drain remaining jobs without processing
                            break
                        }

                        try {
                            copyOneFile(job, treeUri, isCancelled)
                            copiedBytes.addAndGet(job.phone.size)
                        } catch (_: CancelledException) {
                            // Don't count cancellation as a failure
                            break
                        } catch (e: CancellationException) {
                            // Coroutine cancelled — rethrow to exit cleanly
                            throw e
                        } catch (e: Exception) {
                            failedCount.incrementAndGet()
                            errors.add("${job.phone.name}: ${e.message}")
                        }

                        val c = completedCount.incrementAndGet()
                        val elapsed = System.currentTimeMillis() - startTime
                        val speed = if (elapsed > 0) copiedBytes.get() * 1000 / elapsed else 0L
                        onProgress(c, job.phone.name, copiedBytes.get(), speed)
                    }
                }
            }
        }

        return CopyResult(
            copiedCount = completedCount.get() - failedCount.get(),
            copiedSize = copiedBytes.get(),
            failedCount = failedCount.get(),
            failedFiles = errors.toList()
        )
    }

    // ── Phase 3: Manifest rebuild from cached data ────────────────────────

    fun buildManifestEntries(
        groupId: Long,
        snapshot: SnapshotResult,
        copyResult: CopyResult,
        copiedFiles: Set<String>,  // "drivePath|name" of successfully copied files
        backupSuccess: Boolean
    ): List<ManifestEntry> {
        val entries = mutableListOf<ManifestEntry>()

        // Files that were already on drive + successfully copied files
        for (pf in snapshot.allPhoneFiles) {
            val key = "${pf.drivePath}|${pf.name}"
            val driveInfo = snapshot.driveFileCache[pf.drivePath]?.get(pf.name)
            val wasOnDrive = driveInfo != null && driveInfo.size == pf.size
            val wasCopied = key in copiedFiles

            if (wasOnDrive || wasCopied) {
                entries.add(ManifestEntry(
                    groupId = groupId,
                    fileName = pf.name,
                    fileSize = pf.size,
                    phoneFolder = pf.phoneFolder,
                    phonePath = pf.phonePath,
                    drivePath = pf.drivePath,
                    dateModified = pf.dateModified,
                    backupSuccess = backupSuccess
                ))
            }
        }

        return entries
    }

    // ── Drive scanning with DocumentsContract cursors ─────────────────────

    private fun scanDriveCursor(
        treeUri: Uri,
        parentDocId: String,
        parentPath: String,
        files: MutableMap<String, MutableMap<String, DriveFileInfo>>,
        dirs: MutableMap<String, String>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: ""

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childPath = "$parentPath/$name"
                    dirs[childPath] = docId
                    scanDriveCursor(treeUri, docId, childPath, files, dirs)
                } else {
                    val size = cursor.getLong(sizeCol)
                    files.getOrPut(parentPath) { mutableMapOf() }[name] = DriveFileInfo(size, docId)
                }
            }
        }
    }

    private fun findChildDocId(treeUri: Uri, parentDocId: String, childName: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == childName) {
                    return cursor.getString(idCol)
                }
            }
        }
        return null
    }

    private fun createChildDir(treeUri: Uri, parentDocId: String, name: String): String? {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val created = DocumentsContract.createDocument(
            resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    private fun ensureDirPath(
        treeUri: Uri,
        rootDocId: String,
        path: String,
        cache: MutableMap<String, String>
    ) {
        val segments = path.split("/")
        var currentDocId = rootDocId
        var currentPath = ""

        for (segment in segments) {
            if (segment.isBlank()) continue
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"

            val cached = cache[currentPath]
            if (cached != null) {
                currentDocId = cached
                continue
            }

            val found = findChildDocId(treeUri, currentDocId, segment)
            if (found != null) {
                cache[currentPath] = found
                currentDocId = found
            } else {
                val created = createChildDir(treeUri, currentDocId, segment)
                if (created != null) {
                    cache[currentPath] = created
                    currentDocId = created
                } else {
                    return
                }
            }
        }
    }

    // ── Phone file queries ────────────────────────────────────────────────

    fun queryPhoneFiles(
        folderPath: String,
        topName: String,
        maxTimestamp: Long = Long.MAX_VALUE
    ): List<PhoneFile> {
        val results = mutableListOf<PhoneFile>()
        val collection = MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val timestampFilter = if (maxTimestamp < Long.MAX_VALUE) {
            " AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} <= ?"
        } else ""

        // Files in subfolders
        val subSel = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.SIZE} > 0$timestampFilter"
        val subArgs = if (maxTimestamp < Long.MAX_VALUE) arrayOf("$folderPath/%", maxTimestamp.toString()) else arrayOf("$folderPath/%")

        resolver.query(collection, projection, subSel, subArgs, null)?.use { cursor ->
            readPhoneCursor(cursor, collection, folderPath, topName, results)
        }

        // Files directly in folder
        val directSel = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.SIZE} > 0$timestampFilter"
        val directArgs = if (maxTimestamp < Long.MAX_VALUE) arrayOf("$folderPath/", maxTimestamp.toString()) else arrayOf("$folderPath/")

        resolver.query(collection, projection, directSel, directArgs, null)?.use { cursor ->
            readPhoneCursor(cursor, collection, folderPath, topName, results)
        }

        return results
    }

    private fun readPhoneCursor(
        cursor: android.database.Cursor,
        collection: Uri,
        folderPath: String,
        topName: String,
        results: MutableList<PhoneFile>
    ) {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: continue
            val size = cursor.getLong(sizeCol)
            val relativePath = cursor.getString(pathCol) ?: continue
            val dateModified = cursor.getLong(dateCol)

            val contentUri = ContentUris.withAppendedId(collection, id)
            val subPath = relativePath.removePrefix("$folderPath/").trimEnd('/')
            val drivePath = if (subPath.isEmpty()) topName else "$topName/$subPath"

            results.add(PhoneFile(
                uri = contentUri,
                name = name,
                size = size,
                dateModified = dateModified,
                phoneFolder = folderPath,
                phonePath = relativePath,
                drivePath = drivePath
            ))
        }
    }

    // ── File copy ─────────────────────────────────────────────────────────

    private fun copyOneFile(job: CopyJob, treeUri: Uri, isCancelled: () -> Boolean) {
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, job.destParentDocId)
        val mimeType = getMimeType(job.phone.name)

        val destUri = DocumentsContract.createDocument(resolver, parentDocUri, mimeType, job.phone.name)
            ?: throw Exception("Could not create file on drive")

        val outputStream = resolver.openOutputStream(destUri)
            ?: throw Exception("Could not open output stream")
        val inputStream = resolver.openInputStream(job.phone.uri)
            ?: throw Exception("Could not read source file")

        try {
            outputStream.use { out ->
                inputStream.use { inp ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (inp.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled()) {
                            // Clean up partial file
                            try { DocumentsContract.deleteDocument(resolver, destUri) } catch (_: Exception) {}
                            throw CancelledException()
                        }
                        out.write(buffer, 0, bytesRead)
                    }
                }
            }
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            // Clean up partial file on error
            try { DocumentsContract.deleteDocument(resolver, destUri) } catch (_: Exception) {}
            throw e
        }

        // Preserve timestamp
        if (job.phone.dateModified > 0) {
            try {
                val values = ContentValues().apply {
                    put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, job.phone.dateModified * 1000)
                }
                resolver.update(destUri, values, null, null)
            } catch (_: Exception) {}
        }
    }

    class CancelledException : Exception("Cancelled")

    private fun getMimeType(fileName: String): String = when {
        fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
        fileName.endsWith(".png", true) -> "image/png"
        fileName.endsWith(".gif", true) -> "image/gif"
        fileName.endsWith(".webp", true) -> "image/webp"
        fileName.endsWith(".heic", true) -> "image/heic"
        fileName.endsWith(".mp4", true) -> "video/mp4"
        fileName.endsWith(".3gp", true) -> "video/3gpp"
        fileName.endsWith(".mkv", true) -> "video/x-matroska"
        fileName.endsWith(".mov", true) -> "video/quicktime"
        fileName.endsWith(".mp3", true) -> "audio/mpeg"
        fileName.endsWith(".m4a", true) -> "audio/mp4"
        fileName.endsWith(".ogg", true) -> "audio/ogg"
        fileName.endsWith(".pdf", true) -> "application/pdf"
        fileName.endsWith(".zip", true) -> "application/zip"
        else -> "application/octet-stream"
    }
}
