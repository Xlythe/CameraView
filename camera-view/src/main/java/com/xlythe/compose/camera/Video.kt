package com.xlythe.compose.camera

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
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
    stream: VideoStream? = null,
    controller: MutableState<VideoController?>,
    onCompletion: () -> Unit = {},
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {},
) {
    // Grab system variables
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize and then cache our VideoView.
    val videoView = remember {
        VideoView(context).apply {
            this.setOnCompletionListener { onCompletion() }

            this.setEventListener(object : VideoView.EventListener {
                override fun onPlay() {
                    onPlay()
                }

                override fun onPause() {
                    onPause()
                }
            })
        }
    }

    // Cache our video controller.
    val videoController = remember(videoView) {
        VideoControllerImpl { videoView }
    }
    LaunchedEffect(videoController) {
        controller.value = videoController
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
        view.file = file
        view.stream = stream
    }, modifier = modifier)
}
