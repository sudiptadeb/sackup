package com.sackup.ui

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
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
    isEdit: Boolean = false,
    onSave: (name: String, phoneFolders: List<String>) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var phoneFolders by remember { mutableStateOf(initialPhoneFolders.toMutableList().ifEmpty { mutableListOf() }) }

    // SAF folder picker — extract relative path from internal storage
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val relativePath = extractRelativePath(uri)
            if (relativePath != null && relativePath !in phoneFolders) {
                phoneFolders = (phoneFolders + relativePath).toMutableList()
            }
        }
    }

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

            // Phone folders
            Text(
                "Phone Folders",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Select folders from your phone to back up",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            for (i in phoneFolders.indices) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            phoneFolders[i],
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        IconButton(onClick = {
                            phoneFolders = phoneFolders.toMutableList().apply { removeAt(i) }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove")
                        }
                    }
                }
            }

            // Initial URI pointing to internal storage root
            val storageUri = Uri.Builder()
                .scheme("content")
                .authority("com.android.externalstorage.documents")
                .appendPath("document")
                .appendPath("primary:")
                .build()

            OutlinedButton(
                onClick = { folderPickerLauncher.launch(storageUri) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Select Phone Folder")
            }

            Spacer(Modifier.height(16.dp))

            // Save button
            Button(
                onClick = {
                    val folders = phoneFolders.filter { it.isNotBlank() }
                    if (name.isNotBlank() && folders.isNotEmpty()) {
                        onSave(name, folders)
                    }
                },
                enabled = name.isNotBlank() && phoneFolders.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Extracts the relative path from a SAF document tree URI.
 * e.g. content://com.android.externalstorage.documents/tree/primary%3ADCIM → "DCIM"
 * e.g. content://com.android.externalstorage.documents/tree/primary%3AWhatsApp%2FMedia → "WhatsApp/Media"
 */
private fun extractRelativePath(uri: Uri): String? {
    val treeId = uri.lastPathSegment ?: return null
    // Format is "primary:relative/path" for internal storage
    val colonIndex = treeId.indexOf(':')
    if (colonIndex < 0) return null
    val path = treeId.substring(colonIndex + 1)
    return path.ifBlank { null }
}
