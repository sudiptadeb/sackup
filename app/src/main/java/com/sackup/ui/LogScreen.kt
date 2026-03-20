package com.sackup.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sackup.data.LogEntry
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    logs: List<LogEntry>,
    onBack: () -> Unit,
    onClearLogs: () -> Unit,
    onRefresh: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var showClearDialog by remember { mutableStateOf(false) }

    // Refresh on enter
    LaunchedEffect(Unit) { onRefresh() }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Logs?") },
            text = { Text("This will delete all log history.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearLogs()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Copy all logs
                    IconButton(onClick = {
                        val text = logs.joinToString("\n") { entry ->
                            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                                .format(Date(entry.timestamp))
                            "[$time] [${entry.level}] ${entry.groupName}: ${entry.message}"
                        }
                        clipboard.setText(AnnotatedString(text))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy all logs")
                    }
                    // Clear logs
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No logs yet.\nRun a backup to see logs here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Group logs by session
                    val grouped = logs.groupBy { it.sessionId }
                    val sessions = grouped.keys.toList()

                    for (sessionId in sessions) {
                        val sessionLogs = grouped[sessionId] ?: continue
                        val firstLog = sessionLogs.firstOrNull() ?: continue
                        val sessionDate = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                            .format(Date(firstLog.timestamp))

                        item(key = "header_$sessionId") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    "Session: $sessionDate",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }

                        items(sessionLogs, key = { it.id }) { entry ->
                            LogEntryRow(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryRow(entry: LogEntry) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        .format(Date(entry.timestamp))

    val levelColor = when (entry.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        Text(
            time,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            entry.level.padEnd(5),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = levelColor
        )
        Spacer(Modifier.width(8.dp))
        Text(
            entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
