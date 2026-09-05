package com.sackup.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sackup.data.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Hoisted out of composition: SimpleDateFormat is expensive to build.
private fun copyTimeFormat() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
private fun sessionDateFormat() = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
private fun rowTimeFormat() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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

    // Sessions stay newest-first (as the DB returns them); entries inside each
    // session are flipped to oldest-first so they read top-to-bottom.
    val sessions = remember(logs) {
        logs.groupBy { it.sessionId }.map { (sessionId, entries) ->
            SessionLogs(
                sessionId = sessionId,
                entries = entries.sortedWith(compareBy({ it.timestamp }, { it.id }))
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all history?") },
            text = { Text("This removes the history list only. Nothing on your phone or USB drive is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearLogs()
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = {
                            val text = sessions.joinToString("\n\n") { session ->
                                session.entries.joinToString("\n") { entry ->
                                    val time = copyTimeFormat().format(Date(entry.timestamp))
                                    "[$time] [${entry.level}] ${entry.groupName}: ${entry.message}"
                                }
                            }
                            clipboard.setText(AnnotatedString(text))
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy all history")
                        }
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear history")
                        }
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
                    "Nothing here yet.\nRun a backup and it will show up here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (session in sessions) {
                    val first = session.entries.firstOrNull() ?: continue
                    val sessionDate = sessionDateFormat().format(Date(first.timestamp))

                    item(key = "header_${session.sessionId}") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    "Backup on $sessionDate",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (first.groupName.isNotEmpty()) {
                                    Text(
                                        first.groupName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    items(session.entries, key = { it.id }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

private data class SessionLogs(val sessionId: String, val entries: List<LogEntry>)

@Composable
fun LogEntryRow(entry: LogEntry) {
    val time = remember(entry.timestamp) { rowTimeFormat().format(Date(entry.timestamp)) }

    val levelColor = when (entry.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
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
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
