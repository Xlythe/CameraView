package com.xlythe.compose.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.util.SparseArray
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xlythe.view.camera.Barcode
import com.xlythe.view.camera.CameraView
import com.xlythe.view.camera.VideoStream
import java.io.File

private const val TAG = "Camera"

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

    /** Records audio alongside the video, so the microphone is needed here and not at Camera. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(file: File)
    fun stopRecording()
    fun stream(): VideoStream?
    fun toggleCamera()
    fun confirmPicture()
    fun confirmVideo()

    /** Discards a picture awaiting confirmation, deleting its file. */
    fun rejectPicture()

    /** Discards a video awaiting confirmation, deleting its file. */
    fun rejectVideo()
    fun enterBarcodeScanner(listener: (List<Barcode>) -> Unit, @Barcode.Format vararg formats: Int)
    fun exitBarcodeScanner()
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
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

    override fun rejectPicture() {
        getView()?.rejectPicture()
            ?: Log.e(TAG, "View not available for rejectPicture")
    }

    override fun rejectVideo() {
        getView()?.rejectVideo()
            ?: Log.e(TAG, "View not available for rejectVideo")
    }

    @SuppressLint("MissingPermission", "WrongConstant")
    override fun enterBarcodeScanner(listener: (List<Barcode>) -> Unit, @Barcode.Format vararg formats: Int) {
        if (formats.isNotEmpty()) {
            val first = formats[0]
            val rest = if (formats.size > 1) formats.sliceArray(1 until formats.size) else IntArray(0)
            getView()?.enterBarcodeScanner({ barcodes -> listener(barcodes) }, first, *rest)
                ?: Log.e(TAG, "View not available for enterBarcodeScanner")
        } else {
            getView()?.enterBarcodeScanner({ barcodes -> listener(barcodes) }, com.xlythe.view.camera.Barcode.Format.QR_CODE)
                ?: Log.e(TAG, "View not available for enterBarcodeScanner")
        }
    }

    override fun exitBarcodeScanner() {
        getView()?.exitBarcodeScanner()
            ?: Log.e(TAG, "View not available for exitBarcodeScanner")
    }
}

/**
 * A Composable that displays a preview of the camera.
 *
 * **Important:** This composable requires [Manifest.permission.CAMERA] to be granted *before* it
 * is included in the composition. The underlying view will not be opened or functional otherwise.
 * Use conditional rendering based on permission status at the call site.
 *
 * Showing a preview and taking pictures need nothing else. The microphone is only required by
 * [CameraController.startRecording] and [CameraController.stream], which ask for it themselves,
 * and outputs are written to the [File] you pass in, so no storage permission is involved.
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
@RequiresPermission(Manifest.permission.CAMERA)
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
    onCameraOpened: () -> Unit = {},
    onCameraClosed: () -> Unit = {},
) {
    // Grab system variables
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The CameraView below is remembered for the life of the composition, so the listeners
    // installed on it capture whichever lambdas the first composition happened to pass. A caller
    // whose callbacks close over state would then keep being called with the stale ones. Track
    // the latest instead, so the listeners read them at the moment they fire.
    val currentOnImageConfirmation by rememberUpdatedState(onImageConfirmation)
    val currentOnImageCaptured by rememberUpdatedState(onImageCaptured)
    val currentOnImageCaptureFailed by rememberUpdatedState(onImageCaptureFailed)
    val currentOnVideoConfirmation by rememberUpdatedState(onVideoConfirmation)
    val currentOnVideoCaptured by rememberUpdatedState(onVideoCaptured)
    val currentOnVideoCaptureFailed by rememberUpdatedState(onVideoCaptureFailed)
    val currentOnCameraOpened by rememberUpdatedState(onCameraOpened)
    val currentOnCameraClosed by rememberUpdatedState(onCameraClosed)

    // Initialize and then cache our CameraView.
    val cameraView = remember {
        object : CameraView(context) {
            override fun onClose() {
                onSaveInstanceState()
                super.onClose()
            }
        }.apply {
            this.onFinishInflate()

            this.setOnImageCapturedListener(object : CameraView.OnImageCapturedListener {
                override fun onImageConfirmation() {
                    currentOnImageConfirmation()
                }

                override fun onImageCaptured(file: File) {
                    currentOnImageCaptured(file)
                }

                override fun onFailure() {
                    currentOnImageCaptureFailed()
                }
            })

            this.setOnVideoCapturedListener(object : CameraView.OnVideoCapturedListener {
                override fun onVideoConfirmation() {
                    currentOnVideoConfirmation()
                }

                override fun onVideoCaptured(file: File) {
                    currentOnVideoCaptured(file)
                }

                override fun onFailure() {
                    currentOnVideoCaptureFailed()
                }
            })

            this.setOnCameraStateChangedListener(object : CameraView.OnCameraStateChangedListener {
                override fun onCameraOpened() {
                    currentOnCameraOpened()
                }

                override fun onCameraClosed() {
                    currentOnCameraClosed()
                }
            })
        }
    }

    // Cache our camera controller.
    val cameraController = remember(cameraView) {
        CameraControllerImpl { cameraView }
    }
    DisposableEffect(cameraController) {
        controller.value = cameraController
        onDispose {}
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

                val display = cameraView.display ?: return
                val displaySize = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(displaySize)
                val width = displaySize.x
                val height = displaySize.y
                if (lastKnownWidth == width && lastKnownHeight == height && lastKnownRotation == display.rotation) {
                    return
                }

                lastKnownWidth = width
                lastKnownHeight = height
                lastKnownRotation = display.rotation

                if (cameraView.isOpen) {
                    cameraView.close()
                    cameraView.open()
                }
            }
        }
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            displayManager.unregisterDisplayListener(displayListener)

            val sparseArray = SparseArray<Parcelable>()
            cameraView.saveHierarchyState(sparseArray)
            cameraView.close()
            if (controller.value === cameraController) {
                controller.value = null
            }
            cameraView.setOnImageCapturedListener(null)
            cameraView.setOnVideoCapturedListener(null)
            cameraView.setOnCameraStateChangedListener(null)
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
