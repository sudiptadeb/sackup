package com.sackup.service

import java.io.FileNotFoundException
import java.io.IOException

// ── Copy error classification ────────────────────────────────────────────────

/** The file was written but what ended up on the drive does not match the source. */
class CopyVerificationException(message: String) : IOException(message)

/** Thrown by the engine when the user (or the system) asked the backup to stop. */
class CancelledException : Exception("Cancelled")

/** Consecutive failures after which the copy phase gives up (the drive is almost certainly gone). */
const val MAX_CONSECUTIVE_FAILURES = 25

const val DRIVE_DISCONNECTED_MESSAGE = "The drive seems to have been disconnected"
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
