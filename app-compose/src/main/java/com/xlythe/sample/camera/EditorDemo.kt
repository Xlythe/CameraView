package com.xlythe.sample.camera

import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xlythe.compose.camera.BrushPanel
import com.xlythe.compose.camera.DrawingSurface
import com.xlythe.compose.camera.Media
import com.xlythe.compose.camera.editPicture
import com.xlythe.view.camera.MediaTags
import com.xlythe.view.camera.edit.Brush
import com.xlythe.view.camera.edit.Drawing
import com.xlythe.view.camera.edit.DrawingView
import com.xlythe.view.camera.edit.PictureEditor
import com.xlythe.view.camera.edit.PictureEdits
import com.xlythe.view.camera.gl.ColorMatrix
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The formats the demo offers, by name. */
private val FORMATS = listOf(
    "Same" to PictureEditor.FORMAT_SAME,
    "JPEG" to PictureEditor.FORMAT_JPEG,
    "PNG" to PictureEditor.FORMAT_PNG,
    "WebP" to PictureEditor.FORMAT_WEBP,
    "HEIF" to PictureEditor.FORMAT_HEIF,
    "AVIF" to PictureEditor.FORMAT_AVIF,
)

/**
 * Edits a picture with the library's pieces: [DrawingSurface] to draw on it, [BrushPanel] to
 * pick the brush, and [editPicture] to turn, flip, crop and filter it with the drawing on it and
 * write it in any format the device can, keeping its metadata, then shows the result.
 */
@Composable
fun EditorDemoScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember(initialUri) { mutableStateOf(initialUri) }
    var edits by remember(uri) { mutableStateOf(PictureEdits.Builder().build()) }
    var format by remember { mutableStateOf(PictureEditor.FORMAT_SAME) }
    var supported by remember { mutableStateOf(setOf(PictureEditor.FORMAT_SAME)) }
    var edited by remember(uri) { mutableStateOf<File?>(null) }
    var summary by remember(uri) { mutableStateOf("") }
    var status by remember(uri) { mutableStateOf(if (uri == null) "Open a picture to edit" else "") }
    var preview by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var drawing by remember(uri) { mutableStateOf<Drawing?>(null) }
    var brush by remember { mutableStateOf(Brush.PEN) }
    var drawingView by remember { mutableStateOf<DrawingView?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uri = picked
        }
    }
    LaunchedEffect(uri) {
        val source = uri ?: return@LaunchedEffect
        // The picture upright and small, to draw over; the drawing is in its own pixels.
        val file = File(context.cacheDir, "editor-demo-preview.png")
        try {
            editPicture(context, source, file, PictureEdits.Builder().build(),
                format = PictureEditor.FORMAT_PNG, maxOutputPixels = 1600L * 1600L)
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) }
            preview = bitmap
            drawing = Drawing(bitmap.width.toFloat(), bitmap.height.toFloat())
        } catch (e: IOException) {
            status = "This device cannot show this file"
        }
    }
    LaunchedEffect(Unit) {
        supported = withContext(Dispatchers.IO) {
            FORMATS.map { it.second }.filter { PictureEditor.isOutputFormatSupported(it) }.toSet()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val shown = preview
            if (edited != null || shown == null) {
                Media(
                    modifier = Modifier.fillMaxSize(),
                    file = edited,
                    uri = if (edited == null) null else uri,
                    onLoaded = { status = "" },
                    onFailed = { status = "This device cannot show this file" },
                )
            } else {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "The picture being edited",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                DrawingSurface(
                    modifier = Modifier.fillMaxSize(),
                    drawing = drawing,
                    brush = brush,
                    onDrawingChanged = { drawing = it },
                    onView = { drawingView = it },
                )
            }
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0x80000000)).padding(8.dp)
                )
            }
            if (status.isNotEmpty()) {
                Text(status, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
        }
        BrushPanel(
            modifier = Modifier.fillMaxWidth(),
            brush = brush,
            onBrushChange = { brush = it },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Open") }
            Button(onClick = { drawingView?.undo() }) { Text("Undo") }
            Button(onClick = { drawingView?.redo() }) { Text("Redo") }
            if (edited != null) {
                Button(onClick = { edited = null; summary = "" }) { Text("Back") }
            }
            FilterChip(
                selected = edits.rotationDegrees != 0,
                onClick = {
                    edits = PictureEdits.Builder(edits)
                        .setRotationDegrees(edits.rotationDegrees + 90).build()
                },
                label = { Text("Turn ${edits.rotationDegrees}°") }
            )
            FilterChip(
                selected = edits.isFlippedHorizontally,
                onClick = {
                    edits = PictureEdits.Builder(edits)
                        .setFlippedHorizontally(!edits.isFlippedHorizontally).build()
                },
                label = { Text("Flip") }
            )
            FilterChip(
                selected = edits.crop != null,
                onClick = {
                    edits = PictureEdits.Builder(edits)
                        .setCrop(if (edits.crop == null) RectF(0.2f, 0.2f, 0.8f, 0.8f) else null)
                        .build()
                },
                label = { Text("Crop") }
            )
            FilterChip(
                selected = edits.colorMatrix != null,
                onClick = {
                    edits = PictureEdits.Builder(edits)
                        .setColorMatrix(if (edits.colorMatrix == null) ColorMatrix.sepia() else null)
                        .build()
                },
                label = { Text("Sepia") }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = uri != null,
                onClick = {
                    val source = uri ?: return@Button
                    val output = File(context.getExternalFilesDir(null) ?: context.filesDir,
                        "edited-${System.currentTimeMillis()}")
                    status = "Saving…"
                    scope.launch {
                        try {
                            val ink = drawing
                            val withInk = if (ink == null || ink.isEmpty) edits else {
                                val overlay = withContext(Dispatchers.IO) {
                                    val scale = minOf(1f, 2048f / maxOf(ink.width, ink.height))
                                    ink.toBitmap((ink.width * scale).toInt().coerceAtLeast(1),
                                        (ink.height * scale).toInt().coerceAtLeast(1))
                                }
                                PictureEdits.Builder(edits).setOverlay(overlay).build()
                            }
                            val file = editPicture(context, source, output, withInk, format, quality = 90)
                            edited?.delete()
                            edited = file
                            summary = withContext(Dispatchers.IO) {
                                describeMedia(MediaTags.open(file))
                            }
                            status = ""
                        } catch (e: IOException) {
                            Log.w("CameraSample", "Could not edit $source", e)
                            status = "This picture could not be edited"
                        }
                    }
                }
            ) { Text("Save") }
            FORMATS.forEach { (name, value) ->
                FilterChip(
                    selected = format == value,
                    enabled = value in supported,
                    onClick = { format = value },
                    label = { Text(name) }
                )
            }
        }
    }
}
