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
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37, qualifiers = "w360dp-h800dp-xxxhdpi",
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
            exerciseAnalysis(module);
            exerciseRebinds(module);
            exerciseCapture(module);
            exercisePausedOperations(module);

            module.onLayoutChanged();
            FakeCameraXHost.idle();

            module.close();
            FakeCameraXHost.idle();
            assertFalse("closing releases the bind", module.isCameraBoundForTesting());
        } finally {
            module.close();
            FakeCameraXHost.idle();
            host.release();
        }
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

        assertFalse(module.isPreviewStabilizationSupported());
        module.setPreviewStabilizationEnabled(true);
        FakeCameraXHost.idle();
        assertFalse(module.isPreviewStabilizationEnabled());

        assertFalse(module.isVideoStabilizationSupported());
        module.setVideoStabilizationEnabled(true);
        FakeCameraXHost.idle();
        assertFalse(module.isVideoStabilizationEnabled());

        // HDR is refused for the same reason, leaving the range at SDR.
        module.setDynamicRange(CameraView.DynamicRange.HDR);
        FakeCameraXHost.idle();
        assertEquals(CameraView.DynamicRange.SDR, module.getDynamicRange());

        // Mirroring and the torch are plain state this camera can hold.
        module.setFrontVideoMirroringEnabled(false);
        assertFalse(module.isFrontVideoMirroringEnabled());
        module.setFrontVideoMirroringEnabled(true);
        assertTrue(module.isFrontVideoMirroringEnabled());

        module.focus(new android.graphics.Rect(-100, -100, 100, 100),
                new android.graphics.Rect(-200, -200, 200, 200));
        FakeCameraXHost.idle();
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
