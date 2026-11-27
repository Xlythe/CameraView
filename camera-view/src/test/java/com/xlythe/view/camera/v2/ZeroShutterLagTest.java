package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;
import com.xlythe.view.camera.shadow.ShadowListingCameraCharacteristics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A picture without the flash is a frame the camera already has; one with the flash waits for
 * auto-exposure to meter with a pre-flash, and is never a frame from before the flash fired.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, qualifiers = "w360dp-h800dp-xxxhdpi",
        shadows = {ShadowCallbackCameraCaptureSession.class})
public class ZeroShutterLagTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;
    private File output;
    private final AtomicInteger shutters = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    @Before
    public void setUp() {
        // Robolectric runs no resetter for a shadow a test declares itself.
        ShadowCallbackCameraCaptureSession.reset();
        ShadowListingCameraCharacteristics.setAvailableCaptureRequestKeys(
                CaptureRequest.CONTROL_ENABLE_ZSL);
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.setOnImageCapturedListener(new CameraView.OnImageCapturedListener() {
            @Override public void onShutter() { shutters.incrementAndGet(); }
            @Override public void onImageConfirmation() {}
            @Override public void onImageCaptured(File file) {}
            @Override public void onFailure() { failures.incrementAndGet(); }
        });
        module = new Camera2Module(cameraView);
        module.setShutterSoundEnabled(false);
        output = new File(context.getCacheDir(), "zsl-test.jpg");
    }

    @After
    public void tearDown() {
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
        ShadowListingCameraCharacteristics.reset();
        if (output.exists() && !output.delete()) {
            output.deleteOnExit();
        }
    }

    @Test
    public void aPictureWithoutTheFlashComesFromTheBuffer() {
        module.setFlash(CameraView.Flash.OFF);

        takePicture();

        assertEquals(0, triggers().size());
        assertEquals(1, stills().size());
        assertEquals(Boolean.TRUE, stills().get(0).get(CaptureRequest.CONTROL_ENABLE_ZSL));
        assertEquals(1, shutters.get());
    }

    /** A camera that does not list the key is not sent it. */
    @Test
    public void aCameraWithoutItIsNotAsked() {
        ShadowListingCameraCharacteristics.reset();
        module.setFlash(CameraView.Flash.OFF);

        takePicture();

        assertEquals(1, stills().size());
        assertNull(stills().get(0).get(CaptureRequest.CONTROL_ENABLE_ZSL));
    }

    /** With auto flash in a bright scene the flash will not fire, so nothing changes. */
    @Test
    public void autoFlashInTheLightComesFromTheBuffer() {
        ShadowCallbackCameraCaptureSession.setAeState(CaptureResult.CONTROL_AE_STATE_CONVERGED);
        module.setFlash(CameraView.Flash.AUTO);

        takePicture();

        assertEquals(0, triggers().size());
        assertEquals(Boolean.TRUE, stills().get(0).get(CaptureRequest.CONTROL_ENABLE_ZSL));
    }

    /** In the dark, auto-exposure says the flash is needed, and the picture is metered first. */
    @Test
    public void autoFlashInTheDarkIsMeteredFirst() {
        ShadowCallbackCameraCaptureSession.setAeState(
                CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED);
        module.setFlash(CameraView.Flash.AUTO);

        takePicture();

        assertEquals(1, triggers().size());
    }

    /** Metering runs until auto-exposure leaves its precapture state, then the picture is taken. */
    @Test
    public void aFlashPictureWaitsForMeteringToFinish() {
        ShadowCallbackCameraCaptureSession.setAeState(CaptureResult.CONTROL_AE_STATE_PRECAPTURE);
        module.setFlash(CameraView.Flash.ON);

        takePicture();

        assertEquals(1, triggers().size());
        assertEquals("still metering", 0, stills().size());

        ShadowCallbackCameraCaptureSession.setAeState(
                CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED);
        ShadowCallbackCameraCaptureSession.deliverRepeatingFrame();
        FakeCamera2.idleMainLooper();

        assertEquals(1, stills().size());
        assertEquals("a flash picture is a new frame", Boolean.FALSE,
                stills().get(0).get(CaptureRequest.CONTROL_ENABLE_ZSL));
        assertEquals(1, shutters.get());
    }

    /** Once the picture is taken the sequence is cancelled, so no camera holds its exposure. */
    @Test
    public void aMeteredPictureEndsTheSequence() {
        module.setFlash(CameraView.Flash.ON);

        takePicture();

        assertEquals(1, stills().size());
        assertEquals(1, triggers(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL).size());
    }

    /** A picture that was not metered has no sequence to end. */
    @Test
    public void anUnmeteredPictureEndsNothing() {
        module.setFlash(CameraView.Flash.OFF);

        takePicture();

        assertEquals(0, triggers(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL).size());
    }

    /** A camera that never says when it is done still takes the picture, late. */
    @Test
    public void meteringThatNeverFinishesTimesOut() {
        ShadowCallbackCameraCaptureSession.setAeState(CaptureResult.CONTROL_AE_STATE_PRECAPTURE);
        module.setFlash(CameraView.Flash.ON);

        takePicture();
        assertEquals(0, stills().size());

        ShadowLooper.idleMainLooper(PictureSession.METERING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        assertEquals(1, stills().size());
        assertEquals(1, shutters.get());
    }

    /** A camera that reports no exposure state cannot be waited on, so it is not. */
    @Test
    public void aCameraThatReportsNoExposureIsNotWaitedOn() {
        module.setFlash(CameraView.Flash.ON);

        takePicture();

        assertEquals(1, triggers().size());
        assertEquals(1, stills().size());
    }

    /** One that skips the precapture state is given a few frames, then the picture is taken. */
    @Test
    public void aCameraThatSkipsMeteringIsGivenAFewFrames() {
        ShadowCallbackCameraCaptureSession.setAeState(CaptureResult.CONTROL_AE_STATE_CONVERGED);
        module.setFlash(CameraView.Flash.ON);

        takePicture();
        assertEquals(0, stills().size());

        for (int frame = 0; frame < 2; frame++) {
            ShadowCallbackCameraCaptureSession.deliverRepeatingFrame();
            FakeCamera2.idleMainLooper();
        }

        assertEquals(1, stills().size());
    }

    /** A second picture asked for while the first meters is refused rather than tangled in. */
    @Test
    public void aPictureWhileMeteringIsRefused() {
        ShadowCallbackCameraCaptureSession.setAeState(CaptureResult.CONTROL_AE_STATE_PRECAPTURE);
        module.setFlash(CameraView.Flash.ON);
        takePicture();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertEquals(1, failures.get());
        assertEquals(1, triggers().size());
    }

    /** Closing mid-metering cancels the picture and its timeout. */
    @Test
    public void closingWhileMeteringTakesNoPicture() {
        ShadowCallbackCameraCaptureSession.setAeState(CaptureResult.CONTROL_AE_STATE_PRECAPTURE);
        module.setFlash(CameraView.Flash.ON);
        takePicture();

        module.close();
        ShadowLooper.idleMainLooper(PictureSession.METERING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        assertEquals(0, stills().size());
    }

    /** A snapshot in a recording is not metered, since the pre-flash would light the video. */
    @Test
    public void aRecordingSnapshotIsNotMetered() {
        module.setFlash(CameraView.Flash.ON);
        module.open();
        FakeCamera2.idleMainLooper();
        module.startRecording(new File(context.getCacheDir(), "zsl-test.mp4"));
        FakeCamera2.idleMainLooper();

        module.takePicture(output);
        FakeCamera2.idleMainLooper();

        assertEquals(0, triggers().size());
        assertEquals(1, stills().size());
        assertEquals(Boolean.FALSE, stills().get(0).get(CaptureRequest.CONTROL_ENABLE_ZSL));
        module.stopRecording();
        FakeCamera2.idleMainLooper();
    }

    @Test
    public void onlyAFlashThatWillFireCountsAsFiring() {
        assertTrue(PictureSession.flashWillFire(true, CameraView.Flash.ON, null));
        assertTrue(PictureSession.flashWillFire(true, CameraView.Flash.AUTO,
                CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED));
        assertFalse(PictureSession.flashWillFire(true, CameraView.Flash.AUTO,
                CaptureResult.CONTROL_AE_STATE_CONVERGED));
        assertFalse(PictureSession.flashWillFire(true, CameraView.Flash.AUTO, null));
        assertFalse(PictureSession.flashWillFire(true, CameraView.Flash.OFF,
                CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED));
        assertFalse(PictureSession.flashWillFire(false, CameraView.Flash.ON, null));
    }

    private void takePicture() {
        module.open();
        FakeCamera2.idleMainLooper();
        module.takePicture(output);
        FakeCamera2.idleMainLooper();
    }

    /** One-off requests carrying a picture, which are the only ones that set a JPEG quality. */
    private static List<CaptureRequest> stills() {
        List<CaptureRequest> stills = new ArrayList<>();
        for (CaptureRequest request : ShadowCallbackCameraCaptureSession.getCaptures()) {
            if (request.get(CaptureRequest.JPEG_QUALITY) != null) {
                stills.add(request);
            }
        }
        return stills;
    }

    /** One-off requests that start a precapture sequence. */
    private static List<CaptureRequest> triggers() {
        return triggers(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
    }

    private static List<CaptureRequest> triggers(int value) {
        List<CaptureRequest> triggers = new ArrayList<>();
        for (CaptureRequest request : ShadowCallbackCameraCaptureSession.getCaptures()) {
            Integer trigger = request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER);
            if (trigger != null && trigger == value) {
                triggers.add(request);
            }
        }
        return triggers;
    }
}
