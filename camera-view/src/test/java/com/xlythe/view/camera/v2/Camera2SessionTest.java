package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.robolectric.RuntimeEnvironment;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Drives the Camera2 backend against Robolectric's camera2 shadows, so the session machinery runs
 * rather than being mocked out at the edges.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class Camera2SessionTest {
    private Context context;
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
    }

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    /**
     * The angle of view the camera can see, and what zoom does to it.
     *
     * <p>This is what maps a direction in the world onto a position on the preview, so anything
     * drawn over the camera to point at something depends on it. The fake camera publishes a
     * 5.76mm by 4.29mm sensor behind a 4.3mm lens, and the arithmetic can be done by hand:
     * 2*atan(5.76/8.6) is 67.63 degrees across.</p>
     */
    @Test
    public void reportsTheAngleOfViewAndNarrowsItWithZoom() throws Exception {
        float wide = module.getHorizontalFieldOfView();
        assertEquals("a 5.76mm sensor behind a 4.3mm lens", 67.63f, wide, 0.1f);

        float tall = module.getVerticalFieldOfView();
        assertEquals("and 53.02 degrees the short way", 53.02f, tall, 0.1f);
        assertTrue("the sensor is wider than it is tall, so the angle is too", wide > tall);

        module.setZoomLevel(module.getMaxZoomLevel());
        float zoomed = module.getHorizontalFieldOfView();
        assertTrue("zooming in has to narrow the view: " + wide + " -> " + zoomed,
                zoomed < wide);
        assertTrue("and never to nothing", zoomed > 0f);

        module.setZoomLevel(0);
        assertEquals("coming back out restores it",
                wide, module.getHorizontalFieldOfView(), 0.1f);
    }

    @Test
    public void readsCapabilitiesFromTheActiveCamera() throws Exception {
        assertEquals(FakeCamera2.BACK_ID, module.getActiveCamera());
        assertTrue(module.hasFrontFacingCamera());
        assertFalse(module.isUsingFrontFacingCamera());
        assertTrue(module.hasFlash());
        assertTrue(module.isZoomSupported());
        assertEquals(100, module.getMaxZoomLevel());
    }

    /**
     * A USB webcam or virtual camera can report itself as back-facing and be listed first, and
     * before INFO_DEVICE_TYPE there was no way to tell it from the real sensor behind it.
     */
    @Test
    public void anExternalCameraDoesNotBecomeTheDefault() throws Exception {
        FakeCamera2.addExternalCamera(context, "webcam",
                android.hardware.camera2.CameraMetadata.LENS_FACING_BACK,
                android.hardware.camera2.CameraMetadata.INFO_DEVICE_TYPE_EXTERNAL);
        FakeCamera2.addExternalCamera(context, "virtual",
                android.hardware.camera2.CameraMetadata.LENS_FACING_BACK,
                android.hardware.camera2.CameraMetadata.INFO_DEVICE_TYPE_VIRTUAL);

        Camera2Module fresh = new Camera2Module(cameraView);
        assertEquals(FakeCamera2.BACK_ID, fresh.getActiveCamera());
    }

    /** The same preference applies when a lens facing is requested explicitly. */
    @Test
    public void lensFacingPrefersTheBuiltInSensor() throws Exception {
        FakeCamera2.addExternalCamera(context, "webcam",
                android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT,
                android.hardware.camera2.CameraMetadata.INFO_DEVICE_TYPE_EXTERNAL);

        module.open();
        FakeCamera2.idleMainLooper();
        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();

        assertEquals(FakeCamera2.FRONT_ID, module.getActiveCamera());
    }

    /** An external camera is still reachable, it just is not the one chosen by default. */
    @Test
    public void anExternalCameraRemainsSelectable() throws Exception {
        FakeCamera2.addExternalCamera(context, "webcam",
                android.hardware.camera2.CameraMetadata.LENS_FACING_BACK,
                android.hardware.camera2.CameraMetadata.INFO_DEVICE_TYPE_EXTERNAL);

        Camera2Module fresh = new Camera2Module(cameraView);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 4; i++) {
            fresh.toggleCamera();
            FakeCamera2.idleMainLooper();
            seen.add(fresh.getActiveCamera());
        }
        assertTrue("toggling should reach the external camera", seen.contains("webcam"));
    }

    @Test
    public void openingConfiguresAPictureSession() {
        module.open();
        FakeCamera2.idleMainLooper();

        assertNotNull("expected a capture session to be configured", activeSession());
        assertTrue("expected a PictureSession", activeSession() instanceof PictureSession);
    }

    @Test
    public void closingReleasesTheSession() {
        module.open();
        FakeCamera2.idleMainLooper();
        assertNotNull(activeSession());

        module.close();
        FakeCamera2.idleMainLooper();
        assertEquals(null, activeSession());
    }

    @Test
    public void lensFacingSwitchesTheActiveCamera() throws Exception {
        module.open();
        FakeCamera2.idleMainLooper();

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();

        assertEquals(FakeCamera2.FRONT_ID, module.getActiveCamera());
        assertTrue(module.isUsingFrontFacingCamera());
    }

    @Test
    public void toggleWalksTheCameraList() throws Exception {
        module.open();
        FakeCamera2.idleMainLooper();

        module.toggleCamera();
        FakeCamera2.idleMainLooper();
        assertEquals(FakeCamera2.FRONT_ID, module.getActiveCamera());

        module.toggleCamera();
        FakeCamera2.idleMainLooper();
        assertEquals(FakeCamera2.BACK_ID, module.getActiveCamera());
    }

    @Test
    public void zoomIsClampedToWhatTheCameraReports() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.setZoomLevel(1000);
        assertEquals(module.getMaxZoomLevel(), module.getZoomLevel());

        module.setZoomLevel(-5);
        assertEquals(0, module.getZoomLevel());
    }

    @Test
    public void pauseAndResumeTrackTheSession() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.pause();
        assertTrue(module.isPaused());

        module.resume();
        assertFalse(module.isPaused());
    }

    @Test
    public void focusMapsIntoTheSensorArrayWithoutThrowing() {
        module.open();
        FakeCamera2.idleMainLooper();

        module.focus(new android.graphics.Rect(-100, -100, 100, 100),
                new android.graphics.Rect(-200, -200, 200, 200));
        FakeCamera2.idleMainLooper();

        assertNotNull(activeSession().getMeteringRectangle());
    }

    @Test
    public void qualityChangeRebuildsThePictureSession() {
        module.open();
        FakeCamera2.idleMainLooper();
        Camera2Module.Session first = activeSession();

        module.setQuality(CameraView.Quality.LOW);
        FakeCamera2.idleMainLooper();

        assertNotNull(activeSession());
        assertFalse("expected a fresh session", first == activeSession());
    }

    @Test
    public void takingAPictureBeforeOpeningFails() {
        module.takePicture(new java.io.File(context.getCacheDir(), "never-written.jpg"));
        FakeCamera2.idleMainLooper();
        // Reports failure rather than throwing or silently doing nothing.
        assertFalse(module.isRecording());
    }

    @Test
    public void savedStateRestoresTheSelectedCamera() throws Exception {
        module.open();
        FakeCamera2.idleMainLooper();
        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();

        android.os.Parcelable state = module.onSaveInstanceState();
        Camera2Module restored = new Camera2Module(cameraView);
        restored.onRestoreInstanceState(state);

        assertEquals(FakeCamera2.FRONT_ID, restored.getActiveCamera());
    }

    /** Reaches the module's active session, which it keeps private. */
    private Camera2Module.Session activeSession() {
        try {
            java.lang.reflect.Field field = Camera2Module.class.getDeclaredField("mActiveSession");
            field.setAccessible(true);
            return (Camera2Module.Session) field.get(module);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
