package com.sackup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sackup.MainActivity
import com.sackup.R
import com.sackup.data.BackupGroup
import com.sackup.data.BackupRepository
import com.sackup.data.LogEntry
import com.sackup.util.formatBytes
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class BackupService : Service() {

    companion object {
        const val CHANNEL_ID = "sackup_backup"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.sackup.START_BACKUP"
        const val ACTION_CANCEL = "com.sackup.CANCEL_BACKUP"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_DRIVE_URI = "drive_uri"

        // Buffer size: 1MB for maximum throughput
        const val BUFFER_SIZE = 1024 * 1024

        // Shared state for UI to observe
        @Volatile var isRunning = false
        @Volatile var currentGroupName = ""
        @Volatile var currentFileName = ""
        @Volatile var totalFiles = 0
        @Volatile var completedFiles = 0
        @Volatile var skippedFiles = 0
        @Volatile var failedFiles = 0
        @Volatile var totalBytes = 0L
        @Volatile var copiedBytes = 0L
        @Volatile var progressPercent = 0
        @Volatile var isDone = false
        @Volatile var lastError = ""
        @Volatile var failedFilesList: List<String> = emptyList()

        fun start(context: Context, groupId: Long, driveUri: Uri) {
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GROUP_ID, groupId)
                putExtra(EXTRA_DRIVE_URI, driveUri.toString())
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
            totalFiles = 0
            completedFiles = 0
            skippedFiles = 0
            failedFiles = 0
            totalBytes = 0L
            copiedBytes = 0L
            progressPercent = 0
            isDone = false
            lastError = ""
            failedFilesList = emptyList()
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
                backupJob = scope.launch { runBackup(groupId, driveUri) }
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

    private suspend fun runBackup(groupId: Long, driveUri: Uri) {
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

        // Parse phone folders from JSON
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

        // Collect all files to copy
        val filesToCopy = mutableListOf<Pair<File, String>>() // file, relative subfolder name

        for (folderPath in phoneFolders) {
            if (cancelled) break
            val phoneDir = File(Environment.getExternalStorageDirectory(), folderPath)
            if (!phoneDir.exists() || !phoneDir.isDirectory) {
                log("WARN", group.name, "Phone folder not found: $folderPath")
                continue
            }
            val files = phoneDir.listFiles()?.filter { it.isFile } ?: emptyList()
            log("INFO", group.name, "Found ${files.size} files in $folderPath")
            for (file in files) {
                filesToCopy.add(Pair(file, folderPath.replace("/", "_")))
            }
        }

        totalFiles = filesToCopy.size
        totalBytes = filesToCopy.sumOf { it.first.length() }
        log("INFO", group.name, "Total: $totalFiles files, ${formatBytes(totalBytes)}")

        if (filesToCopy.isEmpty()) {
            log("INFO", group.name, "Nothing to back up — all folders empty")
            finishBackup(group)
            return
        }

        // Scan existing files on drive to skip already-backed-up files
        val existingFiles = mutableMapOf<String, MutableMap<String, Long>>() // subfolder -> (name -> size)
        for (folderPath in phoneFolders) {
            if (cancelled) break
            val subName = folderPath.replace("/", "_")
            val subDir = driveFolder.findFile(subName)
            if (subDir != null && subDir.isDirectory) {
                val map = mutableMapOf<String, Long>()
                for (f in subDir.listFiles()) {
                    if (f.isFile) {
                        map[f.name ?: ""] = f.length()
                    }
                }
                existingFiles[subName] = map
            }
        }

        val errors = mutableListOf<String>()
        var copiedCount = 0
        var copiedSize = 0L
        val startTime = System.currentTimeMillis()

        for ((file, subFolder) in filesToCopy) {
            if (cancelled) {
                log("INFO", group.name, "Backup cancelled by user")
                break
            }

            currentFileName = file.name

            // Check if already exists on drive (same name and size = skip)
            val existing = existingFiles[subFolder]
            if (existing != null && existing[file.name] == file.length()) {
                skippedFiles++
                completedFiles++
                updateProgress()
                continue
            }

            // Get or create subfolder on drive
            val subDir = driveFolder.findFile(subFolder)
                ?: driveFolder.createDirectory(subFolder)
            if (subDir == null) {
                val msg = "Could not create subfolder '$subFolder' on drive"
                log("ERROR", group.name, msg)
                errors.add("${file.name}: $msg")
                failedFiles++
                completedFiles++
                updateProgress()
                continue
            }

            // Copy the file
            try {
                copyFile(file, subDir)
                copiedCount++
                copiedSize += file.length()
                copiedBytes += file.length()
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                log("ERROR", group.name, "Failed to copy ${file.name}: $msg")
                errors.add("${file.name}: $msg")
                failedFiles++
                // Try to clean up partial file
                try {
                    subDir.findFile(file.name)?.delete()
                } catch (_: Exception) {}
            }

            completedFiles++
            updateProgress()
            updateNotification("Copying: ${file.name} ($completedFiles/$totalFiles)")
        }

        val elapsed = System.currentTimeMillis() - startTime

        // Summary
        val summary = buildString {
            append("${group.name} backup ")
            if (cancelled) append("cancelled. ") else append("complete. ")
            append("$copiedCount files copied (${formatBytes(copiedSize)})")
            if (skippedFiles > 0) append(", $skippedFiles already on drive")
            if (failedFiles > 0) append(", $failedFiles failed")
            append(".")
        }
        log("INFO", group.name, summary)

        failedFilesList = errors

        // Update group stats
        repo.updateGroup(
            group.copy(
                lastBackupTime = System.currentTimeMillis(),
                lastBackupFileCount = copiedCount,
                lastBackupBytes = copiedSize
            )
        )

        finishBackup(group)
    }

    private fun copyFile(source: File, destDir: DocumentFile) {
        // Determine MIME type
        val mimeType = when {
            source.name.endsWith(".jpg", true) || source.name.endsWith(".jpeg", true) -> "image/jpeg"
            source.name.endsWith(".png", true) -> "image/png"
            source.name.endsWith(".mp4", true) -> "video/mp4"
            source.name.endsWith(".pdf", true) -> "application/pdf"
            else -> "application/octet-stream"
        }

        // Delete existing file if present (might be partial from previous failed attempt)
        destDir.findFile(source.name)?.delete()

        val destFile = destDir.createFile(mimeType, source.name)
            ?: throw Exception("Could not create file on drive")

        val resolver = contentResolver
        val outputStream = resolver.openOutputStream(destFile.uri)
            ?: throw Exception("Could not open output stream")

        outputStream.use { out ->
            source.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelled) {
                        // Clean up partial file
                        destFile.delete()
                        throw Exception("Cancelled")
                    }
                    out.write(buffer, 0, bytesRead)
                }
            }
        }

        // Verify file size
        val destSize = destFile.length()
        val sourceSize = source.length()
        if (destSize != sourceSize) {
            destFile.delete()
            throw Exception("Size mismatch after copy: expected ${formatBytes(sourceSize)}, got ${formatBytes(destSize)}")
        }
    }

    private fun updateProgress() {
        progressPercent = if (totalFiles > 0) (completedFiles * 100 / totalFiles) else 0
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
