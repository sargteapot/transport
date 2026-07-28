package nz.co.fordwalls.transportercam.ui

import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

@Composable
fun OcrScanner(
    onTextScanned: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val barcodeScanner = remember { BarcodeScanning.getClient() }
    
    var lastScannedText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    
                    val textTask = textRecognizer.process(image)
                    val barcodeTask = barcodeScanner.process(image)
                    
                    Tasks.whenAllComplete(textTask, barcodeTask).addOnCompleteListener {
                        val imageWidth = image.width.toFloat()
                        val imageHeight = image.height.toFloat()
                        
                        var detectedResult: String? = null
                        
                        // Refined box check:
                        // In portrait, the camera frame is rotated.
                        // For most Android devices, the 0,0 of the camera sensor is at the top right of the phone.
                        // ML Kit coordinates are relative to the image passed (already rotated by imageProxy.rotationDegrees).
                        // Let's broaden the vertical window slightly but shift it DOWN to account for the reported "above the box" offset.
                        
                        // Shifted window even further down based on test feedback.
                        // Moving from center 0.60 to center 0.65.
                        val minX = 0.1f
                        val maxX = 0.9f
                        val minY = 0.60f // Shifted down from 0.55
                        val maxY = 0.70f // Shifted down from 0.65
                        
                        // 1. Check Barcodes first
                        if (barcodeTask.isSuccessful) {
                            val barcodes = barcodeTask.result
                            val result = barcodes.filter { barcode ->
                                val rect = barcode.boundingBox
                                if (rect != null) {
                                    val centerX = rect.centerX() / imageWidth
                                    val centerY = rect.centerY() / imageHeight
                                    centerX in minX..maxX && centerY in minY..maxY
                                } else false
                            }.firstOrNull()?.rawValue
                            
                            if (result != null) {
                                detectedResult = result
                            }
                        }
                        
                        // 2. Check Text if no barcode found
                        if (detectedResult == null && textTask.isSuccessful) {
                            val visionText = textTask.result
                            val textBlocks = visionText.textBlocks.flatMap { it.lines }
                                .filter { line ->
                                    val rect = line.boundingBox
                                    if (rect != null) {
                                        val centerX = rect.centerX() / imageWidth
                                        val centerY = rect.centerY() / imageHeight
                                        centerX in minX..maxX && centerY in minY..maxY
                                    } else false
                                }
                                .map { it.text.filter { char -> char.isLetterOrDigit() } }
                                .filter { it.length == 6 }
                            
                            detectedResult = textBlocks.firstOrNull()
                        }
                        
                        if (detectedResult != null && detectedResult != lastScannedText) {
                            lastScannedText = detectedResult
                        }
                        
                        imageProxy.close()
                    }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("OcrScanner", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        
        // Overlay for guidance
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(120.dp)
                .align(Alignment.Center)
                .border(
                    width = 2.dp, 
                    color = if (lastScannedText.isNotEmpty()) Color.Green else Color.White, 
                    shape = MaterialTheme.shapes.medium
                )
                .background(if (lastScannedText.isNotEmpty()) Color.Green.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f))
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (lastScannedText.isNotEmpty()) {
                Text(
                    text = "Detected: $lastScannedText",
                    color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onTextScanned(lastScannedText) }) {
                    Text("Use This Text")
                }
            } else {
                Text(
                    "Point at Rego, VIN, or Barcode",
                    color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                Text("Cancel")
            }
        }
    }
}
