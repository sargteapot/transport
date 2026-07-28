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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    timestampEnabled: Boolean,
    onTimestampToggle: (Boolean) -> Unit,
    currentDateFormat: String,
    onDateFormatChange: (String) -> Unit,
    gpsEnabled: Boolean,
    onGpsToggle: (Boolean) -> Unit,
    darkMode: String,
    onDarkModeChange: (String) -> Unit,
    imageQuality: Int,
    onImageQualityChange: (Int) -> Unit,
    shareSummaryEnabled: Boolean,
    onShareSummaryToggle: (Boolean) -> Unit,
    captureFeedbackEnabled: Boolean,
    onCaptureFeedbackToggle: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val dateFormats = listOf(
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yy HH:mm:ss",
        "MM/dd/yy HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm"
    )
    
    val darkModeOptions = listOf("auto" to "Follow System", "on" to "On", "off" to "Off")
    
    var dateExpanded by remember { mutableStateOf(false) }
    var darkExpanded by remember { mutableStateOf(false) }
    var showChangeLog by remember { mutableStateOf(false) }

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
            SettingsSection(title = "Photo Documentation") {
                SettingsSwitchRow(
                    title = "Enable Timestamp",
                    subtitle = "Add date and time to the bottom of new photos",
                    checked = timestampEnabled,
                    onCheckedChange = onTimestampToggle
                )
                
                if (timestampEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsDropdownRow(
                        title = "Date Format",
                        subtitle = "Choose how the date appears",
                        currentValue = currentDateFormat,
                        onExpand = { dateExpanded = true }
                    )
                    
                    DropdownMenu(
                        expanded = dateExpanded,
                        onDismissRequest = { dateExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        dateFormats.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format) },
                                onClick = {
                                    onDateFormatChange(format)
                                    dateExpanded = false
                                }
                            )
                        }
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                SettingsSwitchRow(
                    title = "GPS Location Stamping",
                    subtitle = "Add latitude and longitude coordinates to photos",
                    checked = gpsEnabled,
                    onCheckedChange = onGpsToggle
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                Text("Image Quality", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Balance between file size and detail ($imageQuality%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = imageQuality.toFloat(),
                    onValueChange = { onImageQualityChange(it.toInt()) },
                    valueRange = 50f..100f,
                    steps = 10,
                    modifier = Modifier.padding(top = 8.dp)
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                SettingsSwitchRow(
                    title = "Include Share Summary",
                    subtitle = "Include job times and notes when sharing documentation",
                    checked = shareSummaryEnabled,
                    onCheckedChange = onShareSummaryToggle
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                SettingsSwitchRow(
                    title = "Capture Feedback",
                    subtitle = "Show a visual flash and preview when media is captured",
                    checked = captureFeedbackEnabled,
                    onCheckedChange = onCaptureFeedbackToggle
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
                            imageVector = if (showChangeLog) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.ArrowDropDown, 
                            contentDescription = null,
                            modifier = Modifier.rotate(if (showChangeLog) 90f else 0f),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                if (showChangeLog) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text("v1.3 (Connected Fleet)", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("• Added Firebase-backed Job Dispatch system", style = MaterialTheme.typography.bodySmall)
                        Text("• Integrated Driver Sign-off and Customer proof of delivery", style = MaterialTheme.typography.bodySmall)
                        Text("• Added Fleet Number filtering for truck-specific jobs", style = MaterialTheme.typography.bodySmall)
                        Text("• Added Username-based secure login", style = MaterialTheme.typography.bodySmall)
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("v1.2 (Safety & Portability)", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("• Added PDF Export for vehicle checklists", style = MaterialTheme.typography.bodySmall)
                        Text("• Fixed Checklist persistence issues", style = MaterialTheme.typography.bodySmall)
                        Text("• Improved organized export structure (Rego + Date)", style = MaterialTheme.typography.bodySmall)
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("v1.1 (Professional Update)", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("• Added Barcode & QR code scanning", style = MaterialTheme.typography.bodySmall)
                        Text("• Precision-tuned OCR for 6-digit Regos/VINs", style = MaterialTheme.typography.bodySmall)
                        Text("• Added Pinch-to-Zoom & Double-tap reset", style = MaterialTheme.typography.bodySmall)
                        Text("• Added Photo Notes & Captions", style = MaterialTheme.typography.bodySmall)
                        Text("• Full Dark Mode & Custom Branding", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                "TransporterCam v1.3",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            
            Text(
                "created by Jesse Walls using Gemini",
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
            )
        }
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
        timestampEnabled = true,
        onTimestampToggle = {},
        currentDateFormat = "dd/MM/yyyy HH:mm:ss",
        onDateFormatChange = {},
        gpsEnabled = false,
        onGpsToggle = {},
        darkMode = "auto",
        onDarkModeChange = {},
        imageQuality = 95,
        onImageQualityChange = {},
        shareSummaryEnabled = true,
        onShareSummaryToggle = {},
        captureFeedbackEnabled = true,
        onCaptureFeedbackToggle = {},
        onBack = {}
    )
}
