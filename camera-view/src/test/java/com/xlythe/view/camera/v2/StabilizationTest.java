package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Stabilization on the Camera2 backend: one switch, steadying the preview and a recording each as
 * far as the camera can.
 *
 * <p>Run at API 29 and 37 on purpose. Preview stabilization arrives in API 33, so at 29 the preview
 * falls back to the lens's optical stabilization and a recording to video stabilization, while at
 * 37 preview stabilization steadies both.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowCallbackCameraCaptureSession.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class StabilizationTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        output = new File(context.getCacheDir(), "stabilization-test.mp4");
    }

    @After
    public void tearDown() {
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    @Test
    public void aCameraWithDigitalStabilizationIsSupported() {
        givenControls();

        assertTrue(module.isStabilizationSupported());
    }

    /**
     * Optical stabilization is used but does not make a camera supported on its own, so that both
     * backends give the same answer about the same camera.
     */
    @Test
    public void opticalStabilizationAloneIsNotSupport() {
        givenControls();
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                new int[] {CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF});

        assertFalse(module.isStabilizationSupported());
    }

    @Test
    public void anOpenCameraThatCannotSteadyDeclines() {
        assertFalse(module.isStabilizationSupported());
        open();

        module.setStabilizationEnabled(true);
        FakeCamera2.idleMainLooper();

        assertFalse(module.isStabilizationEnabled());
        assertNull(repeating().get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
        assertNull(repeating().get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE));
    }

    /** Left off, the camera's own defaults stand. */
    @Test
    public void nothingIsAskedForWhileItIsOff() {
        givenControls();
        open();

        assertNull(repeating().get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
        assertNull(repeating().get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE));
    }

    @Test
    public void thePreviewAndAStillAreSteadied() {
        givenControls();
        open();

        module.setStabilizationEnabled(true);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isStabilizationEnabled());
        assertPreviewSteadied(repeating());

        module.takePicture(new File(context.getCacheDir(), "stabilization-still.jpg"));
        FakeCamera2.idleMainLooper();
        List<CaptureRequest> stills = ShadowCallbackCameraCaptureSession.getCaptureRequests();
        assertEquals(1, stills.size());
        assertPreviewSteadied(stills.get(0));
    }

    @Test
    public void aRecordingIsSteadied() {
        givenControls();
        open();
        module.setStabilizationEnabled(true);
        FakeCamera2.idleMainLooper();

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue(activeSession() instanceof VideoSession);
        assertRecordingSteadied(repeating());
    }

    /**
     * A camera that can only steady a recording leaves the preview alone, rather than asking for
     * video stabilization on a stream it does not cover.
     */
    @Test
    public void videoStabilizationIsOnlyAskedForARecording() {
        givenControls();
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                new int[] {CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON});
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
                new int[] {CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF});
        open();
        module.setStabilizationEnabled(true);
        FakeCamera2.idleMainLooper();
        assertNull(repeating().get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));

        module.startRecording(output);
        FakeCamera2.idleMainLooper();
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON),
                repeating().get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
    }

    /** The mode is a session parameter on many cameras, so a change builds a new session. */
    @Test
    public void aChangeRebuildsThePreviewSession() {
        givenControls();
        open();
        Camera2Module.Session before = activeSession();

        module.setStabilizationEnabled(true);
        FakeCamera2.idleMainLooper();

        assertNotSame(before, activeSession());
        assertTrue(activeSession() instanceof PictureSession);
    }

    /** A recording keeps going, and the session that follows it is built with the change. */
    @Test
    public void aChangeWaitsForARecordingToEnd() {
        givenControls();
        open();
        module.startRecording(output);
        FakeCamera2.idleMainLooper();
        Camera2Module.Session recording = activeSession();

        module.setStabilizationEnabled(true);
        FakeCamera2.idleMainLooper();
        assertSame("the recording was not interrupted", recording, activeSession());
        assertTrue(module.isRecording());

        module.stopRecording();
        FakeCamera2.idleMainLooper();
        assertPreviewSteadied(repeating());
    }

    @Test
    public void stabilizationSurvivesALensSwitchAndAQualityChange() {
        givenControls();
        open();
        module.setStabilizationEnabled(true);
        FakeCamera2.idleMainLooper();

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isUsingFrontFacingCamera());
        assertPreviewSteadied(repeating());

        module.setQuality(CameraView.Quality.LOW);
        FakeCamera2.idleMainLooper();
        assertPreviewSteadied(repeating());
    }

    @Test
    public void anUnchangedRequestDoesNotRebuildTheSession() {
        givenControls();
        open();
        Camera2Module.Session before = activeSession();

        module.setStabilizationEnabled(false);
        FakeCamera2.idleMainLooper();

        assertSame(before, activeSession());
    }

    /** Preview stabilization where the platform has it, and the lens's own where it does not. */
    private static void assertPreviewSteadied(CaptureRequest request) {
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals(Integer.valueOf(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION),
                    request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
            assertNull("optical stabilization is the camera's to choose under it",
                    request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE));
        } else {
            assertNull(request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
            assertEquals(Integer.valueOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON),
                    request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE));
        }
    }

    /** Preview stabilization where the platform has it, and video stabilization where it does not. */
    private static void assertRecordingSteadied(CaptureRequest request) {
        int expected = Build.VERSION.SDK_INT >= 33
                ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
        assertEquals(Integer.valueOf(expected),
                request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
        assertNull("optical and digital stabilization are not run together",
                request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE));
    }

    private void givenControls() {
        FakeCamera2.addExposureAndStabilization(context, FakeCamera2.BACK_ID);
        FakeCamera2.addExposureAndStabilization(context, FakeCamera2.FRONT_ID);
    }

    private void open() {
        module.open();
        FakeCamera2.idleMainLooper();
        assertNotNull("expected a session", activeSession());
    }

    private static CaptureRequest repeating() {
        CaptureRequest request = ShadowCallbackCameraCaptureSession.getLastRepeatingRequest();
        assertNotNull("expected a repeating request", request);
        return request;
    }

    /** Reaches the module's active session, which it keeps private. */
    private Camera2Module.Session activeSession() {
        try {
            Field field = Camera2Module.class.getDeclaredField("mActiveSession");
            field.setAccessible(true);
            return (Camera2Module.Session) field.get(module);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
