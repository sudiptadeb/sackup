package com.sackup

import android.app.Application
import android.util.Log
import com.sackup.data.BackupRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SackUpApp : Application() {
    lateinit var repo: BackupRepository
        private set

    /** Lives as long as the process; a failed child never cancels its siblings. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Completes once the default backup groups have been seeded (or the attempt
     * failed). `MainActivity` may `await()` it before its first group load so a
     * fresh install never shows an empty list for a frame.
     */
    val seedingDone = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        repo = BackupRepository(this)
        appScope.launch {
            try {
                repo.seedDefaults()
            } catch (e: Exception) {
                Log.w(TAG, "Seeding default groups failed", e)
            } finally {
                seedingDone.complete(Unit)
            }
            try {
                repo.pruneOldLogs()
            } catch (e: Exception) {
                Log.w(TAG, "Pruning old logs failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "SackUpApp"
    }
}
