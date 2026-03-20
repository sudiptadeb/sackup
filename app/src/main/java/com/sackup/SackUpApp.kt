package com.sackup

import android.app.Application
import com.sackup.data.BackupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SackUpApp : Application() {
    lateinit var repo: BackupRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = BackupRepository(this)
        // Seed default backup groups on first launch
        CoroutineScope(Dispatchers.IO).launch {
            repo.seedDefaults()
            repo.pruneOldLogs()
        }
    }
}
