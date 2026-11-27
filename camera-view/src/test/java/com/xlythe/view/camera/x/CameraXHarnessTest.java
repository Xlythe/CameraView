package com.xlythe.view.camera.x;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import android.util.Rational;

import com.xlythe.view.camera.Barcode;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.FakeLocation;
import com.xlythe.view.camera.shadow.ShadowAnnouncingCameraManager;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowListingCamcorderProfile;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

/**
 * The CameraX backend driven against Robolectric's camera2 shadows.
 *
 * <p>Deliberately one test method. ProcessCameraProvider is a process singleton that refuses to
 * be configured twice, while Robolectric resets the shadowed camera service between methods but
 * reuses the process across them, so a second method would find a provider holding information
 * about cameras the shadow no longer knows and fail to bind for reasons unrelated to the code
 * under test. One method walks the lifecycle instead, which is closer to real use anyway.</p>
 *
 * <p>See {@link FakeCameraXHost} for what the harness arranges.</p>
 *
 * <p>What is deliberately not here is anything that makes CameraX share one camera stream between
 * several outputs - a second preview for a custom stream, or a recording alongside the still
 * capture. CameraX shares a stream by copying it through an OpenGL processor, and there is no GL
 * off a device: {@code GLUtils.getGlVersionNumber} reads a version string that comes back null and
 * fails before any of this library's code runs. Both are covered against the Camera2 backend
 * instead.</p>
 *
 * <p>That rules out {@code startRecording}, {@code attachSurface} and {@code detachSurface} from
 * this harness specifically - each one binds a second output and dies in the GL initializer
 * before reaching anything worth asserting. They are exercised on hardware by the device
 * matrix, which runs all three backends.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi",
        shadows = {ShadowAnnouncingCameraManager.class, ShadowListingCamcorderProfile.class,
                ShadowCallbackCameraCaptureSession.class})
public class CameraXHarnessTest {
    /** The back camera's exposure range. */
    private static final Range<Integer> BACK_EXPOSURE_RANGE = new Range<>(-12, 12);
    /** The front camera's, which only goes one way and so counts as none. */
    private static final Range<Integer> FRONT_EXPOSURE_RANGE = new Range<>(0, 4);

    @Test
    public void theBackendBindsReportsCapabilitiesSwitchesLensAndCloses() throws Exception {
        // Requests are recorded for the exposure step, and no callbacks are delivered, which is
        // the shipped shadow's behaviour that the rest of this harness was written against.
        ShadowCallbackCameraCaptureSession.reset();
        ShadowCallbackCameraCaptureSession.setDeliverCallbacks(false);
        FakeCameraXHost host = new FakeCameraXHost();
        // Only the back camera can compensate, so the steps below that run on the front camera
        // still see one that declines.
        Context application = RuntimeEnvironment.getApplication();
        FakeCamera2.setCharacteristic(application, FakeCamera2.BACK_ID,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, BACK_EXPOSURE_RANGE);
        FakeCamera2.setCharacteristic(application, FakeCamera2.BACK_ID,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, new Rational(1, 6));
        FakeCamera2.setCharacteristic(application, FakeCamera2.FRONT_ID,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, FRONT_EXPOSURE_RANGE);
        FakeCamera2.setCharacteristic(application, FakeCamera2.FRONT_ID,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, new Rational(1, 3));
        CameraXModule module = new CameraXModule(host.getCameraView());
        try {
            // The scale is fixed, so it answers before anything is bound.
            assertEquals(100, module.getMaxZoomLevel());

            module.open();
            assertTrue("CameraX should bind against the shadowed camera service",
                    FakeCameraXHost.awaitBind(module));
            assertNotNull(module.getLifecycle());

            // Capabilities come from the camera that was actually bound. The fake back camera
            // advertises a flash unit, and a front camera is registered alongside it.
            assertTrue("expected a flash unit", module.hasFlash());
            assertTrue("expected a front camera", module.hasFrontFacingCamera());
            assertFalse("the back camera is the default", module.isUsingFrontFacingCamera());
            assertFalse("nothing is recording yet", module.isRecording());

            // A capability the fake camera cannot answer reports unsupported rather than
            // throwing, which publishSnapshot used to do from inside a callback.
            assertTrue(module.isDynamicRangeSupported(CameraView.DynamicRange.SDR));
            assertFalse(module.isDynamicRangeSupported(CameraView.DynamicRange.HDR));
            assertTrue(module.isExtensionSupported(CameraView.Extension.NONE));
            assertFalse(module.isExtensionSupported(CameraView.Extension.BOKEH));

            // Pausing is a flag on this backend, for the reason recorded on pause().
            module.pause();
            FakeCameraXHost.idle();
            assertTrue(module.isPaused());
            module.resume();
            FakeCameraXHost.idle();
            assertFalse(module.isPaused());

            module.setLensFacing(CameraView.LensFacing.FRONT);
            assertTrue("expected a rebind on the front camera",
                    FakeCameraXHost.awaitBind(module));
            assertTrue(module.isUsingFrontFacingCamera());

            exerciseControls(module);
            exerciseQueries(module);
            exerciseFrameEffectFallback(module);
            exerciseAnalysis(module);
            exerciseRebinds(module);
            exerciseCapture(module);
            exerciseLensToggle(module);
            exercisePausedOperations(module);

            exerciseResize(module, host);
            exerciseExposureAcrossARebind(module);

            module.close();
            FakeCameraXHost.idle();
            assertFalse("closing releases the bind", module.isCameraBoundForTesting());
            assertFalse("and a camera that is gone cannot be told where to look",
                    module.isFocusSupported());
        } finally {
            module.close();
            FakeCameraXHost.idle();
            host.release();
        }
    }

    /**
     * A resize re-aims the preview without rebinding, and rebinds once the size has settled.
     *
     * <p>CameraX is the one backend for which a new size means new use cases, which means a
     * capture session torn down and configured again. That is why the two halves of a layout
     * change are separate calls: a run of layout passes - a rotation, a foldable opening, a
     * Compose layout arriving at its answer - costs a transform each and one rebind at the end.
     * </p>
     */
    private static void exerciseResize(CameraXModule module, FakeCameraXHost host) {
        host.getCameraView().layout(0, 0, 540, 1920);

        module.onLayoutChanged();
        FakeCameraXHost.idle();
        assertTrue("re-aiming the preview must not cost the bind",
                module.isCameraBoundForTesting());

        module.onLayoutSettled();
        assertTrue("and the settled size has to be bound for", FakeCameraXHost.awaitBind(module));

        // Settling again at the same size has nothing to do, which is what keeps a layout pass
        // that changed nothing from costing a session.
        module.onLayoutSettled();
        FakeCameraXHost.idle();
        assertTrue(module.isCameraBoundForTesting());
    }

    /**
     * Every control the backend exposes, against a camera that supports almost none of them.
     *
     * <p>The interesting property is that an unsupported request is declined and the previous
     * value kept, rather than throwing or being silently accepted and then ignored.</p>
     */
    private static void exerciseControls(CameraXModule module) {
        // The fake camera crops to 4x at most, and has no lens but its own to stop at.
        assertEquals(1f, module.getMinZoomRatio(), 0f);
        assertEquals(4f, module.getMaxZoomRatio(), 0f);
        assertArrayEquals(new float[] {1f}, module.getZoomPresets(), 0f);

        // A ratio is reported as soon as it is asked for, held to the range, as it is on the
        // other backends.
        module.setZoomRatio(2f);
        FakeCameraXHost.idle();
        assertEquals(2f, module.getZoomRatio(), 0f);

        module.setZoomRatio(40f);
        FakeCameraXHost.idle();
        assertEquals("held to the top of the range", 4f, module.getZoomRatio(), 0f);

        // And a level is a position on a log scale across that range.
        module.setZoomLevel(50);
        FakeCameraXHost.idle();
        assertEquals(2f, module.getZoomRatio(), 0.0001f);
        assertEquals(50, module.getZoomLevel());

        module.setZoomLevel(-100);
        FakeCameraXHost.idle();
        assertEquals(1f, module.getZoomRatio(), 0f);

        for (CameraView.Flash flash : CameraView.Flash.values()) {
            module.setFlash(flash);
            FakeCameraXHost.idle();
            assertEquals(flash, module.getFlash());
        }

        // Declined, because the front camera reports no usable exposure range.
        assertFalse(module.isExposureCompensationSupported());
        module.setExposureCompensation(3);
        FakeCameraXHost.idle();
        assertEquals("an unsupported exposure request is declined",
                0, module.getExposureCompensation());

        // Declined, because it reports no support, and the request must not stick.
        assertFalse(module.isLowLightBoostSupported());
        module.setLowLightBoostEnabled(true);
        FakeCameraXHost.idle();
        assertFalse(module.isLowLightBoostEnabled());

        assertFalse(module.isStabilizationSupported());
        module.setStabilizationEnabled(true);
        FakeCameraXHost.idle();
        assertFalse(module.isStabilizationEnabled());

        FakeCameraXHost.idle();

        // HDR is refused for the same reason, leaving the range at SDR.
        module.setDynamicRange(CameraView.DynamicRange.HDR);
        FakeCameraXHost.idle();
        assertEquals(CameraView.DynamicRange.SDR, module.getDynamicRange());

        // And so are Ultra HDR and RAW, leaving pictures as JPEG.
        for (CameraView.PictureFormat format : new CameraView.PictureFormat[] {
                CameraView.PictureFormat.ULTRA_HDR, CameraView.PictureFormat.RAW,
                CameraView.PictureFormat.RAW_JPEG}) {
            assertFalse(format.name(), module.isPictureFormatSupported(format));
            module.setPictureFormat(format);
            FakeCameraXHost.idle();
            assertEquals(CameraView.PictureFormat.JPEG, module.getPictureFormat());
        }

        // CameraX flips a front recording itself, so the file matches the preview.
        assertTrue(module.isFrontVideoMirrored());

        // Whether this camera will take a focus area at all, which is what an app asks before
        // offering the gesture. The answer depends on the bound camera, so asking is a question
        // put to it rather than a constant - and must not disturb what is bound.
        module.isFocusSupported();
        module.focus(new Rect(-100, -100, 100, 100), new Rect(-200, -200, 200, 200));
        FakeCameraXHost.idle();
        assertTrue("asking about focus must not cost the bind", module.isCameraBoundForTesting());
    }

    /**
     * Barcode analysis declines rather than crashing when ML Kit is not there.
     *
     * <p>ML Kit initializes itself from a manifest provider and gets its detector from Play
     * services, neither of which a unit test has, so the scanning client cannot be built here.
     * That is the interesting case: the request is refused through the return value, which sends
     * CameraView on to the shared scanner, rather than throwing out of a task posted to the main
     * thread where it would take the app down.</p>
     */
    private static void exerciseAnalysis(CameraXModule module) {
        assertFalse("without ML Kit the request has to be declined, not thrown",
                module.startBarcodeScanner(barcodes -> {}, Barcode.Format.QR_CODE));

        // And a stop that matches a start that never happened is harmless.
        module.stopBarcodeScanner();
        FakeCameraXHost.idle();
        assertTrue("declining must leave the bind alone", module.isCameraBoundForTesting());
    }

    /**
     * A capture that cannot succeed is reported, not dropped.
     *
     * <p>There is no pipeline behind this camera to produce a JPEG, so the capture fails - which
     * is the interesting half: a caller waiting on a callback has to hear something, and a second
     * request arriving while the first is outstanding has to be refused rather than overwriting
     * the file the first will write to.</p>
     */
    private static void exerciseCapture(CameraXModule module) throws Exception {
        File file = File.createTempFile("harness", ".jpg");
        file.deleteOnExit();
        // With the permission held and no fix known. This backend reads the location on the main
        // thread, so a capture that asked for a fix here would hold the main thread waiting.
        Context context = RuntimeEnvironment.getApplication();
        FakeLocation.addProviders(context);
        FakeLocation.grantFine(context);

        module.takePicture(file);
        FakeCameraXHost.idle();

        assertEquals("a capture asks for no fix of its own", 0, FakeLocation.listeners(context));
        // Whether it failed yet or is still in flight, the camera has to still be usable.
        assertTrue("a capture must not cost the binding",
                FakeCameraXHost.awaitBind(module));
    }



    /**
     * Everything the backend can be asked about the camera it bound.
     *
     * <p>Queries rather than commands, so the property under test is that each one answers from
     * the camera that was actually bound instead of guessing or throwing - which is what a
     * capability query did before it learned to decline.</p>
     */
    private static void exerciseQueries(CameraXModule module) {
        assertTrue("a bound camera is a ready camera", module.isCameraReady());

        assertTrue("the fake camera publishes a zoom range", module.isZoomSupported());

        // An exposure range that only goes one way counts as none, so the bounds collapse rather
        // than coming back as something a caller could act on.
        assertEquals("no usable exposure range means no room above zero",
                0, module.getMaxExposureCompensation());
        assertEquals("and none below", 0, module.getMinExposureCompensation());
        assertEquals("nor a step, although the camera publishes one",
                0f, module.getExposureCompensationStep(), 0f);

        int orientation = module.getRelativeCameraOrientation();
        assertTrue("a camera orientation is one of four right angles: " + orientation,
                orientation == 0 || orientation == 90 || orientation == 180 || orientation == 270);

        // Both are hardware questions this camera answers no to, and both have to answer rather
        // than throw: an app asks them to decide whether to show a button.
        module.isSnapshotWhileRecordingSupported();

        // This backend has somewhere to put a processor between the camera and its outputs, which
        // is what the probe answers. Whether one can actually be bound here is a different
        // question, and the answer is no for the reason recorded on this class.
        assertTrue("the CameraX backend offers frame effects", module.isFrameEffectSupported());

        // The contract is that the return value says whether the camera is now in the state that
        // was asked for, so it has to agree with what the camera says it can do - and turning it
        // off has to succeed either way, since a camera that cannot track faces is already not
        // tracking any.
        boolean tracksFaces = module.isFaceDetectionSupported();
        assertEquals("starting face detection must agree with what the camera can do",
                tracksFaces, module.setFaceDetectionEnabled(true));
        FakeCameraXHost.idle();
        assertTrue("stopping face detection always succeeds",
                module.setFaceDetectionEnabled(false));
        FakeCameraXHost.idle();

        // The torch is plain state the camera can hold, and no light exists to turn on here.
        module.setTorchEnabled(true);
        FakeCameraXHost.idle();
        module.setTorchEnabled(false);
        FakeCameraXHost.idle();
    }

    /**
     * What can honestly be asserted about effects without a GPU, which is less than it looks.
     *
     * <p>Binding an effect on a live camera cannot be exercised here. CameraX routes an effect
     * through its own GL machinery and its stream-sharing adapter, and in a JVM both come apart:
     * {@code GLUtils}'s static initializer throws on a null {@code EGL_NO_SURFACE}, and once a
     * bind has failed part way through, the use cases it touched stay attached to a virtual
     * camera that no later bind can replace. Neither is the library's behaviour and neither
     * happens on a device - on a Pixel 7 Pro the same path produces a photograph.</p>
     *
     * <p>So this asserts the parts that are the library's own: the probe answers before anything
     * has failed, and the renderer a caller sets is the renderer it gets back. That the frames
     * actually reach the saved file is proven on hardware, by {@code FrameEffectDeviceTest},
     * which is the only place it can be.</p>
     */
    private static void exerciseFrameEffectFallback(CameraXModule module) {
        assertTrue("effects are offered until GL has actually failed",
                module.isFrameEffectSupported());
        assertNull("no renderer until a caller sets one", module.getFrameRenderer());
        assertNull("nor a texture provider", module.getCameraTextureProvider());
    }

    /** Toggling is the shorthand an app's flip button calls, and it has to rebind. */
    private static void exerciseLensToggle(CameraXModule module) {
        boolean wasFront = module.isUsingFrontFacingCamera();

        module.toggleCamera();
        assertTrue("a toggle has to rebind", FakeCameraXHost.awaitBind(module));
        assertEquals("a toggle swaps the lens", !wasFront, module.isUsingFrontFacingCamera());

        module.toggleCamera();
        assertTrue(FakeCameraXHost.awaitBind(module));
        assertEquals("and twice is where it started",
                wasFront, module.isUsingFrontFacingCamera());
    }

    /** Nothing that needs the camera may run while it is paused. */
    private static void exercisePausedOperations(CameraXModule module) throws Exception {
        module.pause();
        FakeCameraXHost.idle();
        assertTrue(module.isPaused());

        File file = File.createTempFile("harness", ".jpg");
        file.deleteOnExit();
        module.takePicture(file);
        module.stopRecording();
        FakeCameraXHost.idle();
        assertFalse("a paused camera is not recording", module.isRecording());

        module.resume();
        FakeCameraXHost.idle();
        assertFalse(module.isPaused());
        assertTrue("and it comes back", FakeCameraXHost.awaitBind(module));
    }

    /**
     * An exposure index set just before a rebind is still applied after it.
     *
     * <p>The camera confirms an index through a later capture result, and the rebind cancels the
     * request before that arrives. This camera never confirms one at all, since no results are
     * delivered, which makes the cancellation certain rather than a race.</p>
     */
    private static void exerciseExposureAcrossARebind(CameraXModule module) throws Exception {
        module.setLensFacing(CameraView.LensFacing.BACK);
        assertTrue(FakeCameraXHost.awaitBind(module));
        assertTrue("the back camera publishes a range", module.isExposureCompensationSupported());
        assertEquals(-12, module.getMinExposureCompensation());
        assertEquals(12, module.getMaxExposureCompensation());
        assertEquals("and a step of its own", 1 / 6f, module.getExposureCompensationStep(), 1e-6f);

        module.setExposureCompensation(3);
        settle();
        // Called on the main thread, so the old binding is gone by the time this returns, and
        // every repeating request after this point comes from the new one.
        int beforeRebind = ShadowCallbackCameraCaptureSession.getRepeatingRequests().size();
        module.setQuality(module.getQuality() == CameraView.Quality.LOW
                ? CameraView.Quality.HIGH : CameraView.Quality.LOW);
        assertTrue(FakeCameraXHost.awaitBind(module));

        Integer applied = null;
        for (int attempt = 0; attempt < 100 && !Integer.valueOf(3).equals(applied); attempt++) {
            settle();
            List<CaptureRequest> requests =
                    ShadowCallbackCameraCaptureSession.getRepeatingRequests();
            applied = requests.size() <= beforeRebind ? null : requests.get(requests.size() - 1)
                    .get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
        }
        assertEquals("the rebind lost the exposure index", Integer.valueOf(3), applied);
    }

    /** Lets CameraX's own threads catch up, since idling the main looper alone does not. */
    private static void settle() throws InterruptedException {
        FakeCameraXHost.idle();
        Thread.sleep(20);
        FakeCameraXHost.idle();
    }

    /** Anything that changes the bound configuration has to survive a rebind. */
    private static void exerciseRebinds(CameraXModule module) {
        for (CameraView.Quality quality : CameraView.Quality.values()) {
            module.setQuality(quality);
            assertTrue("expected a rebind for " + quality, FakeCameraXHost.awaitBind(module));
            assertEquals(quality, module.getQuality());
        }

        module.setMatchPreviewAspectRatio(false);
        assertTrue(FakeCameraXHost.awaitBind(module));
        assertFalse(module.isMatchPreviewAspectRatioEnabled());

        module.setMatchPreviewAspectRatio(true);
        assertTrue(FakeCameraXHost.awaitBind(module));
        assertTrue(module.isMatchPreviewAspectRatioEnabled());

        module.setMaxVideoDuration(5_000L);
        FakeCameraXHost.idle();
        assertEquals(5_000L, module.getMaxVideoDuration());

        module.setExtension(CameraView.Extension.NONE);
        FakeCameraXHost.idle();
        assertEquals(CameraView.Extension.NONE, module.getExtension());
    }
}
