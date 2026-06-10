package com.sackup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.provider.MediaStore
import com.sackup.service.BackupEngine
import com.sackup.service.SnapshotResult
import com.sackup.data.BackupGroup
import com.sackup.data.BackupRepository
import com.sackup.data.LogEntry
import com.sackup.data.ManifestEntry
import com.sackup.service.BackupService
import com.sackup.ui.*
import com.sackup.ui.theme.SackUpTheme
import com.sackup.util.FolderStats
import com.sackup.util.queryFolderStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var repo: BackupRepository
    private var driveUri by mutableStateOf<Uri?>(null)
    private var driveConnected by mutableStateOf(false)
    private var driveName by mutableStateOf("")
    private var groups = mutableStateListOf<BackupGroup>()
    private var groupStats = mutableStateMapOf<Long, FolderStats>()
    private var logs = mutableStateListOf<LogEntry>()
    private var pendingBackupGroupId: Long? = null

    // SAF folder picker
    private val pickDriveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist permission so it survives app restarts
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            driveUri = uri
            driveConnected = true
            driveName = resolveDriveName(uri)

            // Save to shared prefs
            getSharedPreferences("sackup", MODE_PRIVATE)
                .edit()
                .putString("drive_uri", uri.toString())
                .apply()
        }
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
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Storage permissions are needed to read your files", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = (application as SackUpApp).repo

        // Restore saved drive URI
        val savedUri = getSharedPreferences("sackup", MODE_PRIVATE).getString("drive_uri", null)
        if (savedUri != null) {
            driveUri = Uri.parse(savedUri)
            checkDriveConnection()
            if (driveConnected) driveName = resolveDriveName(driveUri!!)
        }

        requestPermissions()

        setContent {
            SackUpTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                // Load groups whenever we return to home
                LaunchedEffect(Unit) {
                    refreshGroups()
                }

                NavHost(navController = navController, startDestination = Routes.HOME) {

                    composable(Routes.HOME) {
                        // Refresh on every visit
                        LaunchedEffect(Unit) {
                            checkDriveConnection()
                            refreshGroups()
                        }

                        HomeScreen(
                            groups = groups,
                            groupStats = groupStats,
                            driveUri = driveUri,
                            driveName = driveName,
                            driveConnected = driveConnected,
                            onPickDrive = { pickDriveLauncher.launch(null) },
                            onBackup = { group ->
                                val uri = driveUri
                                if (uri == null) {
                                    Toast.makeText(this@MainActivity, "Please select a USB drive first", Toast.LENGTH_SHORT).show()
                                    return@HomeScreen
                                }
                                BackupService.start(this@MainActivity, group.id, uri)
                                navController.navigate(Routes.PROGRESS)
                            },
                            onEditGroup = { group ->
                                navController.navigate(Routes.setup(group.id))
                            },
                            onAddGroup = {
                                navController.navigate(Routes.SETUP_NEW)
                            },
                            onDeleteGroup = { group ->
                                scope.launch {
                                    repo.deleteGroup(group)
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
                                            phoneFolders = Gson().toJson(phoneFolders)
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
                            val folders: List<String> = try {
                                Gson().fromJson(g.phoneFolders, object : TypeToken<List<String>>() {}.type)
                            } catch (e: Exception) { emptyList() }

                            SetupScreen(
                                initialName = g.name,
                                initialPhoneFolders = folders,
                                isEdit = true,
                                onSave = { name, phoneFolders ->
                                    scope.launch {
                                        repo.updateGroup(
                                            g.copy(
                                                name = name,
                                                phoneFolders = Gson().toJson(phoneFolders)
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

                        LaunchedEffect(groupId) {
                            group = repo.getGroup(groupId)
                            group?.let { g ->
                                val phoneFolders: List<String> = try {
                                    Gson().fromJson(g.phoneFolders, object : TypeToken<List<String>>() {}.type)
                                } catch (_: Exception) { emptyList() }

                                val manifest = withContext(Dispatchers.IO) {
                                    repo.getSuccessfulManifest(groupId)
                                }
                                val allManifest = withContext(Dispatchers.IO) {
                                    repo.getManifestForGroup(groupId)
                                }

                                folders = phoneFolders.map { phoneFolder ->
                                    val successEntries = manifest.filter { it.phoneFolder == phoneFolder }
                                        .sortedBy { it.dateModified }
                                    val allEntries = allManifest.filter { it.phoneFolder == phoneFolder }
                                    val hasSuccess = allEntries.isEmpty() || allEntries.any { it.backupSuccess }
                                    FolderClearInfo(
                                        phoneFolder = phoneFolder,
                                        entries = successEntries,
                                        totalSize = successEntries.sumOf { it.fileSize },
                                        hasSuccessfulBackup = hasSuccess && successEntries.isNotEmpty(),
                                        drivePath = if (driveName.isNotEmpty()) "$driveName/$phoneFolder" else phoneFolder
                                    )
                                }

                                // Batch-resolve all file URIs upfront for smooth scrolling
                                val allEntries = folders.flatMap { it.entries }
                                fileUris = withContext(Dispatchers.IO) {
                                    batchResolveFileUris(allEntries)
                                }

                                isLoading = false
                            }
                        }

                        ClearSpaceScreen(
                            groupName = group?.name ?: "",
                            folders = folders,
                            isLoading = isLoading,
                            loadingStatus = deleteProgressText,
                            onDeleteEntries = { entries ->
                                isLoading = true
                                scope.launch {
                                    val deletedIds = deleteFilesFromPhone(entries, fileUris) { done, total ->
                                        deleteProgressText = "Deleting $done of $total files..."
                                    }
                                    deleteProgressText = ""
                                    if (deletedIds.isNotEmpty()) {
                                        repo.removeManifestEntries(deletedIds)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Deleted ${deletedIds.size} files",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    navController.popBackStack()
                                    navController.navigate(Routes.clearSpace(groupId))
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
                                kotlinx.coroutines.delay(1000)
                            }
                        }

                        LaunchedEffect(groupId) {
                            val group = repo.getGroup(groupId)
                            if (group != null) {
                                val phoneFolders: List<String> = try {
                                    Gson().fromJson(group.phoneFolders, object : TypeToken<List<String>>() {}.type)
                                } catch (_: Exception) { emptyList() }

                                val uri = driveUri
                                if (uri != null && driveConnected) {
                                    val engine = BackupEngine(contentResolver)
                                    val snapshot = withContext(Dispatchers.IO) {
                                        engine.snapshot(phoneFolders, uri) { phase, detail, count ->
                                            scanStatus = if (detail.isNotEmpty()) {
                                                "$phase: $detail ($count files)"
                                            } else {
                                                "$phase ($count files)"
                                            }
                                        }
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
                                    val engine = BackupEngine(contentResolver)
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
                            isLoading = false
                        }

                        AnalyzeScreen(
                            summary = analyzeSummary,
                            isLoading = isLoading,
                            scanStatus = scanStatus,
                            scanElapsedSeconds = scanElapsedSeconds,
                            onSyncNow = {
                                val uri = driveUri
                                if (uri != null) {
                                    BackupService.pendingSnapshot = cachedSnapshot
                                    BackupService.start(this@MainActivity, groupId, uri)
                                    navController.popBackStack()
                                    navController.navigate(Routes.PROGRESS)
                                }
                            },
                            onExport = {
                                analyzeSummary?.let { s -> exportAnalyzeReport(s) }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    /**
     * Batch-resolve manifest entries to MediaStore content URIs.
     * Groups by phonePath to minimize queries. Returns map of entry.id → URI.
     */
    private fun batchResolveFileUris(entries: List<ManifestEntry>): Map<Long, Uri> {
        val result = mutableMapOf<Long, Uri>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE
        )

        // Group entries by phonePath to batch queries
        val byPath = entries.groupBy { it.phonePath }
        for ((phonePath, pathEntries) in byPath) {
            val entryLookup = pathEntries.associateBy { "${it.fileName}|${it.fileSize}" }
            val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.SIZE} > 0"
            contentResolver.query(collection, projection, selection, arrayOf(phonePath), null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val entry = entryLookup["$name|$size"] ?: continue
                    val mediaId = cursor.getLong(idCol)
                    result[entry.id] = android.content.ContentUris.withAppendedId(collection, mediaId)
                }
            }
        }
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
        startActivity(Intent.createChooser(intent, "Share report"))
    }

    private fun checkDriveConnection() {
        val uri = driveUri ?: run {
            driveConnected = false
            driveName = ""
            return
        }
        val hasPermission = contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isWritePermission }
        if (!hasPermission) {
            driveConnected = false
            return
        }
        driveConnected = try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
            docFile != null && docFile.exists() && docFile.canWrite()
        } catch (_: Exception) {
            false
        }
        if (driveConnected) driveName = resolveDriveName(uri)
    }

    /** Get display name of the selected drive folder from its SAF URI. */
    private fun resolveDriveName(uri: Uri): String {
        return try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
            docFile?.name ?: ""
        } catch (_: Exception) { "" }
    }

    private suspend fun refreshGroups() {
        val updated = repo.getAllGroups()
        groups.clear()
        groups.addAll(updated)

        // Compute folder stats for each group in background
        withContext(Dispatchers.IO) {
            for (group in updated) {
                val folders: List<String> = try {
                    Gson().fromJson(group.phoneFolders, object : TypeToken<List<String>>() {}.type)
                } catch (_: Exception) { emptyList() }
                if (folders.isNotEmpty()) {
                    val stats = queryFolderStats(contentResolver, folders)
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
     * a single system consent dialog for the whole batch. On confirmation the OS deletes the files.
     *
     * On API <= 29 (legacy storage) a direct delete works.
     *
     * Returns the ids of the manifest entries whose files were actually deleted.
     */
    private suspend fun deleteFilesFromPhone(
        entries: List<ManifestEntry>,
        preResolved: Map<Long, Uri> = emptyMap(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<Long> {
        if (entries.isEmpty()) return emptyList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Prefer URIs already resolved for the on-screen thumbnails; resolve any leftovers fresh.
            val resolved = LinkedHashMap<Long, Uri>()
            for (e in entries) preResolved[e.id]?.let { resolved[e.id] = it }
            val missing = entries.filter { it.id !in resolved }
            if (missing.isNotEmpty()) {
                val fresh = withContext(Dispatchers.IO) { batchResolveFileUris(missing) }
                resolved.putAll(fresh)
            }

            if (resolved.isEmpty()) {
                // Nothing matched in MediaStore — surface it instead of bouncing silently.
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Couldn't find these files in phone storage to delete. " +
                            "They may already be gone, or the app wasn't granted full access to them.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return emptyList()
            }

            onProgress?.invoke(0, resolved.size)
            val granted = requestDeleteConsent(resolved.values.toList())
            // createDeleteRequest is all-or-nothing: on consent the OS deletes every URI in the batch.
            return if (granted) resolved.keys.toList() else emptyList()
        }

        // Legacy path (API <= 29): direct delete, matched by RELATIVE_PATH + DISPLAY_NAME + SIZE.
        return withContext(Dispatchers.IO) {
            val deletedIds = mutableListOf<Long>()
            val total = entries.size
            val collection = MediaStore.Files.getContentUri("external")

            for ((index, entry) in entries.withIndex()) {
                try {
                    val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
                            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                            "${MediaStore.Files.FileColumns.SIZE} = ?"
                    val args = arrayOf(entry.phonePath, entry.fileName, entry.fileSize.toString())

                    val count = contentResolver.delete(collection, selection, args)
                    if (count > 0) deletedIds.add(entry.id)
                } catch (_: Exception) {
                }
                if ((index + 1) % 10 == 0 || index == total - 1) {
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(index + 1, total)
                    }
                }
            }
            deletedIds
        }
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
                deleteConsentCallback = null
                cont.resume(false)
            }
            cont.invokeOnCancellation { deleteConsentCallback = null }
        }

    private suspend fun refreshLogs() {
        val updated = repo.getAllLogs()
        logs.clear()
        logs.addAll(updated)
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            // Android 10 and below need write permission to delete files (legacy storage).
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}
