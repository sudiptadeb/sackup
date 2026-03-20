package com.sackup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sackup.MainActivity
import com.sackup.R
import com.sackup.data.BackupRepository
import com.sackup.data.LogEntry
import com.sackup.util.formatBytes
import kotlinx.coroutines.*
import java.util.UUID

class BackupService : Service() {

    companion object {
        const val CHANNEL_ID = "sackup_backup"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.sackup.START_BACKUP"
        const val ACTION_CANCEL = "com.sackup.CANCEL_BACKUP"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_DRIVE_URI = "drive_uri"

        // Shared state for UI to observe
        @Volatile var isRunning = false
        @Volatile var currentPhase = ""        // "Scanning", "Copying", "Finishing"
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
        @Volatile var failedFilesList: List<String> = emptyList()
        @Volatile var startTimeMillis = 0L
        @Volatile var bytesPerSecond = 0L

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
            currentPhase = ""
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
                if (!cancelled) {
                    cancelled = true
                    scope.launch {
                        log("INFO", currentGroupName.ifEmpty { "Backup" }, "Cancel requested by user")
                    }
                    backupJob?.cancel()
                    updateNotification("Cancelling...")
                }
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
            finishBackup()
            return
        }

        currentGroupName = group.name
        log("INFO", group.name, "Starting backup for ${group.name}")

        val phoneFolders: List<String> = try {
            Gson().fromJson(group.phoneFolders, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
            log("ERROR", group.name, "Invalid folder configuration: ${e.message}")
            finishBackup()
            return
        }

        val engine = BackupEngine(contentResolver)

        // ── Phase 1: Snapshot & Diff ──────────────────────────────────────
        currentPhase = "Scanning"
        updateNotification("Scanning phone and drive...")
        log("INFO", group.name, "Phase 1: Scanning...")

        val syncTimestamp = System.currentTimeMillis() / 1000  // freeze point

        val snapshot: SnapshotResult
        try {
            snapshot = engine.snapshot(phoneFolders, driveUri, syncTimestamp)
        } catch (e: Exception) {
            log("ERROR", group.name, "Scan failed: ${e.message}")
            finishBackup()
            return
        }

        if (cancelled) { finishBackup(); return }

        totalFiles = snapshot.filesToCopy.size
        totalBytes = snapshot.totalBytesToCopy
        skippedFiles = snapshot.alreadyOnDrive

        log("INFO", group.name,
            "${snapshot.filesToCopy.size} files to copy (${formatBytes(snapshot.totalBytesToCopy)}), " +
            "${snapshot.alreadyOnDrive} already on drive")

        if (snapshot.filesToCopy.isEmpty()) {
            log("INFO", group.name, "Everything is already backed up")
            // Still rebuild manifest
            currentPhase = "Finishing"
            rebuildManifest(group.id, engine, snapshot, emptySet(), true)
            finishBackup()
            return
        }

        // ── Phase 2: Parallel Copy ────────────────────────────────────────
        currentPhase = "Copying"
        startTimeMillis = System.currentTimeMillis()
        updateNotification("Copying ${snapshot.filesToCopy.size} files...")
        log("INFO", group.name, "Phase 2: Copying with ${BackupEngine.WORKER_COUNT} workers, ${BackupEngine.BUFFER_SIZE / 1024 / 1024}MB buffers...")

        val copiedFileKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        val copyResult: CopyResult
        try {
            copyResult = engine.parallelCopy(
                snapshot = snapshot,
                treeUri = driveUri,
                isCancelled = { cancelled },
                onProgress = { completed, fileName, bytes, speed ->
                    completedFiles = completed
                    currentFileName = fileName
                    copiedBytes = bytes
                    bytesPerSecond = speed
                    progressPercent = if (totalFiles > 0) (completed * 100 / totalFiles) else 0
                    updateNotification("Copying: $fileName ($completed/$totalFiles)")

                    // Track successfully copied files (the callback fires after success)
                    val pf = snapshot.filesToCopy.getOrNull(completed - 1)
                    if (pf != null) copiedFileKeys.add("${pf.drivePath}|${pf.name}")
                }
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            log("INFO", group.name, "Backup cancelled — copy workers stopped")
            finishBackup()
            return
        }

        if (cancelled) {
            log("INFO", group.name, "Backup cancelled after copying $completedFiles/$totalFiles files")
            finishBackup()
            return
        }

        failedFiles = copyResult.failedCount
        failedFilesList = copyResult.failedFiles

        val elapsed = System.currentTimeMillis() - startTimeMillis
        val summary = buildString {
            append("${group.name} backup complete. ")
            append("${copyResult.copiedCount} files copied (${formatBytes(copyResult.copiedSize)})")
            if (skippedFiles > 0) append(", $skippedFiles already on drive")
            if (copyResult.failedCount > 0) append(", ${copyResult.failedCount} failed")
            if (elapsed > 0 && copyResult.copiedSize > 0) {
                val avgSpeed = copyResult.copiedSize * 1000 / elapsed
                append(". Speed: ${formatBytes(avgSpeed)}/s")
            }
            append(".")
        }
        log("INFO", group.name, summary)

        // Update group stats
        repo.updateGroup(
            group.copy(
                lastBackupTime = System.currentTimeMillis(),
                lastBackupFileCount = copyResult.copiedCount,
                lastBackupBytes = copyResult.copiedSize
            )
        )

        // ── Phase 3: Manifest rebuild ─────────────────────────────────────
        currentPhase = "Finishing"
        updateNotification("Updating manifest...")
        log("INFO", group.name, "Phase 3: Rebuilding manifest...")
        val backupSuccess = copyResult.failedCount == 0
        rebuildManifest(group.id, engine, snapshot, copiedFileKeys, backupSuccess)
        log("INFO", group.name, "Manifest updated")

        finishBackup()
    }

    private suspend fun rebuildManifest(
        groupId: Long,
        engine: BackupEngine,
        snapshot: SnapshotResult,
        copiedFileKeys: Set<String>,
        backupSuccess: Boolean
    ) {
        val entries = engine.buildManifestEntries(groupId, snapshot, CopyResult(0, 0, 0, emptyList()), copiedFileKeys, backupSuccess)
        repo.rebuildManifest(groupId, entries)
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

    private fun finishBackup() {
        isDone = true
        isRunning = false
        currentFileName = ""

        val summary = if (cancelled) {
            "Backup cancelled"
        } else if (failedFiles > 0) {
            "$currentGroupName: $completedFiles files done, $failedFiles failed"
        } else {
            "$currentGroupName: $completedFiles files backed up"
        }

        updateNotification(summary)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Backup Progress", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows backup progress" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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
