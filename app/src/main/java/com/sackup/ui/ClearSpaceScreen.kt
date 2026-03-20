package com.sackup.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sackup.data.ManifestEntry
import com.sackup.util.formatBytes

data class FolderClearInfo(
    val phoneFolder: String,
    val entries: List<ManifestEntry>,  // sorted oldest first
    val totalSize: Long,
    val hasSuccessfulBackup: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearSpaceScreen(
    groupName: String,
    folders: List<FolderClearInfo>,
    isLoading: Boolean,
    onDeleteOldest: (phoneFolder: String, count: Int, entries: List<ManifestEntry>) -> Unit,
    onBack: () -> Unit,
) {
    var showConfirmDialog by remember { mutableStateOf<Triple<String, Int, List<ManifestEntry>>?>(null) }

    showConfirmDialog?.let { (folder, count, entries) ->
        val size = entries.sumOf { it.fileSize }
        AlertDialog(
            onDismissRequest = { showConfirmDialog = null },
            title = { Text("Delete $count files?") },
            text = {
                Text("This will delete the $count oldest backed-up files from $folder on your phone, freeing up ${formatBytes(size)}.\n\nThese files are safely on your USB drive.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = null
                    onDeleteOldest(folder, count, entries)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Free Up Space — $groupName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Loading manifest...")
                }
            }
        } else if (folders.isEmpty() || folders.all { it.entries.isEmpty() }) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No backed-up files to clear.\nRun a backup first.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(folders, key = { it.phoneFolder }) { folder ->
                    FolderClearCard(
                        folder = folder,
                        onDeleteOldest = { count, entries ->
                            showConfirmDialog = Triple(folder.phoneFolder, count, entries)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FolderClearCard(
    folder: FolderClearInfo,
    onDeleteOldest: (count: Int, entries: List<ManifestEntry>) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with folder name and backup status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (folder.hasSuccessfulBackup) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (folder.hasSuccessfulBackup)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    folder.phoneFolder,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "${folder.entries.size} files backed up · ${formatBytes(folder.totalSize)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!folder.hasSuccessfulBackup) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last backup had errors — files may not be fully safe",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (folder.entries.isNotEmpty() && folder.hasSuccessfulBackup) {
                Spacer(Modifier.height(12.dp))

                // Delete oldest N options
                val options = buildList {
                    if (folder.entries.size >= 100) add(100)
                    if (folder.entries.size >= 500) add(500)
                    add(folder.entries.size) // "All"
                }.distinct()

                for (count in options) {
                    val entriesToDelete = folder.entries.take(count)
                    val size = entriesToDelete.sumOf { it.fileSize }
                    val label = if (count == folder.entries.size) {
                        "Delete all ${folder.entries.size} files — free ${formatBytes(size)}"
                    } else {
                        "Delete oldest $count files — free ${formatBytes(size)}"
                    }

                    OutlinedButton(
                        onClick = { onDeleteOldest(count, entriesToDelete) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(label, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
