package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;

import com.xlythe.view.camera.shadow.ShadowCallbackCameraCaptureSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Holding auto-exposure and auto white balance on the Camera2 backend, as a sweep does: both locks
 * on every repeating request while held, neither once let go, and only the ones the camera says it
 * can hold.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowCallbackCameraCaptureSession.class},
        qualifiers = "w360dp-h800dp-xxxhdpi")
public class ExposureLockTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    @Before
    public void setUp() {
        ShadowCallbackCameraCaptureSession.reset();
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
    }

    @After
    public void tearDown() {
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    @Test
    public void holdingReachesTheRepeatingRequest_andLettingGoFreesIt() {
        open();
        assertNull("nothing is held until asked", repeating().get(CaptureRequest.CONTROL_AE_LOCK));

        module.setExposureLocked(true);
        FakeCamera2.idleMainLooper();
        assertTrue(module.isExposureLocked());
        assertEquals(Boolean.TRUE, repeating().get(CaptureRequest.CONTROL_AE_LOCK));
        assertEquals(Boolean.TRUE, repeating().get(CaptureRequest.CONTROL_AWB_LOCK));

        module.setExposureLocked(false);
        FakeCamera2.idleMainLooper();
        assertFalse(module.isExposureLocked());
        assertNull(repeating().get(CaptureRequest.CONTROL_AE_LOCK));
        assertNull(repeating().get(CaptureRequest.CONTROL_AWB_LOCK));
    }

    @Test
    public void aLockAskedForBeforeOpening_isOnTheFirstRequest() {
        module.setExposureLocked(true);
        open();
        assertEquals(Boolean.TRUE, repeating().get(CaptureRequest.CONTROL_AE_LOCK));
        assertEquals(Boolean.TRUE, repeating().get(CaptureRequest.CONTROL_AWB_LOCK));
    }

    /** A camera that publishes that it cannot hold white balance is asked only for exposure. */
    @Test
    @Config(sdk = 37)
    public void onlyTheLocksTheCameraHasAreAskedFor() {
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE, false);
        FakeCamera2.setCharacteristic(context, FakeCamera2.BACK_ID,
                CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE, true);
        module.setExposureLocked(true);
        open();
        assertEquals(Boolean.TRUE, repeating().get(CaptureRequest.CONTROL_AE_LOCK));
        assertNull(repeating().get(CaptureRequest.CONTROL_AWB_LOCK));
    }

    private void open() {
        module.open();
        FakeCamera2.idleMainLooper();
    }

    private static CaptureRequest repeating() {
        CaptureRequest request = ShadowCallbackCameraCaptureSession.getLastRepeatingRequest();
        assertNotNull("expected a repeating request", request);
        return request;
    }
}
