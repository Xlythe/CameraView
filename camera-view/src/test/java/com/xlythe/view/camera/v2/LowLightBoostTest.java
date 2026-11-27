package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Low-light boost on the Camera2 backend, which is an auto-exposure mode of its own:
 * CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY, from API 35, on a camera that lists it.
 *
 * <p>From API 35 because that is where the mode exists; {@link #anOlderPlatformCannotBoost} runs
 * below it to show the mode is not asked for there even by a camera that claims it.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(minSdk = 35, shadows = {ShadowCallbackCameraCaptureSession.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class LowLightBoostTest {
    private static final int LOW_LIGHT_BOOST =
            CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY;

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
        output = new File(context.getCacheDir(), "low-light-test.mp4");
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

    /** The fake lists vendor modes ahead of low-light boost, so finding it means reading past them. */
    @Test
    public void aCameraListingTheModeIsSupported() {
        givenControls();

        assertTrue(module.isLowLightBoostSupported());
    }

    @Test
    public void vendorModesAloneAreNotSupport() {
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
                new int[] {CameraMetadata.CONTROL_AE_MODE_OFF, CameraMetadata.CONTROL_AE_MODE_ON,
                        101, 102, 104});

        assertFalse(module.isLowLightBoostSupported());
    }

    @Test
    @Config(sdk = {29, 34})
    public void anOlderPlatformCannotBoost() {
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
                new int[] {CameraMetadata.CONTROL_AE_MODE_ON, LOW_LIGHT_BOOST});

        assertFalse(module.isLowLightBoostSupported());
        open();
        module.setLowLightBoostEnabled(true);
        FakeCamera2.idleMainLooper();
        assertFalse(module.isLowLightBoostEnabled());
        assertNotEquals(Integer.valueOf(LOW_LIGHT_BOOST),
                repeating().get(CaptureRequest.CONTROL_AE_MODE));
    }

    @Test
    public void anOpenCameraWithoutTheModeDeclines() {
        open();

        module.setLowLightBoostEnabled(true);
        FakeCamera2.idleMainLooper();

        assertFalse(module.isLowLightBoostEnabled());
        assertNotEquals(Integer.valueOf(LOW_LIGHT_BOOST),
                repeating().get(CaptureRequest.CONTROL_AE_MODE));
    }

    /** With no camera to ask, the request is kept, and only a camera that has the mode uses it. */
    @Test
    public void aRequestMadeBeforeOpeningIsKept() {
        module.setLowLightBoostEnabled(true);
        assertTrue(module.isLowLightBoostEnabled());

        open();
        assertTrue(module.isLowLightBoostEnabled());
        assertNotEquals("this camera does not have the mode",
                Integer.valueOf(LOW_LIGHT_BOOST), repeating().get(CaptureRequest.CONTROL_AE_MODE));
    }

    @Test
    public void theBoostLandsOnThePreviewAStillAndARecording() {
        givenControls();
        open();

        module.setLowLightBoostEnabled(true);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isLowLightBoostEnabled());
        assertBoosted(repeating());

        module.takePicture(new File(context.getCacheDir(), "low-light-still.jpg"));
        FakeCamera2.idleMainLooper();
        List<CaptureRequest> stills = ShadowCallbackCameraCaptureSession.getCaptures();
        assertEquals(1, stills.size());
        assertBoosted(stills.get(0));

        module.startRecording(output);
        FakeCamera2.idleMainLooper();
        assertTrue(activeSession() instanceof VideoSession);
        assertBoosted(repeating());
    }

    /** An auto-flash mode cannot share the request, so the strategy waits until it is off. */
    @Test
    public void theBoostSetsTheFlashStrategyAsideUntilItIsOff() {
        givenControls();
        module.setFlash(CameraView.Flash.AUTO);
        open();

        module.setLowLightBoostEnabled(true);
        FakeCamera2.idleMainLooper();
        assertBoosted(repeating());

        module.setLowLightBoostEnabled(false);
        FakeCamera2.idleMainLooper();
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH),
                repeating().get(CaptureRequest.CONTROL_AE_MODE));
    }

    /**
     * A picture the flash was asked to fire for still gets it, and the preview stays boosted. The
     * metering before it is in the flash's mode too, or there would be no pre-flash to meter by.
     */
    @Test
    public void aStillTheFlashWasAskedForGetsIt() {
        givenControls();
        module.setFlash(CameraView.Flash.ON);
        open();
        module.setLowLightBoostEnabled(true);
        FakeCamera2.idleMainLooper();

        module.takePicture(new File(context.getCacheDir(), "low-light-flash.jpg"));
        FakeCamera2.idleMainLooper();

        assertBoosted(repeating());
        List<CaptureRequest> triggers = new ArrayList<>();
        List<CaptureRequest> stills = new ArrayList<>();
        for (CaptureRequest request : ShadowCallbackCameraCaptureSession.getCaptures()) {
            Integer trigger = request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER);
            if (trigger != null && trigger == CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START) {
                triggers.add(request);
            } else if (request.get(CaptureRequest.JPEG_QUALITY) != null) {
                stills.add(request);
            }
        }
        assertEquals(1, triggers.size());
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH),
                triggers.get(0).get(CaptureRequest.CONTROL_AE_MODE));
        assertEquals(1, stills.size());
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH),
                stills.get(0).get(CaptureRequest.CONTROL_AE_MODE));
    }

    /** Each needs the auto-exposure mode to itself, so the one asked for last wins. */
    @Test
    public void theTorchAndTheBoostTurnEachOtherOff() {
        givenControls();
        open();
        module.setTorchEnabled(true);
        FakeCamera2.idleMainLooper();

        module.setLowLightBoostEnabled(true);
        FakeCamera2.idleMainLooper();
        assertFalse(module.isTorchEnabled());
        assertTrue(module.isLowLightBoostEnabled());
        assertBoosted(repeating());
        assertNotEquals(Integer.valueOf(CaptureRequest.FLASH_MODE_TORCH),
                repeating().get(CaptureRequest.FLASH_MODE));

        module.setTorchEnabled(true);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isTorchEnabled());
        assertFalse(module.isLowLightBoostEnabled());
        assertEquals(Integer.valueOf(CaptureRequest.FLASH_MODE_TORCH),
                repeating().get(CaptureRequest.FLASH_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON),
                repeating().get(CaptureRequest.CONTROL_AE_MODE));
    }

    @Test
    public void theBoostSurvivesALensSwitchAndAQualityChange() {
        givenControls();
        open();
        module.setLowLightBoostEnabled(true);
        FakeCamera2.idleMainLooper();

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isUsingFrontFacingCamera());
        assertBoosted(repeating());

        module.setQuality(CameraView.Quality.LOW);
        FakeCamera2.idleMainLooper();
        assertBoosted(repeating());
    }

    @Test
    public void anUnchangedRequestIsNotReissued() {
        givenControls();
        open();
        int before = ShadowCallbackCameraCaptureSession.getRepeatingRequests().size();

        module.setLowLightBoostEnabled(false);
        FakeCamera2.idleMainLooper();

        assertEquals(before, ShadowCallbackCameraCaptureSession.getRepeatingRequests().size());
    }

    private static void assertBoosted(CaptureRequest request) {
        assertEquals(Integer.valueOf(LOW_LIGHT_BOOST), request.get(CaptureRequest.CONTROL_AE_MODE));
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
