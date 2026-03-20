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

    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns.SIZE
    )

    for (folderPath in phoneFolders) {
        // Files in subfolders (RELATIVE_PATH LIKE "DCIM/%")
        val subSelection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.SIZE} > 0"
        resolver.query(collection, projection, subSelection, arrayOf("$folderPath/%"), null)?.use { cursor ->
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            while (cursor.moveToNext()) {
                totalCount++
                totalSize += cursor.getLong(sizeCol)
            }
        }

        // Files directly in folder (RELATIVE_PATH = "DCIM/")
        val directSelection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.SIZE} > 0"
        resolver.query(collection, projection, directSelection, arrayOf("$folderPath/"), null)?.use { cursor ->
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            while (cursor.moveToNext()) {
                totalCount++
                totalSize += cursor.getLong(sizeCol)
            }
        }
    }

    return FolderStats(totalCount, totalSize)
}
