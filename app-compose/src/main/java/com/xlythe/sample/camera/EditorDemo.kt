package com.xlythe.sample.camera

import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xlythe.compose.camera.Media
import com.xlythe.compose.camera.editPicture
import com.xlythe.view.camera.MediaTags
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
 * Edits a picture with the library's [editPicture]: turns, flips, crops and filters it, and
 * writes it in any format the device can, keeping its metadata, then shows the result.
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uri = picked
        }
    }
    LaunchedEffect(Unit) {
        supported = withContext(Dispatchers.IO) {
            FORMATS.map { it.second }.filter { PictureEditor.isOutputFormatSupported(it) }.toSet()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Media(
                modifier = Modifier.fillMaxSize(),
                file = edited,
                uri = uri,
                onLoaded = { status = "" },
                onFailed = { status = "This device cannot show this file" },
            )
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
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Open") }
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
                            val file = editPicture(context, source, output, edits, format, quality = 90)
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
