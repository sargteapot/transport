package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import nz.co.fordwalls.transportercam.MainViewModel

@Serializable
data class PrestartItem(
    val part: String,
    val status: String = "OK",
    val isNumberInput: Boolean = false,
    val value: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrestartScreen(
    fleetNumber: String,
    viewModel: MainViewModel,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    val truckItems = listOf(
        "Engine Oil", "Start Engine", "Right Front Tyres", "Right Body Lights",
        "Right Rear Tyres", "Rear Tail Lights", "Hazard Lights"
    )
    val trailerItems = listOf(
        "Trailer: Right Body Lights", "Trailer: Right Tyres", "Trailer: Rear Tail Lights",
        "Trailer: Hazard Lights", "Trailer: Left Tyres", "Trailer Hubo", "Trailer: Left Body Lights"
    )
    val truckFinalItems = listOf(
        "Truck: Left Rear Tyres", "Truck: Left Body Lights", "Truck: Left Front Tyres",
        "Truck: Front Head Lights", "Truck: Front Hazard Lights", "Truck Hubo"
    )

    val checklist = remember { 
        mutableStateListOf<PrestartItem>().apply {
            addAll(truckItems.map { PrestartItem(it) })
            addAll(trailerItems.map { PrestartItem(it, isNumberInput = it == "Trailer Hubo", status = if (it == "Trailer Hubo") "" else "OK") })
            addAll(truckFinalItems.map { PrestartItem(it, isNumberInput = it == "Truck Hubo", status = if (it == "Truck Hubo") "" else "OK") })
        }
    }
    
    var notes by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pre-start: $fleetNumber") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(checklist) { index, item ->
                PrestartRow(
                    item = item,
                    onStatusChange = { newStatus -> checklist[index] = checklist[index].copy(status = newStatus) },
                    onValueChange = { newValue -> checklist[index] = checklist[index].copy(value = newValue) }
                )
            }

            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    placeholder = { Text("Any issues or comments...") }
                )
            }

            item {
                Button(
                    onClick = {
                        isSaving = true
                        viewModel.savePrestart(fleetNumber, checklist.toList(), notes) { success ->
                            isSaving = false
                            if (success) onComplete()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(vertical = 16.dp),
                    enabled = !isSaving
                ) {
                    if (isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    else Text("Submit Pre-start", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun PrestartRow(
    item: PrestartItem,
    onStatusChange: (String) -> Unit,
    onValueChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(item.part, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            
            if (item.isNumberInput) {
                OutlinedTextField(
                    value = item.value,
                    onValueChange = { if (it.all { char -> char.isDigit() }) onValueChange(it) },
                    label = { Text("Reading") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusButton("OK", item.status == "OK", Color(0xFF4CAF50)) { onStatusChange("OK") }
                    StatusButton("ISSUE", item.status == "ISSUE", Color(0xFFF44336)) { onStatusChange("ISSUE") }
                    StatusButton("NA", item.status == "NA", Color.Gray) { onStatusChange("NA") }
                }
            }
        }
    }
}

@Composable
fun StatusButton(label: String, isSelected: Boolean, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) color else color.copy(alpha = 0.1f),
            contentColor = if (isSelected) Color.White else color
        ),
        modifier = Modifier.height(36.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
