package com.sackup.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema is exported to app/schemas/com.sackup.data.AppDatabase/<version>.json
 * (see `room.schemaLocation` in app/build.gradle.kts). Commit the JSON whenever
 * the schema changes.
 *
 * MIGRATION RULE: versions 1 and 2 pre-date the manifest table and may be
 * wiped (users lose nothing but re-scan on the next backup). Any bump beyond
 * [VERSION] MUST ship a `Migration` added via `addMigrations(...)` below —
 * never widen the destructive fallback, or users lose their manifest and
 * "Free Up Space" can no longer tell what is safely on the drive.
 */
@Database(
    entities = [BackupGroup::class, LogEntry::class, ManifestEntry::class],
    version = 3, // keep in sync with VERSION below
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun backupGroupDao(): BackupGroupDao
    abstract fun logEntryDao(): LogEntryDao
    abstract fun manifestEntryDao(): ManifestEntryDao

    companion object {
        const val VERSION = 3
        const val NAME = "sackup.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME
                )
                    // Only the pre-manifest schemas may be dropped. See class doc.
                    .fallbackToDestructiveMigrationFrom(1, 2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
