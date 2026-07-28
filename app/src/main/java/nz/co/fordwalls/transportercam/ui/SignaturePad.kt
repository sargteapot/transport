package nz.co.fordwalls.transportercam.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap

@Composable
fun SignaturePad(
    onSave: (Bitmap) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val path = remember { Path() }
    var lastTouch by remember { mutableStateOf<Offset?>(null) }
    var pathVersion by remember { mutableIntStateOf(0) }

    Column(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                path.moveTo(offset.x, offset.y)
                                lastTouch = offset
                                pathVersion++
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                path.lineTo(change.position.x, change.position.y)
                                pathVersion++
                            }
                        )
                    }
            ) {
                // Dummy usage of pathVersion to trigger recomposition
                pathVersion
                drawPath(
                    path = path,
                    color = Color.Black,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = { 
                    path.reset()
                    pathVersion++
                    onClear()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Clear, null)
                Spacer(Modifier.width(8.dp))
                Text("Clear")
            }

            Button(
                onClick = {
                    val bitmap = createBitmap(800, 400, Bitmap.Config.ARGB_8888)
                    bitmap.applyCanvas {
                        drawColor(android.graphics.Color.WHITE)
                        val paint = Paint().apply {
                            color = android.graphics.Color.BLACK
                            strokeWidth = 10f
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            isAntiAlias = true
                        }
                        drawPath(path.asAndroidPath(), paint)
                    }
                    onSave(bitmap)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Done, null)
                Spacer(Modifier.width(8.dp))
                Text("Confirm")
            }
        }
    }
}
