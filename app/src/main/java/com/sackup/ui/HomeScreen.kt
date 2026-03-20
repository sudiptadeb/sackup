package com.sackup.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sackup.data.BackupGroup
import com.sackup.service.BackupService
import com.sackup.util.formatBytes
import java.text.SimpleDateFormat
import java.util.*

import com.sackup.util.FolderStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    groups: List<BackupGroup>,
    groupStats: Map<Long, FolderStats>,
    driveUri: Uri?,
    driveConnected: Boolean,
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SackUp", fontWeight = FontWeight.Bold, fontSize = 24.sp) },
                actions = {
                    IconButton(onClick = onViewLogs) {
                        Icon(Icons.Default.List, contentDescription = "Logs")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddGroup) {
                Icon(Icons.Default.Add, contentDescription = "Add backup group")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Drive status card
            item {
                DriveStatusCard(
                    connected = driveConnected,
                    driveUri = driveUri,
                    onPickDrive = onPickDrive
                )
            }

            // Service running banner
            if (BackupService.isRunning) {
                item {
                    Card(
                        onClick = onViewProgress,
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
                            Column {
                                Text("Backup in progress", fontWeight = FontWeight.Bold)
                                Text(
                                    "${BackupService.currentGroupName} — ${BackupService.completedFiles}/${BackupService.totalFiles}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Backup group cards
            items(groups, key = { it.id }) { group ->
                BackupGroupCard(
                    group = group,
                    stats = groupStats[group.id],
                    driveConnected = driveConnected,
                    onBackup = { onBackup(group) },
                    onEdit = { onEditGroup(group) },
                    onDelete = { onDeleteGroup(group) },
                    onClearSpace = { onClearSpace(group) },
                    onAnalyze = { onAnalyze(group) }
                )
            }

            if (groups.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No backup groups yet.\nTap + to add one.",
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
fun DriveStatusCard(connected: Boolean, driveUri: Uri?, onPickDrive: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (connected) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (connected) {
                    Text(
                        "USB Drive Connected",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                } else if (driveUri != null) {
                    // Previously selected but no longer accessible
                    Text(
                        "USB Drive Not Connected",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "Plug in the drive or select a new one",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "No USB Drive",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "Plug in a USB drive and tap Select Drive",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Button(onClick = onPickDrive) {
                Text(if (connected) "Change" else "Select Drive")
            }
        }
    }
}

@Composable
fun BackupGroupCard(
    group: BackupGroup,
    stats: FolderStats?,
    driveConnected: Boolean,
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
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
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
                            text = { Text("Analyze") },
                            onClick = { showMenu = false; onAnalyze() }
                        )
                        DropdownMenuItem(
                            text = { Text("Free Up Space") },
                            onClick = { showMenu = false; onClearSpace() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }

            Text(
                "To: ${group.driveFolder}/",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Folder stats from phone
            if (stats != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${stats.fileCount} files on phone · ${formatBytes(stats.totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (group.lastBackupTime > 0) {
                Spacer(Modifier.height(4.dp))
                val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    .format(Date(group.lastBackupTime))
                Text(
                    "Last backup: $dateStr — ${group.lastBackupFileCount} files, ${formatBytes(group.lastBackupBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onBackup,
                enabled = driveConnected && !BackupService.isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
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
