package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.co.fordwalls.transportercam.MainViewModel
import nz.co.fordwalls.transportercam.database.JobStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    jobId: String,
    viewModel: MainViewModel,
    onAccept: () -> Unit,
    onScan: () -> Unit,
    onConfirmDelivery: (String, String) -> Unit, // Changed to pass jobId and rego
    onBack: () -> Unit
) {
    val jobs by viewModel.jobs.collectAsState()
    val job = remember(jobs, jobId) { jobs.find { it.id == jobId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Job Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (job == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                // Status Header
                StatusBadge(status = job.status)
                Text(
                    text = "Rego: ${job.rego}",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                job.dispatchedBy?.let {
                    Text(
                        text = "Dispatched by: $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // Info Sections
                InfoSection(title = "Load Information", content = job.loadInfo, icon = Icons.Default.Inventory)
                InfoSection(title = "Pickup Address", content = job.pickupAddress, icon = Icons.Default.ArrowUpward)
                InfoSection(title = "Delivery Address", content = job.deliveryAddress, icon = Icons.Default.ArrowDownward)
                InfoSection(title = "Contact Info", content = job.contactInfo, icon = Icons.Default.Phone)
                
                job.notes?.let {
                    InfoSection(title = "Dispatcher Notes", content = it, icon = Icons.AutoMirrored.Filled.Note)
                }

                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(32.dp))

                // Context Actions
                when (job.status) {
                    JobStatus.NEW -> {
                        Button(
                            onClick = { 
                                viewModel.updateJobStatus(job.id, JobStatus.ACCEPTED)
                                onAccept() 
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.Check, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Accept Job", fontSize = 18.sp)
                        }
                    }
                    JobStatus.ACCEPTED -> {
                        Button(
                            onClick = onScan,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan Vehicle & Pickup", fontSize = 18.sp)
                        }
                    }
                    JobStatus.PICKED_UP -> {
                        Button(
                            onClick = { onConfirmDelivery(job.id, job.rego) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Icon(Icons.Default.LocalShipping, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Arrived & Delivery Check", fontSize = 18.sp)
                        }
                    }
                    JobStatus.DONE -> {
                        OutlinedButton(
                            onClick = { /* View history/photos? */ },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = MaterialTheme.shapes.medium,
                            enabled = false
                        ) {
                            Text("Job Completed")
                        }
                    }
                    JobStatus.ONSCAN -> {
                        // This shouldn't normally be reachable in detail but for safety:
                        Text("This job is currently at the wharf awaiting scan.")
                    }
                }
            }
        }
    }
}

@Composable
fun InfoSection(title: String, content: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 28.dp, top = 4.dp)
        )
    }
}
