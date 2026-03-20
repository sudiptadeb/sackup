package com.sackup.util

import android.content.ContentResolver
import android.provider.MediaStore

data class FolderStats(
    val fileCount: Int = 0,
    val totalSize: Long = 0
)

/**
 * Query MediaStore for file count and total size for a list of phone folders.
 * Returns aggregated stats across all folders.
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
        val selection = "(${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?) AND ${MediaStore.Files.FileColumns.SIZE} > 0"
        resolver.query(collection, projection, selection, arrayOf("$folderPath/%", "$folderPath/"), null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                if (!seenIds.add(id)) continue
                totalCount++
                totalSize += cursor.getLong(sizeCol)
            }
        }
    }

    return FolderStats(totalCount, totalSize)
}
