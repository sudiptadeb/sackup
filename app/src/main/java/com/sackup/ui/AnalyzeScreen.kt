package com.sackup.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sackup.service.FolderDiff
import com.sackup.util.formatBytes
import com.sackup.util.formatDuration

data class AnalyzeSummary(
    val groupName: String,
    val folders: List<FolderDiff>,
    val driveConnected: Boolean,
    val totalToCopy: Int,
    val totalToCopySize: Long,
    val scanDurationSeconds: Long = 0
)

private data class AnalyzeTotals(
    val notBacked: Int,
    val notBackedSize: Long,
    val backed: Int,
    val backedSize: Long,
    val driveOnly: Int,
    val driveOnlySize: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeScreen(
    summary: AnalyzeSummary?,
    isLoading: Boolean,
    scanStatus: String = "",
    scanElapsedSeconds: Long = 0,
    onSyncNow: () -> Unit,
    onExport: () -> Unit,
    onCancelScan: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check what's backed up", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (summary != null && !isLoading) {
                        IconButton(onClick = onExport) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        scanStatus.ifEmpty { "Checking your files…" },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        formatDuration(scanElapsedSeconds * 1000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = onCancelScan,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Cancel") }
                }
            }
        } else if (summary == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "This backup no longer exists.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Go Back") }
                }
            }
        } else {
            val totals = remember(summary) {
                AnalyzeTotals(
                    notBacked = summary.folders.sumOf { it.toCopy },
                    notBackedSize = summary.folders.sumOf { it.toCopySize },
                    backed = summary.folders.sumOf { it.alreadyOnDrive },
                    backedSize = summary.folders.sumOf { it.alreadyOnDriveSize },
                    driveOnly = summary.folders.sumOf { it.onDriveOnly },
                    driveOnlySize = summary.folders.sumOf { it.onDriveOnlySize },
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item(key = "title") {
                    Text(
                        summary.groupName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                item(key = "summary") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Summary",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))

                            if (summary.scanDurationSeconds > 0) {
                                Text(
                                    "Checked in ${formatDuration(summary.scanDurationSeconds * 1000)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            if (!summary.driveConnected) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Warning, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Drive not plugged in — showing what was backed up last time",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            AnalyzeStatRow(
                                icon = Icons.Default.Warning,
                                iconTint = MaterialTheme.colorScheme.tertiary,
                                label = "Not backed up yet",
                                count = totals.notBacked,
                                size = totals.notBackedSize
                            )
                            AnalyzeStatRow(
                                icon = Icons.Default.CheckCircle,
                                iconTint = MaterialTheme.colorScheme.primary,
                                label = "Safe on the drive",
                                count = totals.backed,
                                size = totals.backedSize
                            )
                            if (totals.driveOnly > 0) {
                                AnalyzeStatRow(
                                    icon = Icons.Default.Info,
                                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    label = "Only on the drive (removed from phone)",
                                    count = totals.driveOnly,
                                    size = totals.driveOnlySize
                                )
                            }
                        }
                    }
                }

                if (summary.totalToCopy > 0 && summary.driveConnected) {
                    item(key = "sync") {
                        Button(
                            onClick = onSyncNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        ) {
                            Text(
                                "Back up these ${summary.totalToCopy} files (${formatBytes(summary.totalToCopySize)})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else if (summary.totalToCopy == 0 && summary.driveConnected) {
                    item(key = "all-good") {
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
                                Text(
                                    "Everything is backed up!",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }

                items(summary.folders, key = { it.phoneFolder }) { result ->
                    AnalyzeFolderCard(result)
                }
            }
        }
    }
}

@Composable
fun AnalyzeStatRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    count: Int,
    size: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            "$count files · ${formatBytes(size)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun AnalyzeFolderCard(result: FolderDiff) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                result.phoneFolder,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("On phone: ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${result.totalOnPhone} files", fontWeight = FontWeight.Bold)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("On drive: ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${result.totalOnDrive} files", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))

            if (result.toCopy > 0) {
                AnalyzeStatRow(
                    icon = Icons.Default.Warning,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    label = "Not backed up yet",
                    count = result.toCopy,
                    size = result.toCopySize
                )
            }
            if (result.alreadyOnDrive > 0) {
                AnalyzeStatRow(
                    icon = Icons.Default.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                    label = "Safe on the drive",
                    count = result.alreadyOnDrive,
                    size = result.alreadyOnDriveSize
                )
            }
            if (result.onDriveOnly > 0) {
                AnalyzeStatRow(
                    icon = Icons.Default.Info,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Removed from phone",
                    count = result.onDriveOnly,
                    size = result.onDriveOnlySize
                )
            }
            if (result.toCopy == 0 && result.totalOnPhone > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "All safe on the drive",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
