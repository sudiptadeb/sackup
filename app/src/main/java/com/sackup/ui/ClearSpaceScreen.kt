package com.sackup.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sackup.data.ManifestEntry
import com.sackup.ui.theme.onScrim
import com.sackup.util.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Hoisted out of composition: SimpleDateFormat is expensive to build.
private fun fileDateFormat() = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
private fun backupDateFormat() = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

data class FolderClearInfo(
    val phoneFolder: String,
    val entries: List<ManifestEntry>,  // sorted oldest first
    val totalSize: Long,
    val hasSuccessfulBackup: Boolean,  // true when there are confirmed backed-up files
    val drivePath: String = ""         // e.g. "Neha's Backup/DCIM"
)

/** One plain-language explanation used by every delete confirmation on this screen. */
private fun safetyNote(lastBackupTime: Long, driveConnected: Boolean): String {
    val base = if (lastBackupTime > 0)
        "These files were on your USB drive when you last backed up on ${backupDateFormat().format(Date(lastBackupTime))}."
    else
        "These files were on your USB drive when you last backed up."
    return if (driveConnected) base
    else "$base\n\nPlug the drive in and run a backup first if you want to be extra sure."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearSpaceScreen(
    groupName: String,
    folders: List<FolderClearInfo>,
    isLoading: Boolean,
    loadingStatus: String = "",
    resultMessage: String = "",
    lastBackupTime: Long = 0L,
    driveConnected: Boolean = false,
    onDeleteEntries: (List<ManifestEntry>) -> Unit,
    fileUris: Map<Long, Uri>,  // manifest entry id → content URI (pre-resolved)
    onBack: () -> Unit,
) {
    // Which sub-screen: null = folder list, non-null = viewing files in that folder
    var viewingFolder by remember { mutableStateOf<FolderClearInfo?>(null) }

    val current = viewingFolder
    if (current != null) {
        FileViewerScreen(
            folder = current,
            safetyNote = safetyNote(lastBackupTime, driveConnected),
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
            resultMessage = resultMessage,
            safetyNote = safetyNote(lastBackupTime, driveConnected),
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
    loadingStatus: String,
    resultMessage: String,
    safetyNote: String,
    onViewFolder: (FolderClearInfo) -> Unit,
    onQuickDelete: (List<ManifestEntry>) -> Unit,
    onBack: () -> Unit,
) {
    var showConfirmDialog by remember { mutableStateOf<Pair<String, List<ManifestEntry>>?>(null) }

    showConfirmDialog?.let { (label, entries) ->
        val size = remember(entries) { entries.sumOf { it.fileSize } }
        AlertDialog(
            onDismissRequest = { showConfirmDialog = null },
            title = { Text("Remove ${entries.size} files from the phone?") },
            text = {
                Text("This removes $label from your phone and frees up ${formatBytes(size)}.\n\n$safetyNote")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = null
                        onQuickDelete(entries)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Free Up Space", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        loadingStatus.ifEmpty { "Finding your backed-up files…" },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item(key = "title") {
                    Text(
                        groupName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (resultMessage.isNotEmpty()) {
                    item(key = "result") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text(resultMessage, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                if (folders.isEmpty() || folders.all { it.entries.isEmpty() }) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Nothing to free up yet.\nRun a backup first, then come back here.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
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
}

@Composable
private fun FolderClearCard(
    folder: FolderClearInfo,
    onViewFiles: () -> Unit,
    onQuickDelete: (label: String, entries: List<ManifestEntry>) -> Unit,
) {
    val hasFiles = folder.entries.isNotEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (hasFiles) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (hasFiles) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    folder.phoneFolder,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (folder.drivePath.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "On the drive at: ${folder.drivePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(4.dp))
            if (hasFiles) {
                Text(
                    "${folder.entries.size} files safe on the drive · ${formatBytes(folder.totalSize)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = onViewFiles,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("View & Select Files")
                }

                Spacer(Modifier.height(8.dp))

                // Quick delete options
                val options = remember(folder.entries.size) {
                    buildList {
                        if (folder.entries.size >= 100) add(100)
                        if (folder.entries.size >= 500) add(500)
                        add(folder.entries.size)
                    }.distinct()
                }

                for (count in options) {
                    val batch = folder.entries.take(count)
                    val size = remember(batch) { batch.sumOf { it.fileSize } }
                    val isAll = count == folder.entries.size
                    val label = if (isAll) "all ${folder.entries.size} files" else "the oldest $count files"
                    val buttonLabel = if (isAll) "Remove all — free ${formatBytes(size)}"
                                      else "Remove oldest $count — free ${formatBytes(size)}"

                    OutlinedButton(
                        onClick = { onQuickDelete(label, batch) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(buttonLabel, fontSize = 14.sp, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(4.dp))
                }
            } else {
                Text(
                    "Nothing to free up here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── File Viewer with thumbnail grid ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerScreen(
    folder: FolderClearInfo,
    safetyNote: String,
    onDelete: (List<ManifestEntry>) -> Unit,
    fileUris: Map<Long, Uri>,
    onBack: () -> Unit,
) {
    val entries = folder.entries
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) } // nothing selected by default
    var quickPick by remember { mutableStateOf<Int?>(null) }              // which "Oldest N" chip is active
    var isGrid by remember { mutableStateOf(true) }
    var showConfirm by remember { mutableStateOf(false) }

    val selectedEntries = remember(selectedIds, entries) { entries.filter { it.id in selectedIds } }
    val selectedSize = remember(selectedEntries) { selectedEntries.sumOf { it.fileSize } }

    fun setSelection(ids: Set<Long>, pick: Int? = null) {
        selectedIds = ids
        quickPick = pick
    }
    fun toggleEntry(entry: ManifestEntry) {
        setSelection(if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id)
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Remove ${selectedEntries.size} files from the phone?") },
            text = {
                Text("This frees up ${formatBytes(selectedSize)}.\n\n$safetyNote")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        onDelete(selectedEntries)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folder.phoneFolder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isGrid = !isGrid }) {
                        if (isGrid) {
                            Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Show as list")
                        } else {
                            Icon(Icons.Default.GridView, contentDescription = "Show as grid")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${selectedEntries.size} of ${entries.size} selected · ${formatBytes(selectedSize)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Row {
                            TextButton(onClick = { setSelection(entries.map { it.id }.toSet()) }) { Text("All") }
                            TextButton(onClick = { setSelection(emptySet()) }) { Text("None") }
                        }
                    }

                    val quickOptions = remember(entries.size) {
                        buildList {
                            if (entries.size >= 100) add(100)
                            if (entries.size >= 500) add(500)
                        }
                    }
                    if (quickOptions.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (n in quickOptions) {
                                FilterChip(
                                    selected = quickPick == n,
                                    onClick = {
                                        if (quickPick == n) setSelection(emptySet())
                                        else setSelection(entries.take(n).map { it.id }.toSet(), n)
                                    },
                                    label = { Text("Oldest $n") }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { showConfirm = true },
                        enabled = selectedEntries.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(
                            if (selectedEntries.isEmpty()) "Tap files to select them"
                            else "Remove ${selectedEntries.size} files — free ${formatBytes(selectedSize)}",
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (isGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    ThumbnailGridItem(
                        entry = entry,
                        uri = fileUris[entry.id],
                        isSelected = entry.id in selectedIds,
                        onToggle = { toggleEntry(entry) }
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
                    FileListItem(
                        entry = entry,
                        uri = fileUris[entry.id],
                        isSelected = entry.id in selectedIds,
                        onToggle = { toggleEntry(entry) }
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
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle() })
    ) {
        if (uri != null && isImageOrVideo(entry.fileName)) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(uri).crossfade(false).size(150).build(),
                contentDescription = entry.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = entry.fileName,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        entry.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            )
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
            )
        }

        // File size label on a dark scrim so it is readable over any photo
        Text(
            formatBytes(entry.fileSize),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onScrim,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FileListItem(
    entry: ManifestEntry,
    uri: Uri?,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val dateStr = remember(entry.dateModified) {
        if (entry.dateModified > 0) fileDateFormat().format(Date(entry.dateModified * 1000)) else ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle() }),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.padding(8.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(entry.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (dateStr.isEmpty()) formatBytes(entry.fileSize) else "$dateStr · ${formatBytes(entry.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Checkbox(checked = isSelected, onCheckedChange = null)
        }
    }
}

private val MEDIA_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp",
    "mp4", "3gp", "mkv", "mov", "avi", "webm"
)

private fun isImageOrVideo(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS
