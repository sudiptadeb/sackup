package com.sackup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sackup.MainActivity
import com.sackup.R
import com.sackup.data.BackupGroup
import com.sackup.data.BackupRepository
import com.sackup.data.LogEntry
import com.sackup.data.folderList
import com.sackup.util.DriveVolumes
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

// ── Run summary (pure, JVM-testable) ─────────────────────────────────────────

/** How one group of a queued run ended. */
internal enum class GroupOutcome {
    SUCCESS,      // every file copied (or nothing needed copying)
    PARTIAL,      // finished, but some files failed
    SKIPPED,      // group missing or without phone folders — nothing was attempted
    FAILED,       // could not scan / unexpected error, for a reason other than a vanished drive
    CANCELLED,    // user or system cancel
    INTERRUPTED   // the drive went away
}

internal data class GroupRunResult(
    val groupId: Long,
    val groupName: String,
    val outcome: GroupOutcome,
    /** Plain-language reason for SKIPPED / FAILED. */
    val message: String = ""
) {
    /** True when the group's copy phase actually completed. */
    val ran: Boolean get() = outcome == GroupOutcome.SUCCESS || outcome == GroupOutcome.PARTIAL
}

internal data class RunSummary(
    val outcome: BackupOutcome,
    /** Set for ERROR and INTERRUPTED, empty otherwise. */
    val errorMessage: String,
    /** Groups whose copy phase completed (SUCCESS or PARTIAL). */
    val groupsDone: Int
)

/**
 * Combine per-group results into the outcome of the whole run:
 *  - INTERRUPTED if the drive went away at any point (the run can be resumed);
 *  - CANCELLED if the user/system stopped it;
 *  - ERROR if no group got as far as copying (the first real attempt failed, or every group was skipped);
 *  - PARTIAL if any file failed or a later group could not be scanned;
 *  - SUCCESS otherwise.
 */
internal fun summarizeRun(results: List<GroupRunResult>): RunSummary {
    val done = results.count { it.ran }
    val outcome = when {
        results.any { it.outcome == GroupOutcome.INTERRUPTED } -> BackupOutcome.INTERRUPTED
        results.any { it.outcome == GroupOutcome.CANCELLED } -> BackupOutcome.CANCELLED
        done == 0 -> BackupOutcome.ERROR
        results.any { it.outcome == GroupOutcome.PARTIAL || it.outcome == GroupOutcome.FAILED } -> BackupOutcome.PARTIAL
        else -> BackupOutcome.SUCCESS
    }
    val message = when (outcome) {
        BackupOutcome.INTERRUPTED -> DRIVE_UNPLUGGED_MESSAGE
        BackupOutcome.ERROR ->
            results.firstOrNull { it.outcome == GroupOutcome.FAILED && it.message.isNotEmpty() }?.message
                ?: results.firstOrNull { it.message.isNotEmpty() }?.message
                ?: "Nothing could be backed up"
        else -> ""
    }
    return RunSummary(outcome, message, done)
}

internal fun fileCount(n: Int): String = if (n == 1) "1 file" else "$n files"

/** Text of the one-shot result notification. [groupsDone] > 1 switches to the whole-run wording. */
internal fun resultNotificationText(
    outcome: BackupOutcome,
    groupsDone: Int,
    lastGroupName: String,
    copiedFiles: Int,
    copiedBytes: Long,
    failedFiles: Int,
    errorMessage: String
): String {
    val subject = if (groupsDone > 1) "$groupsDone backups done" else lastGroupName.ifEmpty { "Backup" }
    val copied = "${fileCount(copiedFiles)} backed up (${formatBytes(copiedBytes)})"
    return when (outcome) {
        BackupOutcome.SUCCESS ->
            if (copiedFiles > 0) "$subject: $copied" else "$subject: everything is already backed up"
        BackupOutcome.PARTIAL ->
            "$subject: $copied; " +
                if (failedFiles > 0) "${fileCount(failedFiles)} failed" else "some files could not be backed up"
        BackupOutcome.CANCELLED ->
            if (copiedFiles > 0) "Backup stopped — ${fileCount(copiedFiles)} were saved" else "Backup stopped"
        BackupOutcome.INTERRUPTED ->
            if (copiedFiles > 0) "USB drive unplugged — ${fileCount(copiedFiles)} were saved. Plug it back in to continue."
            else "USB drive unplugged. Plug it back in to continue."
        BackupOutcome.ERROR, BackupOutcome.NONE ->
            "Backup failed: ${errorMessage.ifEmpty { "something went wrong" }}"
    }
}

/** Progress notification line; prefixed with the group's position when several groups are queued. */
internal fun progressNotificationText(text: String, groupName: String, groupIndex: Int, groupCount: Int): String =
    if (groupCount > 1 && groupName.isNotEmpty()) "$groupName (${groupIndex + 1} of $groupCount): $text" else text

// ── Service ──────────────────────────────────────────────────────────────────

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
        const val EXTRA_GROUP_IDS = "group_ids"
        const val EXTRA_DRIVE_URI = "drive_uri"
        /** Boolean extra on the notification's content Intent: MainActivity should show the progress screen. */
        const val EXTRA_OPEN_PROGRESS = "open_progress"

        const val TIMEOUT_MESSAGE = "Android stopped the backup after its time limit. Run it again to continue."
        private const val NOTIFICATION_MIN_INTERVAL_MS = 1000L
        private const val MAX_FAILURES_LOGGED = 200

        private val _progress = MutableStateFlow(BackupProgress())
        /** Live backup state for the UI (`collectAsState()`); replaced wholesale on every update. */
        val progress: StateFlow<BackupProgress> = _progress.asStateFlow()

        /**
         * Cached snapshot from Analyze — used instead of re-scanning by a single-group
         * [start] whose group has the same phone folders. Queue starts discard it.
         */
        @Volatile var pendingSnapshot: SnapshotResult? = null

        /** Back up one group (a queue of one). Consumes [pendingSnapshot] if it fits the group. */
        fun start(context: Context, groupId: Long, driveUri: Uri) {
            startQueue(context, listOf(groupId), driveUri)
        }

        /** Back up [groupIds] one after the other in one session. Empty list is a no-op. */
        fun start(context: Context, groupIds: List<Long>, driveUri: Uri) {
            if (groupIds.isEmpty()) return
            pendingSnapshot = null  // a cached scan only ever belongs to a single-group start
            startQueue(context, groupIds, driveUri)
        }

        private fun startQueue(context: Context, groupIds: List<Long>, driveUri: Uri) {
            if (_progress.value.isRunning) return  // one backup at a time
            // Publish synchronously so the Progress screen never shows the previous run's DONE state.
            _progress.value = queuedProgress(groupIds)
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GROUP_IDS, groupIds.toLongArray())
                putExtra(EXTRA_DRIVE_URI, driveUri.toString())
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start backup service", e)
                pendingSnapshot = null
                _progress.value = queuedProgress(groupIds).copy(
                    phase = BackupPhase.DONE,
                    outcome = BackupOutcome.ERROR,
                    errorMessage = "Android did not let the backup start. Open SackUp and try again.",
                    endTimeMillis = System.currentTimeMillis()
                )
            }
        }

        private fun queuedProgress(groupIds: List<Long>) = BackupProgress(
            phase = BackupPhase.SCANNING,
            groupIndex = 0,
            groupCount = groupIds.size.coerceAtLeast(1),
            queuedGroupIds = groupIds
        )

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
                statusText = "",
                endTimeMillis = System.currentTimeMillis(),
                bytesPerSecond = 0L
            )
        }
        driveUri = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private var backupJob: Job? = null
    private var speedJob: Job? = null
    private lateinit var repo: BackupRepository
    private lateinit var notificationManager: NotificationManager
    private var sessionId = ""
    @Volatile private var driveUri: Uri? = null
    @Volatile private var cancelled = false
    @Volatile private var cancelReason = ""
    /** True once the run was stopped because the drive went away (unmount broadcast, breaker, scan error). */
    @Volatile private var interruptedByDisconnect = false
    private val bytesCounter = AtomicLong(0)
    /** Files/bytes copied by the groups that finished before the current one. */
    @Volatile private var runFilesBase = 0
    @Volatile private var runBytesBase = 0L
    @Volatile private var progressText = ""
    @Volatile private var lastNotificationMillis = 0L

    /** The system tells us the moment a volume goes away; no need to wait for I/O errors. */
    private val driveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val uri = driveUri ?: return
            if (backupJob?.isActive != true) return
            if (!DriveVolumes.eventConcernsDrive(intent.data, uri)) return
            Log.w(TAG, "Drive went away: ${intent.action} ${intent.data}")
            onDriveDisconnected()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = BackupRepository(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannels()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        // System broadcasts must be received with RECEIVER_EXPORTED on API 33+.
        ContextCompat.registerReceiver(this, driveReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
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

                val groupIds = intent.getLongArrayExtra(EXTRA_GROUP_IDS)?.toList()
                    ?: listOf(intent.getLongExtra(EXTRA_GROUP_ID, -1L)).filter { it != -1L }
                val driveUriString = intent.getStringExtra(EXTRA_DRIVE_URI)
                if (groupIds.isEmpty() || driveUriString.isNullOrBlank()) {
                    pendingSnapshot = null
                    _progress.value = queuedProgress(groupIds).copy(
                        phase = BackupPhase.DONE,
                        outcome = BackupOutcome.ERROR,
                        errorMessage = "No backup group or drive was selected",
                        endTimeMillis = System.currentTimeMillis()
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val uri = Uri.parse(driveUriString)
                val cachedSnapshot = if (groupIds.size == 1) pendingSnapshot else null
                pendingSnapshot = null

                driveUri = uri
                cancelled = false
                cancelReason = ""
                interruptedByDisconnect = false
                bytesCounter.set(0)
                runFilesBase = 0
                runBytesBase = 0L
                lastNotificationMillis = 0L
                progressText = "Preparing backup..."
                sessionId = UUID.randomUUID().toString().take(8)
                _progress.value = queuedProgress(groupIds)

                backupJob = scope.launch { runQueue(groupIds, uri, cachedSnapshot) }
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

    private fun requestCancel(reason: String) = stopRun(reason, byDisconnect = false)

    private fun onDriveDisconnected() = stopRun(DRIVE_UNPLUGGED_MESSAGE, byDisconnect = true)

    /** Stop the running job. The first caller wins: a later unplug does not turn a user cancel into a resume. */
    private fun stopRun(reason: String, byDisconnect: Boolean) {
        if (cancelled) return
        interruptedByDisconnect = byDisconnect
        cancelled = true
        cancelReason = reason
        _progress.update { it.copy(statusText = "Stopping...") }
        postProgressNotification("Stopping...", force = true)
        backupJob?.cancel()
    }

    override fun onDestroy() {
        try { unregisterReceiver(driveReceiver) } catch (e: Exception) { Log.w(TAG, "unregisterReceiver failed", e) }
        scope.cancel()
        super.onDestroy()
    }

    // ── Run ───────────────────────────────────────────────────────────────

    /** Run every group in [groupIds] in order under one session id, then publish the combined outcome. */
    private suspend fun runQueue(groupIds: List<Long>, driveUri: Uri, cachedSnapshot: SnapshotResult?) {
        val results = mutableListOf<GroupRunResult>()
        var index = 0
        try {
            for ((i, groupId) in groupIds.withIndex()) {
                index = i
                if (cancelled) {
                    // Stopped in the gap between two groups: nothing of this group was touched.
                    results += GroupRunResult(groupId, _progress.value.groupName, stoppedOutcome())
                    break
                }
                val result = runGroup(groupId, i, groupIds.size, driveUri, cachedSnapshot.takeIf { i == 0 })
                results += result
                when (result.outcome) {
                    GroupOutcome.SUCCESS, GroupOutcome.PARTIAL -> InterruptedBackups.remove(this, listOf(groupId))
                    GroupOutcome.CANCELLED, GroupOutcome.INTERRUPTED -> break
                    // First real attempt could not even scan: the drive is unusable, do not grind through the rest.
                    GroupOutcome.FAILED -> if (results.none { it.ran }) break
                    GroupOutcome.SKIPPED -> {}
                }
            }
        } catch (_: CancellationException) {
            results += GroupRunResult(groupIds[index], _progress.value.groupName, stoppedOutcome())
        } catch (e: Throwable) {
            Log.e(TAG, "Backup failed", e)
            val name = _progress.value.groupName
            val msg = plainRunError(e)
            log("ERROR", name, msg)
            results += GroupRunResult(groupIds[index], name, GroupOutcome.FAILED, msg)
        } finally {
            withContext(NonCancellable) {
                val summary = summarizeRun(results)
                if (summary.outcome == BackupOutcome.INTERRUPTED) {
                    // The current group plus everything not yet started still needs a run.
                    val remaining = groupIds.drop(index)
                    InterruptedBackups.save(this@BackupService, (InterruptedBackups.load(this@BackupService) + remaining).distinct())
                }
                finishBackup(summary)
            }
        }
    }

    private fun stoppedOutcome(): GroupOutcome =
        if (interruptedByDisconnect) GroupOutcome.INTERRUPTED else GroupOutcome.CANCELLED

    /** Back up one group; never throws for expected failures (they come back as the result). */
    private suspend fun runGroup(
        groupId: Long,
        index: Int,
        count: Int,
        driveUri: Uri,
        cachedSnapshot: SnapshotResult?
    ): GroupRunResult {
        val group = repo.getGroup(groupId)
        if (group == null) {
            log("WARN", "", "Backup group $groupId no longer exists; skipping it")
            return GroupRunResult(groupId, "", GroupOutcome.SKIPPED, "This backup group no longer exists")
        }

        resetGroupProgress(group, index)
        if (count > 1) log("INFO", group.name, "Backing up group ${index + 1} of $count: ${group.name}")
        log("INFO", group.name, "Starting backup for ${group.name}")

        val phoneFolders = group.folderList()
        if (phoneFolders.isEmpty()) {
            log("WARN", group.name, "No phone folders configured for this group; skipping it")
            return GroupRunResult(groupId, group.name, GroupOutcome.SKIPPED, "This backup group has no phone folders set up")
        }

        val engine = BackupEngine(contentResolver)

        // ── Phase 1: Snapshot & Diff ──────────────────────────────────────
        val snapshot: SnapshotResult
        val usable = cachedSnapshot?.takeIf { it.perFolder.map { f -> f.phoneFolder }.toSet() == phoneFolders.toSet() }
        if (usable != null) {
            log("INFO", group.name, "Using cached scan from Analyze")
            snapshot = usable
        } else {
            setPhase(BackupPhase.SCANNING, "Scanning phone and drive...")
            log("INFO", group.name, "Phase 1: Scanning phone and drive...")

            val syncTimestamp = System.currentTimeMillis() / 1000  // freeze point

            try {
                snapshot = engine.snapshot(phoneFolders, driveUri, syncTimestamp, isCancelled = { cancelled }) { phase, detail, c ->
                    val text = if (detail.isNotEmpty()) "$phase: $detail ($c)" else "$phase ($c)"
                    _progress.update { it.copy(statusText = text) }
                    postProgressNotification(text)
                }
            } catch (_: BackupEngine.ScanCancelledException) {
                log("INFO", group.name, "Scan stopped")
                return GroupRunResult(groupId, group.name, stoppedOutcome())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                if (interruptedByDisconnect || isDriveVanishedError(e)) {
                    interruptedByDisconnect = true
                    log("ERROR", group.name, "Could not scan the drive: it seems to have been unplugged")
                    return GroupRunResult(groupId, group.name, GroupOutcome.INTERRUPTED)
                }
                val msg = "Could not scan the drive: ${plainRunError(e)}"
                log("ERROR", group.name, msg)
                return GroupRunResult(groupId, group.name, GroupOutcome.FAILED, msg)
            }
        }

        if (cancelled) return GroupRunResult(groupId, group.name, stoppedOutcome())

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
                    driveGone = { interruptedByDisconnect },
                    onFileDone = { completed, failed, fileName ->
                        val total = snapshot.filesToCopy.size
                        _progress.update {
                            it.copy(
                                completedFiles = completed,
                                failedFiles = failed,
                                statusText = fileName,
                                copiedBytes = bytesCounter.get(),
                                runCopiedFiles = runFilesBase + (completed - failed).coerceAtLeast(0)
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

            if (copyResult.abortReason == DRIVE_DISCONNECTED_MESSAGE) interruptedByDisconnect = true

            runFilesBase += copyResult.copiedCount
            runBytesBase += copyResult.copiedSize
            _progress.update {
                it.copy(
                    completedFiles = copyResult.copiedCount + copyResult.failedCount,
                    failedFiles = copyResult.failedCount,
                    failedFilesList = copyResult.failedFiles,
                    copiedBytes = copyResult.copiedSize,
                    bytesPerSecond = 0L,
                    runCopiedFiles = runFilesBase,
                    runCopiedBytes = runBytesBase,
                    runFailedFiles = it.runFailedFiles + copyResult.failedCount
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
                        interruptedByDisconnect -> "${group.name} backup interrupted. "
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

        // ── Phase 3: Manifest rebuild (always — even after cancel or unplug, from what was verified) ──
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

        val outcome = when {
            interruptedByDisconnect -> GroupOutcome.INTERRUPTED
            copyResult.cancelled || cancelled -> GroupOutcome.CANCELLED
            copyResult.failedCount > 0 || copyResult.abortReason != null -> GroupOutcome.PARTIAL
            else -> GroupOutcome.SUCCESS
        }
        return GroupRunResult(groupId, group.name, outcome)
    }

    /** Fresh per-group counters; the queue position and run-wide totals carry over. */
    private fun resetGroupProgress(group: BackupGroup, index: Int) {
        bytesCounter.set(0)
        _progress.update {
            it.copy(
                phase = BackupPhase.SCANNING,
                groupName = group.name,
                groupIndex = index,
                statusText = "",
                totalFiles = 0,
                completedFiles = 0,
                skippedFiles = 0,
                failedFiles = 0,
                totalBytes = 0L,
                copiedBytes = 0L,
                bytesPerSecond = 0L,
                startTimeMillis = 0L,
                failedFilesList = emptyList()
            )
        }
    }

    /** Publish the end state, swap the ongoing notification for a result one, and stop. */
    private suspend fun finishBackup(summary: RunSummary) {
        stopSpeedSampler()
        val outcome = summary.outcome
        val final = _progress.updateAndGet {
            it.copy(
                phase = BackupPhase.DONE,
                outcome = outcome,
                errorMessage = if (outcome == BackupOutcome.ERROR || outcome == BackupOutcome.INTERRUPTED) summary.errorMessage else "",
                statusText = "",
                bytesPerSecond = 0L,
                endTimeMillis = System.currentTimeMillis()
            )
        }
        when (outcome) {
            BackupOutcome.CANCELLED -> {
                val reason = cancelReason.ifEmpty { "Backup stopped" }
                log(if (reason == TIMEOUT_MESSAGE) "WARN" else "INFO", final.groupName, reason)
            }
            BackupOutcome.INTERRUPTED -> log("WARN", final.groupName, DRIVE_UNPLUGGED_MESSAGE)
            else -> {}
        }

        val text = resultNotificationText(
            outcome = outcome,
            groupsDone = summary.groupsDone,
            lastGroupName = final.groupName,
            copiedFiles = final.runCopiedFiles,
            copiedBytes = final.runCopiedBytes,
            failedFiles = final.runFailedFiles,
            errorMessage = summary.errorMessage
        )

        driveUri = null
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
                _progress.update { it.copy(copiedBytes = bytes, bytesPerSecond = speed, runCopiedBytes = runBytesBase + bytes) }
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
            .setContentText(progressNotificationText(text, p.groupName, p.groupIndex, p.groupCount))
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
