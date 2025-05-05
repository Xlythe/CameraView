package com.xlythe.compose.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Handler
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xlythe.view.camera.CameraView
import com.xlythe.view.camera.VideoStream
import java.io.File

private val TAG = "Camera"

enum class Quality {
    LOW,
    MEDIUM,
    HIGH,
    MAX
}

enum class Flash {
    ON,
    OFF,
    AUTO
}

enum class LensFacing {
    BACK,
    FRONT
}

interface CameraController {
    fun takePicture(file: File)
    fun startRecording(file: File)
    fun stopRecording()
    fun stream(): VideoStream?
    fun toggleCamera()
    fun confirmPicture()
    fun confirmVideo()
}

private class CameraControllerImpl(
    private val viewProvider: () -> CameraView?
) : CameraController {
    private fun getView(): CameraView? {
        return viewProvider()
    }

    override fun takePicture(file: File) {
        getView()?.takePicture(file)
            ?: Log.e(TAG, "View not available for takePicture")
    }

    override fun startRecording(file: File) {
        getView()?.startRecording(file)
            ?: Log.e(TAG, "View not available for startRecording")
    }

    override fun stopRecording() {
        getView()?.stopRecording()
            ?: Log.e(TAG, "View not available for stopRecording")
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    override fun stream(): VideoStream? {
        return getView()?.stream()
    }

    override fun toggleCamera() {
        getView()?.toggleCamera()
            ?: Log.e(TAG, "View not available for toggleCamera")
    }

    override fun confirmPicture() {
        getView()?.confirmPicture()
            ?: Log.e(TAG, "View not available for confirmPicture")
    }

    override fun confirmVideo() {
        getView()?.confirmVideo()
            ?: Log.e(TAG, "View not available for confirmVideo")
    }
}

/**
 * A Composable that displays a preview of the camera.
 *
 * **Important:** This composable requires the [Manifest.permission.CAMERA],
 * [Manifest.permission.RECORD_AUDIO] , and [Manifest.permission.WRITE_EXTERNAL_STORAGE] permissions
 * to be granted *before* it is included in the composition. The underlying view
 * will not be opened or functional otherwise. Use conditional rendering based on
 * permission status at the call site.
 *
 * @param modifier Modifier for layout.
 * @param quality The quality for image and video outputs.
 * @param flash The active flash strategy.
 * @param lensFacing Which camera (front or back) to use.
 * @param pinchToZoom Allows the user to pinch the camera to zoom in/out.
 * @param maxVideoDuration The maximum video duration in milliseconds.
 * @param maxVideoSize The maximum video size in bytes.
 * @param imageConfirmationEnabled Requires images to be confirmed via {@link CameraController#confirmPicture()} before saved. A preview of the image will be displayed.
 * @param videoConfirmationEnabled Requires videos to be confirmed via {@link CameraController#confirmVideo()} before saved. A preview of the video will be displayed.
 * @param matchPreviewAspectRatioEnabled Attempts to match image and video outputs to the preview aspect ratio.
 * @param controller Exposes the methods {@link CameraController#takePicture(File)},
 *  * {@link CameraController#startRecording(File)} and {@link CameraController#stopRecording()}.
 */
@RequiresPermission(
    allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE]
)
@Composable
fun Camera(
    modifier: Modifier = Modifier,
    quality: Quality = Quality.HIGH,
    flash: Flash = Flash.AUTO,
    lensFacing: LensFacing = LensFacing.BACK,
    pinchToZoom: Boolean = true,
    maxVideoDuration: Long? = null,
    maxVideoSize: Long? = null,
    imageConfirmationEnabled: Boolean = false,
    videoConfirmationEnabled: Boolean = false,
    matchPreviewAspectRatioEnabled: Boolean = true,
    controller: MutableState<CameraController?>,
    onImageConfirmation: () -> Unit = {},
    onImageCaptured: (file: File) -> Unit = {},
    onImageCaptureFailed: () -> Unit = {},
    onVideoConfirmation: () -> Unit = {},
    onVideoCaptured: (file: File) -> Unit = {},
    onVideoCaptureFailed: () -> Unit = {},
) {
    // Grab system variables
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize and then cache our CameraView.
    val cameraView = remember {
        CameraView(context).apply {
            this.onFinishInflate()

            this.setOnImageCapturedListener(object : CameraView.OnImageCapturedListener {
                override fun onImageConfirmation() {
                    onImageConfirmation()
                }

                override fun onImageCaptured(file: File) {
                    onImageCaptured(file)
                }

                override fun onFailure() {
                    onImageCaptureFailed()
                }
            })

            this.setOnVideoCapturedListener(object : CameraView.OnVideoCapturedListener {
                override fun onVideoConfirmation() {
                    onVideoConfirmation()
                }

                override fun onVideoCaptured(file: File) {
                    onVideoCaptured(file)
                }

                override fun onFailure() {
                    onVideoCaptureFailed()
                }
            })
        }
    }

    // Cache our camera controller.
    val cameraController = remember(cameraView) {
        CameraControllerImpl { cameraView }
    }
    LaunchedEffect(cameraController) {
        controller.value = cameraController
    }

    // Monitor lifecycle states.
    DisposableEffect(lifecycleOwner) {
        // Listen to onStart/onStop
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    cameraView.open()
                }
                Lifecycle.Event.ON_STOP -> {
                    cameraView.close()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    cameraView.close()
                }
                else -> { /* Do nothing for other events */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Listen to Display changes
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displayListener = object : DisplayListener {
            var lastKnownWidth: Int = -1
            var lastKnownHeight: Int = -1
            var lastKnownRotation: Int = -1

            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            @SuppressLint("WrongConstant", "MissingPermission")
            override fun onDisplayChanged(displayId: Int) {
                if (cameraView.display?.displayId != displayId) {
                    return
                }

                val display = displayManager.getDisplay(displayId)
                if (lastKnownWidth == display.width && lastKnownHeight == display.height && lastKnownRotation == display.rotation) {
                    return
                }

                lastKnownWidth = display.width
                lastKnownHeight = display.height
                lastKnownRotation = display.rotation

                if (cameraView.isOpen) {
                    cameraView.close()
                    cameraView.open()
                }
            }
        }
        displayManager.registerDisplayListener(displayListener, Handler())

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            displayManager.unregisterDisplayListener(displayListener)

            cameraView.close()
            if (controller.value === cameraController) {
                controller.value = null
            }
            cameraView.setOnImageCapturedListener(null)
            cameraView.setOnVideoCapturedListener(null)
        }
    }

    // Wrap our View in a Composable, and then keep it up to date on updates.
    AndroidView(factory = { _ -> cameraView }, update = { view ->
        view.quality = when (quality) {
            Quality.LOW -> CameraView.Quality.LOW
            Quality.MEDIUM -> CameraView.Quality.MEDIUM
            Quality.HIGH -> CameraView.Quality.HIGH
            Quality.MAX -> CameraView.Quality.MAX
        }
        view.flash = when (flash) {
            Flash.AUTO -> CameraView.Flash.AUTO
            Flash.ON -> CameraView.Flash.ON
            Flash.OFF -> CameraView.Flash.OFF
        }
        view.lensFacing =  when (lensFacing) {
            LensFacing.BACK -> CameraView.LensFacing.BACK
            LensFacing.FRONT -> CameraView.LensFacing.FRONT
        }
        view.isPinchToZoomEnabled = pinchToZoom
        if (maxVideoDuration != null) {
            view.maxVideoDuration = maxVideoDuration
        } else {
            view.maxVideoDuration = CameraView.INDEFINITE_VIDEO_DURATION.toLong()
        }
        if (maxVideoSize != null) {
            view.maxVideoSize = maxVideoSize
        } else {
            view.maxVideoSize = CameraView.INDEFINITE_VIDEO_SIZE.toLong()
        }
        view.isImageConfirmationEnabled = imageConfirmationEnabled
        view.isVideoConfirmationEnabled = videoConfirmationEnabled
        view.setMatchPreviewAspectRatio(matchPreviewAspectRatioEnabled)
    }, modifier = modifier)
}
