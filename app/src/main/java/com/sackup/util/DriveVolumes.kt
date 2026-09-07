package com.sackup.util

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Helpers for matching the saved SAF drive URI against storage mount/unmount broadcasts.
 *
 * A tree URI for a USB drive looks like
 *   content://com.android.externalstorage.documents/tree/1234-5678%3ABackups
 * whose tree document id is "1234-5678:Backups" — the part before ':' is the volume id,
 * and MEDIA_MOUNTED / MEDIA_UNMOUNTED broadcasts carry file:///storage/1234-5678.
 */
object DriveVolumes {

    /** Volume id ("1234-5678", or "primary") of a tree/document URI, or null if it has none. */
    fun volumeIdOf(treeUri: Uri?): String? {
        if (treeUri == null) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        return volumeIdOfDocumentId(docId)
    }

    internal fun volumeIdOfDocumentId(docId: String?): String? {
        val id = docId?.substringBefore(':', missingDelimiterValue = "")?.trim().orEmpty()
        return id.ifEmpty { null }
    }

    /** Volume id from a media broadcast's data URI (file:///storage/1234-5678), or null. */
    fun volumeIdOfMountPath(data: Uri?): String? {
        val path = data?.path ?: return null
        val last = path.trimEnd('/').substringAfterLast('/')
        return last.ifEmpty { null }
    }

    /**
     * True when a mount/unmount event for [mountData] concerns the drive at [treeUri].
     * When either side is unknown we err on the side of "yes" so a genuine unplug is never missed;
     * callers that need certainty should check [volumeIdOf] themselves.
     */
    fun eventConcernsDrive(mountData: Uri?, treeUri: Uri?): Boolean {
        val drive = volumeIdOf(treeUri) ?: return true
        val event = volumeIdOfMountPath(mountData) ?: return true
        return drive.equals(event, ignoreCase = true)
    }
}
