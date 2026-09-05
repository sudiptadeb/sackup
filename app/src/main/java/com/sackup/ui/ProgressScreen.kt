package com.sackup.ui

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sackup.service.BackupOutcome
import com.sackup.service.BackupPhase
import com.sackup.service.BackupProgress
import com.sackup.service.BackupService
import com.sackup.ui.theme.findActivity
import com.sackup.util.formatBytes
import com.sackup.util.formatDuration
import kotlinx.coroutines.delay

private const val MAX_INLINE_FAILED = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress: BackupProgress by BackupService.progress.collectAsState()
    val context = LocalContext.current

    // Keep the screen on only while a backup is running.
    DisposableEffect(progress.isRunning) {
        val window = context.findActivity()?.window
        if (progress.isRunning) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 1-second ticker for the elapsed-time line, only while running.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.isRunning) {
        while (progress.isRunning) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                progress.groupName.ifEmpty { "Backup" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            when {
                progress.isRunning -> RunningContent(progress = progress, now = now, onCancel = onCancel)
                progress.isDone -> DoneContent(progress = progress, onBack = onBack)
                else -> {
                    Text(
                        "No backup is running right now.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    ) {
                        Text("Go Back", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningContent(progress: BackupProgress, now: Long, onCancel: () -> Unit) {
    when (progress.phase) {
        BackupPhase.SCANNING -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(12.dp))
            Text(
                "Looking for new files…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (progress.statusText.isNotEmpty()) {
                Text(
                    progress.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        BackupPhase.FINISHING -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(12.dp))
            Text(
                "Almost done…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        else -> {
            // COPYING
            val current = (progress.completedFiles + 1).coerceAtMost(progress.totalFiles.coerceAtLeast(1))
            Text(
                "Copying file $current of ${progress.totalFiles}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (progress.statusText.isNotEmpty()) {
                Text(
                    progress.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(12.dp),
            )
            Text(
                "${progress.percent}%",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "${formatBytes(progress.copiedBytes)} of ${formatBytes(progress.totalBytes)}",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }

    // Details
    val hasDetails = progress.bytesPerSecond > 0 || progress.startTimeMillis > 0 ||
        progress.skippedFiles > 0 || progress.failedFiles > 0
    if (hasDetails) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Details",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (progress.bytesPerSecond > 0) {
                    StatRow("Speed", "${formatBytes(progress.bytesPerSecond)}/s")
                    val remaining = progress.totalBytes - progress.copiedBytes
                    if (remaining > 0) {
                        StatRow("Time left", "about ${formatDuration(remaining * 1000 / progress.bytesPerSecond)}")
                    }
                }
                if (progress.startTimeMillis > 0) {
                    StatRow("Elapsed", formatDuration((now - progress.startTimeMillis).coerceAtLeast(0)))
                }
                if (progress.skippedFiles > 0) StatRow("Already on drive", "${progress.skippedFiles}")
                if (progress.failedFiles > 0) StatRow("Could not copy", "${progress.failedFiles}")
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text("Stop Backup", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun DoneContent(progress: BackupProgress, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val copied = progress.copiedCount
    val failed = progress.failedFiles

    val headline: String
    val sentence: String
    val headlineColor = when (progress.outcome) {
        BackupOutcome.SUCCESS -> MaterialTheme.colorScheme.primary
        BackupOutcome.PARTIAL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    when (progress.outcome) {
        BackupOutcome.SUCCESS -> {
            headline = "All done"
            sentence = if (copied == 0 && failed == 0)
                "Everything was already on the drive."
            else
                "$copied files are now safely on your USB drive (${formatBytes(progress.copiedBytes)})"
        }
        BackupOutcome.PARTIAL -> {
            headline = "$failed files could not be copied"
            sentence = if (copied > 0)
                "$copied files are safely on the drive (${formatBytes(progress.copiedBytes)}). Try running the backup again for the rest."
            else
                "Nothing was copied this time. Try running the backup again."
        }
        BackupOutcome.CANCELLED -> {
            headline = "Backup stopped"
            sentence = if (copied > 0)
                "$copied files were copied before stopping. Run the backup again to finish."
            else
                "You can run the backup again whenever you like."
        }
        else -> {
            headline = "Something went wrong"
            sentence = progress.errorMessage.ifEmpty { "The backup could not run. Check the drive and try again." }
        }
    }

    Icon(
        when (progress.outcome) {
            BackupOutcome.SUCCESS -> Icons.Default.CheckCircle
            BackupOutcome.PARTIAL, BackupOutcome.CANCELLED -> Icons.Default.Warning
            else -> Icons.Default.ErrorOutline
        },
        contentDescription = null,
        tint = headlineColor,
        modifier = Modifier.size(64.dp)
    )
    Text(
        headline,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = headlineColor,
        textAlign = TextAlign.Center
    )
    Text(
        sentence,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )

    // Failed list (capped)
    if (progress.failedFilesList.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Files that could not be copied", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(progress.failedFilesList.joinToString("\n")))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy list of failed files")
                    }
                }
                for (err in progress.failedFilesList.take(MAX_INLINE_FAILED)) {
                    Text("• $err", style = MaterialTheme.typography.bodySmall)
                }
                val more = progress.failedFilesList.size - MAX_INLINE_FAILED
                if (more > 0) {
                    Text(
                        "+$more more (see History)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Details
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Details",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatRow("Files copied", "$copied")
            StatRow("Data copied", formatBytes(progress.copiedBytes))
            if (progress.skippedFiles > 0) StatRow("Already on drive", "${progress.skippedFiles}")
            if (failed > 0) StatRow("Could not copy", "$failed")
            if (progress.startTimeMillis > 0 && progress.endTimeMillis > progress.startTimeMillis) {
                val elapsed = progress.endTimeMillis - progress.startTimeMillis
                StatRow("Time taken", formatDuration(elapsed))
                if (progress.copiedBytes > 0) {
                    StatRow("Average speed", "${formatBytes(progress.copiedBytes * 1000 / elapsed)}/s")
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    ) {
        Text("Done", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, fontWeight = FontWeight.Bold)
    }
}
