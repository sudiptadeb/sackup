package com.sackup.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sackup.data.ManifestEntry
import com.sackup.util.formatBytes
import java.text.SimpleDateFormat
import java.util.*

data class FolderClearInfo(
    val phoneFolder: String,
    val entries: List<ManifestEntry>,  // sorted oldest first
    val totalSize: Long,
    val hasSuccessfulBackup: Boolean,
    val drivePath: String = ""       // e.g. "Neha's Backup/DCIM"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearSpaceScreen(
    groupName: String,
    folders: List<FolderClearInfo>,
    isLoading: Boolean,
    loadingStatus: String = "",
    onDeleteEntries: (entries: List<ManifestEntry>) -> Unit,
    fileUris: Map<Long, Uri>,  // manifest entry id → content URI (pre-resolved)
    onBack: () -> Unit,
) {
    // Which sub-screen: null = folder list, non-null = viewing files in that folder
    var viewingFolder by remember { mutableStateOf<FolderClearInfo?>(null) }

    if (viewingFolder != null) {
        FileViewerScreen(
            folder = viewingFolder!!,
            onDelete = { entries ->
                onDeleteEntries(entries)
                viewingFolder = null
            },
            fileUris = fileUris,
            onBack = { viewingFolder = null }
        )
    } else {
        FolderListScreen(
            groupName = groupName,
            folders = folders,
            isLoading = isLoading,
            loadingStatus = loadingStatus,
            onViewFolder = { viewingFolder = it },
            onQuickDelete = { entries -> onDeleteEntries(entries) },
            onBack = onBack
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderListScreen(
    groupName: String,
    folders: List<FolderClearInfo>,
    isLoading: Boolean,
    loadingStatus: String = "",
    onViewFolder: (FolderClearInfo) -> Unit,
    onQuickDelete: (List<ManifestEntry>) -> Unit,
    onBack: () -> Unit,
) {
    var showConfirmDialog by remember { mutableStateOf<Pair<String, List<ManifestEntry>>?>(null) }

    showConfirmDialog?.let { (label, entries) ->
        val size = entries.sumOf { it.fileSize }
        AlertDialog(
            onDismissRequest = { showConfirmDialog = null },
            title = { Text("Delete ${entries.size} files?") },
            text = {
                Text("This will delete $label from your phone, freeing up ${formatBytes(size)}.\n\nThese files are safely on your USB drive.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = null
                    onQuickDelete(entries)
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
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(loadingStatus.ifEmpty { "Loading manifest..." })
                }
            }
        } else if (folders.isEmpty() || folders.all { it.entries.isEmpty() }) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No backed-up files to clear.\nRun a backup first.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(folders, key = { it.phoneFolder }) { folder ->
                    FolderClearCard(
                        folder = folder,
                        onViewFiles = { onViewFolder(folder) },
                        onQuickDelete = { label, entries ->
                            showConfirmDialog = Pair(label, entries)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderClearCard(
    folder: FolderClearInfo,
    onViewFiles: () -> Unit,
    onQuickDelete: (label: String, entries: List<ManifestEntry>) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (folder.hasSuccessfulBackup) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (folder.hasSuccessfulBackup) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(folder.phoneFolder, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (folder.drivePath.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Backed up to: ${folder.drivePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "${folder.entries.size} files backed up · ${formatBytes(folder.totalSize)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!folder.hasSuccessfulBackup) {
                Spacer(Modifier.height(4.dp))
                Text("Last backup had errors — files may not be fully safe",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (folder.entries.isNotEmpty() && folder.hasSuccessfulBackup) {
                Spacer(Modifier.height(12.dp))

                // View & Select button
                Button(
                    onClick = onViewFiles,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("View & Select Files")
                }

                Spacer(Modifier.height(8.dp))

                // Quick delete options
                val options = buildList {
                    if (folder.entries.size >= 100) add(100)
                    if (folder.entries.size >= 500) add(500)
                    add(folder.entries.size)
                }.distinct()

                for (count in options) {
                    val batch = folder.entries.take(count)
                    val size = batch.sumOf { it.fileSize }
                    val label = if (count == folder.entries.size) "all ${folder.entries.size} oldest files"
                                else "oldest $count files"
                    val buttonLabel = if (count == folder.entries.size) "Delete all — free ${formatBytes(size)}"
                                      else "Delete oldest $count — free ${formatBytes(size)}"

                    OutlinedButton(
                        onClick = { onQuickDelete(label, batch) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(buttonLabel, fontSize = 14.sp) }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ── File Viewer with thumbnail grid ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerScreen(
    folder: FolderClearInfo,
    onDelete: (List<ManifestEntry>) -> Unit,
    fileUris: Map<Long, Uri>,
    onBack: () -> Unit,
) {
    val entries = folder.entries
    var selected by remember { mutableStateOf(entries.toSet()) } // all selected by default
    var isGrid by remember { mutableStateOf(true) }
    var showConfirm by remember { mutableStateOf(false) }

    val selectedSize = selected.sumOf { it.fileSize }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete ${selected.size} files?") },
            text = {
                Text("Free up ${formatBytes(selectedSize)}.\nThese files are safely on your USB drive.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onDelete(selected.toList())
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folder.phoneFolder) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Grid/List toggle
                    IconButton(onClick = { isGrid = !isGrid }) {
                        Icon(
                            if (isGrid) Icons.Default.Menu else Icons.Default.Star,
                            contentDescription = if (isGrid) "List view" else "Grid view"
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Selection summary
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${selected.size} of ${entries.size} selected · ${formatBytes(selectedSize)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row {
                            TextButton(onClick = { selected = entries.toSet() }) { Text("All") }
                            TextButton(onClick = { selected = emptySet() }) { Text("None") }
                        }
                    }

                    // Quick select options
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val quickOptions = buildList {
                            if (entries.size >= 100) add(100)
                            if (entries.size >= 500) add(500)
                        }
                        for (n in quickOptions) {
                            FilterChip(
                                selected = selected.size == n,
                                onClick = { selected = entries.take(n).toSet() },
                                label = { Text("Oldest $n") }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Delete button
                    Button(
                        onClick = { showConfirm = true },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            "Delete ${selected.size} Files — Free ${formatBytes(selectedSize)}",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (isGrid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    val isSelected = entry in selected
                    val uri = fileUris[entry.id]
                    ThumbnailGridItem(
                        entry = entry,
                        uri = uri,
                        isSelected = isSelected,
                        onClick = {
                            selected = if (isSelected) selected - entry else selected + entry
                        }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    val isSelected = entry in selected
                    val uri = fileUris[entry.id]
                    FileListItem(
                        entry = entry,
                        uri = uri,
                        isSelected = isSelected,
                        onClick = {
                            selected = if (isSelected) selected - entry else selected + entry
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailGridItem(
    entry: ManifestEntry,
    uri: Uri?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
    ) {
        if (uri != null && isImageOrVideo(entry.fileName)) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(uri).crossfade(false).size(150).build(),
                contentDescription = entry.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Non-image placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        entry.fileName.takeLast(12),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Selection overlay
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            )
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
            )
        }

        // File size label
        Text(
            formatBytes(entry.fileSize),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FileListItem(
    entry: ManifestEntry,
    uri: Uri?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val dateStr = if (entry.dateModified > 0) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(entry.dateModified * 1000))
    } else ""

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small)) {
                if (uri != null && isImageOrVideo(entry.fileName)) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(uri).crossfade(false).size(80).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(entry.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Text("$dateStr · ${formatBytes(entry.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Checkbox(checked = isSelected, onCheckedChange = { onClick() })
        }
    }
}

private fun isImageOrVideo(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp",
                        "mp4", "3gp", "mkv", "mov", "avi", "webm")
}
