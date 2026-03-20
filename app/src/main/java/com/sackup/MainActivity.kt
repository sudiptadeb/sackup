package com.sackup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

            // Save to shared prefs
            getSharedPreferences("sackup", MODE_PRIVATE)
                .edit()
                .putString("drive_uri", uri.toString())
                .apply()
        }
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
        repo = (application as SackUpApp).repo

        // Restore saved drive URI
        val savedUri = getSharedPreferences("sackup", MODE_PRIVATE).getString("drive_uri", null)
        if (savedUri != null) {
            driveUri = Uri.parse(savedUri)
            checkDriveConnection()
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
                            onSave = { name, phoneFolders, driveFolder ->
                                scope.launch {
                                    repo.insertGroup(
                                        BackupGroup(
                                            name = name,
                                            phoneFolders = Gson().toJson(phoneFolders),
                                            driveFolder = driveFolder
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
                                initialDriveFolder = g.driveFolder,
                                isEdit = true,
                                onSave = { name, phoneFolders, driveFolder ->
                                    scope.launch {
                                        repo.updateGroup(
                                            g.copy(
                                                name = name,
                                                phoneFolders = Gson().toJson(phoneFolders),
                                                driveFolder = driveFolder
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
                        var isLoading by remember { mutableStateOf(true) }

                        LaunchedEffect(groupId) {
                            group = repo.getGroup(groupId)
                            group?.let { g ->
                                val phoneFolders: List<String> = try {
                                    Gson().fromJson(g.phoneFolders, object : TypeToken<List<String>>() {}.type)
                                } catch (_: Exception) { emptyList() }

                                val manifest = withContext(Dispatchers.IO) {
                                    repo.getSuccessfulManifest(groupId)
                                }
                                // Also get failed entries to show warning
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
                                        hasSuccessfulBackup = hasSuccess && successEntries.isNotEmpty()
                                    )
                                }
                                isLoading = false
                            }
                        }

                        ClearSpaceScreen(
                            groupName = group?.name ?: "",
                            folders = folders,
                            isLoading = isLoading,
                            onDeleteOldest = { phoneFolder, count, entries ->
                                scope.launch {
                                    val deleted = deleteFilesFromPhone(entries)
                                    if (deleted > 0) {
                                        // Remove deleted entries from manifest
                                        repo.removeManifestEntries(entries.take(deleted).map { it.id })
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Deleted $deleted files",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    // Refresh the clear space data
                                    navController.popBackStack()
                                    navController.navigate(Routes.clearSpace(groupId))
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        Routes.ANALYZE,
                        arguments = listOf(navArgument("groupId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val groupId = backStackEntry.arguments?.getLong("groupId") ?: return@composable

                        var analyzeSummary by remember { mutableStateOf<AnalyzeSummary?>(null) }
                        var isLoading by remember { mutableStateOf(true) }

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
                                        engine.snapshot(phoneFolders, uri, group.driveFolder)
                                    }
                                    analyzeSummary = AnalyzeSummary(
                                        groupName = group.name,
                                        driveFolder = group.driveFolder,
                                        folders = snapshot.perFolder,
                                        driveConnected = true,
                                        totalToCopy = snapshot.filesToCopy.size,
                                        totalToCopySize = snapshot.totalBytesToCopy
                                    )
                                } else {
                                    analyzeSummary = AnalyzeSummary(
                                        groupName = group.name,
                                        driveFolder = group.driveFolder,
                                        folders = emptyList(),
                                        driveConnected = false,
                                        totalToCopy = 0,
                                        totalToCopySize = 0
                                    )
                                }
                            }
                            isLoading = false
                        }

                        AnalyzeScreen(
                            summary = analyzeSummary,
                            isLoading = isLoading,
                            onSyncNow = {
                                val uri = driveUri
                                if (uri != null) {
                                    BackupService.start(this@MainActivity, groupId, uri)
                                    navController.popBackStack()
                                    navController.navigate(Routes.PROGRESS)
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    private fun checkDriveConnection() {
        val uri = driveUri ?: run {
            driveConnected = false
            return
        }
        // Check permission still exists
        val hasPermission = contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isWritePermission }
        if (!hasPermission) {
            driveConnected = false
            return
        }
        // Actually try to access the drive to verify it's plugged in
        driveConnected = try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
            docFile != null && docFile.exists() && docFile.canWrite()
        } catch (_: Exception) {
            false
        }
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
     * Delete files from phone via MediaStore. Matches by RELATIVE_PATH + DISPLAY_NAME + SIZE.
     * Returns the number of files successfully deleted.
     */
    private suspend fun deleteFilesFromPhone(entries: List<ManifestEntry>): Int =
        withContext(Dispatchers.IO) {
            var deleted = 0
            val collection = MediaStore.Files.getContentUri("external")

            for (entry in entries) {
                try {
                    // Find the file in MediaStore
                    val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
                            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                            "${MediaStore.Files.FileColumns.SIZE} = ?"
                    val args = arrayOf(entry.phonePath, entry.fileName, entry.fileSize.toString())

                    val count = contentResolver.delete(collection, selection, args)
                    if (count > 0) deleted++
                } catch (e: SecurityException) {
                    // On Android 11+, may need createDeleteRequest for files not owned by us
                    // For now, skip and count as not deleted
                } catch (_: Exception) {
                    // Skip failed deletions
                }
            }
            deleted
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
        }

        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}
