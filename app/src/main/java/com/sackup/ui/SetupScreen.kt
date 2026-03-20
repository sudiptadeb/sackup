package com.sackup.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    initialName: String = "",
    initialPhoneFolders: List<String> = emptyList(),
    initialDriveFolder: String = "",
    isEdit: Boolean = false,
    onSave: (name: String, phoneFolders: List<String>, driveFolder: String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var driveFolder by remember { mutableStateOf(initialDriveFolder) }
    var phoneFolders by remember { mutableStateOf(initialPhoneFolders.toMutableList().ifEmpty { mutableListOf("") }) }
    var newFolder by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit Backup Group" else "New Backup Group") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Group Name") },
                placeholder = { Text("e.g. Camera, Downloads") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Drive folder
            OutlinedTextField(
                value = driveFolder,
                onValueChange = { driveFolder = it },
                label = { Text("Drive Folder Name") },
                placeholder = { Text("e.g. Camera-Backup") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("Files will be saved to this folder on the USB drive") }
            )

            // Phone folders
            Text(
                "Phone Folders",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Relative paths from internal storage (e.g. DCIM, Pictures, Download, WhatsApp/Media)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            for (i in phoneFolders.indices) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = phoneFolders[i],
                        onValueChange = { value ->
                            phoneFolders = phoneFolders.toMutableList().apply { set(i, value) }
                        },
                        placeholder = { Text("e.g. DCIM") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    if (phoneFolders.size > 1) {
                        IconButton(onClick = {
                            phoneFolders = phoneFolders.toMutableList().apply { removeAt(i) }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    phoneFolders = phoneFolders.toMutableList().apply { add("") }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Phone Folder")
            }

            Spacer(Modifier.height(16.dp))

            // Save button
            Button(
                onClick = {
                    val folders = phoneFolders.filter { it.isNotBlank() }
                    if (name.isNotBlank() && driveFolder.isNotBlank() && folders.isNotEmpty()) {
                        onSave(name, folders, driveFolder)
                    }
                },
                enabled = name.isNotBlank() && driveFolder.isNotBlank() && phoneFolders.any { it.isNotBlank() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        }
    }
}
