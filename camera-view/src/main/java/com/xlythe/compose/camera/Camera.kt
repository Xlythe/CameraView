package com.xlythe.compose.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.util.SparseArray
import androidx.annotation.RawRes
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.xlythe.view.camera.Face
import com.xlythe.view.camera.FrameStream
import com.xlythe.view.camera.ICameraModule
import com.xlythe.view.camera.VideoStream
import com.xlythe.view.camera.ZoomPreset
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

/** The range of colours a picture is recorded in. See [CameraState.supportedColorSpaces]. */
enum class ColorSpace {
    SRGB,
    DISPLAY_P3
}

/** The file a picture is written as. See [CameraState.supportedPictureFormats]. */
enum class PictureFormat {
    JPEG,
    ULTRA_HDR,
    RAW,
    RAW_JPEG
}

/** Standard or high dynamic range video. See [CameraState.supportedDynamicRanges]. */
enum class DynamicRange {
    SDR,
    HDR
}

/** An optional vendor photo-processing extension. See [CameraState.supportedExtensions]. */
enum class Extension {
    NONE,
    BOKEH,
    HDR,
    NIGHT,
    FACE_RETOUCH,
    AUTO
}

/**
 * What the camera can do, and what it is doing.
 *
 * Everything else [Camera] takes is a request the caller makes; these are the camera's answers,
 * which is why they are published rather than passed in. Ask before offering a control: a request
 * the active camera cannot honour is logged and dropped rather than applied, so a button for one
 * does nothing at all.
 *
 * A closed camera has no answers to give, so the published state is null until one opens. It is a
 * snapshot rather than a stream, taken when the camera opens, when [Camera] recomposes, and after
 * a [CameraController] action that changes it; one that finds nothing new publishes an equal value
 * and so does not recompose the caller.
 *
 * The defaults describe a camera that can do nothing, for a preview or a test that needs to stand
 * one in.
 */
data class CameraState(
    val hasFlash: Boolean = false,
    val hasFrontFacingCamera: Boolean = false,
    val isUsingFrontFacingCamera: Boolean = false,
    val isZoomSupported: Boolean = false,
    /** The far end of the range [Camera]'s zoomLevel accepts; 0 is the widest lens there is. */
    @Deprecated("Only the deprecated zoomLevel used it. Zoom by ratio, from minZoomRatio to " +
        "maxZoomRatio.")
    val maxZoomLevel: Int = 0,
    /** The widest [Camera]'s zoomRatio reaches: below 1 on a phone with an ultra-wide lens. */
    val minZoomRatio: Float = 1f,
    /** The furthest [Camera]'s zoomRatio reaches, or 1 on a camera that cannot zoom. */
    val maxZoomRatio: Float = 1f,
    /**
     * The zoom stops worth a button each, in increasing order. Always holds 1, the main lens, and
     * otherwise one stop per lens and 2 where the main sensor can crop to it without upscaling.
     * Each has the exact ratio to pass as zoomRatio and a rounded label to show.
     */
    val zoomPresets: List<ZoomPreset> = emptyList(),
    /**
     * Whether [Camera]'s exposureCompensation does anything on this camera. Where it is false the
     * range and the step are all 0.
     */
    val isExposureCompensationSupported: Boolean = false,
    /** The lowest index exposureCompensation accepts, which darkens the picture furthest. */
    val minExposureCompensation: Int = 0,
    /** The highest index exposureCompensation accepts, which brightens the picture furthest. */
    val maxExposureCompensation: Int = 0,
    /**
     * How many EV one exposure compensation index is worth, such as 1/3 or 1/6, or 0 where the
     * camera cannot compensate. An index times the step is the shift in EV to show a user.
     */
    val exposureCompensationStep: Float = 0f,
    /** Always holds at least [ColorSpace.SRGB], which every camera can produce. */
    val supportedColorSpaces: Set<ColorSpace> = emptySet(),
    /** Always holds at least [PictureFormat.JPEG], which every camera can write. */
    val supportedPictureFormats: Set<PictureFormat> = emptySet(),
    val supportedDynamicRanges: Set<DynamicRange> = emptySet(),
    val supportedExtensions: Set<Extension> = emptySet(),
    val isStabilizationSupported: Boolean = false,
    val isLowLightBoostSupported: Boolean = false,
    /** Whether [CameraController.takePicture] works while a recording is running. */
    val isSnapshotWhileRecordingSupported: Boolean = false,
    val isFaceDetectionSupported: Boolean = false,
    /**
     * The angle of view in degrees, which turns a bearing in the world into a position on the
     * preview. It narrows as the camera zooms in, so the snapshot taken after a zoom is the one to
     * read.
     */
    val horizontalFieldOfView: Float = 0f,
    val verticalFieldOfView: Float = 0f,
    val isRecording: Boolean = false,
    val isAnalyzingFrames: Boolean = false
)

interface CameraController {
    /**
     * Takes a picture and files it where the gallery looks, reporting it to [Camera]'s
     * onImageSaved. Only this overload files into MediaStore. Below API 29 that needs
     * WRITE_EXTERNAL_STORAGE, and without it onImageCaptureFailed is called instead.
     * [PictureFormat.RAW] is filed as a DNG, and [PictureFormat.RAW_JPEG] as both its files.
     */
    fun takePicture()
    fun takePicture(file: File)

    /**
     * Records a video and files it where the gallery looks, reporting it to [Camera]'s
     * onVideoSaved. Only this overload files into MediaStore. Below API 29 that needs
     * WRITE_EXTERNAL_STORAGE, and without it onVideoCaptureFailed is called instead.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording()

    /** Records audio alongside the video, so the microphone is needed here and not at Camera. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(file: File)
    fun stopRecording()

    /**
     * A stream carries audio as well as video, so it needs the microphone too - the implementation
     * has always required it, and saying so here is what warns a caller coding against this
     * interface rather than against the class behind it.
     */
    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
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

    /**
     * Focuses and exposes for an area of the preview, in coordinates from -1000 to 1000.
     *
     * The camera already focuses where the preview is tapped; this is for a caller driving focus
     * from something else, such as a face it is tracking.
     */
    fun focus(focusArea: Rect, meteringArea: Rect)

    /**
     * Hands every camera frame to [analyzer], on a background thread, until [stopFrameAnalysis].
     *
     * The frame's image belongs to the camera and is recycled as soon as the call returns, so
     * anything that outlives it has to be copied out.
     *
     * @return whether analysis started. False on a camera that cannot produce frames to read.
     * @throws IllegalStateException if the camera is not open, or analysis is already running
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun startFrameAnalysis(
        analyzer: (frame: FrameStream.Frame) -> Unit,
        params: FrameStream.Params = FrameStream.Params()
    ): Boolean

    fun stopFrameAnalysis()

    /**
     * Reports the faces the camera is tracking, or stops when given null.
     *
     * This is the camera's own detector, the one a manufacturer's app draws boxes from: it costs
     * nothing, it arrives with the preview, and it finds faces rather than describing them.
     *
     * @return whether the camera took it on. False means the listener will never be called, which
     *     is what [CameraState.isFaceDetectionSupported] says in advance.
     */
    fun setFaceDetectionListener(
        listener: ((faces: List<Face>, faceToView: Matrix) -> Unit)?
    ): Boolean
}

private class CameraControllerImpl(
    private val viewProvider: () -> CameraView?,
    /**
     * Called after anything that changes what [CameraState] reports. The queries are pulled from
     * the camera rather than announced by it, so nothing else would notice that a recording has
     * started, or that a lens switch has left another camera's capabilities published.
     */
    private val onCameraStateChanged: () -> Unit
) : CameraController {
    private fun getView(): CameraView? {
        return viewProvider()
    }

    override fun takePicture() {
        getView()?.takePicture()
            ?: Log.e(TAG, "View not available for takePicture")
    }

    override fun takePicture(file: File) {
        getView()?.takePicture(file)
            ?: Log.e(TAG, "View not available for takePicture")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startRecording() {
        val view = getView()
        if (view == null) {
            Log.e(TAG, "View not available for startRecording")
            return
        }
        view.startRecording()
        onCameraStateChanged()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startRecording(file: File) {
        val view = getView()
        if (view == null) {
            Log.e(TAG, "View not available for startRecording")
            return
        }
        view.startRecording(file)
        onCameraStateChanged()
    }

    override fun stopRecording() {
        val view = getView()
        if (view == null) {
            Log.e(TAG, "View not available for stopRecording")
            return
        }
        view.stopRecording()
        onCameraStateChanged()
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    override fun stream(): VideoStream? {
        return getView()?.stream()
    }

    override fun toggleCamera() {
        val view = getView()
        if (view == null) {
            Log.e(TAG, "View not available for toggleCamera")
            return
        }
        view.toggleCamera()
        onCameraStateChanged()
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

    @SuppressLint("WrongConstant")
    @RequiresPermission(Manifest.permission.CAMERA)
    override fun enterBarcodeScanner(listener: (List<Barcode>) -> Unit, @Barcode.Format vararg formats: Int) {
        val view = getView()
        if (view == null) {
            Log.e(TAG, "View not available for enterBarcodeScanner")
            return
        }
        val requested = if (formats.isNotEmpty()) {
            formats
        } else {
            intArrayOf(com.xlythe.view.camera.Barcode.Format.QR_CODE)
        }
        val rest = if (requested.size > 1) {
            requested.sliceArray(1 until requested.size)
        } else {
            IntArray(0)
        }
        if (!view.enterBarcodeScanner({ barcodes -> listener(barcodes) }, requested[0], *rest)) {
            Log.w(TAG, "Barcode scanning is unavailable on this device")
        }
    }

    override fun exitBarcodeScanner() {
        getView()?.exitBarcodeScanner()
            ?: Log.e(TAG, "View not available for exitBarcodeScanner")
    }

    override fun focus(focusArea: Rect, meteringArea: Rect) {
        getView()?.focus(focusArea, meteringArea)
            ?: Log.e(TAG, "View not available for focus")
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override fun startFrameAnalysis(
        analyzer: (frame: FrameStream.Frame) -> Unit,
        params: FrameStream.Params
    ): Boolean {
        val view = getView()
        if (view == null) {
            Log.e(TAG, "View not available for startFrameAnalysis")
            return false
        }
        val started = view.startFrameAnalysis(FrameStream.Analyzer { frame -> analyzer(frame) }, params)
        onCameraStateChanged()
        return started
    }

    override fun stopFrameAnalysis() {
        val view = getView()
        if (view == null) {
            Log.e(TAG, "View not available for stopFrameAnalysis")
            return
        }
        view.stopFrameAnalysis()
        onCameraStateChanged()
    }

    override fun setFaceDetectionListener(
        listener: ((faces: List<Face>, faceToView: Matrix) -> Unit)?
    ): Boolean {
        val view = getView()
        if (view == null) {
            Log.e(TAG, "View not available for setFaceDetectionListener")
            return false
        }
        if (listener == null) {
            return view.setFaceDetectionListener(null)
        }
        return view.setFaceDetectionListener { faces, faceToView -> listener(faces, faceToView) }
    }
}

/**
 * The members of an enum this camera accepts. CameraView answers for one value at a time, so the
 * set a caller needs in order to offer a choice is built by asking for each of them.
 */
private fun <T : Enum<T>> supported(values: List<T>, isSupported: (T) -> Boolean): Set<T> {
    return values.filterTo(mutableSetOf(), isSupported)
}

/** Reads everything [CameraState] reports off an open camera. */
private fun CameraView.readCameraState(): CameraState {
    return CameraState(
        hasFlash = hasFlash(),
        hasFrontFacingCamera = hasFrontFacingCamera(),
        isUsingFrontFacingCamera = isUsingFrontFacingCamera,
        isZoomSupported = isZoomSupported,
        maxZoomLevel = maxZoomLevel,
        minZoomRatio = minZoomRatio,
        maxZoomRatio = maxZoomRatio,
        zoomPresets = zoomPresets,
        isExposureCompensationSupported = isExposureCompensationSupported,
        minExposureCompensation = minExposureCompensation,
        maxExposureCompensation = maxExposureCompensation,
        exposureCompensationStep = exposureCompensationStep,
        supportedColorSpaces = supported(ColorSpace.entries) {
            isColorSpaceSupported(it.toCameraView())
        },
        supportedPictureFormats = supported(PictureFormat.entries) {
            isPictureFormatSupported(it.toCameraView())
        },
        supportedDynamicRanges = supported(DynamicRange.entries) {
            isDynamicRangeSupported(it.toCameraView())
        },
        supportedExtensions = supported(Extension.entries) {
            isExtensionSupported(it.toCameraView())
        },
        isStabilizationSupported = isStabilizationSupported,
        isLowLightBoostSupported = isLowLightBoostSupported,
        isSnapshotWhileRecordingSupported = isSnapshotWhileRecordingSupported,
        isFaceDetectionSupported = isFaceDetectionSupported,
        horizontalFieldOfView = horizontalFieldOfView,
        verticalFieldOfView = verticalFieldOfView,
        isRecording = isRecording,
        isAnalyzingFrames = isAnalyzingFrames
    )
}

private fun Quality.toCameraView() = when (this) {
    Quality.LOW -> CameraView.Quality.LOW
    Quality.MEDIUM -> CameraView.Quality.MEDIUM
    Quality.HIGH -> CameraView.Quality.HIGH
    Quality.MAX -> CameraView.Quality.MAX
}

private fun Flash.toCameraView() = when (this) {
    Flash.AUTO -> CameraView.Flash.AUTO
    Flash.ON -> CameraView.Flash.ON
    Flash.OFF -> CameraView.Flash.OFF
}

private fun LensFacing.toCameraView() = when (this) {
    LensFacing.BACK -> CameraView.LensFacing.BACK
    LensFacing.FRONT -> CameraView.LensFacing.FRONT
}

private fun ColorSpace.toCameraView() = when (this) {
    ColorSpace.SRGB -> CameraView.ColorSpace.SRGB
    ColorSpace.DISPLAY_P3 -> CameraView.ColorSpace.DISPLAY_P3
}

private fun PictureFormat.toCameraView() = when (this) {
    PictureFormat.JPEG -> CameraView.PictureFormat.JPEG
    PictureFormat.ULTRA_HDR -> CameraView.PictureFormat.ULTRA_HDR
    PictureFormat.RAW -> CameraView.PictureFormat.RAW
    PictureFormat.RAW_JPEG -> CameraView.PictureFormat.RAW_JPEG
}

private fun DynamicRange.toCameraView() = when (this) {
    DynamicRange.SDR -> CameraView.DynamicRange.SDR
    DynamicRange.HDR -> CameraView.DynamicRange.HDR
}

private fun Extension.toCameraView() = when (this) {
    Extension.NONE -> CameraView.Extension.NONE
    Extension.BOKEH -> CameraView.Extension.BOKEH
    Extension.HDR -> CameraView.Extension.HDR
    Extension.NIGHT -> CameraView.Extension.NIGHT
    Extension.FACE_RETOUCH -> CameraView.Extension.FACE_RETOUCH
    Extension.AUTO -> CameraView.Extension.AUTO
}

/**
 * A Composable that displays a preview of the camera.
 *
 * **Important:** This composable requires [Manifest.permission.CAMERA] to be granted *before* it
 * is included in the composition. The underlying view will not be opened or functional otherwise.
 * Use conditional rendering based on permission status at the call site.
 *
 * Showing a preview and taking pictures need nothing else. The microphone is only required by
 * [CameraController.startRecording] and [CameraController.stream], which ask for it themselves.
 * Outputs are written to the [File] you pass in, or filed where the gallery looks by the
 * controller's takePicture() and startRecording(), which below API 29 needs
 * WRITE_EXTERNAL_STORAGE.
 *
 * Several of the settings below depend on what the camera can do. [CameraState] says which ones
 * the active camera will accept.
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
 * @param zoomRatio The zoom in the camera's own terms, where 1 is the main lens, from
 *  [CameraState.minZoomRatio] to [CameraState.maxZoomRatio]. Null leaves the zoom to the camera,
 *  which is what [pinchToZoom] drives. A ratio reaches the camera when it changes, so an
 *  unrelated recomposition does not undo a pinch, and writing the ratios [onZoomChanged] reports
 *  back into it is safe: the camera's own report is not handed back to it.
 * @param animateZoom Eases the camera over to each new zoomRatio, as a tap on a zoom button does
 *  in a phone's camera app, rather than jumping to it.
 * @param exposureCompensation Shifts the camera's automatic exposure brighter (positive) or darker
 *  (negative) than it would choose by itself, in the preview, pictures and recordings. It biases
 *  auto-exposure rather than replacing it. The value is an index, not a number of stops: each
 *  step is worth [CameraState.exposureCompensationStep] EV, so with a step of 1/6, 6 is +1 EV.
 *  0 is no compensation, and an index outside [CameraState.minExposureCompensation] to
 *  [CameraState.maxExposureCompensation] is clamped to that range. A camera without
 *  [CameraState.isExposureCompensationSupported] ignores it.
 * @param torchEnabled Lights the flash as a steady light, rather than for a capture, on a camera
 *  with [CameraState.hasFlash]. It stays lit through pictures and recordings. It cannot be
 *  combined with [lowLightBoostEnabled], which wins when both are asked for.
 * @param colorSpace The range of colours pictures are recorded in.
 * @param pictureFormat The file a picture is written as.
 * @param dynamicRange The dynamic range videos are recorded in.
 * @param extension An optional vendor photo-processing extension.
 * @param pictureQuality How hard a still capture is compressed, from 1 to 100.
 * @param stabilizationEnabled Steadies the preview and recorded video against hand shake, on a
 *  camera with [CameraState.isStabilizationSupported]. A change reconfigures the camera, and one
 *  made during a recording waits for the recording to end.
 * @param lowLightBoostEnabled Brightens the preview and recordings in a dark scene, on a camera
 *  with [CameraState.isLowLightBoostSupported], for which it may lower the frame rate.
 * @param shutterSoundEnabled Plays the platform's click and recording tones. Where the law
 *  requires them, they play regardless.
 * @param shutterSound A raw resource played in place of the platform's click, or 0 for the click.
 * @param controller Exposes the methods {@link CameraController#takePicture(File)},
 *  {@link CameraController#startRecording(File)} and {@link CameraController#stopRecording()}.
 * @param state Receives what the camera can do and what it is doing, or null while it is closed.
 * @param onShutter Called as a picture is exposed, before it is saved: the moment for a shutter
 *  animation, or for a sound of the app's own with [shutterSoundEnabled] off.
 * @param onImageCaptured Receives a picture from [CameraController.takePicture] given a file.
 * @param onImageSaved Receives where a picture from [CameraController.takePicture] without a file
 *  was filed. [PictureFormat.RAW_JPEG] files a JPEG and a DNG, and this receives each, the JPEG
 *  first, once both are filed.
 * @param onVideoCaptured Receives a video from [CameraController.startRecording] given a file.
 * @param onVideoSaved Receives where a video from [CameraController.startRecording] without a
 *  file was filed.
 * @param onZoomChanged Called with the zoom ratio whenever it changes, a pinch included, which is
 *  what keeps buttons for [CameraState.zoomPresets] in step with the gesture.
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
    zoomRatio: Float? = null,
    animateZoom: Boolean = false,
    exposureCompensation: Int = 0,
    torchEnabled: Boolean = false,
    colorSpace: ColorSpace = ColorSpace.SRGB,
    pictureFormat: PictureFormat = PictureFormat.JPEG,
    dynamicRange: DynamicRange = DynamicRange.SDR,
    extension: Extension = Extension.NONE,
    pictureQuality: Int = ICameraModule.DEFAULT_PICTURE_QUALITY,
    stabilizationEnabled: Boolean = false,
    lowLightBoostEnabled: Boolean = false,
    shutterSoundEnabled: Boolean = true,
    @RawRes shutterSound: Int = 0,
    controller: MutableState<CameraController?>,
    state: MutableState<CameraState?> = remember { mutableStateOf(null) },
    onShutter: () -> Unit = {},
    onImageConfirmation: () -> Unit = {},
    onImageCaptured: (file: File) -> Unit = {},
    onImageSaved: (uri: Uri) -> Unit = {},
    onImageCaptureFailed: () -> Unit = {},
    onVideoConfirmation: () -> Unit = {},
    onVideoCaptured: (file: File) -> Unit = {},
    onVideoSaved: (uri: Uri) -> Unit = {},
    onVideoCaptureFailed: () -> Unit = {},
    onCameraOpened: () -> Unit = {},
    onCameraClosed: () -> Unit = {},
    onZoomChanged: (ratio: Float) -> Unit = {},
) {
    val zoomSync = remember { ZoomSync() }
    CameraImpl(
        modifier = modifier,
        quality = quality,
        flash = flash,
        lensFacing = lensFacing,
        pinchToZoom = pinchToZoom,
        maxVideoDuration = maxVideoDuration,
        maxVideoSize = maxVideoSize,
        imageConfirmationEnabled = imageConfirmationEnabled,
        videoConfirmationEnabled = videoConfirmationEnabled,
        matchPreviewAspectRatioEnabled = matchPreviewAspectRatioEnabled,
        applyZoom = { view -> zoomSync.update(view, zoomRatio, animateZoom) },
        exposureCompensation = exposureCompensation,
        torchEnabled = torchEnabled,
        colorSpace = colorSpace,
        pictureFormat = pictureFormat,
        dynamicRange = dynamicRange,
        extension = extension,
        pictureQuality = pictureQuality,
        stabilizationEnabled = stabilizationEnabled,
        lowLightBoostEnabled = lowLightBoostEnabled,
        shutterSoundEnabled = shutterSoundEnabled,
        shutterSound = shutterSound,
        controller = controller,
        state = state,
        onShutter = onShutter,
        onImageConfirmation = onImageConfirmation,
        onImageCaptured = onImageCaptured,
        onImageSaved = onImageSaved,
        onImageCaptureFailed = onImageCaptureFailed,
        onVideoConfirmation = onVideoConfirmation,
        onVideoCaptured = onVideoCaptured,
        onVideoSaved = onVideoSaved,
        onVideoCaptureFailed = onVideoCaptureFailed,
        onCameraOpened = onCameraOpened,
        onCameraClosed = onCameraClosed,
        onZoomChanged = { ratio ->
            zoomSync.onReported(ratio)
            onZoomChanged(ratio)
        },
    )
}

/**
 * The camera, zoomed by level rather than by ratio. Everything else is as for the overload that
 * takes zoomRatio.
 *
 * @param zoomLevel A position in the camera's zoom range, from 0 to 100 on a log scale, asserted
 *  again on every recomposition. Null leaves the zoom to the camera.
 */
@Deprecated("A level cannot land exactly on a lens. Pass zoomRatio, from " +
    "CameraState.minZoomRatio to maxZoomRatio, where each of CameraState.zoomPresets is exact.")
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
    // No default, so that a call leaving the zoom out resolves to the overload above.
    zoomLevel: Int?,
    exposureCompensation: Int = 0,
    torchEnabled: Boolean = false,
    colorSpace: ColorSpace = ColorSpace.SRGB,
    pictureFormat: PictureFormat = PictureFormat.JPEG,
    dynamicRange: DynamicRange = DynamicRange.SDR,
    extension: Extension = Extension.NONE,
    pictureQuality: Int = ICameraModule.DEFAULT_PICTURE_QUALITY,
    stabilizationEnabled: Boolean = false,
    lowLightBoostEnabled: Boolean = false,
    shutterSoundEnabled: Boolean = true,
    @RawRes shutterSound: Int = 0,
    controller: MutableState<CameraController?>,
    state: MutableState<CameraState?> = remember { mutableStateOf(null) },
    onShutter: () -> Unit = {},
    onImageConfirmation: () -> Unit = {},
    onImageCaptured: (file: File) -> Unit = {},
    onImageSaved: (uri: Uri) -> Unit = {},
    onImageCaptureFailed: () -> Unit = {},
    onVideoConfirmation: () -> Unit = {},
    onVideoCaptured: (file: File) -> Unit = {},
    onVideoSaved: (uri: Uri) -> Unit = {},
    onVideoCaptureFailed: () -> Unit = {},
    onCameraOpened: () -> Unit = {},
    onCameraClosed: () -> Unit = {},
) {
    CameraImpl(
        modifier = modifier,
        quality = quality,
        flash = flash,
        lensFacing = lensFacing,
        pinchToZoom = pinchToZoom,
        maxVideoDuration = maxVideoDuration,
        maxVideoSize = maxVideoSize,
        imageConfirmationEnabled = imageConfirmationEnabled,
        videoConfirmationEnabled = videoConfirmationEnabled,
        matchPreviewAspectRatioEnabled = matchPreviewAspectRatioEnabled,
        applyZoom = { view -> if (zoomLevel != null) view.zoomLevel = zoomLevel },
        exposureCompensation = exposureCompensation,
        torchEnabled = torchEnabled,
        colorSpace = colorSpace,
        pictureFormat = pictureFormat,
        dynamicRange = dynamicRange,
        extension = extension,
        pictureQuality = pictureQuality,
        stabilizationEnabled = stabilizationEnabled,
        lowLightBoostEnabled = lowLightBoostEnabled,
        shutterSoundEnabled = shutterSoundEnabled,
        shutterSound = shutterSound,
        controller = controller,
        state = state,
        onShutter = onShutter,
        onImageConfirmation = onImageConfirmation,
        onImageCaptured = onImageCaptured,
        onImageSaved = onImageSaved,
        onImageCaptureFailed = onImageCaptureFailed,
        onVideoConfirmation = onVideoConfirmation,
        onVideoCaptured = onVideoCaptured,
        onVideoSaved = onVideoSaved,
        onVideoCaptureFailed = onVideoCaptureFailed,
        onCameraOpened = onCameraOpened,
        onCameraClosed = onCameraClosed,
        onZoomChanged = {},
    )
}

/**
 * Decides when [Camera]'s zoomRatio is pushed to the view.
 *
 * A caller keeping the zoom in state writes each ratio onZoomChanged reports back into it, so
 * every step of a pinch or an animation comes straight back in as zoomRatio. Pushed to the view,
 * that echo would stop the animation it came from. So a ratio is pushed only when it differs from
 * the one the previous update was given, which keeps an unrelated recomposition from undoing a
 * pinch, and from the one the view last reported, which is the echo.
 *
 * The previous update's ratio rather than the last one pushed: an echo is a change of request
 * too. After a pinch from a 3x preset to 1.5x is written back, tapping 3x again has to be pushed,
 * although 3x was the last ratio pushed.
 */
private class ZoomSync {
    /** The zoomRatio the previous update was given, pushed or not; null before the first. */
    private var lastGiven: Float? = null

    /** The ratio the view last reported. NaN, which equals nothing, until it reports one. */
    private var lastReported = Float.NaN

    /** Hears each ratio the view reports, before the caller does. */
    fun onReported(ratio: Float) {
        lastReported = ratio
    }

    /** Pushes zoomRatio to the view, unless it is no change or only the view's own report. */
    fun update(view: CameraView, zoomRatio: Float?, animate: Boolean) {
        val previous = lastGiven
        lastGiven = zoomRatio
        if (zoomRatio == null || zoomRatio == previous || zoomRatio == lastReported) {
            return
        }
        view.setZoomRatio(zoomRatio, animate)
    }
}

/**
 * The camera both [Camera] overloads show. They differ only in how they name the zoom, so each
 * passes its own [applyZoom], which pushes it onto the view along with the other attributes.
 */
@RequiresPermission(Manifest.permission.CAMERA)
@Composable
private fun CameraImpl(
    modifier: Modifier,
    quality: Quality,
    flash: Flash,
    lensFacing: LensFacing,
    pinchToZoom: Boolean,
    maxVideoDuration: Long?,
    maxVideoSize: Long?,
    imageConfirmationEnabled: Boolean,
    videoConfirmationEnabled: Boolean,
    matchPreviewAspectRatioEnabled: Boolean,
    applyZoom: (CameraView) -> Unit,
    exposureCompensation: Int,
    torchEnabled: Boolean,
    colorSpace: ColorSpace,
    pictureFormat: PictureFormat,
    dynamicRange: DynamicRange,
    extension: Extension,
    pictureQuality: Int,
    stabilizationEnabled: Boolean,
    lowLightBoostEnabled: Boolean,
    shutterSoundEnabled: Boolean,
    @RawRes shutterSound: Int,
    controller: MutableState<CameraController?>,
    state: MutableState<CameraState?>,
    onShutter: () -> Unit,
    onImageConfirmation: () -> Unit,
    onImageCaptured: (file: File) -> Unit,
    onImageSaved: (uri: Uri) -> Unit,
    onImageCaptureFailed: () -> Unit,
    onVideoConfirmation: () -> Unit,
    onVideoCaptured: (file: File) -> Unit,
    onVideoSaved: (uri: Uri) -> Unit,
    onVideoCaptureFailed: () -> Unit,
    onCameraOpened: () -> Unit,
    onCameraClosed: () -> Unit,
    onZoomChanged: (ratio: Float) -> Unit,
) {
    // Grab system variables
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The CameraView below is remembered for the life of the composition, so the listeners
    // installed on it capture whichever lambdas the first composition happened to pass. A caller
    // whose callbacks close over state would then keep being called with the stale ones. Track
    // the latest instead, so the listeners read them at the moment they fire.
    val currentOnShutter by rememberUpdatedState(onShutter)
    val currentOnImageConfirmation by rememberUpdatedState(onImageConfirmation)
    val currentOnImageCaptured by rememberUpdatedState(onImageCaptured)
    val currentOnImageSaved by rememberUpdatedState(onImageSaved)
    val currentOnImageCaptureFailed by rememberUpdatedState(onImageCaptureFailed)
    val currentOnVideoConfirmation by rememberUpdatedState(onVideoConfirmation)
    val currentOnVideoCaptured by rememberUpdatedState(onVideoCaptured)
    val currentOnVideoSaved by rememberUpdatedState(onVideoSaved)
    val currentOnVideoCaptureFailed by rememberUpdatedState(onVideoCaptureFailed)
    val currentOnCameraOpened by rememberUpdatedState(onCameraOpened)
    val currentOnCameraClosed by rememberUpdatedState(onCameraClosed)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val currentState by rememberUpdatedState(state)

    // Takes the camera as an argument rather than closing over it, because it is declared ahead of
    // the view so that the listeners installed on the view can publish through it.
    val publishState = { camera: CameraView ->
        currentState.value = if (camera.isOpen) camera.readCameraState() else null
    }

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
                override fun onShutter() {
                    currentOnShutter()
                }

                override fun onImageConfirmation() {
                    currentOnImageConfirmation()
                }

                override fun onImageCaptured(file: File) {
                    currentOnImageCaptured(file)
                }

                override fun onImageSaved(uri: Uri) {
                    currentOnImageSaved(uri)
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
                    // A recording also ends on its own, at the duration or size cap or in
                    // failure, so the state is republished on every way out rather than only
                    // where stopRecording was asked for.
                    publishState(this@apply)
                    currentOnVideoCaptured(file)
                }

                override fun onVideoSaved(uri: Uri) {
                    publishState(this@apply)
                    currentOnVideoSaved(uri)
                }

                override fun onFailure() {
                    publishState(this@apply)
                    currentOnVideoCaptureFailed()
                }
            })

            this.setOnCameraStateChangedListener(object : CameraView.OnCameraStateChangedListener {
                override fun onCameraOpened() {
                    publishState(this@apply)
                    currentOnCameraOpened()
                }

                override fun onCameraClosed() {
                    publishState(this@apply)
                    currentOnCameraClosed()
                }
            })

            this.setOnZoomChangedListener { ratio -> currentOnZoomChanged(ratio) }
        }
    }

    // Cache our camera controller.
    val cameraController = remember(cameraView) {
        CameraControllerImpl({ cameraView }, { publishState(cameraView) })
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
            currentState.value = null
            cameraView.setOnImageCapturedListener(null)
            cameraView.setOnVideoCapturedListener(null)
            cameraView.setOnCameraStateChangedListener(null)
            cameraView.setOnZoomChangedListener(null)
        }
    }

    // Wrap our View in a Composable, and then keep it up to date on updates.
    AndroidView(factory = { _ -> cameraView }, update = { view ->
        view.quality = quality.toCameraView()
        view.flash = flash.toCameraView()
        view.lensFacing = lensFacing.toCameraView()
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
        applyZoom(view)
        view.exposureCompensation = exposureCompensation
        view.isTorchEnabled = torchEnabled
        view.colorSpace = colorSpace.toCameraView()
        view.pictureFormat = pictureFormat.toCameraView()
        view.dynamicRange = dynamicRange.toCameraView()
        view.extension = extension.toCameraView()
        view.pictureQuality = pictureQuality
        view.isStabilizationEnabled = stabilizationEnabled
        view.isLowLightBoostEnabled = lowLightBoostEnabled
        view.isShutterSoundEnabled = shutterSoundEnabled
        view.shutterSound = shutterSound
        // What the camera can do depends on what it has just been asked for - a lens, an extension
        // and a zoom each change the answers - so the snapshot is taken after the settings land.
        publishState(view)
    }, modifier = modifier)
}
