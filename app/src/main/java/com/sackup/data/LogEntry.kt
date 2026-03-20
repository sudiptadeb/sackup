package com.sackup.data

import androidx.room.*

@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,       // groups logs by backup session
    val groupName: String,       // which backup group this belongs to
    val level: String,           // "INFO", "WARN", "ERROR"
    val message: String
)

@Dao
interface LogEntryDao {
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC, id DESC")
    suspend fun getAll(): List<LogEntry>

    @Query("SELECT * FROM log_entries WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getBySession(sessionId: String): List<LogEntry>

    @Query("SELECT DISTINCT sessionId FROM log_entries ORDER BY timestamp DESC")
    suspend fun getSessionIds(): List<String>

    @Insert
    suspend fun insert(entry: LogEntry)

    @Query("DELETE FROM log_entries WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM log_entries")
    suspend fun deleteAll()
}
