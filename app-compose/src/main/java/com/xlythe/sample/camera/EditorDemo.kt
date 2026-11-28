package com.xlythe.sample.camera

import android.content.ContentResolver
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xlythe.compose.camera.BrushPanel
import com.xlythe.compose.camera.DrawingSurface
import com.xlythe.compose.camera.Media
import com.xlythe.compose.camera.Overlays
import com.xlythe.compose.camera.editPicture
import com.xlythe.view.camera.MediaTags
import com.xlythe.view.camera.edit.Brush
import com.xlythe.view.camera.edit.Drawing
import com.xlythe.view.camera.edit.DrawingView
import com.xlythe.view.camera.edit.EditHistory
import com.xlythe.view.camera.edit.Overlay
import com.xlythe.view.camera.edit.OverlayView
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

/** The sample's own stickers, our art: a heart, and a spinning star that animates. */
private val STICKERS = listOf(
    R.raw.sticker_heart to "image/png",
    R.raw.sticker_star to "image/gif",
)

/**
 * Edits a picture with the library's pieces: [DrawingSurface] to draw on it, [Overlays] for
 * stickers and text over the drawing, [BrushPanel] to pick the brush, one [EditHistory] to undo
 * across them, and [editPicture] to turn, flip, crop and filter it with the drawing and stickers
 * on it and write it in any format the device can, keeping its metadata, then shows the result.
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
    var overlayView by remember { mutableStateOf<OverlayView?>(null) }
    var chosen by remember { mutableStateOf<Overlay?>(null) }
    var askingText by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    // One undo across the drawing and the stickers, in the order things were done.
    val history = remember {
        EditHistory().apply {
            setOnHistoryChangedListener {
                canUndo = canUndo()
                canRedo = canRedo()
            }
        }
    }
    val stickers = remember {
        STICKERS.map { (id, mimeType) ->
            val uri = Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.packageName)
                .appendPath(context.resources.getResourceTypeName(id))
                .appendPath(context.resources.getResourceEntryName(id))
                .build()
            Triple(uri, mimeType, BitmapFactory.decodeResource(context.resources, id))
        }
    }
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
            overlayView?.setOverlays(emptyList())
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
            // The editing views stay while the result shows over them, so the steps to undo
            // stay with the views that took them.
            if (shown != null) {
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
                    onView = {
                        drawingView = it
                        history.attach(it)
                    },
                )
                // Stickers and text over the ink; a touch that misses them draws.
                Overlays(
                    modifier = Modifier.fillMaxSize(),
                    canvasWidth = shown.width.toFloat(),
                    canvasHeight = shown.height.toFloat(),
                    dropTarget = true,
                    onSelectionChanged = { chosen = it },
                    onView = {
                        overlayView = it
                        history.attach(it)
                    },
                )
            }
            if (edited != null || shown == null) {
                Media(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    file = edited,
                    uri = if (edited == null) null else uri,
                    onLoaded = { status = "" },
                    onFailed = { status = "This device cannot show this file" },
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
            stickers.forEach { (stickerUri, mimeType, thumbnail) ->
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "A sticker to add",
                    modifier = Modifier.size(48.dp)
                        .clickable { overlayView?.addImage(stickerUri, mimeType, null) },
                )
            }
            Button(onClick = { askingText = true }) { Text("Text") }
            val selected = chosen
            Button(
                enabled = selected != null,
                onClick = { selected?.let { overlayView?.bringToFront(it.id) } }
            ) { Text("To front") }
            Button(
                enabled = selected != null,
                onClick = { selected?.let { overlayView?.sendToBack(it.id) } }
            ) { Text("To back") }
        }
        if (askingText) {
            val editing = chosen?.takeIf { it.kind == Overlay.KIND_TEXT }
            var text by remember { mutableStateOf(editing?.text?.toString() ?: "") }
            AlertDialog(
                onDismissRequest = { askingText = false },
                text = {
                    TextField(value = text, onValueChange = { text = it }, singleLine = true,
                        placeholder = { Text("Words to add") })
                },
                confirmButton = {
                    TextButton(onClick = {
                        askingText = false
                        val words = text.trim()
                        if (words.isNotEmpty()) {
                            if (editing != null) {
                                overlayView?.updateOverlay(
                                    Overlay.Builder(editing).setText(words).build())
                            } else {
                                overlayView?.addText(words, Color.White.toArgb())
                            }
                        }
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { askingText = false }) { Text("Cancel") } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Open") }
            Button(enabled = canUndo, onClick = { history.undo() }) { Text("Undo") }
            Button(enabled = canRedo, onClick = { history.redo() }) { Text("Redo") }
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
                            val items = overlayView
                            val picture = preview
                            val withOverlay = if (picture == null) edits else {
                                val scale = minOf(1f, 2048f / maxOf(picture.width, picture.height))
                                val width = (picture.width * scale).toInt().coerceAtLeast(1)
                                val height = (picture.height * scale).toInt().coerceAtLeast(1)
                                // Stickers are drawn on the main thread, the ink beneath them off it.
                                val stickerLayer = items?.takeIf { it.overlays.isNotEmpty() }
                                    ?.toBitmap(width, height, 0)
                                val overlay = if (ink == null || ink.isEmpty) stickerLayer else {
                                    withContext(Dispatchers.IO) {
                                        ink.toBitmap(width, height).also { layer ->
                                            stickerLayer?.let {
                                                Canvas(layer).drawBitmap(it, 0f, 0f, null)
                                            }
                                        }
                                    }
                                }
                                if (overlay == null) edits
                                else PictureEdits.Builder(edits).setOverlay(overlay).build()
                            }
                            val file = editPicture(context, source, output, withOverlay, format, quality = 90)
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
