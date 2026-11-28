package com.xlythe.compose.camera

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.xlythe.view.camera.edit.PictureEditor
import com.xlythe.view.camera.edit.PictureEdits
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Applies [edits] to a picture and writes the result to [output], suspending until it is
 * written, for a coroutine such as one a `LaunchedEffect` or `rememberCoroutineScope` starts.
 * Cancelling the coroutine abandons the edit, and nothing is written. See [PictureEditor] for
 * what the edit keeps.
 *
 * @param source The picture to read, a file or content Uri, which is never written to.
 * @param output Where to write the edited picture, overwritten if it already exists.
 * @param edits What to change. With none, the picture is converted to [format].
 * @param format One of [PictureEditor]'s formats.
 * @param quality The quality of a lossy format, from 0 to 100.
 * @param maxOutputPixels The most pixels the result may have, or 0 for full resolution.
 * @param motionPhotoPolicy What becomes of a motion photo's clip, one of [PictureEditor]'s
 *   policies.
 * @return [output], once it is written.
 * @throws IOException if the picture could not be edited.
 */
suspend fun editPicture(
    context: Context,
    source: Uri,
    output: File,
    edits: PictureEdits,
    format: Int = PictureEditor.FORMAT_SAME,
    quality: Int = 95,
    maxOutputPixels: Long = 0,
    motionPhotoPolicy: Int = PictureEditor.MOTION_PHOTO_AUTO,
): File = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val editor = PictureEditor(context, source, output, edits)
        editor.outputFormat = format
        editor.quality = quality
        editor.maxOutputPixels = maxOutputPixels
        editor.motionPhotoPolicy = motionPhotoPolicy
        editor.setOnPictureEditedListener(object : PictureEditor.OnPictureEditedListener {
            override fun onPictureEdited(file: File) {
                continuation.resume(file)
            }

            override fun onFailure() {
                continuation.resumeWithException(IOException("Unable to edit $source"))
            }
        })
        // Cancellation can arrive on any thread; the editor is driven from the main one.
        val main = Handler(Looper.getMainLooper())
        continuation.invokeOnCancellation { main.post { editor.cancel() } }
        editor.start()
    }
}
