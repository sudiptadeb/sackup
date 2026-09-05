package com.sackup.util

import android.database.Cursor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Helpers that let MediaStore folder queries run on every supported API level.
 *
 * `RELATIVE_PATH` only exists from API 29. On API 26-28 the provider throws
 * "no such column: relative_path", so there we fall back to the legacy `DATA`
 * (absolute path) column and derive the relative path ourselves.
 */
object MediaStoreCompat {

    val hasRelativePath: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Column to project so [relativePathOf] can be answered. */
    val pathColumn: String
        get() = if (hasRelativePath) MediaStore.Files.FileColumns.RELATIVE_PATH
                else @Suppress("DEPRECATION") MediaStore.Files.FileColumns.DATA

    /** Root of primary external storage, with trailing slash, e.g. "/storage/emulated/0/". */
    @Suppress("DEPRECATION")
    val legacyRoot: String
        get() = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"

    /**
     * Selection + args matching every file whose path is inside [folder] (recursively).
     * [folder] is a relative folder like "DCIM" or "WhatsApp/Media" without slashes at either end.
     * Zero-byte rows (pending / placeholder entries) are excluded.
     */
    fun folderSelection(folder: String): Pair<String, Array<String>> {
        val f = folder.trim('/')
        val sizeClause = "${MediaStore.Files.FileColumns.SIZE} > 0"
        return if (hasRelativePath) {
            val col = MediaStore.Files.FileColumns.RELATIVE_PATH
            "($col LIKE ? OR $col = ?) AND $sizeClause" to arrayOf("$f/%", "$f/")
        } else {
            @Suppress("DEPRECATION")
            val col = MediaStore.Files.FileColumns.DATA
            "$col LIKE ? AND $sizeClause" to arrayOf("$legacyRoot$f/%")
        }
    }

    /**
     * Relative directory of the row in MediaStore's `RELATIVE_PATH` convention
     * ("DCIM/Camera/", always trailing slash), or null if it cannot be derived.
     * [pathColIndex] must be the index of [pathColumn] in the cursor's projection.
     */
    fun relativePathOf(cursor: Cursor, pathColIndex: Int): String? {
        val raw = cursor.getString(pathColIndex) ?: return null
        if (hasRelativePath) return raw
        // Legacy: raw is an absolute file path. Strip the storage root and the file name.
        val root = legacyRoot
        if (!raw.startsWith(root)) return null
        val rel = raw.removePrefix(root)
        val dir = rel.substringBeforeLast('/', missingDelimiterValue = "")
        return if (dir.isEmpty()) "" else "$dir/"
    }
}
