package com.sackup.data

import androidx.room.*

@Entity(
    tableName = "manifest_entries",
    indices = [Index(value = ["groupId", "phoneFolder", "fileName", "fileSize"], unique = true)]
)
data class ManifestEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val fileName: String,
    val fileSize: Long,
    val phoneFolder: String,      // top-level folder e.g. "DCIM"
    val phonePath: String,        // full relative path e.g. "DCIM/Camera/"
    val drivePath: String,        // path on drive e.g. "DCIM/Camera"
    val dateModified: Long,       // original file modification timestamp (seconds)
    val backupSuccess: Boolean,   // true if confirmed via successful backup session
    val manifestTimestamp: Long = System.currentTimeMillis()
)

@Dao
interface ManifestEntryDao {
    @Query("SELECT * FROM manifest_entries WHERE groupId = :groupId ORDER BY dateModified ASC")
    suspend fun getByGroup(groupId: Long): List<ManifestEntry>

    @Query("SELECT * FROM manifest_entries WHERE groupId = :groupId AND backupSuccess = 1 ORDER BY dateModified ASC")
    suspend fun getSuccessfulByGroup(groupId: Long): List<ManifestEntry>

    /**
     * Default (ABORT) conflict strategy: [BackupRepository.rebuildManifest]
     * always deletes the group's rows first, so a unique-index conflict here
     * means the engine produced duplicate entries — surface it, don't hide it.
     */
    @Insert
    suspend fun insertAll(entries: List<ManifestEntry>)

    @Query("DELETE FROM manifest_entries WHERE groupId = :groupId")
    suspend fun deleteByGroup(groupId: Long)

    @Query("DELETE FROM manifest_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
