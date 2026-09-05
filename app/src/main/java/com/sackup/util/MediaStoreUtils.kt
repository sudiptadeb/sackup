package com.sackup.util

import android.content.ContentResolver
import android.provider.MediaStore
import android.util.Log

data class FolderStats(
    val fileCount: Int = 0,
    val totalSize: Long = 0
)

/**
 * Query MediaStore for file count and total size for a list of phone folders.
 * Returns aggregated stats across all folders.
 *
 * Uses [MediaStoreCompat] so the query works on API 26-28 (no RELATIVE_PATH column) as well
 * as API 29+. A provider failure for one folder degrades to "no stats for that folder"
 * instead of propagating and crashing the caller.
 */
fun queryFolderStats(resolver: ContentResolver, phoneFolders: List<String>): FolderStats {
    var totalCount = 0
    var totalSize = 0L
    val seenIds = mutableSetOf<Long>()

    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.SIZE
    )

    for (folderPath in phoneFolders) {
        val (selection, args) = MediaStoreCompat.folderSelection(folderPath)
        runCatching {
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    if (!seenIds.add(id)) continue
                    totalCount++
                    totalSize += cursor.getLong(sizeCol)
                }
            }
        }.onFailure { e ->
            Log.w("SackUpStats", "Folder stats query failed for '$folderPath'", e)
        }
    }

    return FolderStats(totalCount, totalSize)
}
