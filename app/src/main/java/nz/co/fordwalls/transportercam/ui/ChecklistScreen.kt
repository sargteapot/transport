package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChecklistItem(
    val part: String,
    val status: String = "OK",
    val otherDetails: String? = null,
    val isTextFieldOnly: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistScreen(
    folderName: String,
    initialChecklistJson: String?,
    isDelivery: Boolean = false,
    onSave: (String) -> Unit,
    onAddPhotos: () -> Unit,
    onBack: () -> Unit
) {
    val baseParts = listOf(
        Pair("Front Bumper", false),
        Pair("Bonnet", false),
        Pair("Windscreen", false),
        Pair("RH Front Fender", false),
        Pair("RH Front Wheel", false),
        Pair("RH Front Door", false),
        Pair("RH Mirror", false),
        Pair("RH Rear Door", false),
        Pair("RH Rear wheel", false),
        Pair("RH Rear Fender", false),
        Pair("Rear Bumper", false),
        Pair("Boot/Tailgate", false),
        Pair("Rear Glass", false),
        Pair("LH Rear Fender", false),
        Pair("LH Rear Wheel", false),
        Pair("LH Rear Door", false),
        Pair("LH Front Door", false),
        Pair("LH Mirror", false),
        Pair("LH Front wheel", false),
        Pair("LH Front Fender", false),
        Pair("Roof", false),
        Pair("Interior", false),
        Pair("Keys", true),
        Pair("Odometer", true),
        Pair("Notes", true)
    )

    val defaultParts = remember(isDelivery) {
        if (isDelivery) {
            baseParts + Pair("Receiver Name", true)
        } else {
            baseParts
        }
    }

    val checklistItems = remember { mutableStateListOf<ChecklistItem>() }
    var hasInitializedFromData by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialChecklistJson, isDelivery) {
        if (!hasInitializedFromData) {
            if (initialChecklistJson != null && initialChecklistJson.isNotEmpty()) {
                try {
                    val items = Json.decodeFromString<List<ChecklistItem>>(initialChecklistJson)
                    checklistItems.clear()
                    checklistItems.addAll(items)
                    
                    if (isDelivery && checklistItems.none { it.part == "Receiver Name" }) {
                        checklistItems.add(ChecklistItem(part = "Receiver Name", isTextFieldOnly = true, status = ""))
                    }
                    
                    hasInitializedFromData = true
                } catch (e: Exception) {}
            }
            
            if (checklistItems.isEmpty()) {
                val items = defaultParts.map { (name, isText) ->
                    ChecklistItem(
                        part = name, 
                        isTextFieldOnly = isText, 
                        status = if (isText) "" else "OK"
                    ) 
                }
                checklistItems.addAll(items)
                hasInitializedFromData = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isDelivery) "Drop-off Check" else "Pickup Check") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddPhotos,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhotoCamera, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Photos")
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(16.dp).navigationBarsPadding()) {
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    
                    Button(
                        onClick = {
                            val keysItem = checklistItems.find { it.part == "Keys" }
                            if (keysItem == null || keysItem.otherDetails.isNullOrBlank()) {
                                errorMessage = "⚠️ Please fill in the 'Keys' field."
                                return@Button
                            }

                            if (isDelivery) {
                                val receiverItem = checklistItems.find { it.part == "Receiver Name" }
                                if (receiverItem == null || receiverItem.otherDetails.isNullOrBlank()) {
                                    errorMessage = "⚠️ Please fill in the 'Receiver Name' field."
                                    return@Button
                                }
                            }

                            errorMessage = null
                            val json = Json.encodeToString(checklistItems.toList())
                            onSave(json)
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(if (isDelivery) "Continue to Customer Signature" else "Continue to Driver Signature")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
        ) {
            itemsIndexed(checklistItems) { index, item ->
                ChecklistRow(
                    item = item,
                    onStatusChange = { newStatus ->
                        checklistItems[index] = checklistItems[index].copy(status = newStatus)
                    },
                    onOtherDetailsChange = { details ->
                        checklistItems[index] = checklistItems[index].copy(otherDetails = details)
                        if ((item.part == "Keys" || item.part == "Receiver Name") && details.isNotBlank()) {
                            errorMessage = null
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistRow(
    item: ChecklistItem,
    onStatusChange: (String) -> Unit,
    onOtherDetailsChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val statuses = listOf("OK", "Scratch", "Dent", "Missing", "Other")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.status != "OK" && !item.isTextFieldOnly) 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f) 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(item.part, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            
            if (item.isTextFieldOnly) {
                val isMandatory = item.part == "Keys" || item.part == "Receiver Name"
                OutlinedTextField(
                    value = item.otherDetails ?: "",
                    onValueChange = onOtherDetailsChange,
                    label = { Text(if (isMandatory) "${item.part} (Mandatory)" else "Details") },
                    placeholder = { Text("Enter ${item.part}...") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = isMandatory && item.otherDetails.isNullOrBlank(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = item.status,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Condition") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        statuses.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(status) },
                                onClick = {
                                    onStatusChange(status)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                if (item.status != "OK" && item.status.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = item.otherDetails ?: "",
                        onValueChange = onOtherDetailsChange,
                        label = { Text("Describe Damage") },
                        placeholder = { Text("Specify damage...") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
    }
}
