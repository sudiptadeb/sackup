package com.sackup.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sackup.service.BackupService
import com.sackup.util.formatBytes
import com.sackup.util.formatDuration
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    // Poll service state every 500ms for live updates
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }

    // Read volatile/atomic fields (tick forces recomposition)
    val isRunning = BackupService.isRunning.also { tick }
    val isDone = BackupService.isDone.also { tick }
    val groupName = BackupService.currentGroupName
    val currentFile = BackupService.currentFileName
    val phase = BackupService.currentPhase
    val total = BackupService.totalFiles
    val completed = BackupService.completedFiles
    val skipped = BackupService.skippedFiles
    val failed = BackupService.failedFiles
    val percent = BackupService.progressPercent
    val copiedBytes = BackupService.copiedBytes
    val totalBytes = BackupService.totalBytes
    val failedList = BackupService.failedFilesList
    val speed = BackupService.bytesPerSecond
    val startTime = BackupService.startTimeMillis

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup Progress") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group name
            Text(
                groupName.ifEmpty { "Backup" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            if (!isDone && isRunning) {
                // Phase 1: Scanning / Comparing (before copy starts)
                if (phase == "scanning" || phase == "comparing") {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                    )

                    val phaseText = when (phase) {
                        "scanning" -> "Scanning files..."
                        "comparing" -> "Comparing with drive..."
                        else -> "Preparing..."
                    }
                    Text(
                        phaseText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        "This won't take long",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Phase 2: Copying
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                    )

                    Text(
                        "$percent%",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Current file
                    if (currentFile.isNotEmpty()) {
                        Text(
                            currentFile,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Stats
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatRow("Files", "$completed / $total")
                            StatRow("Copied", "${formatBytes(copiedBytes)} / ${formatBytes(totalBytes)}")
                            if (speed > 0) {
                                StatRow("Speed", "${formatBytes(speed)}/s")
                                val remaining = totalBytes - copiedBytes
                                if (remaining > 0) {
                                    val etaMillis = remaining * 1000 / speed
                                    StatRow("Time remaining", "~${formatDuration(etaMillis)}")
                                }
                            }
                            if (startTime > 0) {
                                val elapsed = System.currentTimeMillis() - startTime
                                StatRow("Elapsed", formatDuration(elapsed))
                            }
                            if (skipped > 0) StatRow("Skipped (already on drive)", "$skipped")
                            if (failed > 0) StatRow("Failed", "$failed")
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Cancel button
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancel Backup", fontWeight = FontWeight.Bold)
                }

            } else if (isDone) {
                // Done state
                val copied = completed - skipped - failed
                Text(
                    "Done!",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatRow("Files copied", "$copied")
                        StatRow("Data copied", formatBytes(copiedBytes))
                        if (startTime > 0) {
                            val elapsed = System.currentTimeMillis() - startTime
                            StatRow("Time taken", formatDuration(elapsed))
                            if (elapsed > 0 && copiedBytes > 0) {
                                StatRow("Average speed", "${formatBytes(copiedBytes * 1000 / elapsed)}/s")
                            }
                        }
                        if (skipped > 0) StatRow("Already on drive", "$skipped")
                        if (failed > 0) StatRow("Failed", "$failed")
                    }
                }

                if (failedList.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Failed Files:", fontWeight = FontWeight.Bold)
                            for (err in failedList) {
                                Text("- $err", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            } else {
                // Not started
                Text(
                    "No backup in progress",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Button(onClick = onBack) {
                    Text("Go Back")
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}
