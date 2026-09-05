package com.sackup.data

import androidx.room.*

@Entity(tableName = "backup_groups")
data class BackupGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                    // e.g. "Camera", "Downloads", "WhatsApp"
    val phoneFolders: String,            // JSON array of relative paths e.g. ["DCIM","Pictures"]
    val lastBackupTime: Long = 0,        // epoch millis
    val lastBackupFileCount: Int = 0,
    val lastBackupBytes: Long = 0
)

@Dao
interface BackupGroupDao {
    @Query("SELECT * FROM backup_groups ORDER BY id ASC")
    suspend fun getAll(): List<BackupGroup>

    @Query("SELECT * FROM backup_groups WHERE id = :id")
    suspend fun getById(id: Long): BackupGroup?

    @Insert
    suspend fun insert(group: BackupGroup): Long

    @Update
    suspend fun update(group: BackupGroup)

    @Delete
    suspend fun delete(group: BackupGroup)
}

// ── Phone folder list codec ─────────────────────────────────────────────────
// phoneFolders is stored as a JSON string array. These are the ONLY two places
// that should encode/decode it; callers must not construct Gson themselves.

private val folderGson = com.google.gson.Gson()
private val folderListType = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type

/** Decode the JSON folder list. Malformed or null JSON yields an empty list. */
fun decodeFolders(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        folderGson.fromJson<List<String>?>(json, folderListType)
            ?.filterNotNull()
            ?.map { it.trim('/') }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

/** Encode a folder list to the JSON stored in [BackupGroup.phoneFolders]. */
fun encodeFolders(folders: List<String>): String =
    folderGson.toJson(folders.map { it.trim('/') }.filter { it.isNotBlank() })

/** Convenience accessor for the decoded folder list. */
fun BackupGroup.folderList(): List<String> = decodeFolders(phoneFolders)
