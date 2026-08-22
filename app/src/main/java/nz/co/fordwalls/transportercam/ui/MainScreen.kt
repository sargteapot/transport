package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import nz.co.fordwalls.transportercam.database.Folder
import nz.co.fordwalls.transportercam.ui.theme.TransporterCamTheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MainScreen(
    onViewFolders: () -> Unit,
    onNewFolder: (String, Boolean) -> Unit,
    onExistingFolderSelected: (Long) -> Unit,
    onCheckExisting: suspend (String) -> List<Folder>,
    onOpenSettings: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    
    var conflictingFolders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var showConflictDialog by remember { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()

    if (showScanner) {
        OcrScanner(
            onTextScanned = { 
                folderName = it
                showScanner = false
            },
            onCancel = { showScanner = false }
        )
    } else {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            ) {
                // Shiny Header Section
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp, bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier.size(80.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.primary,
                            tonalElevation = 8.dp
                        ) {
                            Icon(
                                Icons.Default.CameraAlt, 
                                null, 
                                modifier = Modifier.padding(16.dp).size(48.dp), 
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "FW Driver",
                            style = MaterialTheme.typography.displaySmall, 
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Vehicle Documentation Made Simple", 
                            style = MaterialTheme.typography.bodyMedium, 
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Action Grid
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ActionCard(
                        title = "New Vehicle", 
                        subtitle = "Start documenting a delivery", 
                        icon = Icons.Default.Add, 
                        containerColor = MaterialTheme.colorScheme.primaryContainer, 
                        onClick = { showDialog = true }
                    )
                    ActionCard(
                        title = "Previous Vehicles", 
                        subtitle = "View and share existing records", 
                        icon = Icons.Default.FolderOpen, 
                        onClick = onViewFolders
                    )
                    ActionCard(
                        title = "Settings", 
                        subtitle = "Configure camera and sharing", 
                        icon = Icons.Default.Settings, 
                        onClick = onOpenSettings
                    )
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("New Vehicle") },
                text = {
                    Column {
                        TextField(
                            value = folderName,
                            onValueChange = { folderName = it },
                            placeholder = { Text("Enter Rego/VIN") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showScanner = true }) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan")
                                }
                            }
                        )
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                         TextButton(onClick = {
                            if (folderName.isNotBlank()) {
                                coroutineScope.launch {
                                    val matches = onCheckExisting(folderName)
                                    if (matches.isNotEmpty()) {
                                        conflictingFolders = matches
                                        showConflictDialog = true
                                    } else {
                                        onNewFolder(folderName, true) // Go to Checklist
                                        showDialog = false
                                        folderName = ""
                                    }
                                }
                            }
                        }) {
                            Text("Checklist")
                        }
                        Button(onClick = {
                            if (folderName.isNotBlank()) {
                                coroutineScope.launch {
                                    val matches = onCheckExisting(folderName)
                                    if (matches.isNotEmpty()) {
                                        conflictingFolders = matches
                                        showConflictDialog = true
                                    } else {
                                        onNewFolder(folderName, false) // Go to Camera
                                        showDialog = false
                                        folderName = ""
                                    }
                                }
                            }
                        }) {
                            Text("Camera")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showDialog = false 
                        folderName = ""
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showConflictDialog) {
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            AlertDialog(
                onDismissRequest = { showConflictDialog = false },
                title = { Text("Vehicle Found") },
                text = {
                    Column {
                        Text("Vehicle '$folderName' already has documentation in the system.")
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Select a record to add new photos to, or create a fresh entry.", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        conflictingFolders.forEach { folder ->
                            OutlinedButton(
                                onClick = {
                                    onExistingFolderSelected(folder.id)
                                    showConflictDialog = false
                                    showDialog = false
                                    folderName = ""
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text("Add to Existing (${dateFormat.format(Date(folder.createdAt))})")
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            onNewFolder(folderName, true)
                            showConflictDialog = false
                            showDialog = false
                            folderName = ""
                        }) {
                            Text("Checklist")
                        }
                        Button(onClick = {
                            onNewFolder(folderName, false)
                            showConflictDialog = false
                            showDialog = false
                            folderName = ""
                        }) {
                            Text("Camera")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConflictDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun ActionCard(
    title: String, 
    subtitle: String, 
    icon: ImageVector, 
    containerColor: Color = MaterialTheme.colorScheme.surface, 
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick, 
        modifier = Modifier.fillMaxWidth(), 
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(24.dp).fillMaxWidth(), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp), 
                shape = MaterialTheme.shapes.medium, 
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ) {
                Icon(
                    icon, 
                    null, 
                    modifier = Modifier.padding(12.dp), 
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    TransporterCamTheme {
        MainScreen(
            onViewFolders = {}, 
            onNewFolder = { _, _ -> },
            onExistingFolderSelected = {},
            onCheckExisting = { emptyList() },
            onOpenSettings = {}
        )
    }
}
