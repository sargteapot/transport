package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.co.fordwalls.transportercam.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignOffScreen(
    jobId: String,
    isDriver: Boolean,
    viewModel: MainViewModel,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isDriver) "Driver Sign-off" else "Customer Sign-off") },
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
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isDriver) "Confirm vehicle pickup" else "Confirm vehicle delivery",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(if (isDriver) "Driver Name" else "Customer Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Signature",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start)
            )
            
            SignaturePad(
                onSave = { bitmap ->
                    if (name.isBlank()) {
                        errorMessage = "Please enter name first"
                        return@SignaturePad
                    }
                    isLoading = true
                    errorMessage = null
                    viewModel.submitSignoff(jobId, isDriver, name, bitmap) { success ->
                        isLoading = false
                        if (success) {
                            onComplete()
                        } else {
                            errorMessage = "Error saving confirmation locally."
                        }
                    }
                },
                onClear = { errorMessage = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        }
    }
}
