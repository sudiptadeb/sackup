package com.sackup.service

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.sackup.data.ManifestEntry
import com.sackup.util.MediaStoreCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    val dirDocIds: Map<String, String>,                            // drivePath → documentId
    /** Size-mismatched (interrupted) copies found on the drive; deleted before copying. */
    val partialsOnDrive: List<Pair<PhoneFile, DriveFileInfo>> = emptyList()
)

data class CopyResult(
    val copiedCount: Int,
    val copiedSize: Long,
    val failedCount: Int,
    val failedFiles: List<String>,
    val copiedFileKeys: Set<String> = emptySet(),  // "drivePath|name" of successfully copied files
    /** True when the copy phase stopped early because the user/system cancelled. */
    val cancelled: Boolean = false,
    /** Non-null when the copy phase gave up on its own (e.g. drive disconnected). */
    val abortReason: String? = null
)

// ── Engine ────────────────────────────────────────────────────────────────────

class BackupEngine(private val resolver: ContentResolver) {

    companion object {
        const val BUFFER_SIZE = 4 * 1024 * 1024  // 4MB read buffer
        const val WRITE_CHUNK = 256 * 1024        // 256KB write chunks for responsive cancel
        const val WORKER_COUNT = 2  // USB is serial — more workers = more SAF overhead, not more throughput

        internal fun getMimeType(fileName: String): String = when {
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

    // ── Phase 1: Snapshot & Diff ──────────────────────────────────────────

    class ScanCancelledException : Exception("Scan cancelled")

    /**
     * Scan the drive and the phone and work out what needs copying.
     * Read-only: never creates, renames or deletes anything on the drive
     * (Analyze calls this too). Stale partial copies are reported in
     * [SnapshotResult.partialsOnDrive] and dealt with by [parallelCopy].
     */
    fun snapshot(
        phoneFolders: List<String>,
        treeUri: Uri,
        syncTimestamp: Long = Long.MAX_VALUE,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((phase: String, detail: String, filesFound: Int) -> Unit)? = null
    ): SnapshotResult {
        // 1. Get drive root doc ID
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)

        // 2. Scan drive using DocumentsContract cursors
        val driveFileCache = mutableMapOf<String, MutableMap<String, DriveFileInfo>>()
        val dirDocIds = mutableMapOf<String, String>()
        scanFileCount = 0
        var driveFilesFound = 0

        for (folderPath in phoneFolders) {
            if (isCancelled?.invoke() == true) throw ScanCancelledException()
            onProgress?.invoke("Scanning drive", folderPath, driveFilesFound)
            val segments = folderPath.split("/")
            var currentDocId = rootDocId
            var currentPath = ""
            var found = true
            for (segment in segments) {
                currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
                val childDocId = findChildDocId(treeUri, currentDocId, segment)
                if (childDocId != null) {
                    dirDocIds[currentPath] = childDocId
                    currentDocId = childDocId
                } else {
                    found = false
                    break
                }
            }
            if (found) {
                scanDriveCursor(treeUri, currentDocId, folderPath, driveFileCache, dirDocIds, isCancelled) { count ->
                    driveFilesFound = count
                    onProgress?.invoke("Scanning drive", folderPath, driveFilesFound)
                }
            }
        }

        // 3. Query phone files from MediaStore with timestamp filter
        onProgress?.invoke("Scanning phone", "", driveFilesFound)
        val allPhoneFiles = mutableListOf<PhoneFile>()
        for (folderPath in phoneFolders) {
            if (isCancelled?.invoke() == true) throw ScanCancelledException()
            allPhoneFiles.addAll(queryPhoneFiles(folderPath, folderPath, syncTimestamp))
            onProgress?.invoke("Scanning phone", folderPath, allPhoneFiles.size)
        }

        onProgress?.invoke("Computing diff", "${allPhoneFiles.size} phone, $driveFilesFound drive", 0)

        // 4. Diff (pure)
        val diff = computeDiff(phoneFolders, allPhoneFiles, driveFileCache)

        return SnapshotResult(
            filesToCopy = diff.filesToCopy,
            totalBytesToCopy = diff.totalBytesToCopy,
            alreadyOnDrive = diff.alreadyOnDrive,
            perFolder = diff.perFolder,
            allPhoneFiles = allPhoneFiles,
            driveFileCache = driveFileCache,
            dirDocIds = dirDocIds,
            partialsOnDrive = diff.partialsOnDrive
        )
    }

    /**
     * Fast snapshot using manifest instead of drive scan.
     * Phone files from MediaStore, drive state from manifest (Room SQLite).
     * No USB I/O at all — completes in milliseconds.
     */
    fun snapshotFromManifest(
        phoneFolders: List<String>,
        manifestEntries: List<ManifestEntry>,
        syncTimestamp: Long = Long.MAX_VALUE
    ): SnapshotResult {
        // Build drive file cache from manifest
        val driveFileCache = mutableMapOf<String, MutableMap<String, DriveFileInfo>>()
        for (entry in manifestEntries) {
            driveFileCache.getOrPut(entry.drivePath) { mutableMapOf() }[entry.fileName] =
                DriveFileInfo(entry.fileSize, "")  // no docId known from the manifest
        }

        // Query phone files
        val allPhoneFiles = mutableListOf<PhoneFile>()
        for (folderPath in phoneFolders) {
            allPhoneFiles.addAll(queryPhoneFiles(folderPath, folderPath, syncTimestamp))
        }

        val diff = computeDiff(phoneFolders, allPhoneFiles, driveFileCache)

        return SnapshotResult(
            filesToCopy = diff.filesToCopy,
            totalBytesToCopy = diff.totalBytesToCopy,
            alreadyOnDrive = diff.alreadyOnDrive,
            perFolder = diff.perFolder,
            allPhoneFiles = allPhoneFiles,
            driveFileCache = driveFileCache,
            dirDocIds = emptyMap(),  // no dir doc IDs from manifest
            partialsOnDrive = diff.partialsOnDrive
        )
    }

    // ── Phase 2: Parallel Copy ────────────────────────────────────────────

    /**
     * Copy every file in [snapshot.filesToCopy] to the drive.
     *
     * - Stale partial copies listed in [SnapshotResult.partialsOnDrive] are deleted first.
     * - [bytesCopied] is advanced per written chunk (and rolled back for files that fail),
     *   so the caller can sample it for byte progress and speed.
     * - [onFileDone] is called after each file with the running completed and failed counts.
     * - After [MAX_CONSECUTIVE_FAILURES] failures in a row the phase stops and the result
     *   carries [CopyResult.abortReason].
     * - If the calling coroutine is cancelled (or [isCancelled] turns true) the partial result
     *   is still returned with [CopyResult.cancelled] = true so the caller can record what
     *   did make it onto the drive.
     */
    suspend fun parallelCopy(
        snapshot: SnapshotResult,
        treeUri: Uri,
        isCancelled: () -> Boolean,
        bytesCopied: AtomicLong = AtomicLong(0),
        onLog: suspend (level: String, message: String) -> Unit = { _, _ -> },
        onFileDone: (completed: Int, failed: Int, fileName: String) -> Unit
    ): CopyResult {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirDocIds = snapshot.dirDocIds.toMutableMap()

        // Remove interrupted copies from an earlier run. They are incomplete by definition;
        // leaving them would make the provider create "name (1)" next to them forever.
        removeStalePartials(snapshot, treeUri, rootDocId, dirDocIds, isCancelled, onLog)

        // Pre-create all needed directories and collect doc IDs
        val neededPaths = snapshot.filesToCopy.map { it.drivePath }.toSet()
        for (path in neededPaths) {
            if (isCancelled()) return CopyResult(0, 0, 0, emptyList(), cancelled = true)
            if (path !in dirDocIds) {
                ensureDirPath(treeUri, rootDocId, path, dirDocIds)
            }
        }

        // Build CopyJobs
        val errors = ConcurrentLinkedQueue<String>()
        val jobs = snapshot.filesToCopy.mapNotNull { pf ->
            val parentDocId = dirDocIds[pf.drivePath]
            if (parentDocId == null) {
                errors.add("${pf.name}: Could not create folder \"${pf.drivePath}\" on the drive")
                null
            } else {
                CopyJob(phone = pf, destParentDocId = parentDocId)
            }
        }

        if (jobs.isEmpty()) {
            return CopyResult(0, 0, errors.size, errors.toList())
        }

        // Parallel copy with workers
        val channel = Channel<CopyJob>(Channel.UNLIMITED)
        val completedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(errors.size)
        val consecutiveFailures = AtomicInteger(0)
        val successKeys = ConcurrentLinkedQueue<String>()
        val verifiedBytes = AtomicLong(0)
        val abortReason = AtomicReference<String?>(null)
        var wasCancelled = false

        for (job in jobs) channel.send(job)
        channel.close()

        try {
            coroutineScope {
                repeat(WORKER_COUNT) {
                    launch(Dispatchers.IO) {
                        for (job in channel) {
                            if (isCancelled() || abortReason.get() != null) break

                            var fileBytes = 0L
                            try {
                                copyOneFile(job, treeUri, isCancelled) { delta ->
                                    fileBytes += delta
                                    bytesCopied.addAndGet(delta)
                                }
                                verifiedBytes.addAndGet(fileBytes)
                                successKeys.add(job.phone.driveKey())
                                consecutiveFailures.set(0)
                            } catch (_: CancelledException) {
                                bytesCopied.addAndGet(-fileBytes)
                                break
                            } catch (e: CancellationException) {
                                bytesCopied.addAndGet(-fileBytes)
                                throw e
                            } catch (e: Exception) {
                                bytesCopied.addAndGet(-fileBytes)
                                failedCount.incrementAndGet()
                                errors.add("${job.phone.name}: ${friendlyCopyError(e)}")
                                // A missing source file is a stale MediaStore row, not a drive problem:
                                // it must not trip the "drive disconnected" breaker.
                                if (e is SourceMissingException) {
                                    consecutiveFailures.set(0)
                                } else if (consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
                                    abortReason.compareAndSet(null, DRIVE_DISCONNECTED_MESSAGE)
                                }
                            }

                            val c = completedCount.incrementAndGet()
                            onFileDone(c, failedCount.get(), job.phone.name)
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            wasCancelled = true
        }
        if (isCancelled()) wasCancelled = true

        return CopyResult(
            copiedCount = successKeys.size,
            copiedSize = verifiedBytes.get(),
            failedCount = failedCount.get(),
            failedFiles = errors.toList(),
            copiedFileKeys = successKeys.toSet(),
            cancelled = wasCancelled,
            abortReason = abortReason.get()
        )
    }

    private suspend fun removeStalePartials(
        snapshot: SnapshotResult,
        treeUri: Uri,
        rootDocId: String,
        dirDocIds: MutableMap<String, String>,
        isCancelled: () -> Boolean,
        onLog: suspend (level: String, message: String) -> Unit
    ) {
        for ((pf, info) in snapshot.partialsOnDrive) {
            if (isCancelled()) return
            var docId: String? = info.documentId.takeIf { it.isNotBlank() }
            if (docId == null) {
                // Manifest-based snapshot: locate the document by name
                if (pf.drivePath !in dirDocIds) ensureDirPath(treeUri, rootDocId, pf.drivePath, dirDocIds)
                val parent = dirDocIds[pf.drivePath] ?: continue
                docId = findChildDocId(treeUri, parent, pf.name) ?: continue
            }
            try {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                DocumentsContract.deleteDocument(resolver, docUri)
                onLog("WARN", "Removed incomplete copy of ${pf.name} from the drive (${info.size} of ${pf.size} bytes)")
            } catch (e: Exception) {
                onLog("WARN", "Could not remove incomplete copy of ${pf.name}: ${friendlyCopyError(e)}")
            }
        }
    }

    // ── Drive scanning with DocumentsContract cursors ─────────────────────

    private var scanFileCount = 0  // mutable counter for recursive scan

    private fun scanDriveCursor(
        treeUri: Uri,
        parentDocId: String,
        parentPath: String,
        files: MutableMap<String, MutableMap<String, DriveFileInfo>>,
        dirs: MutableMap<String, String>,
        isCancelled: (() -> Boolean)? = null,
        onFileCount: ((Int) -> Unit)? = null
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
                if (isCancelled?.invoke() == true) throw ScanCancelledException()
                val docId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: ""

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name.startsWith(".")) continue  // skip hidden dirs (.thumbnails, .trashed, etc.)
                    val childPath = "$parentPath/$name"
                    dirs[childPath] = docId
                    scanDriveCursor(treeUri, docId, childPath, files, dirs, isCancelled, onFileCount)
                } else {
                    if (name.startsWith(".")) continue  // skip hidden files (.nomedia, .DS_Store, etc.)
                    val size = cursor.getLong(sizeCol)
                    files.getOrPut(parentPath) { mutableMapOf() }[name] = DriveFileInfo(size, docId)
                    scanFileCount++
                    if (scanFileCount % 50 == 0) {
                        onFileCount?.invoke(scanFileCount)
                    }
                }
            }
        }
        onFileCount?.invoke(scanFileCount)
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
                val created = try {
                    createChildDir(treeUri, currentDocId, segment)
                } catch (_: Exception) {
                    null
                }
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
        val seenIds = mutableSetOf<Long>()  // deduplicate by MediaStore ID
        val collection = MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStoreCompat.pathColumn,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        // Single query: everything inside folderPath (covers both direct and subfolders).
        // MediaStoreCompat picks RELATIVE_PATH (API 29+) or DATA (API 26-28).
        val (folderSelection, folderArgs) = MediaStoreCompat.folderSelection(folderPath)
        val selection: String
        val args: Array<String>
        if (maxTimestamp < Long.MAX_VALUE) {
            selection = "($folderSelection) AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} <= ?"
            args = folderArgs + maxTimestamp.toString()
        } else {
            selection = folderSelection
            args = folderArgs
        }

        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            readPhoneCursor(cursor, collection, folderPath, topName, results, seenIds)
        }

        return results
    }

    private fun readPhoneCursor(
        cursor: android.database.Cursor,
        collection: Uri,
        folderPath: String,
        topName: String,
        results: MutableList<PhoneFile>,
        seenIds: MutableSet<Long> = mutableSetOf()
    ) {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val pathCol = cursor.getColumnIndexOrThrow(MediaStoreCompat.pathColumn)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            if (!seenIds.add(id)) continue  // skip duplicates
            val name = cursor.getString(nameCol) ?: continue
            val size = cursor.getLong(sizeCol)
            val relativePath = MediaStoreCompat.relativePathOf(cursor, pathCol) ?: continue
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

    /**
     * Copy one file and verify it. Order matters:
     *  1. open the source first — a stale MediaStore row must not leave a 0-byte file behind;
     *  2. create the destination document;
     *  3. write through a file descriptor so the data can be fsync'ed before we trust it;
     *  4. re-query the destination: size and display name must match what we asked for
     *     (the provider silently renames on collision and sanitises invalid FAT names).
     * Anything that fails after step 2 deletes the destination and rethrows.
     */
    private fun copyOneFile(
        job: CopyJob,
        treeUri: Uri,
        isCancelled: () -> Boolean,
        onChunk: (Long) -> Unit
    ) {
        if (isCancelled()) throw CancelledException()

        val inputStream = try {
            resolver.openInputStream(job.phone.uri)
        } catch (e: FileNotFoundException) {
            throw SourceMissingException(e)
        } ?: throw SourceMissingException(FileNotFoundException("openInputStream returned null"))

        val destUri: Uri
        try {
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, job.destParentDocId)
            destUri = DocumentsContract.createDocument(resolver, parentDocUri, getMimeType(job.phone.name), job.phone.name)
                ?: throw CopyVerificationException("The drive did not let us create \"${job.phone.name}\"")
        } catch (e: Exception) {
            try { inputStream.close() } catch (_: Exception) {}
            throw e
        }

        var written = 0L
        try {
            inputStream.use { inp ->
                val pfd = resolver.openFileDescriptor(destUri, "w")
                    ?: throw CopyVerificationException("Could not open \"${job.phone.name}\" on the drive for writing")
                pfd.use {
                    FileOutputStream(pfd.fileDescriptor).use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (inp.read(buffer).also { bytesRead = it } != -1) {
                            // Write in small chunks so cancel checks are responsive
                            var offset = 0
                            while (offset < bytesRead) {
                                if (isCancelled()) throw CancelledException()
                                val chunk = minOf(WRITE_CHUNK, bytesRead - offset)
                                out.write(buffer, offset, chunk)
                                offset += chunk
                                written += chunk
                                onChunk(chunk.toLong())
                            }
                        }
                        out.flush()
                        pfd.fileDescriptor.sync()   // durable before we call it done
                    }
                }
            }

            if (written != job.phone.size) {
                throw CopyVerificationException(
                    "Copied $written of ${job.phone.size} bytes of \"${job.phone.name}\" — the file may have changed while copying"
                )
            }
            verifyDestination(destUri, job.phone)
        } catch (e: Exception) {
            // Clean up the partial/unverified file on the drive
            try { DocumentsContract.deleteDocument(resolver, destUri) } catch (_: Exception) {}
            throw e
        }

        // Preserve timestamp (best effort)
        if (job.phone.dateModified > 0) {
            try {
                val values = ContentValues().apply {
                    put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, job.phone.dateModified * 1000)
                }
                resolver.update(destUri, values, null, null)
            } catch (_: Exception) {}
        }
    }

    private fun verifyDestination(destUri: Uri, phone: PhoneFile) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val cursor = resolver.query(destUri, projection, null, null, null)
            ?: throw CopyVerificationException("Could not check \"${phone.name}\" on the drive after copying")
        cursor.use {
            if (!it.moveToFirst()) {
                throw CopyVerificationException("\"${phone.name}\" is missing from the drive after copying")
            }
            val nameIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val onDriveName = if (nameIdx >= 0) it.getString(nameIdx) else null
            val onDriveSize = if (sizeIdx >= 0 && !it.isNull(sizeIdx)) it.getLong(sizeIdx) else -1L

            if (onDriveName != null && onDriveName != phone.name) {
                throw CopyVerificationException(
                    "The drive saved \"${phone.name}\" as \"$onDriveName\" — a file with that name may already exist there"
                )
            }
            if (onDriveSize != phone.size) {
                throw CopyVerificationException(
                    "\"${phone.name}\" is ${if (onDriveSize < 0) "of unknown size" else "$onDriveSize bytes"} on the drive but ${phone.size} bytes on the phone"
                )
            }
        }
    }
}
