package com.sackup.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sackup.data.BackupGroup
import com.sackup.service.BackupProgress
import com.sackup.service.BackupService
import com.sackup.util.FolderStats
import com.sackup.util.formatBytes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Hoisted out of composition: SimpleDateFormat is expensive to build.
private fun lastBackupFormat() = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    groups: List<BackupGroup>,
    groupStats: Map<Long, FolderStats>,
    driveUri: Uri?,
    driveName: String,
    driveConnected: Boolean,
    driveChecking: Boolean,
    mediaPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onPickDrive: () -> Unit,
    onBackup: (BackupGroup) -> Unit,
    onEditGroup: (BackupGroup) -> Unit,
    onAddGroup: () -> Unit,
    onDeleteGroup: (BackupGroup) -> Unit,
    onClearSpace: (BackupGroup) -> Unit,
    onAnalyze: (BackupGroup) -> Unit,
    onViewLogs: () -> Unit,
    onViewProgress: () -> Unit,
) {
    val progress: BackupProgress by BackupService.progress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var groupToDelete by remember { mutableStateOf<BackupGroup?>(null) }

    groupToDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text("Remove the '${group.name}' backup?") },
            text = { Text("Files already on the USB drive are not touched.") },
            confirmButton = {
                Button(
                    onClick = {
                        groupToDelete = null
                        onDeleteGroup(group)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) { Text("Keep it") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SackUp", fontWeight = FontWeight.Bold, fontSize = 24.sp) },
                actions = {
                    IconButton(onClick = onViewLogs) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddGroup) {
                Icon(Icons.Default.Add, contentDescription = "Add backup")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
        ) {
            if (!mediaPermissionGranted) {
                item(key = "permission") {
                    PermissionCard(
                        onRequestPermission = onRequestPermission,
                        onOpenAppSettings = onOpenAppSettings
                    )
                }
            }

            item(key = "drive") {
                DriveStatusCard(
                    connected = driveConnected,
                    checking = driveChecking,
                    driveUri = driveUri,
                    driveName = driveName,
                    onPickDrive = onPickDrive
                )
            }

            if (progress.isRunning) {
                item(key = "running") {
                    RunningBanner(progress = progress, onClick = onViewProgress)
                }
            }

            items(groups, key = { it.id }) { group ->
                BackupGroupCard(
                    group = group,
                    stats = groupStats[group.id],
                    mediaPermissionGranted = mediaPermissionGranted,
                    onBackup = {
                        when {
                            !driveConnected -> scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Plug in the USB drive and tap Select Drive first"
                                )
                            }
                            progress.isRunning -> scope.launch {
                                snackbarHostState.showSnackbar(
                                    "A backup is already running — tap the blue banner to watch it"
                                )
                            }
                            else -> onBackup(group)
                        }
                    },
                    onEdit = { onEditGroup(group) },
                    onDelete = { groupToDelete = group },
                    onClearSpace = { onClearSpace(group) },
                    onAnalyze = { onAnalyze(group) }
                )
            }

            if (groups.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No backups set up yet.\nTap + to add one.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "SackUp can't see your photos yet",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Allow access so SackUp can copy your photos and videos to the drive.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Allow") }
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Open settings") }
            }
        }
    }
}

@Composable
private fun RunningBanner(progress: BackupProgress, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 3.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Backup in progress", fontWeight = FontWeight.Bold)
                val detail = if (progress.totalFiles > 0)
                    "${progress.groupName} — ${progress.completedFiles} of ${progress.totalFiles} files"
                else
                    "${progress.groupName} — ${progress.statusText.ifEmpty { "Getting ready…" }}"
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Tap to watch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun DriveStatusCard(
    connected: Boolean,
    checking: Boolean,
    driveUri: Uri?,
    driveName: String,
    onPickDrive: () -> Unit,
) {
    val containerColor = when {
        checking -> MaterialTheme.colorScheme.surfaceVariant
        connected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    if (connected) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = if (connected) "Drive connected" else "Drive not connected",
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                when {
                    checking -> {
                        Text("Checking drive…", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("One moment", style = MaterialTheme.typography.bodySmall)
                    }
                    connected -> {
                        Text("USB drive ready", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (driveName.isNotEmpty()) {
                            Text(
                                driveName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    driveUri != null -> {
                        Text("USB drive not found", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Plug in the drive, or pick a different one",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    else -> {
                        Text("No USB drive yet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Plug in a USB drive and tap Select Drive",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onPickDrive,
                enabled = !checking,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (connected) "Change" else "Select Drive")
            }
        }
    }
}

@Composable
fun BackupGroupCard(
    group: BackupGroup,
    stats: FolderStats?,
    mediaPermissionGranted: Boolean,
    onBackup: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClearSpace: () -> Unit = {},
    onAnalyze: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options for ${group.name}")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = { showMenu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Check what's backed up") },
                            onClick = { showMenu = false; onAnalyze() }
                        )
                        DropdownMenuItem(
                            text = { Text("Free Up Space") },
                            onClick = { showMenu = false; onClearSpace() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            when {
                stats != null -> Text(
                    "${stats.fileCount} files on phone · ${formatBytes(stats.totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                !mediaPermissionGranted -> Text(
                    "Allow photo access above to see what's on your phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Text(
                    "Counting files on phone…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))
            if (group.lastBackupTime > 0) {
                val dateStr = remember(group.lastBackupTime) {
                    lastBackupFormat().format(Date(group.lastBackupTime))
                }
                Text(
                    "Last backup: $dateStr — ${group.lastBackupFileCount} files, ${formatBytes(group.lastBackupBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Not backed up yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onBackup,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Text(
                    "Back Up Now",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
