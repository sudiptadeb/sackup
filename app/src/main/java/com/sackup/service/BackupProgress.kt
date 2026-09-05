package com.sackup.service

/** Which stage the backup service is in. */
enum class BackupPhase { IDLE, SCANNING, COPYING, FINISHING, DONE }

/** How the last backup ended. Only meaningful when [BackupPhase.DONE]. */
enum class BackupOutcome {
    NONE,
    SUCCESS,        // every file copied (or nothing needed copying)
    PARTIAL,        // finished, but some files failed
    CANCELLED,      // user cancelled
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
    /** Plain-language reason when [outcome] is [BackupOutcome.ERROR]. */
    val errorMessage: String = "",
) {
    val isRunning: Boolean get() = phase != BackupPhase.IDLE && phase != BackupPhase.DONE
    val isDone: Boolean get() = phase == BackupPhase.DONE
    val copiedCount: Int get() = (completedFiles - failedFiles).coerceAtLeast(0)
    val percent: Int get() = if (totalFiles > 0) (completedFiles * 100 / totalFiles).coerceIn(0, 100) else 0
}
