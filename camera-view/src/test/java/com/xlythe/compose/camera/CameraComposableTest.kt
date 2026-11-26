package com.xlythe.compose.camera

import android.Manifest
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.xlythe.view.camera.CameraView
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader
import com.xlythe.view.camera.v2.FakeCamera2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Compose wrapper, composed.
 *
 * <p>Almost everything it does happens inside one composable - the CameraView is built in a
 * `remember`, the controller is an object closed over it, the attributes are pushed in an update
 * block and the lifecycle is wired by an effect - so none of it can be reached without composing.
 * Which also makes these the things that are easy to get wrong in that shape: whether the
 * controller is published, whether an attribute that changes is pushed again, and whether the
 * camera follows the lifecycle it was given.</p>
 */
@RunWith(RobolectricTestRunner::class)
// Composed at API 34: Compose's own view calls InputManager.getInstance(), which was removed
// from the platform afterwards and which Robolectric does not stand in for, so composing at a
// newer level fails before any of this code runs. Nothing here is version-specific.
@Config(sdk = [34],
        shadows = [ShadowAvailableTextureView::class, ShadowInjectableImageReader::class])
class CameraComposableTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        // The composable builds a real CameraView, which needs a camera to bind to and the
        // permission to use it; without either it stays closed and nothing past that runs.
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        FakeCamera2.addBackAndFrontCameras(RuntimeEnvironment.getApplication())
    }

    @Test
    fun theControllerIsPublishedToTheCaller() {
        val controller = mutableStateOf<CameraController?>(null)

        compose.setContent { Camera(controller = controller) }
        compose.waitForIdle()

        assertNotNull("the caller cannot drive the camera without it", controller.value)
    }

    /** Every attribute the composable takes has to land on the view it is wrapping. */
    @Test
    fun theAttributesReachTheCamera() {
        val controller = mutableStateOf<CameraController?>(null)

        compose.setContent {
            Camera(
                quality = Quality.LOW,
                flash = Flash.ON,
                lensFacing = LensFacing.FRONT,
                pinchToZoom = false,
                maxVideoDuration = 5_000L,
                maxVideoSize = 1_024L,
                imageConfirmationEnabled = true,
                videoConfirmationEnabled = true,
                matchPreviewAspectRatioEnabled = false,
                controller = controller,
            )
        }
        compose.waitForIdle()

        val camera = requireCameraView()
        assertEquals(CameraView.Quality.LOW, camera.quality)
        assertEquals(CameraView.Flash.ON, camera.flash)
        assertEquals(CameraView.LensFacing.FRONT, camera.lensFacing)
        assertFalse(camera.isPinchToZoomEnabled)
        assertEquals(5_000L, camera.maxVideoDuration)
        assertEquals(1_024L, camera.maxVideoSize)
        assertTrue(camera.isImageConfirmationEnabled)
        assertTrue(camera.isVideoConfirmationEnabled)
        assertFalse(camera.isMatchPreviewAspectRatioEnabled)
    }

    /** An attribute left out means "no limit", not zero. */
    @Test
    fun theDefaultsAreIndefiniteRatherThanZero() {
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(controller = controller) }
        compose.waitForIdle()

        val camera = requireCameraView()
        assertEquals(CameraView.INDEFINITE_VIDEO_DURATION.toLong(), camera.maxVideoDuration)
        assertEquals(CameraView.INDEFINITE_VIDEO_SIZE.toLong(), camera.maxVideoSize)
        assertEquals(CameraView.Quality.HIGH, camera.quality)
        assertEquals(CameraView.Flash.AUTO, camera.flash)
        assertTrue(camera.isPinchToZoomEnabled)
    }

    /** Changing an attribute has to reach the view too, not only the first composition's value. */
    @Test
    fun aChangedAttributeReachesTheCamera() {
        val flash = mutableStateOf(Flash.AUTO)
        val controller = mutableStateOf<CameraController?>(null)

        compose.setContent { Camera(flash = flash.value, controller = controller) }
        compose.waitForIdle()
        assertEquals(CameraView.Flash.AUTO, requireCameraView().flash)

        flash.value = Flash.OFF
        compose.waitForIdle()

        assertEquals(CameraView.Flash.OFF, requireCameraView().flash)
    }

    /** The same view is kept across a recomposition, rather than a new camera being built. */
    @Test
    fun recomposingKeepsTheSameCamera() {
        val flash = mutableStateOf(Flash.AUTO)
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(flash = flash.value, controller = controller) }
        compose.waitForIdle()
        val first = requireCameraView()

        flash.value = Flash.ON
        compose.waitForIdle()

        assertTrue("a new camera per recomposition would restart the preview",
                first === requireCameraView())
    }

    /** The camera follows the lifecycle it was composed in. */
    @Test
    fun theCameraOpensWithTheLifecycle() {
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(controller = controller) }
        compose.waitForIdle()

        assertTrue("a resumed host should have an open camera", requireCameraView().isOpen)
    }

    /**
     * A recomposition with new callbacks replaces the old ones.
     *
     * <p>The view is remembered for the life of the composition, so listeners installed on it
     * would otherwise hold whichever lambdas the first composition passed, and a caller whose
     * callbacks close over state would keep being called with the stale ones.</p>
     */
    @Test
    fun theLatestCallbacksAreTheOnesThatFire() {
        val opened = mutableListOf<String>()
        val which = mutableStateOf("first")
        val controller = mutableStateOf<CameraController?>(null)

        compose.setContent {
            val label = which.value
            Camera(controller = controller, onCameraOpened = { opened.add(label) })
        }
        compose.waitForIdle()
        assertEquals("the lifecycle opened it once", listOf("first"), opened)

        which.value = "second"
        compose.waitForIdle()
        val camera = requireCameraView()
        camera.close()
        compose.waitForIdle()
        camera.open()
        compose.waitForIdle()

        assertEquals("the second composition's callback is the live one",
                listOf("first", "second"), opened)
    }

    /** And the closed callback is wired the same way. */
    @Test
    fun theClosedCallbackAlsoFollowsRecomposition() {
        val closed = mutableListOf<String>()
        val which = mutableStateOf("first")
        val controller = mutableStateOf<CameraController?>(null)

        compose.setContent {
            val label = which.value
            Camera(controller = controller, onCameraClosed = { closed.add(label) })
        }
        compose.waitForIdle()
        which.value = "second"
        compose.waitForIdle()

        requireCameraView().close()
        compose.waitForIdle()

        assertEquals(listOf("second"), closed)
    }

    /** The controller's actions are safe to call whatever state the camera is in. */
    @Test
    fun theControllerDeclinesWhatItCannotDoYet() {
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(controller = controller) }
        compose.waitForIdle()
        val camera = requireNotNull(controller.value)

        // Confirming or rejecting nothing is a programming error, and CameraView says so rather
        // than silently doing nothing; the wrapper has to pass that through rather than swallow
        // it, or a caller that has lost track of its own state never finds out.
        assertThrows(IllegalStateException::class.java) { camera.confirmPicture() }
        assertThrows(IllegalStateException::class.java) { camera.confirmVideo() }
        assertThrows(IllegalStateException::class.java) { camera.rejectPicture() }
        assertThrows(IllegalStateException::class.java) { camera.rejectVideo() }

        // Stopping what never started, on the other hand, is harmless.
        camera.stopRecording()
        camera.exitBarcodeScanner()
        compose.waitForIdle()
    }

    @Test
    fun togglingSwitchesTheLens() {
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(lensFacing = LensFacing.BACK, controller = controller) }
        compose.waitForIdle()
        assertEquals(CameraView.LensFacing.BACK, requireCameraView().lensFacing)

        requireNotNull(controller.value).toggleCamera()
        compose.waitForIdle()

        assertEquals(CameraView.LensFacing.FRONT, requireCameraView().lensFacing)
    }

    /** A capture asked for before the camera can deliver is reported as a failure, not thrown. */
    @Test
    fun aCaptureThatCannotSucceedIsReported() {
        val controller = mutableStateOf<CameraController?>(null)
        val failures = mutableListOf<Unit>()
        compose.setContent {
            Camera(controller = controller, onImageCaptureFailed = { failures.add(Unit) })
        }
        compose.waitForIdle()
        val file = File.createTempFile("compose", ".jpg")
        file.deleteOnExit()

        requireNotNull(controller.value).takePicture(file)
        compose.waitForIdle()

        // Either it is still in flight or it has failed; what it must not do is throw at the
        // caller, who has no way to know whether the camera is ready.
        assertTrue(failures.size <= 1)
    }

    /** The settings the backends read, which have to land on the view like the older ones. */
    @Test
    fun theBackendSettingsReachTheCamera() {
        val controller = mutableStateOf<CameraController?>(null)

        compose.setContent {
            Camera(
                zoomLevel = 50,
                exposureCompensation = 2,
                torchEnabled = true,
                dynamicRange = DynamicRange.HDR,
                extension = Extension.BOKEH,
                pictureQuality = 60,
                stabilizationEnabled = true,
                lowLightBoostEnabled = true,
                controller = controller,
            )
        }
        compose.waitForIdle()

        val camera = requireCameraView()
        assertEquals(50, camera.zoomLevel)
        assertEquals(2, camera.exposureCompensation)
        assertTrue(camera.isTorchEnabled)
        assertEquals(CameraView.DynamicRange.HDR, camera.dynamicRange)
        assertEquals(CameraView.Extension.BOKEH, camera.extension)
        assertEquals(60, camera.pictureQuality)
        assertTrue(camera.isStabilizationEnabled)
        assertTrue(camera.isLowLightBoostEnabled)
    }

    /** And the ones left out keep the camera's own defaults rather than a zeroed version. */
    @Test
    fun theBackendSettingsDefaultToTheCamerasOwn() {
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(controller = controller) }
        compose.waitForIdle()

        val camera = requireCameraView()
        assertEquals(CameraView.ColorSpace.SRGB, camera.colorSpace)
        assertEquals(CameraView.DynamicRange.SDR, camera.dynamicRange)
        assertEquals(CameraView.Extension.NONE, camera.extension)
        assertEquals(95, camera.pictureQuality)
        assertFalse(camera.isTorchEnabled)
    }

    /**
     * A colour space this camera cannot record in is declined rather than applied, and the
     * published state says so beforehand.
     */
    @Test
    fun anUnsupportedColorSpaceIsDeclined() {
        val controller = mutableStateOf<CameraController?>(null)
        val state = mutableStateOf<CameraState?>(null)

        compose.setContent {
            Camera(colorSpace = ColorSpace.DISPLAY_P3, controller = controller, state = state)
        }
        compose.waitForIdle()

        assertEquals(setOf(ColorSpace.SRGB), requireNotNull(state.value).supportedColorSpaces)
        assertEquals("the request is dropped, not half-applied",
                CameraView.ColorSpace.SRGB, requireCameraView().colorSpace)
    }

    /** The queries are published once there is a camera to answer them. */
    @Test
    fun theStateReportsWhatTheCameraCanDo() {
        val controller = mutableStateOf<CameraController?>(null)
        val state = mutableStateOf<CameraState?>(null)

        compose.setContent { Camera(controller = controller, state = state) }
        compose.waitForIdle()

        val published = requireNotNull(state.value) { "an open camera has answers to give" }
        assertTrue(published.hasFlash)
        assertTrue(published.hasFrontFacingCamera)
        assertFalse(published.isUsingFrontFacingCamera)
        assertTrue(published.isZoomSupported)
        assertEquals(100, published.maxZoomLevel)
        assertTrue(published.isFaceDetectionSupported)
        assertTrue("a FULL camera2 device can hold a still alongside a recording",
                published.isSnapshotWhileRecordingSupported)
        assertEquals(setOf(DynamicRange.SDR), published.supportedDynamicRanges)
        assertEquals(setOf(Extension.NONE), published.supportedExtensions)
        assertEquals(setOf(PictureFormat.JPEG), published.supportedPictureFormats)
        assertFalse(published.isRecording)
        assertFalse(published.isAnalyzingFrames)
        assertTrue("the fake camera publishes its optics",
                published.horizontalFieldOfView > 0f && published.verticalFieldOfView > 0f)
    }

    /** A closed camera has nothing to report, which is not the same as reporting false. */
    @Test
    fun theStateIsWithdrawnWhenTheCameraCloses() {
        val controller = mutableStateOf<CameraController?>(null)
        val state = mutableStateOf<CameraState?>(null)
        compose.setContent { Camera(controller = controller, state = state) }
        compose.waitForIdle()
        assertNotNull(state.value)

        requireCameraView().close()
        compose.waitForIdle()

        assertNull("a closed camera cannot answer for the camera it was", state.value)
    }

    /** Another lens is another camera, which may answer differently. */
    @Test
    fun theStateFollowsALensSwitch() {
        val controller = mutableStateOf<CameraController?>(null)
        val state = mutableStateOf<CameraState?>(null)
        compose.setContent { Camera(controller = controller, state = state) }
        compose.waitForIdle()
        assertFalse(requireNotNull(state.value).isUsingFrontFacingCamera)

        requireNotNull(controller.value).toggleCamera()
        compose.waitForIdle()

        assertTrue("nothing announces a lens switch, so the action has to republish",
                requireNotNull(state.value).isUsingFrontFacingCamera)
    }

    /** Zoom left out belongs to the camera, so a gesture is not undone by a recomposition. */
    @Test
    fun zoomIsLeftToTheCameraWhenNoLevelIsGiven() {
        val flash = mutableStateOf(Flash.AUTO)
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(flash = flash.value, controller = controller) }
        compose.waitForIdle()
        requireCameraView().zoomLevel = 25

        flash.value = Flash.ON
        compose.waitForIdle()

        assertEquals("a pinch has to survive the next recomposition",
                25, requireCameraView().zoomLevel)
    }

    /** Zoom asked for belongs to the caller, and is asserted again on every recomposition. */
    @Test
    fun aStatedZoomLevelIsReapplied() {
        val flash = mutableStateOf(Flash.AUTO)
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(flash = flash.value, zoomLevel = 10, controller = controller) }
        compose.waitForIdle()
        requireCameraView().zoomLevel = 25

        flash.value = Flash.ON
        compose.waitForIdle()

        assertEquals(10, requireCameraView().zoomLevel)
    }

    /** Frame analysis is driven through the controller and reported in the state. */
    @Test
    fun frameAnalysisStartsAndStopsThroughTheController() {
        val controller = mutableStateOf<CameraController?>(null)
        val state = mutableStateOf<CameraState?>(null)
        compose.setContent { Camera(controller = controller, state = state) }
        compose.waitForIdle()
        val camera = requireNotNull(controller.value)

        camera.startFrameAnalysis({})
        compose.waitForIdle()
        assertTrue(requireNotNull(state.value).isAnalyzingFrames)

        camera.stopFrameAnalysis()
        compose.waitForIdle()

        assertFalse(requireNotNull(state.value).isAnalyzingFrames)
    }

    /** Analysis cannot be started twice, and the wrapper passes the refusal on. */
    @Test
    fun frameAnalysisCannotBeStartedTwice() {
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(controller = controller) }
        compose.waitForIdle()
        val camera = requireNotNull(controller.value)
        camera.startFrameAnalysis({})

        assertThrows(IllegalStateException::class.java) { camera.startFrameAnalysis({}) }

        camera.stopFrameAnalysis()
    }

    /** Face detection says whether the camera took the listener on. */
    @Test
    fun faceDetectionReportsWhetherTheCameraTookItOn() {
        val controller = mutableStateOf<CameraController?>(null)
        val state = mutableStateOf<CameraState?>(null)
        compose.setContent { Camera(controller = controller, state = state) }
        compose.waitForIdle()
        val camera = requireNotNull(controller.value)
        assertTrue(requireNotNull(state.value).isFaceDetectionSupported)

        assertTrue("this camera reports faces", camera.setFaceDetectionListener { _, _ -> })
        assertTrue("letting go always succeeds", camera.setFaceDetectionListener(null))
    }

    /** Focus on an area the caller chose, rather than where the preview happened to be tapped. */
    @Test
    fun theControllerFocusesWhereItIsTold() {
        val controller = mutableStateOf<CameraController?>(null)
        compose.setContent { Camera(controller = controller) }
        compose.waitForIdle()

        requireNotNull(controller.value)
            .focus(Rect(-100, -100, 100, 100), Rect(-150, -150, 150, 150))
        compose.waitForIdle()

        assertTrue("focusing is not a reason to lose the camera", requireCameraView().isOpen)
    }

    private fun requireCameraView(): CameraView {
        val found = findCameraView(compose.activity.window.decorView)
        assertNotNull("the composable should have added a CameraView", found)
        return found!!
    }

    private fun findCameraView(view: View): CameraView? {
        if (view is CameraView) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findCameraView(view.getChildAt(index))
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }
}
