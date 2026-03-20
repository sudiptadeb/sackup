package com.sackup.data

import android.content.Context

class BackupRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val groupDao = db.backupGroupDao()
    private val logDao = db.logEntryDao()
    private val manifestDao = db.manifestEntryDao()

    // Backup Groups
    suspend fun getAllGroups(): List<BackupGroup> = groupDao.getAll()
    suspend fun getGroup(id: Long): BackupGroup? = groupDao.getById(id)
    suspend fun insertGroup(group: BackupGroup): Long = groupDao.insert(group)
    suspend fun updateGroup(group: BackupGroup) = groupDao.update(group)
    suspend fun deleteGroup(group: BackupGroup) = groupDao.delete(group)
    suspend fun groupCount(): Int = groupDao.count()

    // Seed default groups if empty
    suspend fun seedDefaults() {
        if (groupDao.count() > 0) return
        groupDao.insert(
            BackupGroup(
                name = "Camera",
                phoneFolders = "[\"DCIM\",\"Pictures\"]"
            )
        )
        groupDao.insert(
            BackupGroup(
                name = "Downloads",
                phoneFolders = "[\"Download\"]"
            )
        )
        groupDao.insert(
            BackupGroup(
                name = "WhatsApp",
                phoneFolders = "[\"WhatsApp/Media\"]"
            )
        )
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
    suspend fun getSuccessfulManifestByFolder(groupId: Long, phoneFolder: String): List<ManifestEntry> =
        manifestDao.getSuccessfulByFolder(groupId, phoneFolder)
    suspend fun rebuildManifest(groupId: Long, entries: List<ManifestEntry>) {
        manifestDao.deleteByGroup(groupId)
        if (entries.isNotEmpty()) {
            manifestDao.insertAll(entries)
        }
    }
    suspend fun removeManifestEntries(ids: List<Long>) = manifestDao.deleteByIds(ids)
}
