package com.sackup

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sackup.data.BackupGroup
import com.sackup.data.BackupRepository
import com.sackup.data.LogEntry
import com.sackup.data.ManifestEntry
import com.sackup.data.encodeFolders
import com.sackup.data.folderList
import com.sackup.service.BackupEngine
import com.sackup.service.BackupService
import com.sackup.service.SnapshotResult
import com.sackup.ui.*
import com.sackup.ui.theme.SackUpTheme
import com.sackup.util.FolderStats
import com.sackup.util.MediaStoreCompat
import com.sackup.util.queryFolderStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "SackUpMain"
private const val PREFS = "sackup"
private const val PREF_DRIVE_URI = "drive_uri"
private const val PREF_DRIVE_HELP_SHOWN = "drive_help_shown"

/**
 * MediaStore.createDeleteRequest() marshals every URI through Binder; thousands of URIs exceed
 * the 1 MB transaction limit. Deletions are split into batches of this size, one consent dialog each.
 */
internal const val DELETE_BATCH_SIZE = 400

/** True when a SAF tree document id points at the phone's own (primary) storage rather than a USB drive. */
internal fun isPrimaryStorageTree(treeDocumentId: String?): Boolean =
    treeDocumentId?.startsWith("primary:") == true

/** Plain-language result line shown on the Clear Space screen after a delete attempt. */
internal fun deleteResultMessage(deletedCount: Int, requestedCount: Int): String = when {
    requestedCount <= 0 -> ""
    deletedCount <= 0 -> "No files were deleted — couldn't find them on the phone, or the request was cancelled"
    deletedCount < requestedCount -> "Deleted $deletedCount of $requestedCount files"
    deletedCount == 1 -> "Deleted 1 file"
    else -> "Deleted $deletedCount files"
}

class MainActivity : ComponentActivity() {

    private lateinit var repo: BackupRepository
    private var driveUri by mutableStateOf<Uri?>(null)
    private var driveConnected by mutableStateOf(false)
    private var driveChecking by mutableStateOf(false)
    private var driveName by mutableStateOf("")
    private var mediaPermissionGranted by mutableStateOf(false)
    private var pendingOpenProgress by mutableStateOf(false)
    private var showPrimaryStorageDialog by mutableStateOf(false)
    private var showDriveHelpDialog by mutableStateOf(false)
    private var groups = mutableStateListOf<BackupGroup>()
    private var groupStats = mutableStateMapOf<Long, FolderStats>()
    private var logs = mutableStateListOf<LogEntry>()

    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    // Serialises drive probes: only the newest in-flight check may publish its result.
    private var driveCheckGeneration = 0
    private var driveCheckJob: Job? = null

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Media broadcast: ${intent?.action}")
            scheduleDriveCheck()
        }
    }

    // SAF folder picker
    private val pickDriveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onDrivePicked(uri)
    }

    // Result of the system "delete these files?" consent dialog (scoped storage, API 30+)
    private var deleteConsentCallback: ((Boolean) -> Unit)? = null
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val cb = deleteConsentCallback
        deleteConsentCallback = null
        cb?.invoke(result.resultCode == Activity.RESULT_OK)
    }

    // Permission request
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val wasGranted = mediaPermissionGranted
        mediaPermissionGranted = computeMediaPermissionGranted()
        if (mediaPermissionGranted && !wasGranted) {
            lifecycleScope.launch { refreshGroups() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = (application as SackUpApp).repo

        // Restore saved drive URI; the actual probe runs off the main thread.
        prefs.getString(PREF_DRIVE_URI, null)?.let { saved ->
            driveUri = runCatching { Uri.parse(saved) }.getOrNull()
        }
        scheduleDriveCheck()

        mediaPermissionGranted = computeMediaPermissionGranted()
        if (savedInstanceState == null) {
            requestPermissions()
            if (intent?.getBooleanExtra(BackupService.EXTRA_OPEN_PROGRESS, false) == true) {
                pendingOpenProgress = true
            }
        }

        setContent {
            SackUpTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                // Notification tap (cold start or onNewIntent) → open the progress screen.
                LaunchedEffect(pendingOpenProgress) {
                    if (pendingOpenProgress) {
                        pendingOpenProgress = false
                        navController.navigate(Routes.PROGRESS) { launchSingleTop = true }
                    }
                }

                // Refresh group cards (last backup time, counts) when a backup finishes.
                LaunchedEffect(Unit) {
                    var wasDone = BackupService.progress.value.isDone
                    BackupService.progress.collect { p ->
                        if (p.isDone && !wasDone) refreshGroups()
                        wasDone = p.isDone
                    }
                }

                if (showPrimaryStorageDialog) {
                    AlertDialog(
                        onDismissRequest = { showPrimaryStorageDialog = false },
                        title = { Text("That's not the USB drive") },
                        text = {
                            Text(
                                "That's your phone's own storage, not the USB drive. " +
                                    "Plug in the drive, tap Select Drive, then open the menu (☰) " +
                                    "in the picker and choose the drive."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showPrimaryStorageDialog = false
                                launchDrivePicker()
                            }) { Text("Try again") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPrimaryStorageDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showDriveHelpDialog) {
                    AlertDialog(
                        onDismissRequest = { showDriveHelpDialog = false },
                        title = { Text("Choose your USB drive") },
                        text = {
                            Text(
                                "Make sure the USB drive is plugged in. On the next screen, " +
                                    "open the menu (☰) in the top-left corner, tap the USB drive, " +
                                    "then tap \"Use this folder\" at the bottom."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDriveHelpDialog = false
                                prefs.edit().putBoolean(PREF_DRIVE_HELP_SHOWN, true).apply()
                                launchDrivePicker()
                            }) { Text("OK") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDriveHelpDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                NavHost(navController = navController, startDestination = Routes.HOME) {

                    composable(Routes.HOME) {
                        // Refresh on every visit
                        LaunchedEffect(Unit) {
                            launch { checkDriveConnection() }
                            refreshGroups()
                        }

                        HomeScreen(
                            groups = groups,
                            groupStats = groupStats,
                            driveUri = driveUri,
                            driveName = driveName,
                            driveConnected = driveConnected,
                            driveChecking = driveChecking,
                            mediaPermissionGranted = mediaPermissionGranted,
                            onRequestPermission = { requestPermissions() },
                            onOpenAppSettings = { openAppSettings() },
                            onPickDrive = { onPickDriveRequested() },
                            onBackup = { group ->
                                val uri = driveUri
                                when {
                                    uri == null -> Toast.makeText(
                                        this@MainActivity,
                                        "Please select a USB drive first",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    !driveConnected -> Toast.makeText(
                                        this@MainActivity,
                                        "Plug in the USB drive first — it isn't connected right now",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    else -> {
                                        BackupService.start(this@MainActivity, group.id, uri)
                                        navController.navigate(Routes.PROGRESS)
                                    }
                                }
                            },
                            onEditGroup = { group ->
                                navController.navigate(Routes.setup(group.id))
                            },
                            onAddGroup = {
                                navController.navigate(Routes.SETUP_NEW)
                            },
                            onDeleteGroup = { group ->
                                // HomeScreen has already asked the user to confirm.
                                // repo.deleteGroup also removes the group's manifest rows.
                                scope.launch {
                                    repo.deleteGroup(group)
                                    groupStats.remove(group.id)
                                    refreshGroups()
                                }
                            },
                            onClearSpace = { group ->
                                navController.navigate(Routes.clearSpace(group.id))
                            },
                            onAnalyze = { group ->
                                navController.navigate(Routes.analyze(group.id))
                            },
                            onViewLogs = {
                                navController.navigate(Routes.LOGS)
                            },
                            onViewProgress = {
                                navController.navigate(Routes.PROGRESS)
                            }
                        )
                    }

                    composable(Routes.SETUP_NEW) {
                        SetupScreen(
                            isEdit = false,
                            onSave = { name, phoneFolders ->
                                scope.launch {
                                    repo.insertGroup(
                                        BackupGroup(
                                            name = name,
                                            phoneFolders = encodeFolders(phoneFolders)
                                        )
                                    )
                                    refreshGroups()
                                    navController.popBackStack()
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        Routes.SETUP,
                        arguments = listOf(navArgument("groupId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val groupId = backStackEntry.arguments?.getLong("groupId") ?: return@composable

                        var group by remember { mutableStateOf<BackupGroup?>(null) }
                        LaunchedEffect(groupId) { group = repo.getGroup(groupId) }

                        group?.let { g ->
                            SetupScreen(
                                initialName = g.name,
                                initialPhoneFolders = g.folderList(),
                                isEdit = true,
                                onSave = { name, phoneFolders ->
                                    scope.launch {
                                        repo.updateGroup(
                                            g.copy(
                                                name = name,
                                                phoneFolders = encodeFolders(phoneFolders)
                                            )
                                        )
                                        refreshGroups()
                                        navController.popBackStack()
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    composable(Routes.PROGRESS) {
                        ProgressScreen(
                            onBack = { navController.popBackStack() },
                            onCancel = { BackupService.cancel(this@MainActivity) }
                        )
                    }

                    composable(Routes.LOGS) {
                        LaunchedEffect(Unit) { refreshLogs() }
                        LogScreen(
                            logs = logs,
                            onBack = { navController.popBackStack() },
                            onClearLogs = {
                                scope.launch {
                                    repo.clearLogs()
                                    refreshLogs()
                                }
                            },
                            onRefresh = {
                                scope.launch { refreshLogs() }
                            }
                        )
                    }

                    composable(
                        Routes.CLEAR_SPACE,
                        arguments = listOf(navArgument("groupId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val groupId = backStackEntry.arguments?.getLong("groupId") ?: return@composable

                        var group by remember { mutableStateOf<BackupGroup?>(null) }
                        var folders by remember { mutableStateOf<List<FolderClearInfo>>(emptyList()) }
                        var fileUris by remember { mutableStateOf<Map<Long, Uri>>(emptyMap()) }
                        var isLoading by remember { mutableStateOf(true) }
                        var deleteProgressText by remember { mutableStateOf("") }
                        var resultMessage by remember { mutableStateOf("") }
                        var reloadKey by remember { mutableIntStateOf(0) }

                        LaunchedEffect(groupId, reloadKey) {
                            isLoading = true
                            val g = repo.getGroup(groupId)
                            group = g
                            if (g != null) {
                                val phoneFolders = g.folderList()

                                // The engine only writes confirmed (successfully backed-up) entries.
                                val manifest = withContext(Dispatchers.IO) {
                                    repo.getSuccessfulManifest(groupId)
                                }

                                // Resolve which backed-up files are still on the phone. Entries whose
                                // file was removed outside the app (e.g. deleted manually) won't
                                // resolve — they're still safe on the drive, but there's nothing left
                                // to free, so drop them so the counts reflect what can actually be freed.
                                val resolved = withContext(Dispatchers.IO) {
                                    batchResolveFileUris(manifest)
                                }
                                fileUris = resolved.mapValues { it.value.uri }
                                val onPhone = manifest.filter { it.id in resolved }

                                folders = phoneFolders.map { phoneFolder ->
                                    val entries = onPhone.filter { it.phoneFolder == phoneFolder }
                                        .sortedBy { it.dateModified }
                                    FolderClearInfo(
                                        phoneFolder = phoneFolder,
                                        entries = entries,
                                        totalSize = entries.sumOf { it.fileSize },
                                        hasSuccessfulBackup = entries.isNotEmpty(),
                                        drivePath = if (driveName.isNotEmpty()) "$driveName/$phoneFolder" else phoneFolder
                                    )
                                }
                            }
                            isLoading = false
                        }

                        ClearSpaceScreen(
                            groupName = group?.name ?: "",
                            folders = folders,
                            isLoading = isLoading,
                            loadingStatus = deleteProgressText,
                            resultMessage = resultMessage,
                            lastBackupTime = group?.lastBackupTime ?: 0L,
                            driveConnected = driveConnected,
                            onDeleteEntries = { entries ->
                                isLoading = true
                                resultMessage = ""
                                scope.launch {
                                    val deletedIds = try {
                                        deleteFilesFromPhone(entries) { done, total ->
                                            deleteProgressText = "Deleting $done of $total files..."
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Delete failed", e)
                                        emptyList()
                                    }
                                    deleteProgressText = ""
                                    if (deletedIds.isNotEmpty()) {
                                        repo.removeManifestEntries(deletedIds)
                                    }
                                    resultMessage = deleteResultMessage(deletedIds.size, entries.size)
                                    reloadKey++
                                }
                            },
                            fileUris = fileUris,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        Routes.ANALYZE,
                        arguments = listOf(navArgument("groupId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val groupId = backStackEntry.arguments?.getLong("groupId") ?: return@composable

                        var analyzeSummary by remember { mutableStateOf<AnalyzeSummary?>(null) }
                        var cachedSnapshot by remember { mutableStateOf<SnapshotResult?>(null) }
                        var isLoading by remember { mutableStateOf(true) }
                        var scanStatus by remember { mutableStateOf("") }
                        var scanElapsedSeconds by remember { mutableLongStateOf(0L) }
                        val scanStartTime = remember { System.currentTimeMillis() }

                        // Tick elapsed time every second while scanning
                        LaunchedEffect(isLoading) {
                            while (isLoading) {
                                scanElapsedSeconds = (System.currentTimeMillis() - scanStartTime) / 1000
                                delay(1000)
                            }
                        }

                        LaunchedEffect(groupId) {
                            // Leaving the screen cancels this effect; the engine polls this so the
                            // scan stops promptly instead of running to completion in the background.
                            val effectContext = currentCoroutineContext()
                            val isCancelled: () -> Boolean = { !effectContext.isActive }
                            try {
                                val group = repo.getGroup(groupId)
                                if (group != null) {
                                    val phoneFolders = group.folderList()
                                    val uri = driveUri
                                    val engine = BackupEngine(contentResolver)
                                    if (uri != null && driveConnected) {
                                        val snapshot = withContext(Dispatchers.IO) {
                                            engine.snapshot(
                                                phoneFolders,
                                                uri,
                                                isCancelled = isCancelled,
                                                onProgress = { phase, detail, count ->
                                                    scanStatus = if (detail.isNotEmpty()) {
                                                        "$phase: $detail ($count files)"
                                                    } else {
                                                        "$phase ($count files)"
                                                    }
                                                }
                                            )
                                        }
                                        cachedSnapshot = snapshot
                                        val scanDuration = (System.currentTimeMillis() - scanStartTime) / 1000
                                        analyzeSummary = AnalyzeSummary(
                                            groupName = group.name,
                                            folders = snapshot.perFolder,
                                            driveConnected = true,
                                            totalToCopy = snapshot.filesToCopy.size,
                                            totalToCopySize = snapshot.totalBytesToCopy,
                                            scanDurationSeconds = scanDuration
                                        )
                                    } else {
                                        // Drive not connected — use manifest for offline analysis
                                        val manifestEntries = repo.getSuccessfulManifest(groupId)
                                        val snapshot = withContext(Dispatchers.IO) {
                                            engine.snapshotFromManifest(phoneFolders, manifestEntries)
                                        }
                                        val scanDuration = (System.currentTimeMillis() - scanStartTime) / 1000
                                        analyzeSummary = AnalyzeSummary(
                                            groupName = group.name,
                                            folders = snapshot.perFolder,
                                            driveConnected = false,
                                            totalToCopy = snapshot.filesToCopy.size,
                                            totalToCopySize = snapshot.totalBytesToCopy,
                                            scanDurationSeconds = scanDuration
                                        )
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: BackupEngine.ScanCancelledException) {
                                Log.d(TAG, "Analyze scan cancelled")
                                analyzeSummary = null
                            } catch (e: Exception) {
                                Log.e(TAG, "Analyze scan failed", e)
                                analyzeSummary = null
                                scanStatus = "Couldn't read the drive or phone folders"
                            }
                            isLoading = false
                        }

                        AnalyzeScreen(
                            summary = analyzeSummary,
                            isLoading = isLoading,
                            scanStatus = scanStatus,
                            scanElapsedSeconds = scanElapsedSeconds,
                            onSyncNow = {
                                val uri = driveUri
                                if (uri != null && driveConnected) {
                                    BackupService.pendingSnapshot = cachedSnapshot
                                    BackupService.start(this@MainActivity, groupId, uri)
                                    navController.popBackStack()
                                    navController.navigate(Routes.PROGRESS)
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Plug in the USB drive first — it isn't connected right now",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            onExport = {
                                analyzeSummary?.let { s -> exportAnalyzeReport(s) }
                            },
                            onCancelScan = { navController.popBackStack() },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(BackupService.EXTRA_OPEN_PROGRESS, false)) {
            pendingOpenProgress = true
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        // System (protected) broadcasts are delivered to non-exported receivers too.
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(mediaReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val wasGranted = mediaPermissionGranted
        mediaPermissionGranted = computeMediaPermissionGranted()
        if (mediaPermissionGranted && !wasGranted) {
            lifecycleScope.launch { refreshGroups() }
        }
        scheduleDriveCheck()
    }

    // ── Drive selection ────────────────────────────────────────────────────

    private fun onPickDriveRequested() {
        if (prefs.getBoolean(PREF_DRIVE_HELP_SHOWN, false)) {
            launchDrivePicker()
        } else {
            showDriveHelpDialog = true
        }
    }

    private fun launchDrivePicker() {
        try {
            pickDriveLauncher.launch(null)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the folder picker", e)
            Toast.makeText(this, "Couldn't open the folder picker on this phone", Toast.LENGTH_LONG).show()
        }
    }

    private fun onDrivePicked(uri: Uri) {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (isPrimaryStorageTree(treeId)) {
            Log.w(TAG, "User picked primary storage ($treeId) instead of a USB drive")
            showPrimaryStorageDialog = true
            return
        }

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Persist permission so it survives app restarts
        val taken = runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        if (taken.isFailure) {
            Log.e(TAG, "takePersistableUriPermission failed for $uri", taken.exceptionOrNull())
            Toast.makeText(this, "Couldn't keep access to that drive. Please try again.", Toast.LENGTH_LONG).show()
            return
        }

        // Release the grant on the previously chosen drive so we don't accumulate stale permissions.
        val previous = driveUri
        if (previous != null && previous != uri) {
            runCatching { contentResolver.releasePersistableUriPermission(previous, flags) }
                .onFailure { Log.w(TAG, "releasePersistableUriPermission failed for $previous", it) }
        }

        driveUri = uri
        driveName = ""
        prefs.edit().putString(PREF_DRIVE_URI, uri.toString()).apply()
        scheduleDriveCheck()
    }

    /** Re-probe the drive from the activity scope (cancels any probe still in flight). */
    private fun scheduleDriveCheck() {
        driveCheckJob?.cancel()
        driveCheckJob = lifecycleScope.launch { checkDriveConnection() }
    }

    /**
     * Probe whether the selected drive is currently reachable and writable. All SAF/USB I/O runs on
     * [Dispatchers.IO]; [driveChecking] is true for the duration. Must be called from the main thread.
     */
    private suspend fun checkDriveConnection() {
        val generation = ++driveCheckGeneration
        val uri = driveUri
        if (uri == null) {
            driveConnected = false
            driveName = ""
            driveChecking = false
            return
        }
        driveChecking = true
        val (connected, name) = withContext(Dispatchers.IO) { probeDrive(uri) }
        // A newer probe has started (e.g. the user just picked another drive) — let it publish.
        if (generation != driveCheckGeneration) return
        driveConnected = connected
        if (name.isNotEmpty()) driveName = name
        driveChecking = false
    }

    /** Blocking SAF probe; call on an IO dispatcher. Returns (connected, displayName). */
    private fun probeDrive(uri: Uri): Pair<Boolean, String> {
        val hasPermission = runCatching {
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        }.getOrDefault(false)
        if (!hasPermission) return false to ""

        val docFile = runCatching { DocumentFile.fromTreeUri(this, uri) }.getOrNull()
            ?: return false to ""
        val connected = runCatching { docFile.exists() && docFile.canWrite() }.getOrDefault(false)
        val name = if (connected) runCatching { docFile.name ?: "" }.getOrDefault("") else ""
        return connected to name
    }

    // ── MediaStore helpers ─────────────────────────────────────────────────

    /** A resolved MediaStore file: its content URI plus whether it is a deletable media item. */
    private data class ResolvedFile(val uri: Uri, val isMediaItem: Boolean)

    /**
     * Batch-resolve manifest entries to MediaStore content URIs.
     * Groups by phone folder to minimize queries. Returns map of entry.id → ResolvedFile.
     *
     * Builds typed media URIs (image/video/audio) where possible so MediaStore.createDeleteRequest()
     * accepts them, and flags non-media files (documents, etc.) which that API rejects.
     *
     * Uses [MediaStoreCompat] so the folder match works on API 26-28 (no RELATIVE_PATH column)
     * as well as 29+. A provider failure for one folder just leaves that folder unresolved.
     */
    private fun batchResolveFileUris(entries: List<ManifestEntry>): Map<Long, ResolvedFile> {
        val result = mutableMapOf<Long, ResolvedFile>()
        if (entries.isEmpty()) return result
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        // Query each configured folder's whole subtree (files can live in subfolders) and match
        // rows to manifest entries by name + size — the same matching the backup engine uses.
        val byFolder = entries.groupBy { it.phoneFolder }
        for ((folder, folderEntries) in byFolder) {
            val entryLookup = folderEntries.associateBy { "${it.fileName}|${it.fileSize}" }
            val (selection, args) = MediaStoreCompat.folderSelection(folder)
            runCatching {
                contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: continue
                        val size = cursor.getLong(sizeCol)
                        val entry = entryLookup["$name|$size"] ?: continue
                        val mediaId = cursor.getLong(idCol)
                        val mediaType = cursor.getInt(typeCol)
                        val typedCollection = when (mediaType) {
                            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> null
                        }
                        val uri = ContentUris.withAppendedId(typedCollection ?: collection, mediaId)
                        result[entry.id] = ResolvedFile(uri, isMediaItem = typedCollection != null)
                    }
                }
            }.onFailure { e ->
                Log.w("SackUpDelete", "MediaStore query failed for folder '$folder'", e)
            }
        }
        Log.d("SackUpDelete", "Resolved ${result.size}/${entries.size} URIs across ${byFolder.size} folder(s)")
        return result
    }

    private fun exportAnalyzeReport(summary: AnalyzeSummary) {
        val sb = StringBuilder()
        sb.appendLine("SackUp Analyze Report — ${summary.groupName}")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("Drive connected: ${summary.driveConnected}")
        sb.appendLine()
        sb.appendLine("=== Summary ===")
        sb.appendLine("To copy: ${summary.totalToCopy} files (${com.sackup.util.formatBytes(summary.totalToCopySize)})")
        sb.appendLine("Already on drive: ${summary.folders.sumOf { it.alreadyOnDrive }}")
        sb.appendLine("On drive only: ${summary.folders.sumOf { it.onDriveOnly }}")
        sb.appendLine()

        for (f in summary.folders) {
            sb.appendLine("=== ${f.phoneFolder} ===")
            sb.appendLine("  On phone: ${f.totalOnPhone}")
            sb.appendLine("  On drive: ${f.totalOnDrive}")
            sb.appendLine("  To copy: ${f.toCopy} (${com.sackup.util.formatBytes(f.toCopySize)})")
            sb.appendLine("  Backed up: ${f.alreadyOnDrive} (${com.sackup.util.formatBytes(f.alreadyOnDriveSize)})")
            sb.appendLine("  On drive only: ${f.onDriveOnly} (${com.sackup.util.formatBytes(f.onDriveOnlySize)})")
            sb.appendLine()
        }

        // Share via intent
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "SackUp Analyze — ${summary.groupName}")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        try {
            startActivity(Intent.createChooser(intent, "Share report"))
        } catch (e: Exception) {
            Log.e(TAG, "No app available to share the report", e)
            Toast.makeText(this, "No app available to share the report", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun refreshGroups() {
        val updated = try {
            repo.getAllGroups()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not load backup groups", e)
            return
        }
        groups.clear()
        groups.addAll(updated)

        // Compute folder stats for each group in background. A MediaStore failure (missing
        // permission, OEM provider quirks) degrades to "no stats" rather than crashing.
        withContext(Dispatchers.IO) {
            for (group in updated) {
                val folders = group.folderList()
                if (folders.isNotEmpty()) {
                    val stats = runCatching { queryFolderStats(contentResolver, folders) }
                        .onFailure { Log.w(TAG, "Folder stats failed for group ${group.id}", it) }
                        .getOrDefault(FolderStats())
                    groupStats[group.id] = stats
                }
            }
        }
    }

    /**
     * Delete files from phone storage.
     *
     * On Android 11+ (API 30+) an app may not delete media it didn't create with a plain
     * contentResolver.delete() — that throws RecoverableSecurityException. Instead we resolve the
     * content URIs and ask the OS to delete them via MediaStore.createDeleteRequest(), which shows
     * a system consent dialog per batch of [DELETE_BATCH_SIZE] URIs. On confirmation the OS
     * deletes the files.
     *
     * On API <= 29 (legacy storage) a direct delete by id works.
     *
     * Returns the ids of the manifest entries whose files were actually deleted.
     */
    private suspend fun deleteFilesFromPhone(
        entries: List<ManifestEntry>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<Long> {
        if (entries.isEmpty()) return emptyList()

        // Resolve the actual MediaStore URIs for the requested entries (API-level aware).
        val resolved = withContext(Dispatchers.IO) { batchResolveFileUris(entries) }
        if (resolved.isEmpty()) {
            Log.w("SackUpDelete", "No MediaStore URIs resolved for ${entries.size} entries — nothing to delete")
            return emptyList()
        }

        val deletedIds = mutableListOf<Long>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // createDeleteRequest rejects the WHOLE batch with IllegalArgumentException if any URI is
            // not an image/video/audio row ("All requested items must be Media items"). Split media
            // from non-media: media go through the system consent dialog, the rest via direct delete.
            val media = resolved.filterValues { it.isMediaItem }
            val nonMedia = resolved.filterValues { !it.isMediaItem }

            if (media.isNotEmpty()) {
                val total = media.size
                onProgress?.invoke(0, total)
                // Chunk so a large selection stays under the Binder transaction limit; one consent
                // dialog per chunk. If the user declines a chunk we stop rather than keep asking.
                for (chunk in media.entries.chunked(DELETE_BATCH_SIZE)) {
                    val granted = requestDeleteConsent(chunk.map { it.value.uri })
                    Log.d("SackUpDelete", "Consent for ${chunk.size} media URIs granted=$granted")
                    if (!granted) break
                    // createDeleteRequest is all-or-nothing per batch: on consent the OS deletes every URI.
                    deletedIds.addAll(chunk.map { it.key })
                    onProgress?.invoke(deletedIds.size, total)
                }
            }

            if (nonMedia.isNotEmpty()) {
                Log.d("SackUpDelete", "${nonMedia.size} non-media file(s) — attempting direct delete")
                deletedIds.addAll(deleteDirectly(nonMedia))
            }
            return deletedIds
        }

        // Legacy path (API <= 29): delete each resolved row by id.
        val total = resolved.size
        var done = 0
        for (chunk in resolved.entries.chunked(50)) {
            deletedIds.addAll(deleteDirectly(chunk.associate { it.key to it.value }))
            done += chunk.size
            onProgress?.invoke(done, total)
        }
        return deletedIds
    }

    /** contentResolver.delete() each resolved URI on IO; returns the manifest ids that were deleted. */
    private suspend fun deleteDirectly(files: Map<Long, ResolvedFile>): List<Long> =
        withContext(Dispatchers.IO) {
            val ok = mutableListOf<Long>()
            for ((id, rf) in files) {
                try {
                    if (contentResolver.delete(rf.uri, null, null) > 0) ok.add(id)
                } catch (e: Exception) {
                    Log.w("SackUpDelete", "Direct delete failed for entry $id", e)
                }
            }
            ok
        }

    /**
     * Ask the OS for permission to delete the given media URIs (Android 11+). Shows a single system
     * dialog and suspends until the user confirms or cancels. Returns true if the user confirmed.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestDeleteConsent(uris: List<Uri>): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteConsentCallback = { granted -> cont.resume(granted) }
                deleteRequestLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                Log.e("SackUpDelete", "createDeleteRequest/launch failed for ${uris.size} URIs", e)
                deleteConsentCallback = null
                cont.resume(false)
            }
            cont.invokeOnCancellation { deleteConsentCallback = null }
        }

    private suspend fun refreshLogs() {
        val updated = try {
            repo.getAllLogs()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not load logs", e)
            return
        }
        logs.clear()
        logs.addAll(updated)
    }

    // ── Permissions ────────────────────────────────────────────────────────

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether we can read the user's media. On Android 14+ a "selected photos only" grant
     * (READ_MEDIA_VISUAL_USER_SELECTED) counts as granted-but-partial — still true here.
     */
    private fun computeMediaPermissionGranted(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            (hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) ||
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
        else -> hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (!hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (!hasPermission(Manifest.permission.READ_MEDIA_AUDIO)) perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                (perms.contains(Manifest.permission.READ_MEDIA_IMAGES) || perms.contains(Manifest.permission.READ_MEDIA_VIDEO))
            ) {
                // Android 14+: request alongside IMAGES/VIDEO so the "select photos" option is offered.
                perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Android 12 and below
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            // Android 10 and below need write permission to delete files (legacy storage).
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (perms.isNotEmpty()) {
            try {
                permissionLauncher.launch(perms.toTypedArray())
            } catch (e: Exception) {
                Log.e(TAG, "Permission request failed", e)
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open app settings", e)
            Toast.makeText(this, "Open Settings > Apps > SackUp > Permissions to allow access", Toast.LENGTH_LONG).show()
        }
    }
}
