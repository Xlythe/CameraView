package com.xlythe.view.camera.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.Camera;
import android.os.Looper;

import com.xlythe.view.camera.BusyCameraRetry;
import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowBusyCamera;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opening a camera the old API reports as unavailable, as it does while another client still
 * has it.
 *
 * <p>Driven through the view, because the view is what an app watches: a camera let go inside
 * the window has to end up announced as open, and one that is not has to stop being asked
 * for.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37}, shadows = {ShadowAvailableTextureView.class, ShadowBusyCamera.class})
public class LegacyBusyCameraTest {
    /** The application, which holds the camera permission. */
    private Application context;
    /** The view under test, on the old camera API. */
    private CameraView cameraView;
    /** How many times the view has announced the camera open. */
    private final AtomicInteger opened = new AtomicInteger();

    @Before
    public void setUp() {
        ShadowBusyCamera.reset();
        context = RuntimeEnvironment.getApplication();
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA);
        addCamera(0, Camera.CameraInfo.CAMERA_FACING_BACK);
        addCamera(1, Camera.CameraInfo.CAMERA_FACING_FRONT);

        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);
        cameraView = new CameraView(context);
        cameraView.layout(0, 0, 1080, 1920);
        cameraView.setOnCameraStateChangedListener(new CameraView.OnCameraStateChangedListener() {
            @Override public void onCameraOpened() {
                opened.incrementAndGet();
            }

            @Override public void onCameraClosed() {}
        });
    }

    @After
    public void tearDown() {
        cameraView.close();
        idle();
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
        ShadowCamera.clearCameraInfo();
    }

    @Test
    public void aCameraLetGoWithinTheWindowOpens() {
        ShadowBusyCamera.refuseNextOpens(3);

        cameraView.open();
        idle();
        assertEquals("the first open is turned down", 1, ShadowBusyCamera.getOpenCount());
        assertFalse(cameraView.isOpen());

        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        assertEquals("three refusals and then the open that worked",
                4, ShadowBusyCamera.getOpenCount());
        assertTrue(cameraView.isOpen());
        assertEquals("the view announces the camera once", 1, opened.get());
    }

    @Test
    public void aCameraBusyThroughoutFailsOnceTheWindowRunsOut() {
        ShadowBusyCamera.refuseNextOpens(Integer.MAX_VALUE);

        cameraView.open();
        idle();
        idleFor(BusyCameraRetry.WINDOW_MILLIS);
        int tries = ShadowBusyCamera.getOpenCount();
        List<Long> openedAt = ShadowBusyCamera.getOpenedAt();

        assertTrue("the camera is tried more than once", tries > 2);
        assertTrue("every try falls inside the window",
                openedAt.get(tries - 1) - openedAt.get(0) <= BusyCameraRetry.WINDOW_MILLIS);
        assertFalse(cameraView.isOpen());

        // Let go now, after the window, it is no longer asked for.
        ShadowBusyCamera.refuseNextOpens(0);
        idleFor(BusyCameraRetry.WINDOW_MILLIS);
        assertEquals(tries, ShadowBusyCamera.getOpenCount());
        assertFalse(cameraView.isOpen());
        assertEquals(0, opened.get());
    }

    @Test
    public void closingStopsTheRetries() {
        ShadowBusyCamera.refuseNextOpens(Integer.MAX_VALUE);
        cameraView.open();
        idle();
        assertEquals(1, ShadowBusyCamera.getOpenCount());

        cameraView.close();
        idle();
        ShadowBusyCamera.refuseNextOpens(0);
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        assertEquals("a closed view does not ask again", 1, ShadowBusyCamera.getOpenCount());
        assertFalse(cameraView.isOpen());
    }

    @Test
    public void switchingCamerasStopsTryingTheOldOne() {
        ShadowBusyCamera.refuseNextOpens(1);
        cameraView.open();
        idle();
        assertEquals(1, ShadowBusyCamera.getOpenCount());

        cameraView.toggleCamera();
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        assertEquals("the busy back camera, then the front one in its place",
                Arrays.asList(0, 1), ShadowBusyCamera.getOpenedIds());
        assertTrue(cameraView.isOpen());
    }

    @Test
    public void aCameraWithoutPermissionIsNotTriedAgain() {
        shadowOf(context).denyPermissions(Manifest.permission.CAMERA);
        ShadowBusyCamera.refuseNextOpens(Integer.MAX_VALUE);

        cameraView.open();
        idle();
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        assertEquals(1, ShadowBusyCamera.getOpenCount());
        assertFalse(cameraView.isOpen());
    }

    @Test
    public void aCameraTurnedOffByPolicyIsNotTriedAgain() {
        DevicePolicyManager policy =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, "Admin");
        shadowOf(policy).setActiveAdmin(admin);
        policy.setCameraDisabled(admin, true);
        ShadowBusyCamera.refuseNextOpens(Integer.MAX_VALUE);

        cameraView.open();
        idle();
        idleFor(BusyCameraRetry.WINDOW_MILLIS);

        assertEquals(1, ShadowBusyCamera.getOpenCount());
        assertFalse(cameraView.isOpen());
    }

    private static void addCamera(int id, int facing) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.facing = facing;
        info.orientation = facing == Camera.CameraInfo.CAMERA_FACING_BACK ? 90 : 270;
        ShadowCamera.addCameraInfo(id, info);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void idleFor(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
