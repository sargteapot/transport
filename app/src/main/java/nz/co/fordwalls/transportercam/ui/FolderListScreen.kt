package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import nz.co.fordwalls.transportercam.database.Folder
import nz.co.fordwalls.transportercam.database.FolderWithTimestamps
import nz.co.fordwalls.transportercam.ui.theme.TransporterCamTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    foldersWithTimestamps: List<FolderWithTimestamps>,
    onFolderClick: (Long) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onBack: () -> Unit
) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val searchDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }
    
    val filteredFolders = foldersWithTimestamps.filter { item ->
        val nameMatch = item.folder.name.contains(searchQuery, ignoreCase = true)
        val dateMatch = if (selectedDateMillis != null) {
            val folderDate = searchDateFormat.format(Date(item.folder.createdAt))
            val selectedDate = searchDateFormat.format(Date(selectedDateMillis!!))
            folderDate == selectedDate
        } else true
        nameMatch && dateMatch
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Previous Vehicles") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Text("<")
                        }
                    }
                )
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    placeholder = { Text("Search by Rego/VIN") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        Row {
                            if (searchQuery.isNotEmpty() || selectedDateMillis != null) {
                                IconButton(onClick = { 
                                    searchQuery = "" 
                                    selectedDateMillis = null
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(
                                    Icons.Default.CalendarMonth, 
                                    contentDescription = "Filter by Date",
                                    tint = if (selectedDateMillis != null) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            }
        }
    ) { padding ->
        if (filteredFolders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isEmpty()) "No vehicles documented yet.\nClick 'New Vehicle' to start." else "No matches found for '$searchQuery'",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(filteredFolders) { item ->
                    val folder = item.folder
                    ListItem(
                        headlineContent = { Text(folder.name) },
                        supportingContent = { 
                            Column {
                                if (item.earliestMedia != null) {
                                    Text("Started: ${dateFormat.format(Date(item.earliestMedia))}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (item.latestMedia != null && item.latestMedia != item.earliestMedia) {
                                    Text("Finished: ${dateFormat.format(Date(item.latestMedia))}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                        trailingContent = {
                            IconButton(onClick = { folderToDelete = folder }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Vehicle", tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.clickable { onFolderClick(folder.id) }
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedDateMillis = datePickerState.selectedDateMillis
                    showDatePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (folderToDelete != null) {
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Delete Vehicle?") },
            text = { Text("This will permanently delete all photos/videos for '${folderToDelete?.name}'.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        folderToDelete?.let { onDeleteFolder(it.id) }
                        folderToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun FolderListScreenPreview() {
    TransporterCamTheme {
        FolderListScreen(
            foldersWithTimestamps = listOf(
                FolderWithTimestamps(
                    folder = Folder(id = 1, name = "ABC-123", createdAt = System.currentTimeMillis()),
                    earliestMedia = System.currentTimeMillis(),
                    latestMedia = System.currentTimeMillis() + 600000
                ),
                FolderWithTimestamps(
                    folder = Folder(id = 2, name = "VIN-987654321", createdAt = System.currentTimeMillis() - 86400000),
                    earliestMedia = System.currentTimeMillis() - 86400000,
                    latestMedia = System.currentTimeMillis() - 86300000
                )
            ),
            onFolderClick = {},
            onDeleteFolder = {},
            onBack = {}
        )
    }
}
