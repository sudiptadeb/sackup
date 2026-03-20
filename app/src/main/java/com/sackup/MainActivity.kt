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
import com.sackup.data.BackupGroup
import com.sackup.data.BackupRepository
import com.sackup.data.LogEntry
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
            // Check if permission is still valid
            val persistedUris = contentResolver.persistedUriPermissions
            driveConnected = persistedUris.any { it.uri == driveUri && it.isWritePermission }
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
                        LaunchedEffect(Unit) { refreshGroups() }

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
                }
            }
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
