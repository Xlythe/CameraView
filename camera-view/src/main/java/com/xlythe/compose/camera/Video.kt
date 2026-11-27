package com.xlythe.compose.camera

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.xlythe.view.camera.VideoStream
import com.xlythe.view.camera.VideoView
import java.io.File

private const val TAG = "Camera"

interface VideoController {
    fun seekToFirstFrame()
    fun play()
    fun pause()
}

private class VideoControllerImpl(
    private val viewProvider: () -> VideoView?
) : VideoController {
    private fun getView(): VideoView? {
        return viewProvider()
    }

    override fun seekToFirstFrame() {
        getView()?.seekToFirstFrame()
            ?: Log.e(TAG, "View not available for seekToFirstFrame")
    }

    override fun play() {
        getView()?.play()
            ?: Log.e(TAG, "View not available for play")
    }

    override fun pause() {
        getView()?.pause()
            ?: Log.e(TAG, "View not available for pause")
    }
}

/**
 * A Composable that displays a video.
 *
 * @param modifier Modifier for layout.
 * @param mirror If the video should be mirrored.
 * @param loop If the video should loop after completing.
 * @param volume The volume from 0.0f to 1.0f.
 * @param file The file to play.
 * @param uri The uri to play, such as the one [Camera]'s onVideoSaved reports.
 * @param stream The stream to play.
 * @param controller Exposes the methods {@link CameraController#takePicture(File)},
 *  * {@link CameraController#startRecording(File)} and {@link CameraController#stopRecording()}.
 */
@Composable
fun Video(
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    loop: Boolean = false,
    volume: Float = 1f,
    file: File? = null,
    uri: Uri? = null,
    stream: VideoStream? = null,
    controller: MutableState<VideoController?>,
    onCompletion: () -> Unit = {},
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {},
) {
    // Grab system variables
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The VideoView below is remembered for the life of the composition, so the listeners
    // installed on it capture whichever lambdas the first composition happened to pass. A caller
    // whose callbacks close over state would then keep being called with the stale ones. Track
    // the latest instead, so the listeners read them at the moment they fire.
    val currentOnCompletion by rememberUpdatedState(onCompletion)
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnPause by rememberUpdatedState(onPause)

    // Initialize and then cache our VideoView.
    val videoView = remember {
        VideoView(context).apply {
            this.setOnCompletionListener { currentOnCompletion() }

            this.setEventListener(object : VideoView.EventListener {
                override fun onPlay() {
                    currentOnPlay()
                }

                override fun onPause() {
                    currentOnPause()
                }
            })
        }
    }

    // Cache our video controller.
    val videoController = remember(videoView) {
        VideoControllerImpl { videoView }
    }
    DisposableEffect(videoController) {
        controller.value = videoController
        onDispose {}
    }

    // Monitor lifecycle states.
    DisposableEffect(lifecycleOwner) {
        onDispose {
            videoView.pause()
            if (controller.value === videoController) {
                controller.value = null
            }
            videoView.setEventListener(null)
            videoView.setOnCompletionListener(null)
        }
    }

    // Wrap our View in a Composable, and then keep it up to date on updates.
    AndroidView(factory = { _ -> videoView }, update = { view ->
        view.setShouldMirror(mirror)
        view.setShouldLoop(loop)
        view.setVolume(volume)
        if (file != null) {
            view.file = file
        }
        if (uri != null) {
            view.setUri(uri)
        }
        if (stream != null) {
            view.stream = stream
        }
    }, modifier = modifier)
}
