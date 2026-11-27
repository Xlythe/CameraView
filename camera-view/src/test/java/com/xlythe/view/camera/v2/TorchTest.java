package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
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
import java.util.List;

/**
 * The torch, as the Camera2 backend lights it: FLASH_MODE_TORCH under plain auto-exposure, on
 * every request the camera is given while it is wanted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowCallbackCameraCaptureSession.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class TorchTest {
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
        output = new File(context.getCacheDir(), "torch-test.mp4");
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
    public void theTorchLightsThePreview() {
        open();

        module.setTorchEnabled(true);
        FakeCamera2.idleMainLooper();

        assertTrue(module.isTorchEnabled());
        assertTorchLit(repeating());
    }

    /**
     * An auto-exposure mode that manages the flash ignores FLASH_MODE, so the strategy has to give
     * way while the torch is on, and come back once it is off.
     */
    @Test
    public void theTorchSetsTheFlashStrategyAsideUntilItIsOff() {
        module.setFlash(CameraView.Flash.AUTO);
        open();
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH,
                (int) repeating().get(CaptureRequest.CONTROL_AE_MODE));

        module.setTorchEnabled(true);
        FakeCamera2.idleMainLooper();
        assertTorchLit(repeating());

        module.setTorchEnabled(false);
        FakeCamera2.idleMainLooper();
        assertFalse(module.isTorchEnabled());
        assertNotEquals(Integer.valueOf(CaptureRequest.FLASH_MODE_TORCH),
                repeating().get(CaptureRequest.FLASH_MODE));
        assertEquals("the strategy is back",
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH,
                (int) repeating().get(CaptureRequest.CONTROL_AE_MODE));
    }

    /**
     * A picture taken under the torch keeps it lit, rather than firing the flash over it, and so
     * has no flash to meter for first.
     */
    @Test
    public void aStillKeepsTheTorchLit() {
        module.setFlash(CameraView.Flash.ON);
        open();
        module.setTorchEnabled(true);
        FakeCamera2.idleMainLooper();

        module.takePicture(new File(context.getCacheDir(), "torch-still.jpg"));
        FakeCamera2.idleMainLooper();

        List<CaptureRequest> stills = ShadowCallbackCameraCaptureSession.getCaptures();
        assertEquals("one still was asked for, and no metering", 1, stills.size());
        assertNull(stills.get(0).get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER));
        assertTorchLit(stills.get(0));
    }

    @Test
    public void aRecordingKeepsTheTorchLit() {
        open();
        module.setTorchEnabled(true);
        FakeCamera2.idleMainLooper();

        module.startRecording(output);
        FakeCamera2.idleMainLooper();

        assertTrue(activeSession() instanceof VideoSession);
        assertTorchLit(repeating());

        module.stopRecording();
        FakeCamera2.idleMainLooper();
        assertTrue(activeSession() instanceof PictureSession);
        assertTorchLit(repeating());
    }

    /** Every rebuild of the session builds its requests again, and the torch has to be in them. */
    @Test
    public void theTorchSurvivesALensSwitchAndAQualityChange() {
        open();
        module.setTorchEnabled(true);
        FakeCamera2.idleMainLooper();

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isUsingFrontFacingCamera());
        assertTorchLit(repeating());

        module.setQuality(CameraView.Quality.LOW);
        FakeCamera2.idleMainLooper();
        assertTorchLit(repeating());
    }

    /** A camera with no flash has nothing to light, and says so by refusing. */
    @Test
    public void anOpenCameraWithoutAFlashDeclines() {
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.FLASH_INFO_AVAILABLE, false);
        open();

        module.setTorchEnabled(true);
        FakeCamera2.idleMainLooper();

        assertFalse(module.isTorchEnabled());
        assertNotEquals(Integer.valueOf(CaptureRequest.FLASH_MODE_TORCH),
                repeating().get(CaptureRequest.FLASH_MODE));
    }

    /**
     * Asked before a camera opens, the request is kept, and lights the torch on a camera that has
     * one: switching to a lens without a flash leaves it unlit, and switching back lights it.
     */
    @Test
    public void aRequestMadeBeforeOpeningFollowsTheLens() {
        FakeCamera2.setCharacteristic(context, FakeCamera2.FRONT_ID,
                CameraCharacteristics.FLASH_INFO_AVAILABLE, false);
        module.setTorchEnabled(true);
        assertTrue("kept, since there is no camera to ask yet", module.isTorchEnabled());
        open();
        assertTorchLit(repeating());

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isTorchEnabled());
        assertNotEquals(Integer.valueOf(CaptureRequest.FLASH_MODE_TORCH),
                repeating().get(CaptureRequest.FLASH_MODE));

        module.setLensFacing(CameraView.LensFacing.BACK);
        FakeCamera2.idleMainLooper();
        assertTorchLit(repeating());
    }

    /** Setting what is already set costs nothing, which matters to a caller that sets it often. */
    @Test
    public void anUnchangedRequestIsNotReissued() {
        open();
        int before = ShadowCallbackCameraCaptureSession.getRepeatingRequests().size();

        module.setTorchEnabled(false);
        module.setTorchEnabled(false);
        FakeCamera2.idleMainLooper();

        assertEquals(before, ShadowCallbackCameraCaptureSession.getRepeatingRequests().size());
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

    private static void assertTorchLit(CaptureRequest request) {
        assertEquals(Integer.valueOf(CaptureRequest.FLASH_MODE_TORCH),
                request.get(CaptureRequest.FLASH_MODE));
        assertEquals("the torch needs an exposure mode that leaves the flash alone",
                Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON),
                request.get(CaptureRequest.CONTROL_AE_MODE));
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
