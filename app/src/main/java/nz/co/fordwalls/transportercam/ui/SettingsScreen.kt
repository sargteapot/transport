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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.shape.RoundedCornerShape
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
    pendingSyncCount: Int,
    onSyncNow: () -> Unit,
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

    if (showChangeLog) {
        AlertDialog(
            onDismissRequest = { showChangeLog = false },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            title = { Text("What's new") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("• Cleaner rounded dashboard and workflow controls", style = MaterialTheme.typography.bodyMedium)
                    Text("• Photo-only camera and reliable checklist drafts", style = MaterialTheme.typography.bodyMedium)
                    Text("• Delivery vehicle verification", style = MaterialTheme.typography.bodyMedium)
                    Text("• Secure photo and signature evidence", style = MaterialTheme.typography.bodyMedium)
                    Text("• Firebase driver authentication", style = MaterialTheme.typography.bodyMedium)
                    Text("• Verified in-app updates", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("v1.3 Fleet Update", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("• Job dispatch, pre-starts and dual sign-off", style = MaterialTheme.typography.bodyMedium)
                    Text("• Wharf Scan global search", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { showChangeLog = false }) { Text("Done") } }
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "Company", icon = Icons.Default.Business) {
                ListItem(
                    headlineContent = { Text(companyName) },
                    supportingContent = { Text(companyId) },
                    trailingContent = {
                        IconButton(onClick = { confirmCompanyChange = true }) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Change company")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsSection(title = "Preferences", icon = Icons.Default.Tune) {
                SettingsSwitchRow(
                    title = "Job Notifications",
                    subtitle = "Sound and alert when a new job arrives",
                    checked = notificationsEnabled,
                    onCheckedChange = onNotificationsToggle
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsDropdownRow(
                    title = "Appearance",
                    subtitle = "App colour theme",
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
            
            Spacer(modifier = Modifier.height(12.dp))

            SettingsSection(title = "Offline sync", icon = Icons.Default.Sync) {
                ListItem(
                    headlineContent = { Text(if (pendingSyncCount == 0) "Everything is synced" else "$pendingSyncCount item${if (pendingSyncCount == 1) "" else "s"} waiting") },
                    supportingContent = { Text(if (pendingSyncCount == 0) "No local changes are waiting to upload" else "Connect to the internet, then force a retry below") },
                    leadingContent = { Icon(if (pendingSyncCount == 0) Icons.Default.CloudDone else Icons.Default.CloudUpload, null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
                Button(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sync now")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsSection(title = "App update", icon = Icons.Default.SystemUpdate) {
                UpdateSettings(
                    state = updateState,
                    onCheck = onCheckForUpdates,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            SettingsSection(title = "About", icon = Icons.Default.Info) {
                SettingsActionRow(
                    title = "What's new",
                    subtitle = "Recent improvements and fixes",
                    icon = Icons.Default.AutoAwesome,
                    onClick = { showChangeLog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text("FW Driver") },
                    supportingContent = { Text("Version ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}") },
                    leadingContent = { Icon(Icons.Default.LocalShipping, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "created by Jesse Walls",
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
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
            UpdateStatusRow(Icons.Default.Update, "Ready to check", "Updates are also checked when FW Driver starts")
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check now") }
        }
        UpdateState.Checking -> UpdateProgress("Checking for updates…")
        UpdateState.UpToDate -> {
            ListItem(
                headlineContent = { Text("FW Driver is up to date") },
                supportingContent = { Text("Installed version ${BuildConfig.VERSION_NAME}") },
                leadingContent = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            )
            TextButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
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
            val friendlyMessage = if (state.message.contains("JSON", ignoreCase = true)) {
                "The update service is not configured correctly."
            } else state.message
            UpdateStatusRow(Icons.Default.CloudOff, "Update unavailable", friendlyMessage, isError = true)
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
        leadingContent = { Icon(Icons.Default.SystemUpdate, null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )
    if (manifest.releaseNotes.isNotEmpty()) {
        Text("What's new", style = MaterialTheme.typography.labelLarge)
        manifest.releaseNotes.forEach { note -> Text("• $note", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun UpdateProgress(label: String, progress: Int? = null) {
    Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(7.dp).size(18.dp)
                    )
                }
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun UpdateStatusRow(icon: ImageVector, title: String, subtitle: String, isError: Boolean = false) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )
}

@Composable
private fun SettingsActionRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
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
            .padding(vertical = 10.dp),
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
        pendingSyncCount = 2,
        onSyncNow = {},
        updateState = UpdateState.UpToDate,
        onCheckForUpdates = {},
        onDownloadUpdate = {},
        onInstallUpdate = { _, _ -> },
        onChangeCompany = {},
        onBack = {}
    )
}
