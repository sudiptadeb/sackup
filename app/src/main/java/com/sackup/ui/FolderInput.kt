package com.sackup.ui

/** A well-known phone folder offered as a one-tap choice in Setup. */
data class CommonFolder(val path: String, val label: String)

/** Common folders on Android phones, in the order shown to the user. */
val COMMON_PHONE_FOLDERS: List<CommonFolder> = listOf(
    CommonFolder("DCIM", "Camera photos & videos"),
    CommonFolder("Pictures", "Pictures & screenshots"),
    CommonFolder("Download", "Downloads"),
    CommonFolder("Documents", "Documents"),
    CommonFolder("Music", "Music"),
    CommonFolder("Movies", "Movies"),
    CommonFolder("Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp media"),
)

/**
 * Cleans up a folder path typed by the user.
 *
 * - trims surrounding whitespace and slashes (both `/` and `\`)
 * - collapses repeated separators and strips whitespace around each segment
 * - drops empty, `.` and `..` segments
 * - returns null when nothing usable is left
 *
 * Pure function so it can be unit-tested without Android.
 */
internal fun normalizeFolderInput(raw: String): String? {
    val segments = raw
        .replace('\\', '/')
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
    if (segments.isEmpty()) return null
    return segments.joinToString("/")
}

/**
 * Adds [raw] to [current] if it normalises to something new.
 * Comparison is case-insensitive so "dcim" does not duplicate "DCIM".
 * Returns the new list, or the same list when nothing was added.
 */
internal fun addFolder(current: List<String>, raw: String): List<String> {
    val normalized = normalizeFolderInput(raw) ?: return current
    if (current.any { it.equals(normalized, ignoreCase = true) }) return current
    return current + normalized
}
