package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import android.widget.VideoView
import android.widget.MediaController
import coil3.compose.AsyncImage
import nz.co.fordwalls.transportercam.database.MediaAsset
import java.io.File
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailScreen(
    folderName: String,
    mediaAssets: List<MediaAsset>,
    initialIndex: Int,
    onDelete: (MediaAsset) -> Unit,
    onUpdateNote: (Long, String?) -> Unit,
    onShare: (MediaAsset) -> Unit,
    onBack: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    
    var isZoomed by remember { mutableStateOf(false) }
    
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { mediaAssets.size })
    val currentAsset = if (mediaAssets.isNotEmpty()) mediaAssets[pagerState.currentPage] else null

    LaunchedEffect(currentAsset) {
        noteText = currentAsset?.notes ?: ""
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            text = folderName, 
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (currentAsset?.isVideo == true) "Video" else "Photo", 
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (currentAsset != null) {
                        IconButton(onClick = { showNoteDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Note")
                        }
                        IconButton(onClick = { onShare(currentAsset) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (mediaAssets.isEmpty()) {
                Text("No media found", color = Color.White)
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    userScrollEnabled = !isZoomed
                ) { page ->
                    val asset = mediaAssets[page]
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (asset.isVideo) {
                            AndroidView(
                                factory = { context ->
                                    VideoView(context).apply {
                                        val mediaController = MediaController(context)
                                        mediaController.setAnchorView(this)
                                        setMediaController(mediaController)
                                        setVideoPath(asset.filePath)
                                        start()
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                update = { videoView ->
                                    // Handle updates if needed when swiping back to a video
                                    if (pagerState.currentPage == page && !videoView.isPlaying) {
                                        videoView.setVideoPath(asset.filePath)
                                        videoView.start()
                                    } else if (pagerState.currentPage != page && videoView.isPlaying) {
                                        videoView.pause()
                                    }
                                }
                            )
                        } else {
                            ZoomableImage(
                                model = File(asset.filePath),
                                modifier = Modifier.fillMaxSize(),
                                onZoomChanged = { isZoomed = it }
                            )
                        }
                    }
                }
            }

            // Note overlay
            if (currentAsset?.notes?.isNotEmpty() == true) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                        .padding(8.dp)
                ) {
                    Text(
                        text = currentAsset.notes,
                        color = Color.White,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        if (showNoteDialog && currentAsset != null) {
            AlertDialog(
                onDismissRequest = { showNoteDialog = false },
                title = { Text("Photo Note") },
                text = {
                    TextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = { Text("Add a caption or note (e.g. scratch found)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        onUpdateNote(currentAsset.id, if (noteText.isBlank()) null else noteText)
                        showNoteDialog = false
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNoteDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showDeleteDialog && currentAsset != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete Item?") },
                text = { Text("This will permanently remove this ${if (currentAsset.isVideo) "video" else "photo"} from your records.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDelete(currentAsset)
                            showDeleteDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun ZoomableImage(
    model: Any,
    modifier: Modifier = Modifier,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier = modifier
            .padding(4.dp)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offset += pan
                        onZoomChanged(true)
                    } else {
                        offset = androidx.compose.ui.geometry.Offset.Zero
                        onZoomChanged(false)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = androidx.compose.ui.geometry.Offset.Zero
                        onZoomChanged(false)
                    }
                )
            }
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentScale = ContentScale.Fit
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MediaDetailScreenPreview() {
    MaterialTheme {
        MediaDetailScreen(
            folderName = "ABC-123",
            mediaAssets = listOf(
                MediaAsset(id = 1, folderId = 1, filePath = "/fake/path1.jpg", isVideo = false, notes = "Front left scratch"),
                MediaAsset(id = 2, folderId = 1, filePath = "/fake/path2.jpg", isVideo = true)
            ),
            initialIndex = 0,
            onDelete = {},
            onUpdateNote = { _, _ -> },
            onShare = {},
            onBack = {}
        )
    }
}
