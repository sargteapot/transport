package nz.co.fordwalls.transportercam.ui

import android.content.Context
import android.util.Log
import android.graphics.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import nz.co.fordwalls.transportercam.database.Folder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    folderId: Long,
    timestampEnabled: Boolean,
    dateFormat: String,
    gpsEnabled: Boolean,
    imageQuality: Int,
    captureFeedbackEnabled: Boolean,
    onMediaCaptured: (String, Boolean) -> Unit,
    onUpdateLastNote: (String) -> Unit,
    onNextVehicle: (String, Boolean) -> Unit,
    onExistingVehicleSelected: (Long) -> Unit,
    onCheckExisting: suspend (String) -> List<Folder>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    val fusedLocationClient = remember { com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context) }
    var currentLocation by remember { mutableStateOf<android.location.Location?>(null) }

    var lastCapturedPath by remember { mutableStateOf<String?>(null) }
    var lastCapturedIsVideo by remember { mutableStateOf(false) }
    var showFlash by remember { mutableStateOf(false) }

    var showNoteDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }

    LaunchedEffect(gpsEnabled) {
        if (gpsEnabled) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    currentLocation = location
                }
            } catch (e: SecurityException) {
                Log.e("CameraScreen", "Location permission missing", e)
            }
        }
    }
    
    val imageCapture = remember { ImageCapture.Builder().build() }
    val recorder = remember { Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build() }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    
    val previewView = remember { PreviewView(context) }
    
    var isVideoMode by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var currentRecording by remember { mutableStateOf<Recording?>(null) }
    
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var isTorchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    
    var showNextVehicleDialog by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var nextVehicleName by remember { mutableStateOf("") }
    
    var conflictingVehicles by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var showConflictDialog by remember { mutableStateOf(false) }
    
    var focusPoint by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    if (showScanner) {
        OcrScanner(
            onTextScanned = { 
                nextVehicleName = it
                showScanner = false
            },
            onCancel = { showScanner = false }
        )
    }

    LaunchedEffect(isVideoMode) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            try {
                cameraProvider.unbindAll()
                val useCase = if (isVideoMode) videoCapture else imageCapture
                val boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    useCase
                )
                camera = boundCamera
                camera?.cameraControl?.enableTorch(isTorchOn)
            } catch (exc: Exception) {
                Log.e("CameraScreen", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(flashMode) {
        imageCapture.flashMode = flashMode
    }

    LaunchedEffect(isTorchOn, camera) {
        camera?.cameraControl?.enableTorch(isTorchOn)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            focusPoint = offset
                            val factory = previewView.meteringPointFactory
                            val point = factory.createPoint(offset.x, offset.y)
                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            camera?.cameraControl?.startFocusAndMetering(action)
                        }
                    )
                }
        )

        focusPoint?.let { offset ->
            Box(
                modifier = Modifier
                    .offset(
                        x = with(androidx.compose.ui.platform.LocalDensity.current) { offset.x.toDp() - 35.dp },
                        y = with(androidx.compose.ui.platform.LocalDensity.current) { offset.y.toDp() - 35.dp }
                    )
                    .size(70.dp)
                    .background(Color.Transparent, CircleShape)
                    .border(2.dp, Color.Yellow, CircleShape)
            )
            
            LaunchedEffect(offset) {
                kotlinx.coroutines.delay(1000)
                focusPoint = null
            }
        }

        // Top Controls - Glassmorphism
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(top = 48.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            IconButton(
                onClick = {
                    when {
                        !isTorchOn && flashMode == ImageCapture.FLASH_MODE_OFF -> {
                            flashMode = ImageCapture.FLASH_MODE_AUTO
                            isTorchOn = false
                        }
                        !isTorchOn && flashMode == ImageCapture.FLASH_MODE_AUTO -> {
                            flashMode = ImageCapture.FLASH_MODE_ON
                            isTorchOn = true
                        }
                        else -> {
                            flashMode = ImageCapture.FLASH_MODE_OFF
                            isTorchOn = false
                        }
                    }
                },
                modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                val icon = when {
                    isTorchOn -> Icons.Default.FlashOn
                    flashMode == ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                    else -> Icons.Default.FlashOff
                }
                Icon(icon, contentDescription = "Flash Mode", tint = Color.White)
            }

            Row(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.2f), MaterialTheme.shapes.medium)
                    .padding(4.dp)
            ) {
                FilterChip(
                    selected = !isVideoMode,
                    onClick = { if (!isRecording) isVideoMode = false },
                    label = { Text("Photo") },
                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Color.White,
                        selectedLabelColor = Color.Black,
                        selectedContainerColor = Color.White,
                        iconColor = Color.White,
                        selectedLeadingIconColor = Color.Black
                    ),
                    border = null
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = isVideoMode,
                    onClick = { if (!isRecording) isVideoMode = true },
                    label = { Text("Video") },
                    leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Color.White,
                        selectedLabelColor = Color.Black,
                        selectedContainerColor = Color.White,
                        iconColor = Color.White,
                        selectedLeadingIconColor = Color.Black
                    ),
                    border = null
                )
            }
        }

        // Bottom Controls - Glassmorphism
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(bottom = 48.dp, top = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRecording) {
                    Text(
                        "Recording...",
                        color = Color.Red,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(48.dp)
                ) {
                    // Thumbnail / Gallery Link
                    Box(modifier = Modifier.size(48.dp)) {
                        lastCapturedPath?.let { path ->
                            Surface(
                                modifier = Modifier.fillMaxSize().clickable { onBack() },
                                shape = MaterialTheme.shapes.small,
                                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White)
                            ) {
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    // Pro Shutter Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(84.dp)
                            .clickable {
                                if (isVideoMode) {
                                    if (isRecording) {
                                        currentRecording?.stop()
                                        currentRecording = null
                                        isRecording = false
                                    } else {
                                        currentRecording = recordVideo(context, videoCapture, folderId) { path ->
                                            if (captureFeedbackEnabled) {
                                                lastCapturedPath = path
                                                lastCapturedIsVideo = true
                                                showFlash = true
                                            }
                                            onMediaCaptured(path, true)
                                        }
                                        isRecording = true
                                    }
                                } else {
                                    takePhoto(context, imageCapture, cameraExecutor, folderId, timestampEnabled, dateFormat, gpsEnabled, currentLocation, imageQuality) { path ->
                                        if (captureFeedbackEnabled) {
                                            lastCapturedPath = path
                                            lastCapturedIsVideo = false
                                            showFlash = true
                                            // Trigger note dialog
                                            showNoteDialog = true
                                        }
                                        onMediaCaptured(path, false)
                                    }
                                }
                            }
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(4.dp, Color.White)
                        ) {}
                        Surface(
                            modifier = Modifier.size(68.dp),
                            shape = CircleShape,
                            color = if (isRecording) Color.Red else Color.White,
                            tonalElevation = 4.dp
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.Stop else (if (isVideoMode) Icons.Default.Videocam else Icons.Default.CameraAlt),
                                contentDescription = "Capture",
                                tint = if (isRecording) Color.White else Color.Black,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { showNextVehicleDialog = true },
                        modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Vehicle", tint = Color.White)
                    }
                }
                
                if (!isRecording) {
                    Text(
                        "Next Vehicle",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Flash Effect
        if (showFlash) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.8f))
            )
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(100)
                showFlash = false
            }
        }
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Add Note") },
            text = {
                Column {
                    Text("Add details about damages or location for the last photo.")
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = { Text("e.g. Scratch on front bumper") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (noteText.isNotBlank()) {
                        onUpdateLastNote(noteText)
                    }
                    showNoteDialog = false
                    noteText = ""
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false; noteText = "" }) {
                    Text("Skip")
                }
            }
        )
    }

    if (showNextVehicleDialog && !showScanner) {
        AlertDialog(
            onDismissRequest = { showNextVehicleDialog = false },
            title = { Text("Next Vehicle") },
            text = {
                Column {
                    Text("Finish documentation for this vehicle and start a new one?")
                    Spacer(modifier = Modifier.height(16.dp))
                    TextField(
                        value = nextVehicleName,
                        onValueChange = { nextVehicleName = it },
                        placeholder = { Text("Enter Rego/VIN for next vehicle") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showScanner = true }) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan")
                            }
                        }
                    )
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        if (nextVehicleName.isNotBlank()) {
                            coroutineScope.launch {
                                val matches = onCheckExisting(nextVehicleName)
                                if (matches.isNotEmpty()) {
                                    conflictingVehicles = matches
                                    showConflictDialog = true
                                } else {
                                    onNextVehicle(nextVehicleName, true) // Go to Checklist
                                    showNextVehicleDialog = false
                                    nextVehicleName = ""
                                }
                            }
                        }
                    }) {
                        Text("Checklist")
                    }
                    Button(onClick = {
                        if (nextVehicleName.isNotBlank()) {
                            coroutineScope.launch {
                                val matches = onCheckExisting(nextVehicleName)
                                if (matches.isNotEmpty()) {
                                    conflictingVehicles = matches
                                    showConflictDialog = true
                                } else {
                                    onNextVehicle(nextVehicleName, false) // Go to Camera
                                    showNextVehicleDialog = false
                                    nextVehicleName = ""
                                }
                            }
                        }
                    }) {
                        Text("Camera")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showNextVehicleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showConflictDialog) {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            title = { Text("Vehicle Found") },
            text = {
                Column {
                    Text("Vehicle '$nextVehicleName' already has documentation.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select a record to add new photos to, or create a fresh entry.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    conflictingVehicles.forEach { folder ->
                        OutlinedButton(
                            onClick = {
                                onExistingVehicleSelected(folder.id)
                                showConflictDialog = false
                                showNextVehicleDialog = false
                                nextVehicleName = ""
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text("Add to Existing (${dateFormat.format(Date(folder.createdAt))})")
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        onNextVehicle(nextVehicleName, true)
                        showConflictDialog = false
                        showNextVehicleDialog = false
                        nextVehicleName = ""
                    }) {
                        Text("Checklist")
                    }
                    Button(onClick = {
                        onNextVehicle(nextVehicleName, false)
                        showConflictDialog = false
                        showNextVehicleDialog = false
                        nextVehicleName = ""
                    }) {
                        Text("Camera")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showConflictDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    folderId: Long,
    timestampEnabled: Boolean,
    dateFormat: String,
    gpsEnabled: Boolean,
    location: android.location.Location?,
    imageQuality: Int,
    onPhotoCaptured: (String) -> Unit
) {
    val outputDirectory = File(context.filesDir, "folders/$folderId")
    if (!outputDirectory.exists()) outputDirectory.mkdirs()

    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis()) + ".jpg"
    val photoFile = File(outputDirectory, name)

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                if (timestampEnabled || gpsEnabled) {
                    addTimestampAndGpsToImage(photoFile, dateFormat, gpsEnabled, location, imageQuality)
                }
                onPhotoCaptured(photoFile.absolutePath)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraScreen", "Photo capture failed: ${exception.message}", exception)
            }
        }
    )
}

private fun addTimestampAndGpsToImage(file: File, formatPattern: String, gpsEnabled: Boolean, location: android.location.Location?, quality: Int) {
    try {
        val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        )

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        
        val matrix = Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        
        val orientedBitmap = if (orientation != androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val mutableBitmap = orientedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val dateFormat = SimpleDateFormat(formatPattern, Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        
        val textSize = mutableBitmap.height * 0.025f
        
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
            style = Paint.Style.FILL
            setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
        }

        val lines = mutableListOf<String>()
        lines.add(timestamp)
        if (gpsEnabled && location != null) {
            lines.add("GPS: ${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}")
        }

        var currentY = mutableBitmap.height - (mutableBitmap.height * 0.05f)
        val lineSpacing = textSize * 1.2f

        lines.reversed().forEach { line ->
            val bounds = Rect()
            paint.getTextBounds(line, 0, line.length, bounds)
            val x = mutableBitmap.width - bounds.width() - (mutableBitmap.width * 0.05f)
            canvas.drawText(line, x, currentY, paint)
            currentY -= lineSpacing
        }

        file.outputStream().use { out ->
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        
        if (orientedBitmap != bitmap) orientedBitmap.recycle()
        bitmap.recycle()
        mutableBitmap.recycle()
    } catch (e: Exception) {
        Log.e("CameraScreen", "Failed to add timestamp/GPS", e)
    }
}

private fun recordVideo(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    folderId: Long,
    onVideoRecorded: (String) -> Unit
): Recording {
    val outputDirectory = File(context.filesDir, "folders/$folderId")
    if (!outputDirectory.exists()) outputDirectory.mkdirs()

    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis()) + ".mp4"
    val videoFile = File(outputDirectory, name)

    val outputOptions = FileOutputOptions.Builder(videoFile).build()

    return videoCapture.output
        .prepareRecording(context, outputOptions)
        .start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (!event.hasError()) {
                    onVideoRecorded(videoFile.absolutePath)
                } else {
                    Log.e("CameraScreen", "Video recording error: ${event.error}")
                }
            }
        }
}
