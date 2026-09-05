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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sackup.MainActivity
import com.sackup.R
import com.sackup.data.BackupRepository
import com.sackup.data.LogEntry
import com.sackup.data.folderList
import com.sackup.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class BackupService : Service() {

    companion object {
        private const val TAG = "BackupService"

        const val CHANNEL_ID = "sackup_backup"                 // ongoing progress (low importance)
        const val RESULT_CHANNEL_ID = "sackup_backup_result"   // one-shot result (default importance)
        const val NOTIFICATION_ID = 1
        const val RESULT_NOTIFICATION_ID = 2
        const val ACTION_START = "com.sackup.START_BACKUP"
        const val ACTION_CANCEL = "com.sackup.CANCEL_BACKUP"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_DRIVE_URI = "drive_uri"
        /** Boolean extra on the notification's content Intent: MainActivity should show the progress screen. */
        const val EXTRA_OPEN_PROGRESS = "open_progress"

        const val TIMEOUT_MESSAGE = "Android stopped the backup after its time limit. Run it again to continue."
        private const val NOTIFICATION_MIN_INTERVAL_MS = 1000L
        private const val MAX_FAILURES_LOGGED = 200

        private val _progress = MutableStateFlow(BackupProgress())
        /** Live backup state for the UI (`collectAsState()`); replaced wholesale on every update. */
        val progress: StateFlow<BackupProgress> = _progress.asStateFlow()

        /** Cached snapshot from Analyze — the service uses it instead of re-scanning. */
        @Volatile var pendingSnapshot: SnapshotResult? = null

        fun start(context: Context, groupId: Long, driveUri: Uri) {
            if (_progress.value.isRunning) return  // one backup at a time
            // Publish synchronously so the Progress screen never shows the previous run's DONE state.
            _progress.value = BackupProgress(phase = BackupPhase.SCANNING)
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GROUP_ID, groupId)
                putExtra(EXTRA_DRIVE_URI, driveUri.toString())
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start backup service", e)
                pendingSnapshot = null
                _progress.value = BackupProgress(
                    phase = BackupPhase.DONE,
                    outcome = BackupOutcome.ERROR,
                    errorMessage = "Android did not let the backup start. Open SackUp and try again.",
                    endTimeMillis = System.currentTimeMillis()
                )
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, BackupService::class.java).apply { action = ACTION_CANCEL }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not deliver cancel to service", e)
            }
        }
    }

    // Last resort: never let a stray exception take the whole process down.
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Unhandled error in backup service", e)
        _progress.update {
            it.copy(
                phase = BackupPhase.DONE,
                outcome = BackupOutcome.ERROR,
                errorMessage = plainRunError(e),
                endTimeMillis = System.currentTimeMillis(),
                bytesPerSecond = 0L
            )
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private var backupJob: Job? = null
    private var speedJob: Job? = null
    private lateinit var repo: BackupRepository
    private lateinit var notificationManager: NotificationManager
    private var sessionId = ""
    @Volatile private var cancelled = false
    @Volatile private var cancelReason = ""
    private val bytesCounter = AtomicLong(0)
    @Volatile private var progressText = ""
    @Volatile private var lastNotificationMillis = 0L

    override fun onCreate() {
        super.onCreate()
        repo = BackupRepository(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (backupJob?.isActive == true) {
                    Log.w(TAG, "Backup already running; ignoring second start request")
                    return START_NOT_STICKY
                }
                // Must go foreground promptly after startForegroundService(), even on the error path.
                startForeground(NOTIFICATION_ID, buildProgressNotification("Preparing backup..."))

                val groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1)
                val driveUriString = intent.getStringExtra(EXTRA_DRIVE_URI)
                if (groupId == -1L || driveUriString.isNullOrBlank()) {
                    pendingSnapshot = null
                    _progress.value = BackupProgress(
                        phase = BackupPhase.DONE,
                        outcome = BackupOutcome.ERROR,
                        errorMessage = "No backup group or drive was selected",
                        endTimeMillis = System.currentTimeMillis()
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val driveUri = Uri.parse(driveUriString)
                val cachedSnapshot = pendingSnapshot
                pendingSnapshot = null

                cancelled = false
                cancelReason = ""
                bytesCounter.set(0)
                lastNotificationMillis = 0L
                progressText = "Preparing backup..."
                sessionId = UUID.randomUUID().toString().take(8)
                _progress.value = BackupProgress(phase = BackupPhase.SCANNING)

                backupJob = scope.launch { runBackup(groupId, driveUri, cachedSnapshot) }
            }
            ACTION_CANCEL -> {
                if (backupJob?.isActive != true) {
                    // Stale notification action: nothing to cancel, do not linger.
                    stopSelf()
                    return START_NOT_STICKY
                }
                requestCancel("Cancel requested by user")
            }
            else -> {
                if (backupJob?.isActive != true) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // API 34: the system ends a dataSync foreground service after its time budget.
    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    // API 35 variant.
    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout()
    }

    private fun handleTimeout() {
        if (backupJob?.isActive != true) {
            stopSelf()
            return
        }
        requestCancel(TIMEOUT_MESSAGE)
    }

    private fun requestCancel(reason: String) {
        if (cancelled) return
        cancelled = true
        cancelReason = reason
        _progress.update { it.copy(statusText = "Stopping...") }
        postProgressNotification("Stopping...", force = true)
        backupJob?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Run ───────────────────────────────────────────────────────────────

    private suspend fun runBackup(groupId: Long, driveUri: Uri, cachedSnapshot: SnapshotResult?) {
        var outcome = BackupOutcome.ERROR
        var errorMessage = "Something went wrong"
        try {
            val (o, msg) = runBackupInner(groupId, driveUri, cachedSnapshot)
            outcome = o
            errorMessage = msg
        } catch (_: CancellationException) {
            outcome = BackupOutcome.CANCELLED
            errorMessage = ""
        } catch (e: Throwable) {
            Log.e(TAG, "Backup failed", e)
            outcome = BackupOutcome.ERROR
            errorMessage = plainRunError(e)
            log("ERROR", _progress.value.groupName, errorMessage)
        } finally {
            withContext(NonCancellable) { finishBackup(outcome, errorMessage) }
        }
    }

    /** Returns the outcome and, for ERROR, a plain-language message. */
    private suspend fun runBackupInner(
        groupId: Long,
        driveUri: Uri,
        cachedSnapshot: SnapshotResult?
    ): Pair<BackupOutcome, String> {
        val group = repo.getGroup(groupId)
        if (group == null) {
            log("ERROR", "", "Backup group not found")
            return BackupOutcome.ERROR to "This backup group no longer exists"
        }

        _progress.update { it.copy(groupName = group.name) }
        log("INFO", group.name, "Starting backup for ${group.name}")

        val phoneFolders = group.folderList()
        if (phoneFolders.isEmpty()) {
            log("ERROR", group.name, "No phone folders configured for this group")
            return BackupOutcome.ERROR to "This backup group has no phone folders set up"
        }

        val engine = BackupEngine(contentResolver)

        // ── Phase 1: Snapshot & Diff ──────────────────────────────────────
        val snapshot: SnapshotResult
        if (cachedSnapshot != null) {
            log("INFO", group.name, "Using cached scan from Analyze")
            snapshot = cachedSnapshot
        } else {
            setPhase(BackupPhase.SCANNING, "Scanning phone and drive...")
            log("INFO", group.name, "Phase 1: Scanning phone and drive...")

            val syncTimestamp = System.currentTimeMillis() / 1000  // freeze point

            try {
                snapshot = engine.snapshot(phoneFolders, driveUri, syncTimestamp, isCancelled = { cancelled }) { phase, detail, count ->
                    val text = if (detail.isNotEmpty()) "$phase: $detail ($count)" else "$phase ($count)"
                    _progress.update { it.copy(statusText = text) }
                    postProgressNotification(text)
                }
            } catch (_: BackupEngine.ScanCancelledException) {
                log("INFO", group.name, "Scan cancelled")
                return BackupOutcome.CANCELLED to ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                val msg = "Could not scan the drive: ${plainRunError(e)}"
                log("ERROR", group.name, msg)
                return BackupOutcome.ERROR to msg
            }
        }

        if (cancelled) return BackupOutcome.CANCELLED to ""

        _progress.update {
            it.copy(
                totalFiles = snapshot.filesToCopy.size,
                totalBytes = snapshot.totalBytesToCopy,
                skippedFiles = snapshot.alreadyOnDrive
            )
        }

        log("INFO", group.name,
            "${snapshot.filesToCopy.size} files to copy (${formatBytes(snapshot.totalBytesToCopy)}), " +
            "${snapshot.alreadyOnDrive} already on drive")
        if (snapshot.partialsOnDrive.isNotEmpty()) {
            log("INFO", group.name, "${snapshot.partialsOnDrive.size} incomplete copies from an earlier run will be replaced")
        }

        // ── Phase 2: Parallel Copy ────────────────────────────────────────
        var copyResult = CopyResult(0, 0L, 0, emptyList())
        if (snapshot.filesToCopy.isEmpty()) {
            log("INFO", group.name, "Everything is already backed up")
        } else {
            val startTime = System.currentTimeMillis()
            bytesCounter.set(0)
            _progress.update { it.copy(startTimeMillis = startTime) }
            setPhase(BackupPhase.COPYING, "Copying ${snapshot.filesToCopy.size} files...")
            log("INFO", group.name,
                "Phase 2: Copying with ${BackupEngine.WORKER_COUNT} workers, ${BackupEngine.BUFFER_SIZE / 1024 / 1024}MB buffers...")

            startSpeedSampler()
            try {
                copyResult = engine.parallelCopy(
                    snapshot = snapshot,
                    treeUri = driveUri,
                    isCancelled = { cancelled },
                    bytesCopied = bytesCounter,
                    onLog = { level, message -> log(level, group.name, message) },
                    onFileDone = { completed, failed, fileName ->
                        val total = snapshot.filesToCopy.size
                        _progress.update {
                            it.copy(
                                completedFiles = completed,
                                failedFiles = failed,
                                statusText = fileName,
                                copiedBytes = bytesCounter.get()
                            )
                        }
                        postProgressNotification("Copying: $fileName ($completed/$total)")
                    }
                )
            } catch (_: CancellationException) {
                copyResult = copyResult.copy(cancelled = true)
            } finally {
                stopSpeedSampler()
            }

            _progress.update {
                it.copy(
                    completedFiles = copyResult.copiedCount + copyResult.failedCount,
                    failedFiles = copyResult.failedCount,
                    failedFilesList = copyResult.failedFiles,
                    copiedBytes = copyResult.copiedSize,
                    bytesPerSecond = 0L
                )
            }

            copyResult.failedFiles.take(MAX_FAILURES_LOGGED).forEach { log("WARN", group.name, "Failed: $it") }
            if (copyResult.failedFiles.size > MAX_FAILURES_LOGGED) {
                log("WARN", group.name, "...and ${copyResult.failedFiles.size - MAX_FAILURES_LOGGED} more failures")
            }
            copyResult.abortReason?.let { log("ERROR", group.name, "Stopped copying: $it") }

            val elapsed = System.currentTimeMillis() - startTime
            val summary = buildString {
                append(
                    when {
                        copyResult.cancelled -> "${group.name} backup stopped. "
                        copyResult.abortReason != null -> "${group.name} backup failed. "
                        else -> "${group.name} backup complete. "
                    }
                )
                append("${copyResult.copiedCount} files copied (${formatBytes(copyResult.copiedSize)})")
                if (snapshot.alreadyOnDrive > 0) append(", ${snapshot.alreadyOnDrive} already on drive")
                if (copyResult.failedCount > 0) append(", ${copyResult.failedCount} failed")
                if (elapsed > 0 && copyResult.copiedSize > 0) {
                    append(". Speed: ${formatBytes(copyResult.copiedSize * 1000 / elapsed)}/s")
                }
                append(".")
            }
            log("INFO", group.name, summary)
        }

        // ── Phase 3: Manifest rebuild (always, even after cancel) ────────
        withContext(NonCancellable) {
            setPhase(BackupPhase.FINISHING, "Updating records...")
            log("INFO", group.name, "Phase 3: Rebuilding manifest...")
            val entries = buildManifestEntries(group.id, snapshot, copyResult.copiedFileKeys)
            repo.rebuildManifest(group.id, entries)
            repo.updateGroup(
                group.copy(
                    lastBackupTime = System.currentTimeMillis(),
                    lastBackupFileCount = copyResult.copiedCount,
                    lastBackupBytes = copyResult.copiedSize
                )
            )
            log("INFO", group.name, "Manifest updated: ${entries.size} files recorded on the drive")
            try { repo.pruneOldLogs() } catch (e: Exception) { Log.w(TAG, "pruneOldLogs failed", e) }
        }

        return when {
            copyResult.cancelled || cancelled -> BackupOutcome.CANCELLED to ""
            copyResult.abortReason != null -> BackupOutcome.ERROR to copyResult.abortReason
            copyResult.failedCount > 0 -> BackupOutcome.PARTIAL to ""
            else -> BackupOutcome.SUCCESS to ""
        }
    }

    /** Publish the end state, swap the ongoing notification for a result one, and stop. */
    private suspend fun finishBackup(outcome: BackupOutcome, errorMessage: String) {
        stopSpeedSampler()
        val final = _progress.updateAndGet {
            it.copy(
                phase = BackupPhase.DONE,
                outcome = outcome,
                errorMessage = if (outcome == BackupOutcome.ERROR) errorMessage else "",
                statusText = "",
                bytesPerSecond = 0L,
                endTimeMillis = System.currentTimeMillis()
            )
        }
        if (outcome == BackupOutcome.CANCELLED) {
            val reason = cancelReason.ifEmpty { "Backup stopped" }
            log(if (reason == TIMEOUT_MESSAGE) "WARN" else "INFO", final.groupName, reason)
        }

        val group = final.groupName.ifEmpty { "Backup" }
        val text = when (outcome) {
            BackupOutcome.SUCCESS ->
                if (final.copiedCount > 0) "$group: ${final.copiedCount} files backed up (${formatBytes(final.copiedBytes)})"
                else "$group: everything is already backed up"
            BackupOutcome.PARTIAL ->
                "$group: ${final.copiedCount} files backed up, ${final.failedFiles} failed"
            BackupOutcome.CANCELLED ->
                if (final.copiedCount > 0) "Backup stopped — ${final.copiedCount} files were saved" else "Backup stopped"
            BackupOutcome.ERROR, BackupOutcome.NONE ->
                "Backup failed: ${errorMessage.ifEmpty { "something went wrong" }}"
        }

        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { Log.w(TAG, "stopForeground failed", e) }
        try {
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.notify(RESULT_NOTIFICATION_ID, buildResultNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Could not post result notification", e)
        }
        stopSelf()
    }

    // ── Progress helpers ──────────────────────────────────────────────────

    private fun setPhase(phase: BackupPhase, text: String) {
        _progress.update { it.copy(phase = phase, statusText = text) }
        postProgressNotification(text, force = true)
    }

    /** Samples the byte counter once a second: byte progress + rolling speed (~5s window). */
    private fun startSpeedSampler() {
        speedJob?.cancel()
        speedJob = scope.launch {
            val samples = ArrayDeque<Pair<Long, Long>>()  // (timeMillis, bytes)
            samples.addLast(System.currentTimeMillis() to bytesCounter.get())
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val bytes = bytesCounter.get()
                samples.addLast(now to bytes)
                while (samples.size > 6) samples.removeFirst()
                val (t0, b0) = samples.first()
                val speed = if (now > t0) ((bytes - b0) * 1000 / (now - t0)).coerceAtLeast(0L) else 0L
                _progress.update { it.copy(copiedBytes = bytes, bytesPerSecond = speed) }
                postProgressNotification(progressText)
            }
        }
    }

    private fun stopSpeedSampler() {
        speedJob?.cancel()
        speedJob = null
    }

    private suspend fun log(level: String, groupName: String, message: String) {
        withContext(NonCancellable) {
            try {
                repo.insertLog(LogEntry(sessionId = sessionId, groupName = groupName, level = level, message = message))
            } catch (e: Exception) {
                Log.w(TAG, "Could not write log entry: $message", e)
            }
        }
    }

    private fun plainRunError(e: Throwable): String = when (e) {
        is SecurityException -> "SackUp no longer has permission to use the drive. Choose the drive again."
        is FileNotFoundException -> "The drive could not be found. Is it still plugged in?"
        else -> {
            val friendly = friendlyCopyError(e)
            if (friendly == DRIVE_FULL_MESSAGE || friendly == DRIVE_READ_ONLY_MESSAGE || friendly == DRIVE_DISCONNECTED_MESSAGE) friendly
            else "Something went wrong: $friendly"
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backup progress", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows backup progress" }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL_ID, "Backup results", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Tells you how a backup ended" }
        )
    }

    private fun openProgressIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_PROGRESS, true)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildProgressNotification(text: String): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BackupService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val p = _progress.value
        val indeterminate = p.phase != BackupPhase.COPYING || p.totalFiles == 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SackUp Backup")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openProgressIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, p.percent, indeterminate)
            .addAction(R.drawable.ic_notification, "Cancel", cancelIntent)
            .build()
    }

    private fun buildResultNotification(text: String): Notification =
        NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle("SackUp Backup")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openProgressIntent())
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

    /** At most one progress notification per second, unless [force] (phase changes, final state). */
    private fun postProgressNotification(text: String, force: Boolean = false) {
        progressText = text
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationMillis < NOTIFICATION_MIN_INTERVAL_MS) return
        lastNotificationMillis = now
        try {
            notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Could not update notification", e)
        }
    }
}
