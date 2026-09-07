package com.sackup.service

/** Which stage the backup service is in. */
enum class BackupPhase { IDLE, SCANNING, COPYING, FINISHING, DONE }

/** How the last backup ended. Only meaningful when [BackupPhase.DONE]. */
enum class BackupOutcome {
    NONE,
    SUCCESS,        // every file copied (or nothing needed copying)
    PARTIAL,        // finished, but some files failed
    CANCELLED,      // user cancelled
    INTERRUPTED,    // the drive was unplugged (or stopped responding) mid-run; can be resumed
    ERROR           // could not run at all (scan failed, drive missing, ...)
}

/**
 * Immutable snapshot of backup progress, published by [BackupService.progress].
 *
 * UI reads this via `collectAsState()`; the service replaces the whole value on every update.
 */
data class BackupProgress(
    val phase: BackupPhase = BackupPhase.IDLE,
    val outcome: BackupOutcome = BackupOutcome.NONE,
    val groupName: String = "",
    /** Current file name while copying, or a scan status line while scanning. */
    val statusText: String = "",
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,     // processed by the copy phase: copied + failed
    val skippedFiles: Int = 0,       // already on the drive before this run
    val failedFiles: Int = 0,
    val totalBytes: Long = 0L,
    val copiedBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val startTimeMillis: Long = 0L,  // start of the copy phase (0 if never reached)
    val endTimeMillis: Long = 0L,
    val failedFilesList: List<String> = emptyList(),
    /** Plain-language reason when [outcome] is [BackupOutcome.ERROR] or [BackupOutcome.INTERRUPTED]. */
    val errorMessage: String = "",
    /** Position of the current group in this run's queue (0-based) and the queue size. */
    val groupIndex: Int = 0,
    val groupCount: Int = 1,
    /** Ids of the groups in this run, in order. */
    val queuedGroupIds: List<Long> = emptyList(),
    /** Files/bytes copied by ALL groups in this run so far (the per-group counters above reset per group). */
    val runCopiedFiles: Int = 0,
    val runCopiedBytes: Long = 0L,
    /** Per-group failures accumulated over the whole run. */
    val runFailedFiles: Int = 0,
) {
    val isRunning: Boolean get() = phase != BackupPhase.IDLE && phase != BackupPhase.DONE
    val isDone: Boolean get() = phase == BackupPhase.DONE
    val copiedCount: Int get() = (completedFiles - failedFiles).coerceAtLeast(0)
    val percent: Int get() = if (totalFiles > 0) (completedFiles * 100 / totalFiles).coerceIn(0, 100) else 0
    val isMultiGroup: Boolean get() = groupCount > 1
    /** True when the run stopped because the drive went away and can be picked up again. */
    val canResume: Boolean get() = isDone && outcome == BackupOutcome.INTERRUPTED
}
