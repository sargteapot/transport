package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import nz.co.fordwalls.transportercam.MainViewModel
import nz.co.fordwalls.transportercam.database.Job
import nz.co.fordwalls.transportercam.database.JobStatus
import nz.co.fordwalls.transportercam.database.MediaAsset
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    initialTab: Int = 0,
    onTabSelected: (Int) -> Unit,
    onJobClick: (Job) -> Unit,
    onScanClick: () -> Unit,
    onWharfScanClick: () -> Unit,
    onPrestartClick: () -> Unit,
    onPhotosClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLogout: () -> Unit
) {
    val jobs by viewModel.jobs.collectAsState()
    val fleetNumber by viewModel.fleetNumber.collectAsState(initial = "...")
    val tabs = listOf("Today", "New", "WIP", "Done")
    var showClearDoneConfirmation by remember { mutableStateOf(false) }
    var clearDoneError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Fleet: $fleetNumber", style = MaterialTheme.typography.titleMedium)
                        Text("Job Dashboard", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = initialTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = initialTab == index,
                        onClick = { onTabSelected(index) },
                        text = { Text(title) }
                    )
                }
            }

            when (initialTab) {
                0 -> TodayTab(onScanClick, onWharfScanClick, onPrestartClick, onPhotosClick)
                else -> {
                    val filteredJobs = remember(jobs, initialTab) {
                        when (initialTab) {
                            1 -> jobs.filter { it.status == JobStatus.NEW }
                            2 -> jobs.filter { it.status == JobStatus.ACCEPTED || it.status == JobStatus.PICKED_UP }
                            else -> jobs.filter { it.status == JobStatus.DONE }
                        }
                    }

                    if (filteredJobs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No jobs found in ${tabs[initialTab]}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (initialTab == 3) {
                                item {
                                    OutlinedButton(
                                        onClick = { showClearDoneConfirmation = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.ClearAll, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Clear completed jobs")
                                    }
                                }
                            }
                            items(filteredJobs) { job ->
                                JobCard(
                                    job = job,
                                    onClick = { onJobClick(job) },
                                    onAccept = if (initialTab == 1) {
                                        { viewModel.updateJobStatus(job.id, JobStatus.ACCEPTED) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearDoneConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearDoneConfirmation = false },
            title = { Text("Clear completed jobs?") },
            text = { Text("This removes all completed jobs from this truck's Done tab.") },
            confirmButton = {
                Button(onClick = {
                    showClearDoneConfirmation = false
                    viewModel.clearDoneJobs { success -> clearDoneError = !success }
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDoneConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (clearDoneError) {
        AlertDialog(
            onDismissRequest = { clearDoneError = false },
            title = { Text("Could not clear jobs") },
            text = { Text("Please check the connection and try again.") },
            confirmButton = {
                TextButton(onClick = { clearDoneError = false }) { Text("OK") }
            }
        )
    }
}

@Composable
fun TodayTab(onScan: () -> Unit, onWharfScan: () -> Unit, onPrestart: () -> Unit, onPhotosClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val uniformColor = MaterialTheme.colorScheme.primary
        
        DashboardButton(
            text = "Scan Booked Vehicle",
            icon = Icons.Default.QrCodeScanner,
            color = uniformColor,
            onClick = onScan
        )
        DashboardButton(
            text = "Wharf Scan (Global)",
            icon = Icons.Default.Anchor,
            color = uniformColor,
            onClick = onWharfScan
        )
        DashboardButton(
            text = "Pre-start Checklist",
            icon = Icons.AutoMirrored.Filled.Assignment,
            color = uniformColor,
            onClick = onPrestart
        )
        DashboardButton(
            text = "Photos",
            icon = Icons.Default.PhotoLibrary,
            color = uniformColor,
            onClick = onPhotosClick
        )
        
        Spacer(Modifier.weight(1f))
        
        Text(
            "Welcome back! Please complete your pre-start before departing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}

@Composable
fun DashboardButton(text: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(icon, null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(20.dp))
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun JobCard(job: Job, onClick: () -> Unit, onAccept: (() -> Unit)? = null) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val activeContact = if (job.status == JobStatus.PICKED_UP) job.deliveryContact else job.pickupContact
    val contactLabel = if (job.status == JobStatus.PICKED_UP) "Delivery Contact" else "Pickup Contact"

    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = when (job.status) {
                JobStatus.NEW -> MaterialTheme.colorScheme.surface
                JobStatus.PICKED_UP -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Rego: ${job.rego}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    StatusBadge(status = job.status)
                }
                
                Spacer(Modifier.height(8.dp))
                
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(contactLabel, style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = activeContact.ifBlank { "No Contact Info" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                
                DetailRow(icon = Icons.Default.Inventory, text = job.loadInfo)
                DetailRow(icon = Icons.Default.ArrowUpward, text = "From: ${job.pickupAddress}")
                DetailRow(icon = Icons.Default.ArrowDownward, text = "To: ${job.deliveryAddress}")
                
                Spacer(Modifier.height(8.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "Received: ${dateFormat.format(Date(job.createdAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    job.dispatchedBy?.let {
                        Text(
                            text = "By: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (onAccept != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onAccept,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Accept Job", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: JobStatus) {
    val color = when (status) {
        JobStatus.NEW -> Color(0xFF2196F3) // Blue
        JobStatus.ACCEPTED -> Color(0xFFFF9800) // Orange
        JobStatus.PICKED_UP -> Color(0xFF4CAF50) // Green
        JobStatus.DONE -> Color(0xFF9E9E9E) // Grey
        JobStatus.ONSCAN -> Color(0xFF673AB7) // Purple
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, color)
    ) {
        Text(
            text = status.name,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DetailRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}
