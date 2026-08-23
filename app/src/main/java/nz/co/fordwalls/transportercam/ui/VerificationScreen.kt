package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.co.fordwalls.transportercam.MainViewModel
import nz.co.fordwalls.transportercam.database.JobStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationScreen(
    jobId: String,
    isDelivery: Boolean = false,
    viewModel: MainViewModel,
    onVerified: (Long, String, String?) -> Unit,
    onBack: () -> Unit
) {
    val jobs by viewModel.jobs.collectAsState()
    
    var regoInput by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }

    if (showScanner) {
        OcrScanner(
            onTextScanned = { 
                regoInput = it
                showScanner = false
            },
            onCancel = { showScanner = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isDelivery) "Verify Delivery Vehicle" else "Verify Pickup Vehicle") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Scan or Enter Rego/VIN",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = if (isDelivery) "Confirm this is the vehicle being delivered" else "Searching booked and accepted jobs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )

            OutlinedTextField(
                value = regoInput,
                onValueChange = { 
                    regoInput = it.uppercase()
                    errorMessage = null 
                },
                label = { Text("Rego/VIN") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { showScanner = true }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan")
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false
                ),
                isError = errorMessage != null
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    if (regoInput.isBlank()) {
                        errorMessage = "Please enter or scan a Rego"
                        return@Button
                    }
                    
                    isVerifying = true
                    val normalizedRego = regoInput.trim()
                    val matchedJob = jobs.find {
                        val requestedJobMatches = jobId.isBlank() || it.id == jobId
                        val statusMatches = if (isDelivery) {
                            it.status == JobStatus.PICKED_UP
                        } else {
                            it.status == JobStatus.ACCEPTED || it.status == JobStatus.NEW
                        }
                        requestedJobMatches && it.rego.trim().equals(normalizedRego, ignoreCase = true) && statusMatches
                    }
                    
                    if (matchedJob != null) {
                        // AUTO-ACCEPT: If job is still NEW, accept it automatically
                        if (!isDelivery && matchedJob.status == JobStatus.NEW) {
                            viewModel.updateJobStatus(matchedJob.id, JobStatus.ACCEPTED)
                        }

                        viewModel.getOrCreateFolderForJob(matchedJob.id, matchedJob.rego) { folderId, error ->
                            isVerifying = false
                            onVerified(folderId, matchedJob.id, error)
                        }
                    } else {
                        isVerifying = false
                        errorMessage = if (isDelivery) {
                            "This rego does not match the delivery vehicle."
                        } else {
                            "No booked or accepted job found for '$normalizedRego'"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium,
                enabled = !isVerifying
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Text("Verify & Start Check", fontSize = 18.sp)
                }
            }
        }
    }
}
