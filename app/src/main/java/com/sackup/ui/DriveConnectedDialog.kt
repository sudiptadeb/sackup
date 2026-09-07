package com.sackup.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dialog title when the drive is plugged in. */
internal fun connectPromptTitle(interrupted: Boolean): String =
    if (interrupted) "Welcome back" else "USB drive connected"

/** Dialog body: offer to continue an interrupted run, or to back up everything. */
internal fun connectPromptBody(interrupted: Boolean, groupCount: Int): String = when {
    interrupted -> "Your last backup was interrupted. Continue where it left off?"
    groupCount == 1 -> "Back up everything now? (1 backup)"
    else -> "Back up everything now? ($groupCount backups)"
}

/** Primary button label. */
internal fun connectPromptButton(interrupted: Boolean): String =
    if (interrupted) "Continue" else "Back up now"

/**
 * Shown when the USB drive is plugged in and the on-connect setting is "Ask me".
 */
@Composable
fun DriveConnectedDialog(
    interrupted: Boolean,
    groupCount: Int,
    driveName: String,
    onBackUp: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                connectPromptTitle(interrupted),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    connectPromptBody(interrupted, groupCount),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (driveName.isNotEmpty()) {
                    Text(
                        "Drive: $driveName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onBackUp,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) {
                Text(connectPromptButton(interrupted), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) {
                Text("Not now", fontSize = 16.sp)
            }
        }
    )
}
