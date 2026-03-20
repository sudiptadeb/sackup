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

    @Query("SELECT COUNT(*) FROM backup_groups")
    suspend fun count(): Int
}
