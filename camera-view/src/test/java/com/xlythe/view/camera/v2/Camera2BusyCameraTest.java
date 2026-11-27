package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

import com.xlythe.view.camera.BusyCameraRetry;
import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

/**
 * Opening a camera another client holds, as Camera2 reports it: refused at once with a camera in
 * use, refused through onError, or disconnected before it ever opened.
 *
 * <p>The camera service is a mock, so each refusal is exactly the one a device gives and the
 * tries the backend makes in answer can be counted.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class Camera2BusyCameraTest {
    /** The camera service, which refuses opens as each test arranges. */
    private CameraManager cameraManager;
    /** The backend under test. */
    private Camera2Module module;

    @Before
    public void setUp() throws Exception {
        cameraManager = Mockito.mock(CameraManager.class);
        CameraCharacteristics back = Mockito.mock(CameraCharacteristics.class);
        Mockito.when(cameraManager.getCameraIdList()).thenReturn(new String[] {"back"});
        Mockito.when(cameraManager.getCameraCharacteristics("back")).thenReturn(back);
        Mockito.when(back.get(CameraCharacteristics.LENS_FACING))
                .thenReturn(CameraCharacteristics.LENS_FACING_BACK);
        Context cameraContext = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public Object getSystemService(String name) {
                return Context.CAMERA_SERVICE.equals(name)
                        ? cameraManager : super.getSystemService(name);
            }
        };
        module = new Camera2Module(new CameraView(cameraContext));
    }

    @After
    public void tearDown() {
        module.close();
        idle();
    }

    @Test
    public void aCameraInUseIsTriedAgainUntilItIsFree() throws Exception {
        Mockito.doThrow(inUse()).doThrow(inUse()).doNothing().when(cameraManager)
                .openCamera(eq("back"), any(CameraDevice.StateCallback.class), any(Handler.class));

        module.open();
        verifyOpens(1);
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        verifyOpens(3);
        assertTrue("still wanting the camera", isOpen());
    }

    @Test
    public void aCameraInUseThroughoutFailsOnceTheWindowRunsOut() throws Exception {
        Mockito.doThrow(inUse()).when(cameraManager)
                .openCamera(eq("back"), any(CameraDevice.StateCallback.class), any(Handler.class));

        module.open();
        idleFor(BusyCameraRetry.WINDOW_MILLIS);
        int tries = Mockito.mockingDetails(cameraManager).getInvocations().stream()
                .mapToInt(invocation ->
                        invocation.getMethod().getName().equals("openCamera") ? 1 : 0)
                .sum();

        assertTrue("the camera is tried more than once", tries > 2);
        assertFalse("the open has failed", isOpen());
        idleFor(BusyCameraRetry.WINDOW_MILLIS);
        verifyOpens(tries);
    }

    @Test
    public void aRefusalThroughOnErrorIsTriedAgain() throws Exception {
        CameraDevice.StateCallback callback = openAndCaptureCallback();

        callback.onError(Mockito.mock(CameraDevice.class),
                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE);
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        verifyOpens(2);
        assertTrue(isOpen());
    }

    @Test
    public void aDisconnectBeforeOpeningIsTriedAgain() throws Exception {
        CameraDevice.StateCallback callback = openAndCaptureCallback();

        callback.onDisconnected(Mockito.mock(CameraDevice.class));
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        verifyOpens(2);
        assertTrue(isOpen());
    }

    /** Both refusals of one busy open, the thrown one and onError, add up to one retry. */
    @Test
    public void theOnErrorThatFollowsARefusalIsNotTriedTwice() throws Exception {
        ArgumentCaptor<CameraDevice.StateCallback> callback =
                ArgumentCaptor.forClass(CameraDevice.StateCallback.class);
        Mockito.doThrow(inUse()).doNothing().when(cameraManager)
                .openCamera(eq("back"), callback.capture(), any(Handler.class));

        module.open();
        callback.getAllValues().get(0).onError(Mockito.mock(CameraDevice.class),
                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE);
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        verifyOpens(2);
    }

    @Test
    public void closingStopsTheRetries() throws Exception {
        Mockito.doThrow(inUse()).doNothing().when(cameraManager)
                .openCamera(eq("back"), any(CameraDevice.StateCallback.class), any(Handler.class));

        module.open();
        module.close();
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        verifyOpens(1);
    }

    @Test
    public void aDisabledCameraIsNotTriedAgain() throws Exception {
        Mockito.doThrow(new CameraAccessException(CameraAccessException.CAMERA_DISABLED))
                .when(cameraManager)
                .openCamera(eq("back"), any(CameraDevice.StateCallback.class), any(Handler.class));

        module.open();
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        verifyOpens(1);
        assertFalse(isOpen());
    }

    @Test
    public void aCameraDisabledThroughOnErrorIsNotOpenedAgain() throws Exception {
        CameraDevice.StateCallback callback = openAndCaptureCallback();

        callback.onError(Mockito.mock(CameraDevice.class),
                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED);
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        verifyOpens(1);
        assertFalse(isOpen());
    }

    @Test
    public void aMissingPermissionIsNotTriedAgain() throws Exception {
        Mockito.doThrow(new SecurityException("no camera permission")).when(cameraManager)
                .openCamera(eq("back"), any(CameraDevice.StateCallback.class), any(Handler.class));

        module.open();
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        verifyOpens(1);
        assertFalse(isOpen());
    }

    private CameraDevice.StateCallback openAndCaptureCallback() throws Exception {
        ArgumentCaptor<CameraDevice.StateCallback> callback =
                ArgumentCaptor.forClass(CameraDevice.StateCallback.class);
        module.open();
        Mockito.verify(cameraManager).openCamera(eq("back"), callback.capture(),
                any(Handler.class));
        return callback.getValue();
    }

    private void verifyOpens(int times) throws Exception {
        Mockito.verify(cameraManager, Mockito.times(times)).openCamera(eq("back"),
                any(CameraDevice.StateCallback.class), any(Handler.class));
    }

    /** Whether the module still wants its camera, which an open that failed gives up. */
    private boolean isOpen() {
        return ReflectionHelpers.getField(module, "mIsOpen");
    }

    private static CameraAccessException inUse() {
        return new CameraAccessException(CameraAccessException.CAMERA_IN_USE);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void idleFor(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
