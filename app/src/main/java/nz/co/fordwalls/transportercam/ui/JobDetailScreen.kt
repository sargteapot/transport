package nz.co.fordwalls.transportercam.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    onConfirmDelivery: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val jobs by viewModel.jobs.collectAsState()
    val job = remember(jobs, jobId) { jobs.find { it.id == jobId } }
    val context = LocalContext.current

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
            SelectionContainer {
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

                    // Determine Primary and Secondary Contacts
                    val isPickedUp = job.status == JobStatus.PICKED_UP
                    val primaryContact = if (isPickedUp) job.deliveryContact else job.pickupContact
                    val primaryLabel = if (isPickedUp) "Delivery Contact" else "Pickup Contact"
                    
                    val secondaryContact = if (isPickedUp) job.pickupContact else job.deliveryContact
                    val secondaryLabel = if (isPickedUp) "Pickup Contact" else "Delivery Contact"

                    // --- PRIMARY CONTACT (PROMINENT) ---
                    if (primaryContact.isNotBlank()) {
                        ContactSection(
                            label = primaryLabel,
                            name = primaryContact,
                            isPrimary = true,
                            onCall = { num -> context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))) },
                            onText = { num -> context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))) }
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    // --- SECONDARY CONTACT (SMALLER) ---
                    if (secondaryContact.isNotBlank()) {
                        ContactSection(
                            label = secondaryLabel,
                            name = secondaryContact,
                            isPrimary = false,
                            onCall = { num -> context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))) },
                            onText = { num -> context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))) }
                        )
                        Spacer(Modifier.height(24.dp))
                    }

                    // Info Sections
                    InfoSection(title = "Load Information", content = job.loadInfo, icon = Icons.Default.Inventory)
                    InfoSection(title = "Pickup Address", content = job.pickupAddress, icon = Icons.Default.ArrowUpward)
                    InfoSection(title = "Delivery Address", content = job.deliveryAddress, icon = Icons.Default.ArrowDownward)
                    
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
                                shape = MaterialTheme.shapes.medium
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
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Default.LocalShipping, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Arrived & Delivery Check", fontSize = 18.sp)
                            }
                        }
                        JobStatus.DONE -> {
                            OutlinedButton(
                                onClick = { /* History */ },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = MaterialTheme.shapes.medium,
                                enabled = false
                            ) {
                                Text("Job Completed")
                            }
                        }
                        JobStatus.ONSCAN -> {
                            Text("This job is currently at the wharf awaiting scan.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactSection(
    label: String,
    name: String,
    isPrimary: Boolean,
    onCall: (String) -> Unit,
    onText: (String) -> Unit
) {
    val phoneNumber = name.filter { it.isDigit() }
    val hasPhone = phoneNumber.length >= 3

    Surface(
        color = if (isPrimary) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) 
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(if (isPrimary) 16.dp else 12.dp)) {
            Text(
                text = label, 
                style = if (isPrimary) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = name,
                style = if (isPrimary) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (isPrimary) FontWeight.ExtraBold else FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            
            if (hasPhone) {
                Row(
                    modifier = Modifier.padding(top = if (isPrimary) 12.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onCall(phoneNumber) },
                        modifier = Modifier.weight(1f).height(if (isPrimary) 48.dp else 40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Phone, null, modifier = Modifier.size(if (isPrimary) 20.dp else 16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPrimary) "Call" else "Call", fontSize = if (isPrimary) 14.sp else 12.sp)
                    }
                    
                    Button(
                        onClick = { onText(phoneNumber) },
                        modifier = Modifier.weight(1f).height(if (isPrimary) 48.dp else 40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Sms, null, modifier = Modifier.size(if (isPrimary) 20.dp else 16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPrimary) "Text" else "Text", fontSize = if (isPrimary) 14.sp else 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoSection(title: String, content: String, icon: ImageVector) {
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
