package com.xlythe.compose.camera

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xlythe.view.camera.viewer.ImageLoadListener
import com.xlythe.view.camera.viewer.LargeImageView
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
