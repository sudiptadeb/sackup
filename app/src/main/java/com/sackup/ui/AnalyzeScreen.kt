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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sackup.service.FolderDiff
import com.sackup.util.formatBytes

data class AnalyzeSummary(
    val groupName: String,
    val folders: List<FolderDiff>,
    val driveConnected: Boolean,
    val totalToCopy: Int,
    val totalToCopySize: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeScreen(
    summary: AnalyzeSummary?,
    isLoading: Boolean,
    onSyncNow: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analyze — ${summary?.groupName ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (summary != null && !isLoading) {
                        IconButton(onClick = onExport) {
                            Icon(Icons.Default.Share, contentDescription = "Export report")
                        }
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
                    Text("Scanning phone and drive...")
                }
            }
        } else if (summary == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Could not load group data.")
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
                // Overall summary card
                item {
                    val totalNotBacked = summary.folders.sumOf { it.toCopy }
                    val totalNotBackedSize = summary.folders.sumOf { it.toCopySize }
                    val totalBacked = summary.folders.sumOf { it.alreadyOnDrive }
                    val totalBackedSize = summary.folders.sumOf { it.alreadyOnDriveSize }
                    val totalDriveOnly = summary.folders.sumOf { it.onDriveOnly }
                    val totalDriveOnlySize = summary.folders.sumOf { it.onDriveOnlySize }

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

                            if (!summary.driveConnected) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Drive not connected — showing manifest data only",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            AnalyzeStatRow(
                                icon = Icons.Default.Warning,
                                iconTint = Color(0xFFFF9800),
                                label = "Not backed up",
                                count = totalNotBacked,
                                size = totalNotBackedSize
                            )
                            AnalyzeStatRow(
                                icon = Icons.Default.CheckCircle,
                                iconTint = MaterialTheme.colorScheme.primary,
                                label = "Backed up (on both)",
                                count = totalBacked,
                                size = totalBackedSize
                            )
                            if (totalDriveOnly > 0) {
                                AnalyzeStatRow(
                                    icon = Icons.Default.Info,
                                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    label = "On drive only (deleted from phone)",
                                    count = totalDriveOnly,
                                    size = totalDriveOnlySize
                                )
                            }
                        }
                    }
                }

                // Sync Now button
                if (summary.totalToCopy > 0 && summary.driveConnected) {
                    item {
                        Button(
                            onClick = onSyncNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text(
                                "Sync Now — ${summary.totalToCopy} files (${formatBytes(summary.totalToCopySize)})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                } else if (summary.totalToCopy == 0 && summary.driveConnected) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, null,
                                    tint = MaterialTheme.colorScheme.primary)
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

                // Per-folder breakdown
                items(summary.folders, key = { it.phoneFolder }) { result ->
                    AnalyzeFolderCard(result)
                }
            }
        }
    }
}

@Composable
fun AnalyzeStatRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            "$count files · ${formatBytes(size)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
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
                fontWeight = FontWeight.Bold
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
                    iconTint = Color(0xFFFF9800),
                    label = "Not backed up",
                    count = result.toCopy,
                    size = result.toCopySize
                )
            }
            if (result.alreadyOnDrive > 0) {
                AnalyzeStatRow(
                    icon = Icons.Default.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                    label = "Backed up",
                    count = result.alreadyOnDrive,
                    size = result.alreadyOnDriveSize
                )
            }
            if (result.onDriveOnly > 0) {
                AnalyzeStatRow(
                    icon = Icons.Default.Info,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Deleted from phone",
                    count = result.onDriveOnly,
                    size = result.onDriveOnlySize
                )
            }
            if (result.toCopy == 0 && result.totalOnPhone > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Fully backed up",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
