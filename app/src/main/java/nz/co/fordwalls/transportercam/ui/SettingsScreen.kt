package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import nz.co.fordwalls.transportercam.BuildConfig
import nz.co.fordwalls.transportercam.UpdateManifest
import nz.co.fordwalls.transportercam.UpdateState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    companyName: String,
    companyId: String,
    notificationsEnabled: Boolean,
    onNotificationsToggle: (Boolean) -> Unit,
    darkMode: String,
    onDarkModeChange: (String) -> Unit,
    updateState: UpdateState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: (UpdateManifest) -> Unit,
    onInstallUpdate: (UpdateManifest, File) -> Unit,
    onChangeCompany: () -> Unit,
    onBack: () -> Unit
) {
    val darkModeOptions = listOf("auto" to "Follow System", "on" to "On", "off" to "Off")
    var darkExpanded by remember { mutableStateOf(false) }
    var showChangeLog by remember { mutableStateOf(false) }
    var confirmCompanyChange by remember { mutableStateOf(false) }

    if (confirmCompanyChange) {
        AlertDialog(
            onDismissRequest = { confirmCompanyChange = false },
            title = { Text("Change company?") },
            text = { Text("This signs you out and stops the current company session. Local work remains attached to $companyName.") },
            confirmButton = { TextButton(onClick = onChangeCompany) { Text("Sign out & change") } },
            dismissButton = { TextButton(onClick = { confirmCompanyChange = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "Company") {
                ListItem(
                    headlineContent = { Text(companyName) },
                    supportingContent = { Text(companyId) },
                    leadingContent = { Icon(Icons.Default.Business, null) }
                )
                OutlinedButton(onClick = { confirmCompanyChange = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Change company")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SettingsSection(title = "Alerts") {
                SettingsSwitchRow(
                    title = "Job Notifications",
                    subtitle = "Sound and alert when a new job arrives",
                    checked = notificationsEnabled,
                    onCheckedChange = onNotificationsToggle
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            SettingsSection(title = "App Appearance") {
                SettingsDropdownRow(
                    title = "Dark Mode",
                    subtitle = "Choose your preferred theme",
                    currentValue = darkModeOptions.find { it.first == darkMode }?.second ?: "Follow System",
                    onExpand = { darkExpanded = true }
                )
                
                DropdownMenu(
                    expanded = darkExpanded,
                    onDismissRequest = { darkExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    darkModeOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.second) },
                            onClick = {
                                onDarkModeChange(option.first)
                                darkExpanded = false
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            SettingsSection(title = "App updates") {
                UpdateSettings(
                    state = updateState,
                    onCheck = onCheckForUpdates,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            SettingsSection(title = "About") {
                TextButton(
                    onClick = { showChangeLog = !showChangeLog },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("View Change Log", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Icon(
                            imageVector = if (showChangeLog) Icons.Default.ArrowDropDown else Icons.Default.ArrowDropDown, 
                            contentDescription = null,
                            modifier = Modifier.rotate(if (showChangeLog) 90f else 0f),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                if (showChangeLog) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text("v1.3 (Fleet Update)", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("• Added Firebase-backed Job Dispatch system", style = MaterialTheme.typography.bodySmall)
                        Text("• Added Pre-start Checklist for truck and trailer", style = MaterialTheme.typography.bodySmall)
                        Text("• Integrated Dual Sign-off and Status Tracking", style = MaterialTheme.typography.bodySmall)
                        Text("• Added Wharf Scan global search", style = MaterialTheme.typography.bodySmall)
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("v1.2 (Stability)", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("• Fixed Checklist persistence and UI logic", style = MaterialTheme.typography.bodySmall)
                        Text("• Added PDF Export and organized ZIP naming", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                "FW Driver v${BuildConfig.VERSION_NAME}",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            
            Text(
                "created by Jesse Walls",
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun UpdateSettings(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (UpdateManifest) -> Unit,
    onInstall: (UpdateManifest, File) -> Unit
) {
    when (state) {
        UpdateState.Idle -> {
            Text("Updates are checked automatically when FW Driver starts.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
        }
        UpdateState.Checking -> UpdateProgress("Checking for updates…")
        UpdateState.UpToDate -> {
            ListItem(
                headlineContent = { Text("FW Driver is up to date") },
                supportingContent = { Text("Installed version ${BuildConfig.VERSION_NAME}") },
                leadingContent = { Icon(Icons.Default.CheckCircle, null) }
            )
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
        }
        is UpdateState.Available -> {
            UpdateDetails(state.manifest, state.required)
            Button(onClick = { onDownload(state.manifest) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Download update")
            }
        }
        is UpdateState.Downloading -> UpdateProgress(
            state.progress?.let { "Downloading update… $it%" } ?: "Downloading update…",
            state.progress
        )
        is UpdateState.ReadyToInstall -> {
            UpdateDetails(state.manifest, required = false)
            Button(onClick = { onInstall(state.manifest, state.apk) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.InstallMobile, null)
                Spacer(Modifier.width(8.dp))
                Text("Install update")
            }
            Text(
                "Android will ask you to confirm installation. Your FW Driver data will be retained.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is UpdateState.Error -> {
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            val retryManifest = state.manifest
            OutlinedButton(
                onClick = { if (retryManifest == null) onCheck() else onDownload(retryManifest) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (retryManifest == null) "Try again" else "Retry download") }
        }
    }
}

@Composable
private fun UpdateDetails(manifest: UpdateManifest, required: Boolean) {
    ListItem(
        headlineContent = { Text("FW Driver ${manifest.versionName}") },
        supportingContent = { Text(if (required) "Required update" else "Update available") },
        leadingContent = { Icon(Icons.Default.SystemUpdate, null) }
    )
    if (manifest.releaseNotes.isNotEmpty()) {
        Text("What's new", style = MaterialTheme.typography.labelLarge)
        manifest.releaseNotes.forEach { note -> Text("• $note", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun UpdateProgress(label: String, progress: Int? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsDropdownRow(title: String, subtitle: String, currentValue: String, onExpand: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpand() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(currentValue, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    SettingsScreen(
        companyName = "FordWalls",
        companyId = "fordwalls",
        notificationsEnabled = true,
        onNotificationsToggle = {},
        darkMode = "auto",
        onDarkModeChange = {},
        updateState = UpdateState.UpToDate,
        onCheckForUpdates = {},
        onDownloadUpdate = {},
        onInstallUpdate = { _, _ -> },
        onChangeCompany = {},
        onBack = {}
    )
}
