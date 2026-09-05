package com.sackup.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

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
    // Immutable list in state; every change replaces the whole value.
    var phoneFolders by remember { mutableStateOf<List<String>>(initialPhoneFolders.toList()) }
    var otherFolder by remember { mutableStateOf("") }

    fun isChosen(path: String) = phoneFolders.any { it.equals(path, ignoreCase = true) }
    fun toggle(path: String) {
        phoneFolders = if (isChosen(path))
            phoneFolders.filterNot { it.equals(path, ignoreCase = true) }
        else
            addFolder(phoneFolders, path)
    }
    fun addOther() {
        val updated = addFolder(phoneFolders, otherFolder)
        if (updated !== phoneFolders) phoneFolders = updated
        otherFolder = ""
    }

    val canSave = name.isNotBlank() && phoneFolders.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEdit) "Edit Backup" else "New Backup",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Backup name") },
                placeholder = { Text("e.g. Camera, Downloads") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text(
                "What to back up",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Tap the folders you want copied to the USB drive.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Common folder choices
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (folder in COMMON_PHONE_FOLDERS) {
                    FolderChoiceCard(
                        label = folder.label,
                        path = folder.path,
                        chosen = isChosen(folder.path),
                        onToggle = { toggle(folder.path) }
                    )
                }
            }

            // Folders the user typed that are not in the common list
            val customFolders = phoneFolders.filter { p ->
                COMMON_PHONE_FOLDERS.none { it.path.equals(p, ignoreCase = true) }
            }
            if (customFolders.isNotEmpty()) {
                Text(
                    "Other folders",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (path in customFolders) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    path,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(onClick = {
                                    phoneFolders = phoneFolders.filterNot { it == path }
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove $path")
                                }
                            }
                        }
                    }
                }
            }

            // "Other folder" entry
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = otherFolder,
                    onValueChange = { otherFolder = it },
                    label = { Text("Other folder") },
                    placeholder = { Text("e.g. Recordings") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addOther() })
                )
                FilledTonalButton(
                    onClick = { addOther() },
                    enabled = normalizeFolderInput(otherFolder) != null,
                    modifier = Modifier.heightIn(min = 56.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }

            Text(
                "On Android 11 and newer, SackUp can only see photos, videos and music, not other document types.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { if (canSave) onSave(name.trim(), phoneFolders) },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun FolderChoiceCard(
    label: String,
    path: String,
    chosen: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = chosen, role = Role.Checkbox, onValueChange = { onToggle() }),
        colors = CardDefaults.cardColors(
            containerColor = if (chosen) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = chosen, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
