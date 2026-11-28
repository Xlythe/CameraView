package com.xlythe.compose.camera

import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xlythe.view.camera.viewer.ImageLoadListener
import com.xlythe.view.camera.viewer.LargeImageView
import com.xlythe.view.camera.viewer.MediaListener
import com.xlythe.view.camera.viewer.MediaView
import com.xlythe.view.camera.viewer.PanoramaView
import com.xlythe.view.camera.viewer.PhotosphereView
import java.io.File

/**
 * Shows a picture of any size, a DNG included, that can be zoomed in on until its own pixels
 * show: pinch or double-tap to zoom, drag or fling to pan. Only the part on screen is decoded at
 * full detail. See [LargeImageView] for the memory it holds.
 *
 * @param modifier Modifier for layout.
 * @param file The picture to show. Takes precedence over [uri].
 * @param uri The picture to show, such as the one [Camera]'s onImageSaved reports.
 * @param onLoaded Called once the picture is showing.
 * @param onFailed Called if it cannot be shown.
 */
@Composable
fun LargeImage(
    modifier: Modifier = Modifier,
    file: File? = null,
    uri: Uri? = null,
    onLoaded: () -> Unit = {},
    onFailed: () -> Unit = {},
) {
    val listener = rememberLoadListener(onLoaded, onFailed)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            LargeImageView(context).apply { setImageLoadListener(listener) }
        },
        update = { view ->
            if (file != null) view.setFile(file) else view.setUri(uri)
        },
    )
}

/**
 * Shows a panorama as a strip to pan along, filling the height and wrapping round if it goes all
 * the way round. See [PanoramaView].
 *
 * @param modifier Modifier for layout.
 * @param file The panorama to show. Takes precedence over [uri].
 * @param uri The panorama to show.
 * @param onLoaded Called once the panorama is showing.
 * @param onFailed Called if it cannot be shown.
 */
@Composable
fun Panorama(
    modifier: Modifier = Modifier,
    file: File? = null,
    uri: Uri? = null,
    onLoaded: () -> Unit = {},
    onFailed: () -> Unit = {},
) {
    val listener = rememberLoadListener(onLoaded, onFailed)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PanoramaView(context).apply { setImageLoadListener(listener) }
        },
        update = { view ->
            if (file != null) view.setFile(file) else view.setUri(uri)
        },
    )
}

/**
 * Shows a photo sphere from the inside, to drag, fling and pinch around. A partial sphere is shown
 * where it was taken, and the rest left black. See [PhotosphereView].
 *
 * @param modifier Modifier for layout.
 * @param file The photo sphere to show. Takes precedence over [uri].
 * @param uri The photo sphere to show.
 * @param gyroscope Whether turning the phone looks around too, where it has a rotation sensor.
 * @param onLoaded Called once the photo sphere is showing.
 * @param onFailed Called if it cannot be shown.
 */
@Composable
fun Photosphere(
    modifier: Modifier = Modifier,
    file: File? = null,
    uri: Uri? = null,
    gyroscope: Boolean = false,
    onLoaded: () -> Unit = {},
    onFailed: () -> Unit = {},
) {
    val listener = rememberLoadListener(onLoaded, onFailed)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PhotosphereView(context).apply { setImageLoadListener(listener) }
        },
        update = { view ->
            if (file != null) view.setFile(file) else view.setUri(uri)
            view.isGyroscopeEnabled = gyroscope
        },
    )
}

/**
 * Shows any picture, video or sound file the way it is meant to be seen, worked out from its
 * contents: a picture to zoom, a panorama to pan, a photo sphere or 360° video to look around, a
 * video or recording to tap to play, or a motion photo to hold to play. HDR content shows in HDR
 * where the device can. See [MediaView] for what it draws and how to restyle it.
 *
 * @param modifier Modifier for layout.
 * @param file The file to show. Takes precedence over [uri].
 * @param uri The file to show, such as one in the gallery.
 * @param type What to show the file as, one of [MediaView]'s media types, or
 *     [MediaView.UNKNOWN] for whatever it is found to be.
 * @param gyroscope Whether turning the phone looks around a sphere.
 * @param tapToPlay Whether a tap plays and pauses.
 * @param holdToPlay Whether holding a motion photo plays its clip.
 * @param showProgress Whether a thin line shows how far playback has got.
 * @param hdr Whether HDR content puts the window in HDR colour mode.
 * @param showError Whether the library's icon shows in place of a file that cannot be shown;
 *     turn it off to show your own from [onFailed].
 * @param onLoaded Called once the file is showing, with what it shows as.
 * @param onFailed Called if it cannot be shown, including after it showed if it cannot play.
 */
@Composable
fun Media(
    modifier: Modifier = Modifier,
    file: File? = null,
    uri: Uri? = null,
    @MediaView.MediaType type: Int = MediaView.UNKNOWN,
    gyroscope: Boolean = false,
    tapToPlay: Boolean = true,
    holdToPlay: Boolean = true,
    showProgress: Boolean = true,
    hdr: Boolean = true,
    showError: Boolean = true,
    onLoaded: (Int) -> Unit = {},
    onFailed: () -> Unit = {},
) {
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    val currentOnFailed by rememberUpdatedState(onFailed)
    val listener = remember {
        object : MediaListener {
            override fun onMediaLoaded(type: Int) = currentOnLoaded(type)
            override fun onMediaFailed() = currentOnFailed()
        }
    }
    // The library's own error icon, kept from the view that made it to put back when asked.
    val libraryError = remember { arrayOfNulls<Drawable>(1) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MediaView(context).apply {
                setMediaListener(listener)
                libraryError[0] = errorPlaceholder
            }
        },
        update = { view ->
            view.setMediaType(type)
            view.errorPlaceholder = if (showError) libraryError[0] else null
            view.isGyroscopeEnabled = gyroscope
            view.isTapToPlayEnabled = tapToPlay
            view.isHoldToPlayEnabled = holdToPlay
            view.isProgressVisible = showProgress
            view.isHdrEnabled = hdr
            if (file != null) view.setFile(file) else view.setUri(uri)
        },
    )
}

/**
 * A listener that calls whichever lambdas the latest composition passed, since the view keeps
 * the listener it was first given.
 */
@Composable
private fun rememberLoadListener(onLoaded: () -> Unit, onFailed: () -> Unit): ImageLoadListener {
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    val currentOnFailed by rememberUpdatedState(onFailed)
    return remember {
        object : ImageLoadListener {
            override fun onImageLoaded() = currentOnLoaded()
            override fun onImageFailed() = currentOnFailed()
        }
    }
}
