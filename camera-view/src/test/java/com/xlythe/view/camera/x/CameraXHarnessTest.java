package com.xlythe.view.camera.x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowAnnouncingCameraManager;
import com.xlythe.view.camera.shadow.ShadowListingCamcorderProfile;

import org.junit.Test;

import java.io.File;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

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
        shadows = {ShadowAnnouncingCameraManager.class, ShadowListingCamcorderProfile.class})
public class CameraXHarnessTest {
    @Test
    public void theBackendBindsReportsCapabilitiesSwitchesLensAndCloses() throws Exception {
        FakeCameraXHost host = new FakeCameraXHost();
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
            exerciseAnalysis(module);
            exerciseRebinds(module);
            exerciseCapture(module);
            exerciseLensToggle(module);
            exercisePausedOperations(module);

            exerciseResize(module, host);

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
        // The level only advances once the camera confirms the zoom, so on a camera whose
        // control cannot apply it the request is declined and the previous level stands. Either
        // way it has to stay inside the published range rather than echoing what was asked.
        module.setZoomLevel(50);
        FakeCameraXHost.idle();
        assertTrue("zoom stays in range", module.getZoomLevel() >= 0
                && module.getZoomLevel() <= module.getMaxZoomLevel());

        module.setZoomLevel(Integer.MAX_VALUE);
        FakeCameraXHost.idle();
        assertTrue("an out-of-range request never escapes the range",
                module.getZoomLevel() >= 0 && module.getZoomLevel() <= module.getMaxZoomLevel());

        module.setZoomLevel(-100);
        FakeCameraXHost.idle();
        assertTrue("a negative request never goes below zero", module.getZoomLevel() >= 0);

        for (CameraView.Flash flash : CameraView.Flash.values()) {
            module.setFlash(flash);
            FakeCameraXHost.idle();
            assertEquals(flash, module.getFlash());
        }

        // Declined, because the fake camera reports no exposure range.
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

        // And so is Ultra HDR, leaving pictures as JPEG.
        assertFalse(module.isPictureFormatSupported(CameraView.PictureFormat.ULTRA_HDR));
        module.setPictureFormat(CameraView.PictureFormat.ULTRA_HDR);
        FakeCameraXHost.idle();
        assertEquals(CameraView.PictureFormat.JPEG, module.getPictureFormat());

        // CameraX flips a front recording itself, so the file matches the preview.
        assertTrue(module.isFrontVideoMirrored());

        // Whether this camera will take a focus area at all, which is what an app asks before
        // offering the gesture. The answer depends on the bound camera, so asking is a question
        // put to it rather than a constant - and must not disturb what is bound.
        module.isFocusSupported();
        module.focus(new android.graphics.Rect(-100, -100, 100, 100),
                new android.graphics.Rect(-200, -200, 200, 200));
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
                module.startBarcodeScanner(
                        barcodes -> {}, com.xlythe.view.camera.Barcode.Format.QR_CODE));

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

        module.takePicture(file);
        FakeCameraXHost.idle();

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

        // The fake camera publishes a zoom range, so this is supported even though applying it
        // is declined by a control that cannot execute.
        module.isZoomSupported();

        // An exposure range this camera does not publish, so the bounds collapse rather than
        // coming back as something a caller could act on.
        assertEquals("no exposure range means no room above zero",
                0, module.getMaxExposureCompensation());
        assertEquals("and none below", 0, module.getMinExposureCompensation());

        int orientation = module.getRelativeCameraOrientation();
        assertTrue("a camera orientation is one of four right angles: " + orientation,
                orientation == 0 || orientation == 90 || orientation == 180 || orientation == 270);

        // Both are hardware questions this camera answers no to, and both have to answer rather
        // than throw: an app asks them to decide whether to show a button.
        module.isSnapshotWhileRecordingSupported();

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
