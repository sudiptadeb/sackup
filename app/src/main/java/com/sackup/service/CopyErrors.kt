package com.sackup.service

import java.io.FileNotFoundException
import java.io.IOException

// ── Copy error classification ────────────────────────────────────────────────

/** The file was written but what ended up on the drive does not match the source. */
class CopyVerificationException(message: String) : IOException(message)

/** Thrown by the engine when the user (or the system) asked the backup to stop. */
class CancelledException : Exception("Cancelled")

/**
 * Consecutive failures after which the copy phase gives up (the drive is almost certainly gone).
 * Only a backstop: an unplugged drive is normally detected instantly from the unmount broadcast.
 */
const val MAX_CONSECUTIVE_FAILURES = 10

const val DRIVE_DISCONNECTED_MESSAGE = "The drive seems to have been disconnected"
/** Plain sentence shown when a run ends [BackupOutcome.INTERRUPTED]. */
const val DRIVE_UNPLUGGED_MESSAGE = "The USB drive was unplugged. Plug it back in to continue."
const val SOURCE_MISSING_MESSAGE = "This file is no longer on the phone"
const val DRIVE_FULL_MESSAGE = "The drive is full"
const val DRIVE_READ_ONLY_MESSAGE = "The drive is read-only"

/** Marks a [FileNotFoundException] that came from opening the *source* (phone) file. */
class SourceMissingException(cause: FileNotFoundException) :
    FileNotFoundException(cause.message ?: "source file not found") {
    init { initCause(cause) }
}

/** Map an exception raised while copying one file to a sentence a non-technical user can read. */
fun friendlyCopyError(e: Throwable): String {
    if (e is SourceMissingException) return SOURCE_MISSING_MESSAGE
    if (e is CopyVerificationException) return e.message ?: "The copy could not be verified"

    val texts = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }
        .mapNotNull { it.message }
        .toList()
    val joined = texts.joinToString(" | ")

    if (joined.contains("ENOSPC") || joined.contains("No space left", ignoreCase = true)) {
        return DRIVE_FULL_MESSAGE
    }
    if (joined.contains("EROFS") || joined.contains("Read-only", ignoreCase = true)) {
        return DRIVE_READ_ONLY_MESSAGE
    }
    return e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}

/**
 * True when an exception raised while talking to the drive (scanning it, creating folders)
 * looks like the drive vanished rather than a data problem: DocumentsContract throws
 * [FileNotFoundException] / [IllegalArgumentException] for a tree whose volume is gone and
 * [SecurityException] once the persisted permission has been dropped with it.
 * A missing *phone* file ([SourceMissingException]) is never blamed on the drive.
 */
fun isDriveVanishedError(e: Throwable): Boolean {
    var t: Throwable? = e
    while (t != null) {
        if (t is SourceMissingException) return false
        if (t is FileNotFoundException || t is SecurityException || t is IllegalArgumentException) return true
        t = t.cause?.takeIf { c -> c !== t }
    }
    return false
}
