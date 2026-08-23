package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import coil3.compose.AsyncImage
import nz.co.fordwalls.transportercam.MainViewModel
import nz.co.fordwalls.transportercam.database.MediaAsset
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: MainViewModel,
    folderId: Long,
    folderName: String,
    onMediaClick: (Int) -> Unit,
    onAddMedia: () -> Unit,
    onChecklist: () -> Unit,
    onShareAll: (String, List<MediaAsset>) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    val media by viewModel.getMediaForFolder(folderId).collectAsState(initial = emptyList())
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onChecklist) {
                        Icon(Icons.Default.Assignment, contentDescription = "Vehicle Checklist")
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Export ZIP/PDF")
                    }
                    if (media.isNotEmpty()) {
                        IconButton(onClick = { onShareAll(folderName, media) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share All")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddMedia,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Add Media")
            }
        }
    ) { padding ->
        if (media.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No photos or videos for this vehicle.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onChecklist) {
                        Icon(Icons.Default.Assignment, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open Checklist")
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(128.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(2.dp)
            ) {
                itemsIndexed(media) { index, asset ->
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .aspectRatio(1f)
                            .clickable { onMediaClick(index) }
                    ) {
                        AsyncImage(
                            model = File(asset.filePath),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (asset.isVideo) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            )
                        }
                        MediaCloudBadge(
                            asset = asset,
                            onRetry = {
                                viewModel.retryMediaUpload(asset.id) { success, message ->
                                    if (!message.isNullOrBlank()) {
                                        Toast.makeText(context, message, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCloudBadge(asset: MediaAsset, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val state = asset.cloudState.uppercase()
    val linked = !asset.jobId.isNullOrBlank()
    if (!linked && state == "LOCAL_ONLY") return
    val failed = state == "FAILED"
    val ready = state == "READY"
    val background = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        ready -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        ready -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.then(if (failed) Modifier.clickable(onClick = onRetry) else Modifier),
        shape = CircleShape,
        color = background,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when {
                failed -> Icon(Icons.Default.Refresh, contentDescription = "Retry upload", modifier = Modifier.size(15.dp))
                ready -> Icon(Icons.Default.CheckCircle, contentDescription = "Uploaded", modifier = Modifier.size(15.dp))
                else -> Icon(Icons.Default.CloudOff, contentDescription = "Upload pending", modifier = Modifier.size(15.dp))
            }
            Text(
                text = when {
                    failed -> "Retry"
                    ready -> "Uploaded"
                    state == "UPLOADING" -> "Uploading"
                    else -> "Pending"
                },
                color = foreground,
                fontSize = 11.sp
            )
        }
    }
}
