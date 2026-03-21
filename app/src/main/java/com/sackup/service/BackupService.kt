package com.sackup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sackup.MainActivity
import com.sackup.R
import com.sackup.data.BackupGroup
import com.sackup.data.BackupRepository
import com.sackup.data.LogEntry
import com.sackup.data.ManifestEntry
import com.sackup.util.formatBytes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a file discovered via MediaStore that needs to be backed up.
 */
private data class MediaFileInfo(
    val uri: Uri,              // content:// URI to read from
    val name: String,          // display name e.g. "IMG_001.jpg"
    val size: Long,            // file size in bytes
    val drivePath: String,     // relative path on drive e.g. "DCIM_Camera"
    val dateModified: Long     // last modified timestamp in seconds (epoch)
)

/**
 * A file that needs to be copied, with its destination directory URI pre-resolved.
 */
private data class CopyJob(
    val source: MediaFileInfo,
    val destDirUri: Uri        // document URI of the destination directory
)

/**
 * Cached info about a file on the drive.
 */
private data class DriveFileInfo(
    val name: String,
    val size: Long,
    val documentId: String
)

class BackupService : Service() {

    companion object {
        const val CHANNEL_ID = "sackup_backup"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.sackup.START_BACKUP"
        const val ACTION_CANCEL = "com.sackup.CANCEL_BACKUP"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_DRIVE_URI = "drive_uri"
        const val EXTRA_SYNC_TIMESTAMP = "sync_timestamp"

        // Buffer size: 4MB for maximum throughput
        const val BUFFER_SIZE = 4 * 1024 * 1024

        // Number of parallel copy workers
        const val COPY_WORKERS = 4

        // Shared state for UI to observe (atomic for thread-safe parallel copy)
        @Volatile var isRunning = false
        @Volatile var currentGroupName = ""
        @Volatile var currentFileName = ""
        @Volatile var currentPhase = ""   // "scanning", "comparing", "copying", "finishing"
        @Volatile var totalFiles = 0
        @Volatile var totalBytes = 0L
        @Volatile var isDone = false
        @Volatile var lastError = ""
        @Volatile var failedFilesList: List<String> = emptyList()
        @Volatile var startTimeMillis = 0L
        @Volatile var bytesPerSecond = 0L

        // Thread-safe counters for parallel copy
        private val _completedFiles = AtomicInteger(0)
        private val _skippedFiles = AtomicInteger(0)
        private val _failedFiles = AtomicInteger(0)
        private val _copiedBytes = AtomicLong(0L)
        private val _progressPercent = AtomicInteger(0)

        val completedFiles: Int get() = _completedFiles.get()
        val skippedFiles: Int get() = _skippedFiles.get()
        val failedFiles: Int get() = _failedFiles.get()
        val copiedBytes: Long get() = _copiedBytes.get()
        val progressPercent: Int get() = _progressPercent.get()

        fun start(context: Context, groupId: Long, driveUri: Uri, syncTimestamp: Long) {
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GROUP_ID, groupId)
                putExtra(EXTRA_DRIVE_URI, driveUri.toString())
                putExtra(EXTRA_SYNC_TIMESTAMP, syncTimestamp)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

        fun resetState() {
            isRunning = false
            currentGroupName = ""
            currentFileName = ""
            currentPhase = ""
            totalFiles = 0
            _completedFiles.set(0)
            _skippedFiles.set(0)
            _failedFiles.set(0)
            totalBytes = 0L
            _copiedBytes.set(0L)
            _progressPercent.set(0)
            isDone = false
            lastError = ""
            failedFilesList = emptyList()
            startTimeMillis = 0L
            bytesPerSecond = 0L
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var backupJob: Job? = null
    private lateinit var repo: BackupRepository
    private lateinit var notificationManager: NotificationManager
    private var sessionId = ""
    private var cancelled = false

    override fun onCreate() {
        super.onCreate()
        repo = BackupRepository(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1)
                val driveUri = Uri.parse(intent.getStringExtra(EXTRA_DRIVE_URI))
                val syncTimestamp = intent.getLongExtra(EXTRA_SYNC_TIMESTAMP, System.currentTimeMillis())
                if (groupId == -1L) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("Preparing backup..."))
                resetState()
                isRunning = true
                isDone = false
                cancelled = false
                sessionId = UUID.randomUUID().toString().take(8)
                backupJob = scope.launch { runBackup(groupId, driveUri, syncTimestamp) }
            }
            ACTION_CANCEL -> {
                cancelled = true
                backupJob?.cancel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ========================================================================================
    // PHASE 1: Snapshot & Diff — scan phone + drive, build copy list, no drive writes
    // PHASE 2: Parallel Copy — N workers consume from a channel
    // PHASE 3: Post-copy — manifest rebuild, summary
    // ========================================================================================

    private suspend fun runBackup(groupId: Long, driveUri: Uri, syncTimestamp: Long) {
        val group = repo.getGroup(groupId)
        if (group == null) {
            log("ERROR", "", "Backup group not found")
            finishBackup(null)
            return
        }

        currentGroupName = group.name
        log("INFO", group.name, "Starting backup for ${group.name}")

        val driveRoot = DocumentFile.fromTreeUri(this, driveUri)
        if (driveRoot == null || !driveRoot.canWrite()) {
            log("ERROR", group.name, "Cannot access USB drive. Please reconnect and grant access.")
            finishBackup(group)
            return
        }

        val phoneFolders: List<String> = try {
            Gson().fromJson(group.phoneFolders, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
            log("ERROR", group.name, "Invalid folder configuration: ${e.message}")
            finishBackup(group)
            return
        }

        // Get or create the drive folder
        val driveFolder = driveRoot.findFile(group.driveFolder)
            ?: driveRoot.createDirectory(group.driveFolder)
        if (driveFolder == null) {
            log("ERROR", group.name, "Could not create folder '${group.driveFolder}' on drive")
            finishBackup(group)
            return
        }

        log("INFO", group.name, "Drive folder: ${group.driveFolder}")

        // ---- PHASE 1: Snapshot & Diff ----
        currentPhase = "scanning"
        updateNotification("Scanning phone files...")

        // 1a. Query phone files with timestamp cutoff
        val syncTimestampSecs = syncTimestamp / 1000
        val phoneFiles = mutableListOf<MediaFileInfo>()
        for (folderPath in phoneFolders) {
            if (cancelled) break
            val topName = folderPath.replace("/", "_")
            val files = queryMediaStoreFiles(folderPath, topName, syncTimestampSecs)
            log("INFO", group.name, "Found ${files.size} files in $folderPath")
            phoneFiles.addAll(files)
        }

        if (cancelled) { finishBackup(group); return }

        log("INFO", group.name, "Phone snapshot: ${phoneFiles.size} files (cutoff: files before sync tap only)")

        // 1b. Scan drive using fast DocumentsContract cursors
        currentPhase = "comparing"
        updateNotification("Scanning drive files...")

        // driveCache: drivePath → (fileName → DriveFileInfo)
        val driveCache = mutableMapOf<String, MutableMap<String, DriveFileInfo>>()
        // dirDocIds: drivePath → documentId of that directory
        val dirDocIds = mutableMapOf<String, String>()

        val driveFolderDocId = DocumentsContract.getDocumentId(driveFolder.uri)
        val treeUri = driveFolder.uri

        for (folderPath in phoneFolders) {
            if (cancelled) break
            val subName = folderPath.replace("/", "_")
            // Find the subfolder on drive
            val subDirDocId = findChildDocumentId(treeUri, driveFolderDocId, subName)
            if (subDirDocId != null) {
                dirDocIds[subName] = subDirDocId
                scanDriveFast(treeUri, subDirDocId, subName, driveCache, dirDocIds)
            }
        }

        if (cancelled) { finishBackup(group); return }

        val driveFileCount = driveCache.values.sumOf { it.size }
        log("INFO", group.name, "Drive scan: $driveFileCount files indexed")

        // 1c. Diff: determine what needs copying
        val copyList = mutableListOf<CopyJob>()
        var skipCount = 0
        var copyBytes = 0L

        for (file in phoneFiles) {
            val existing = driveCache[file.drivePath]
            if (existing != null && existing[file.name]?.size == file.size) {
                skipCount++
                continue
            }
            // We'll resolve the dest dir URI later after pre-creating dirs
            copyList.add(CopyJob(source = file, destDirUri = Uri.EMPTY))
        }

        // Set totals now — totalFiles includes skipped for progress tracking
        totalFiles = phoneFiles.size
        totalBytes = phoneFiles.sumOf { it.size }
        _skippedFiles.set(skipCount)
        _completedFiles.set(skipCount) // skipped are already "done"
        updateProgress()

        log("INFO", group.name, "Diff: ${copyList.size} to copy, $skipCount already on drive, total ${formatBytes(totalBytes)}")

        if (copyList.isEmpty()) {
            log("INFO", group.name, "Everything is already backed up!")
            finishBackup(group)
            return
        }

        // 1d. Pre-create all destination directories
        val uniquePaths = copyList.map { it.source.drivePath }.toSet()
        val resolvedDirUris = mutableMapOf<String, Uri>()

        for (path in uniquePaths) {
            if (cancelled) break
            val dirUri = getOrCreateDirFast(treeUri, driveFolderDocId, path, dirDocIds)
            if (dirUri != null) {
                resolvedDirUris[path] = dirUri
            } else {
                log("ERROR", group.name, "Could not create directory '$path' on drive")
            }
        }

        if (cancelled) { finishBackup(group); return }

        // Resolve dest URIs in copy jobs
        val resolvedCopyList = copyList.mapNotNull { job ->
            val dirUri = resolvedDirUris[job.source.drivePath]
            if (dirUri != null) {
                job.copy(destDirUri = dirUri)
            } else {
                _failedFiles.incrementAndGet()
                _completedFiles.incrementAndGet()
                updateProgress()
                null
            }
        }

        // ---- PHASE 2: Parallel Copy ----
        currentPhase = "copying"
        val startTime = System.currentTimeMillis()
        startTimeMillis = startTime

        log("INFO", group.name, "Starting parallel copy with $COPY_WORKERS workers...")

        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        var copiedCount = AtomicInteger(0)
        var copiedSize = AtomicLong(0)

        val channel = Channel<CopyJob>(capacity = Channel.UNLIMITED)

        // Feed all jobs into the channel
        for (job in resolvedCopyList) {
            channel.send(job)
        }
        channel.close()

        // Launch workers
        val workers = (1..COPY_WORKERS).map { workerNum ->
            scope.launch {
                for (job in channel) {
                    if (cancelled) break

                    currentFileName = job.source.name

                    try {
                        copyFileFast(job.source, job.destDirUri)
                        copiedCount.incrementAndGet()
                        copiedSize.addAndGet(job.source.size)
                        _copiedBytes.addAndGet(job.source.size)
                    } catch (e: Exception) {
                        val msg = e.message ?: "Unknown error"
                        log("ERROR", group.name, "Failed to copy ${job.source.name}: $msg")
                        errors.add("${job.source.name}: $msg")
                        _failedFiles.incrementAndGet()
                    }

                    _completedFiles.incrementAndGet()
                    updateProgress()

                    // Update speed estimate
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > 0) {
                        bytesPerSecond = _copiedBytes.get() * 1000 / elapsed
                    }

                    updateNotification("Copying: ${job.source.name} (${completedFiles}/$totalFiles)")
                }
            }
        }

        // Wait for all workers to finish
        workers.forEach { it.join() }

        if (cancelled) {
            log("INFO", group.name, "Backup cancelled by user")
        }

        val elapsed = System.currentTimeMillis() - startTime

        // ---- PHASE 3: Post-copy ----
        currentPhase = "finishing"

        val summary = buildString {
            append("${group.name} backup ")
            if (cancelled) append("cancelled. ") else append("complete. ")
            append("${copiedCount.get()} files copied (${formatBytes(copiedSize.get())})")
            if (skipCount > 0) append(", $skipCount already on drive")
            if (failedFiles > 0) append(", $failedFiles failed")
            append(".")
        }
        log("INFO", group.name, summary)

        failedFilesList = errors.toList()

        repo.updateGroup(
            group.copy(
                lastBackupTime = System.currentTimeMillis(),
                lastBackupFileCount = copiedCount.get(),
                lastBackupBytes = copiedSize.get()
            )
        )

        // Rebuild manifest using cached data + newly copied files
        if (!cancelled) {
            updateNotification("Building manifest...")
            log("INFO", group.name, "Rebuilding manifest...")
            val backupSuccess = failedFiles == 0
            rebuildManifest(group, phoneFolders, treeUri, driveFolderDocId, backupSuccess)
            log("INFO", group.name, "Manifest updated")
        }

        finishBackup(group)
    }

    // ========================================================================================
    // Fast drive scanning using DocumentsContract (no DocumentFile overhead)
    // ========================================================================================

    /**
     * Find a child document by name within a parent directory. Returns documentId or null.
     */
    private fun findChildDocumentId(treeUri: Uri, parentDocId: String, childName: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
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

    /**
     * Recursively scan a drive directory using DocumentsContract cursors.
     * Much faster than DocumentFile.listFiles() — no per-file object allocation.
     */
    private fun scanDriveFast(
        treeUri: Uri,
        dirDocId: String,
        dirPath: String,
        cache: MutableMap<String, MutableMap<String, DriveFileInfo>>,
        dirDocIds: MutableMap<String, String>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: ""
                val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val subPath = "$dirPath/$name"
                    dirDocIds[subPath] = docId
                    scanDriveFast(treeUri, docId, subPath, cache, dirDocIds)
                } else {
                    cache.getOrPut(dirPath) { mutableMapOf() }[name] = DriveFileInfo(name, size, docId)
                }
            }
        }
    }

    /**
     * Get or create a directory path on the drive, using cached documentIds where possible.
     * Returns the document URI of the final directory.
     */
    private fun getOrCreateDirFast(
        treeUri: Uri,
        rootDocId: String,
        path: String,
        dirDocIds: MutableMap<String, String>
    ): Uri? {
        val segments = path.split("/").filter { it.isNotBlank() }
        var currentDocId = rootDocId
        var currentPath = ""

        for (segment in segments) {
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"

            val cached = dirDocIds[currentPath]
            if (cached != null) {
                currentDocId = cached
                continue
            }

            // Try to find existing child
            val existingId = findChildDocumentId(treeUri, currentDocId, segment)
            if (existingId != null) {
                dirDocIds[currentPath] = existingId
                currentDocId = existingId
                continue
            }

            // Create the directory
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
            val newUri = DocumentsContract.createDocument(
                contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, segment
            ) ?: return null
            val newDocId = DocumentsContract.getDocumentId(newUri)
            dirDocIds[currentPath] = newDocId
            currentDocId = newDocId
        }

        return DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
    }

    // ========================================================================================
    // MediaStore queries (with timestamp filter)
    // ========================================================================================

    /**
     * Query MediaStore for files modified before the sync timestamp.
     * Files created after the user tapped Sync are excluded.
     */
    private fun queryMediaStoreFiles(folderPath: String, topName: String, beforeTimestampSecs: Long): List<MediaFileInfo> {
        val results = mutableListOf<MediaFileInfo>()
        val resolver = contentResolver
        val collection = MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        // Subfolder files: RELATIVE_PATH LIKE "folderPath/%"
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.SIZE} > 0 AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} <= ?"
        val selectionArgs = arrayOf("$folderPath/%", beforeTimestampSecs.toString())

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
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

                results.add(MediaFileInfo(contentUri, name, size, drivePath, dateModified))
            }
        }

        // Direct files: RELATIVE_PATH = "folderPath/"
        val directSelection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.SIZE} > 0 AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} <= ?"
        val directArgs = arrayOf("$folderPath/", beforeTimestampSecs.toString())

        resolver.query(collection, projection, directSelection, directArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val size = cursor.getLong(sizeCol)
                val dateModified = cursor.getLong(dateCol)

                val contentUri = ContentUris.withAppendedId(collection, id)
                results.add(MediaFileInfo(contentUri, name, size, topName, dateModified))
            }
        }

        return results
    }

    // ========================================================================================
    // Fast file copy using DocumentsContract (no findFile/createFile overhead)
    // ========================================================================================

    private fun copyFileFast(source: MediaFileInfo, destDirUri: Uri) {
        val mimeType = when {
            source.name.endsWith(".jpg", true) || source.name.endsWith(".jpeg", true) -> "image/jpeg"
            source.name.endsWith(".png", true) -> "image/png"
            source.name.endsWith(".gif", true) -> "image/gif"
            source.name.endsWith(".webp", true) -> "image/webp"
            source.name.endsWith(".mp4", true) -> "video/mp4"
            source.name.endsWith(".3gp", true) -> "video/3gpp"
            source.name.endsWith(".mkv", true) -> "video/x-matroska"
            source.name.endsWith(".mov", true) -> "video/quicktime"
            source.name.endsWith(".mp3", true) -> "audio/mpeg"
            source.name.endsWith(".m4a", true) -> "audio/mp4"
            source.name.endsWith(".pdf", true) -> "application/pdf"
            source.name.endsWith(".heic", true) -> "image/heic"
            source.name.endsWith(".heif", true) -> "image/heif"
            source.name.endsWith(".webm", true) -> "video/webm"
            source.name.endsWith(".avi", true) -> "video/x-msvideo"
            else -> "application/octet-stream"
        }

        val resolver = contentResolver

        // Delete existing partial file if present (size mismatch from previous attempt)
        val destDirDocId = DocumentsContract.getDocumentId(destDirUri)
        val existingDocId = findChildDocumentId(destDirUri, destDirDocId, source.name)
        if (existingDocId != null) {
            try {
                val existingUri = DocumentsContract.buildDocumentUriUsingTree(destDirUri, existingDocId)
                DocumentsContract.deleteDocument(resolver, existingUri)
            } catch (_: Exception) {}
        }

        // Create the file directly
        val destFileUri = DocumentsContract.createDocument(resolver, destDirUri, mimeType, source.name)
            ?: throw Exception("Could not create file on drive")

        val outputStream = resolver.openOutputStream(destFileUri)
            ?: throw Exception("Could not open output stream")
        val inputStream = resolver.openInputStream(source.uri)
            ?: throw Exception("Could not read source file")

        var bytesWritten = 0L
        try {
            outputStream.use { out ->
                inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) {
                            throw Exception("Cancelled")
                        }
                        out.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead
                    }
                }
            }
        } catch (e: Exception) {
            // Clean up partial file
            try { DocumentsContract.deleteDocument(resolver, destFileUri) } catch (_: Exception) {}
            throw e
        }

        // Verify file size
        if (bytesWritten != source.size) {
            try { DocumentsContract.deleteDocument(resolver, destFileUri) } catch (_: Exception) {}
            throw Exception("Size mismatch after copy: expected ${formatBytes(source.size)}, got ${formatBytes(bytesWritten)}")
        }

        // Preserve original modification timestamp
        if (source.dateModified > 0) {
            try {
                val values = ContentValues().apply {
                    put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, source.dateModified * 1000)
                }
                resolver.update(destFileUri, values, null, null)
            } catch (_: Exception) {
                // Not all document providers support setting timestamps — ignore
            }
        }
    }

    // ========================================================================================
    // Manifest rebuild (using fast DocumentsContract scan)
    // ========================================================================================

    private suspend fun rebuildManifest(
        group: BackupGroup,
        phoneFolders: List<String>,
        treeUri: Uri,
        driveFolderDocId: String,
        backupSuccess: Boolean
    ) {
        // Scan drive using fast cursors
        val driveFiles = mutableListOf<Triple<String, String, Long>>()
        val dirDocIds = mutableMapOf<String, String>()

        for (folderPath in phoneFolders) {
            val subName = folderPath.replace("/", "_")
            val subDocId = findChildDocumentId(treeUri, driveFolderDocId, subName) ?: continue
            dirDocIds[subName] = subDocId

            // Collect files
            val cache = mutableMapOf<String, MutableMap<String, DriveFileInfo>>()
            scanDriveFast(treeUri, subDocId, subName, cache, dirDocIds)
            for ((path, files) in cache) {
                for ((name, info) in files) {
                    driveFiles.add(Triple(path, name, info.size))
                }
            }
        }

        // Build phone file lookup (no timestamp filter for manifest — include everything)
        val phoneFiles = mutableMapOf<String, Pair<String, Long>>()
        for (folderPath in phoneFolders) {
            val topName = folderPath.replace("/", "_")
            val files = queryMediaStoreFiles(folderPath, topName, Long.MAX_VALUE)
            for (f in files) {
                phoneFiles["${f.drivePath}|${f.name}|${f.size}"] = Pair(folderPath, f.dateModified)
            }
        }

        val entries = mutableListOf<ManifestEntry>()
        for ((drivePath, fileName, fileSize) in driveFiles) {
            val key = "$drivePath|$fileName|$fileSize"
            val phoneInfo = phoneFiles[key]
            if (phoneInfo != null) {
                val (phoneFolder, dateModified) = phoneInfo
                val topName = phoneFolder.replace("/", "_")
                val subPath = drivePath.removePrefix(topName).trimStart('/')
                val phonePath = if (subPath.isEmpty()) "$phoneFolder/" else "$phoneFolder/$subPath/"

                entries.add(ManifestEntry(
                    groupId = group.id,
                    fileName = fileName,
                    fileSize = fileSize,
                    phoneFolder = phoneFolder,
                    phonePath = phonePath,
                    drivePath = drivePath,
                    dateModified = dateModified,
                    backupSuccess = backupSuccess
                ))
            }
        }

        repo.rebuildManifest(group.id, entries)
    }

    // ========================================================================================
    // Progress, logging, notifications
    // ========================================================================================

    private fun updateProgress() {
        _progressPercent.set(if (totalFiles > 0) (completedFiles * 100 / totalFiles) else 0)
    }

    private suspend fun log(level: String, groupName: String, message: String) {
        repo.insertLog(
            LogEntry(
                sessionId = sessionId,
                groupName = groupName,
                level = level,
                message = message
            )
        )
    }

    private fun finishBackup(group: BackupGroup?) {
        isDone = true
        isRunning = false
        currentFileName = ""
        currentPhase = ""

        val summary = if (cancelled) {
            "Backup cancelled"
        } else if (failedFiles > 0) {
            "${currentGroupName}: $completedFiles files done, $failedFiles failed"
        } else {
            "${currentGroupName}: $completedFiles files backed up"
        }

        updateNotification(summary)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Backup Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows backup progress"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BackupService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SackUp Backup")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setProgress(100, progressPercent, progressPercent == 0)
            .addAction(R.drawable.ic_notification, "Cancel", cancelIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
