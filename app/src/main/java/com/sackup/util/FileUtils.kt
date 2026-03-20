package com.sackup.util

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    val secs = seconds % 60
    if (minutes < 60) return "${minutes}m ${secs}s"
    val hours = minutes / 60
    val mins = minutes % 60
    return "${hours}h ${mins}m"
}
