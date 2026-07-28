package nz.co.fordwalls.transportercam

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.co.fordwalls.transportercam.database.Job
import nz.co.fordwalls.transportercam.database.JobStatus
import nz.co.fordwalls.transportercam.database.MediaAsset
import nz.co.fordwalls.transportercam.ui.*
import nz.co.fordwalls.transportercam.ui.theme.TransporterCamTheme
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
        enableEdgeToEdge()

        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestPermissionLauncher.launch(permissions.toTypedArray())

        setContent {
            val darkMode by viewModel.darkMode.collectAsState(initial = "auto")
            val darkTheme = when (darkMode) {
                "on" -> true
                "off" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            
            TransporterCamTheme(darkTheme = darkTheme) {
                AppNavigation(
                    viewModel = viewModel,
                    onShareMedia = { asset -> shareMedia(asset) },
                    onShareAllMedia = { name, assets, includeSummary -> 
                        shareAllMedia(name, assets, includeSummary) 
                    },
                    onExportVehicle = { folderId ->
                        viewModel.exportVehicleZip(folderId) { file ->
                            if (file != null) shareFile(file, "Export Vehicle Documentation")
                        }
                    }
                )
            }
        }
    }

    private fun shareMedia(asset: MediaAsset) {
        val file = File(asset.filePath)
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (asset.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            asset.notes?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${if (asset.isVideo) "Video" else "Photo"}"))
    }

    private fun shareAllMedia(folderName: String, assets: List<MediaAsset>, includeSummary: Boolean) {
        val uris = assets.map { asset ->
            FileProvider.getUriForFile(this, "$packageName.provider", File(asset.filePath))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            
            if (includeSummary) {
                val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                val started = assets.minByOrNull { it.timestamp }?.timestamp?.let { dateFormat.format(java.util.Date(it)) } ?: "Unknown"
                val finished = assets.maxByOrNull { it.timestamp }?.timestamp?.let { dateFormat.format(java.util.Date(it)) } ?: "Unknown"
                val notes = assets.mapNotNull { it.notes }.distinct().joinToString("\n") { "- $it" }
                
                val summary = StringBuilder().apply {
                    appendLine("Vehicle: $folderName")
                    appendLine("Started: $started")
                    appendLine("Finished: $finished")
                    appendLine("Total Items: ${assets.size}")
                    if (notes.isNotEmpty()) {
                        appendLine("\nNotes:")
                        append(notes)
                    }
                }.toString()
                
                putExtra(Intent.EXTRA_TEXT, summary)
            }

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share All Documentation"))
    }

    private fun shareFile(file: File, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, title))
    }
}

@Composable
fun AppNavigation(
    viewModel: MainViewModel = viewModel(),
    onShareMedia: (MediaAsset) -> Unit,
    onShareAllMedia: (String, List<MediaAsset>, Boolean) -> Unit,
    onExportVehicle: (Long) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val fleetNumberFlow by viewModel.fleetNumber.collectAsState(initial = null)
    
    // Lifted Tab State for Persistence
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    var navigationStack by remember { 
        mutableStateOf(
            listOf<Screen>(
                if (!isLoggedIn) Screen.Login 
                else Screen.Dashboard
            )
        ) 
    }
    
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            if (navigationStack.last() != Screen.Login) {
                navigationStack = listOf(Screen.Login)
            }
        } else {
            if (navigationStack.last() == Screen.Login) {
                navigationStack = listOf(Screen.Dashboard)
            }
        }
    }

    val currentScreen = navigationStack.last()
    
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState(initial = true)
    val darkMode by viewModel.darkMode.collectAsState(initial = "auto")
    val shareSummaryEnabled by viewModel.shareSummaryEnabled.collectAsState(initial = true)

    androidx.activity.compose.BackHandler(enabled = navigationStack.size > 1) {
        navigationStack = navigationStack.dropLast(1)
    }

    when (val screen = currentScreen) {
        is Screen.Login -> {
            LoginScreen(viewModel = viewModel, onLoginSuccess = {})
        }
        is Screen.Dashboard -> {
            DashboardScreen(
                viewModel = viewModel,
                initialTab = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it },
                onJobClick = { job -> navigationStack = navigationStack + Screen.JobDetail(job.id) },
                onScanClick = { navigationStack = navigationStack + Screen.Verification("") },
                onWharfScanClick = { navigationStack = navigationStack + Screen.WharfScan },
                onPrestartClick = { navigationStack = navigationStack + Screen.Prestart(fleetNumberFlow ?: "") },
                onPhotosClick = { navigationStack = navigationStack + Screen.GlobalPhotos },
                onSettingsClick = { navigationStack = navigationStack + Screen.Settings },
                onLogout = { viewModel.logout() }
            )
        }
        is Screen.GlobalPhotos -> {
            GlobalPhotosScreen(
                viewModel = viewModel,
                onPhotoClick = { index -> 
                    navigationStack = navigationStack + Screen.MediaDetail(0, index) 
                },
                onAddPhoto = {
                    navigationStack = navigationStack + Screen.Camera(0)
                },
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
        is Screen.Prestart -> {
            PrestartScreen(
                fleetNumber = screen.fleetNumber,
                viewModel = viewModel,
                onComplete = {
                    navigationStack = navigationStack.dropLast(1)
                    Toast.makeText(context, "Pre-start submitted!", Toast.LENGTH_SHORT).show()
                },
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
        is Screen.WharfScan -> {
            WharfScanScreen(
                viewModel = viewModel,
                onJobClaimed = {
                    navigationStack = navigationStack.dropLast(1)
                    Toast.makeText(context, "Job successfully claimed!", Toast.LENGTH_SHORT).show()
                },
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
        is Screen.FolderList -> {
            val folders by viewModel.foldersWithTimestamps.collectAsState(initial = emptyList())
            FolderListScreen(
                foldersWithTimestamps = folders,
                onFolderClick = { folderId -> navigationStack = navigationStack + Screen.Gallery(folderId) },
                onDeleteFolder = { folderId -> viewModel.deleteFolder(folderId) },
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
        is Screen.JobDetail -> {
            JobDetailScreen(
                jobId = screen.jobId,
                viewModel = viewModel,
                onAccept = { /* Handled in screen */ },
                onScan = { navigationStack = navigationStack + Screen.Verification(screen.jobId) },
                onConfirmDelivery = { matchedId, rego -> 
                    viewModel.getOrCreateFolderForJob(matchedId, rego) { folderId, error ->
                        if (folderId > 0) {
                            navigationStack = navigationStack + Screen.Checklist(folderId, isDelivery = true)
                        } else {
                            Toast.makeText(context, error ?: "Error identifying vehicle.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
        is Screen.Verification -> {
            VerificationScreen(
                jobId = screen.jobId,
                viewModel = viewModel,
                onVerified = { folderId, matchedJobId, error -> 
                    if (folderId > 0) {
                        navigationStack = navigationStack.dropLast(1) + Screen.Checklist(folderId, isDelivery = false) 
                    } else {
                        Toast.makeText(context, error ?: "Error creating vehicle folder.", Toast.LENGTH_SHORT).show()
                    }
                },
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
        is Screen.Signature -> {
            SignOffScreen(
                jobId = screen.jobId,
                isDriver = screen.isDriver,
                viewModel = viewModel,
                onComplete = {
                    if (!screen.isDriver) {
                        navigationStack = listOf(Screen.Dashboard)
                    } else {
                        navigationStack = navigationStack.dropLast(2)
                    }
                },
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
        is Screen.Settings -> {
            SettingsScreen(
                notificationsEnabled = notificationsEnabled,
                onNotificationsToggle = { viewModel.setNotificationsEnabled(it) },
                darkMode = darkMode,
                onDarkModeChange = { viewModel.setDarkMode(it) },
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
        is Screen.Camera -> {
            CameraScreen(
                folderId = screen.folderId,
                timestampEnabled = true,
                dateFormat = "dd/MM/yyyy HH:mm:ss",
                gpsEnabled = false,
                imageQuality = 95,
                captureFeedbackEnabled = true,
                onMediaCaptured = { filePath, isVideo ->
                    viewModel.addMediaAsset(screen.folderId, filePath, isVideo)
                },
                onUpdateLastNote = { note ->
                    viewModel.updateLastMediaNote(screen.folderId, note)
                },
                onNextVehicle = { name, goToChecklist ->
                    viewModel.createFolder(name) { newFolderId ->
                        if (goToChecklist) {
                            navigationStack = navigationStack.dropLast(1) + Screen.Checklist(newFolderId, isDelivery = false)
                        } else {
                            navigationStack = navigationStack.dropLast(1) + Screen.Camera(newFolderId)
                        }
                    }
                },
                onExistingVehicleSelected = { folderId ->
                    navigationStack = navigationStack.dropLast(1) + Screen.Checklist(folderId, isDelivery = false)
                },
                onCheckExisting = { name ->
                    viewModel.getFoldersByName(name)
                },
                onBack = { 
                    if (screen.folderId == 0L) {
                        navigationStack = navigationStack.dropLast(1)
                    } else {
                        navigationStack = navigationStack.dropLast(1) + Screen.Gallery(screen.folderId)
                    }
                }
            )
        }
        is Screen.Gallery -> {
            val folder by viewModel.getFolderById(screen.folderId).collectAsState(initial = null)
            GalleryScreen(
                viewModel = viewModel,
                folderId = screen.folderId,
                folderName = folder?.name ?: "Loading...",
                onMediaClick = { index -> navigationStack = navigationStack + Screen.MediaDetail(screen.folderId, index) },
                onAddMedia = { navigationStack = navigationStack + Screen.Camera(screen.folderId) },
                onChecklist = { navigationStack = navigationStack + Screen.Checklist(screen.folderId, isDelivery = false) },
                onShareAll = { name, assets -> 
                    onShareAllMedia(name, assets, shareSummaryEnabled) 
                },
                onExport = { onExportVehicle(screen.folderId) },
                onBack = { 
                    navigationStack = navigationStack.dropLast(1)
                    if (navigationStack.isEmpty()) navigationStack = listOf(Screen.Dashboard)
                }
            )
        }
        is Screen.Checklist -> {
            val folder by viewModel.getFolderById(screen.folderId).collectAsState(initial = null)
            ChecklistScreen(
                folderName = folder?.name ?: "Loading...",
                initialChecklistJson = folder?.checklistJson,
                isDelivery = screen.isDelivery, 
                onSave = { json ->
                    viewModel.updateFolderChecklist(screen.folderId, json)
                    navigationStack = navigationStack.dropLast(1) + Screen.Signature(
                        folder?.jobId ?: "", 
                        isDriver = !screen.isDelivery
                    )
                },
                onAddPhotos = {
                    navigationStack = navigationStack + Screen.Camera(screen.folderId)
                },
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
        is Screen.MediaDetail -> {
            val folder by viewModel.getFolderById(screen.folderId).collectAsState(initial = null)
            val mediaAssets by (if (screen.folderId == 0L) viewModel.allMediaAssets else viewModel.getMediaForFolder(screen.folderId)).collectAsState(initial = emptyList())
            MediaDetailScreen(
                folderName = if (screen.folderId == 0L) "All Photos" else (folder?.name ?: "Loading..."),
                mediaAssets = mediaAssets,
                initialIndex = screen.initialIndex,
                onDelete = { asset ->
                    viewModel.deleteMediaAsset(asset.id)
                },
                onUpdateNote = { mediaId, notes ->
                    viewModel.updateMediaNotes(mediaId, notes)
                },
                onShare = onShareMedia,
                onBack = { navigationStack = navigationStack.dropLast(1) }
            )
        }
    }
}
