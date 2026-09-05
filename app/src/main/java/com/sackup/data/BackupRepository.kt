package com.sackup.data

import android.content.Context
import androidx.room.withTransaction

class BackupRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val groupDao = db.backupGroupDao()
    private val logDao = db.logEntryDao()
    private val manifestDao = db.manifestEntryDao()

    // Backup Groups
    suspend fun getAllGroups(): List<BackupGroup> = groupDao.getAll()
    suspend fun getGroup(id: Long): BackupGroup? = groupDao.getById(id)
    suspend fun insertGroup(group: BackupGroup): Long = groupDao.insert(group)
    suspend fun updateGroup(group: BackupGroup) = groupDao.update(group)

    /** Deletes the group together with its manifest rows, atomically. */
    suspend fun deleteGroup(group: BackupGroup) {
        db.withTransaction {
            manifestDao.deleteByGroup(group.id)
            groupDao.delete(group)
        }
    }

    /**
     * Seeds the default backup groups exactly once per install. Gated by a
     * SharedPreferences flag rather than `count() == 0` so a user who deletes
     * every default group does not get them re-created on the next launch.
     */
    suspend fun seedDefaults() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DEFAULTS_SEEDED, false)) return
        db.withTransaction {
            if (groupDao.getAll().isEmpty()) {
                for (group in defaultGroups()) groupDao.insert(group)
            }
        }
        prefs.edit().putBoolean(KEY_DEFAULTS_SEEDED, true).apply()
    }

    // Logs
    suspend fun getAllLogs(): List<LogEntry> = logDao.getAll()
    suspend fun getLogsBySession(sessionId: String): List<LogEntry> = logDao.getBySession(sessionId)
    suspend fun getLogSessions(): List<String> = logDao.getSessionIds()
    suspend fun insertLog(entry: LogEntry) = logDao.insert(entry)
    suspend fun clearLogs() = logDao.deleteAll()

    // Keep last 30 days of logs
    suspend fun pruneOldLogs() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        logDao.deleteOlderThan(thirtyDaysAgo)
    }

    // Manifest
    suspend fun getManifestForGroup(groupId: Long): List<ManifestEntry> = manifestDao.getByGroup(groupId)
    suspend fun getSuccessfulManifest(groupId: Long): List<ManifestEntry> = manifestDao.getSuccessfulByGroup(groupId)

    /** Replaces the group's manifest atomically: readers never see a half-written manifest. */
    suspend fun rebuildManifest(groupId: Long, entries: List<ManifestEntry>) {
        db.withTransaction {
            manifestDao.deleteByGroup(groupId)
            if (entries.isNotEmpty()) {
                manifestDao.insertAll(entries)
            }
        }
    }

    /**
     * Removes manifest rows by id. SQLite on Android <= 11 allows at most 999
     * bound variables per statement, so a "Delete all" of thousands of files
     * would throw after the files were already gone. Chunk the IN (...) list
     * and run every chunk in one transaction.
     */
    suspend fun removeManifestEntries(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            ids.chunked(SQLITE_MAX_BIND_ARGS).forEach { manifestDao.deleteByIds(it) }
        }
    }

    companion object {
        const val PREFS_NAME = "sackup"
        const val KEY_DEFAULTS_SEEDED = "defaults_seeded"

        /** Below SQLite's historical SQLITE_MAX_VARIABLE_NUMBER of 999. */
        private const val SQLITE_MAX_BIND_ARGS = 900

        /** The three groups created on first launch. */
        fun defaultGroups(): List<BackupGroup> = listOf(
            BackupGroup(name = "Images", phoneFolders = encodeFolders(listOf("DCIM", "Pictures"))),
            BackupGroup(name = "Documents", phoneFolders = encodeFolders(listOf("Documents"))),
            BackupGroup(name = "Music", phoneFolders = encodeFolders(listOf("Music")))
        )
    }
}
