package com.xlythe.view.camera.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;

import java.util.ArrayList;
import java.util.List;

/**
 * The legacy android.hardware.Camera backend, which the library keeps for releases below the
 * Camera2 floor. Robolectric shadows that API, so the lens bookkeeping, zoom scaling and
 * open/close lifecycle can be driven without a device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class LegacyCameraModuleTest {
    private Context context;
    private TestCameraView cameraView;
    private LegacyCameraModule module;

    /** Supplies the SurfaceTexture a TextureView never produces without a hardware layer. */
    private static class TestCameraView extends CameraView {
        private final SurfaceTexture mSurfaceTexture = new SurfaceTexture(0);

        TestCameraView(Context context) {
            super(context);
        }

        @Override
        public SurfaceTexture getSurfaceTexture() {
            return mSurfaceTexture;
        }

        void release() {
            mSurfaceTexture.release();
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        addCamera(0, Camera.CameraInfo.CAMERA_FACING_BACK, 90);
        addCamera(1, Camera.CameraInfo.CAMERA_FACING_FRONT, 270);

        cameraView = new TestCameraView(context);
        cameraView.layout(0, 0, 1080, 1920);
        module = new LegacyCameraModule(cameraView);
    }

    @After
    public void tearDown() {
        module.close();
        cameraView.release();
        ShadowCamera.clearCameraInfo();
    }

    private static void addCamera(int id, int facing, int orientation) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.facing = facing;
        info.orientation = orientation;
        ShadowCamera.addCameraInfo(id, info);
    }

    @Test
    public void openingTakesTheBackCameraAndStartsPreviewing() {
        module.open();

        assertEquals(0, ShadowCamera.getLastOpenedCameraId());
        assertFalse(module.isUsingFrontFacingCamera());
        assertTrue(module.hasFrontFacingCamera());
    }

    @Test
    public void closingReleasesTheCamera() {
        module.open();
        assertEquals(0, ShadowCamera.getLastOpenedCameraId());

        module.close();

        // Reopening has to work, which it only does if the first camera was released.
        module.open();
        assertEquals(0, ShadowCamera.getLastOpenedCameraId());
    }

    /**
     * Asserts the lens bookkeeping rather than a reopen: this backend only reopens when the
     * owning CameraView reports itself open, which a test driving the module directly cannot
     * arrange. Camera2 tracks that itself, which is worth knowing if these ever converge.
     */
    @Test
    public void lensFacingSelectsTheMatchingCamera() {
        module.open();

        module.setLensFacing(CameraView.LensFacing.FRONT);
        assertTrue(module.isUsingFrontFacingCamera());

        module.setLensFacing(CameraView.LensFacing.BACK);
        assertFalse(module.isUsingFrontFacingCamera());
    }

    @Test
    public void togglingWalksTheCameraList() {
        module.open();
        assertFalse(module.isUsingFrontFacingCamera());

        module.toggleCamera();
        assertTrue(module.isUsingFrontFacingCamera());

        module.toggleCamera();
        assertFalse(module.isUsingFrontFacingCamera());
    }

    /** A request outside the range must not reach the camera, which would reject it. */
    @Test
    public void zoomIsClampedToTheSupportedRange() {
        module.open();

        module.setZoomLevel(Integer.MAX_VALUE);
        assertTrue("zoom must not exceed the maximum",
                module.getZoomLevel() <= module.getMaxZoomLevel());

        module.setZoomLevel(-20);
        assertEquals(0, module.getZoomLevel());
    }

    /** The published scale matches the other backends, so one control drives all three. */
    @Test
    public void theZoomScaleIsTheSameOnEveryBackend() {
        assertEquals(100, module.getMaxZoomLevel());
    }

    @Test
    public void zoomLevelsMapOntoTheCamerasOwnZoomTable() {
        // A camera offering 30 steps: the ends line up and the middle lands mid-table.
        assertEquals(0, LegacyCameraModule.toZoomIndex(0, 30));
        assertEquals(30, LegacyCameraModule.toZoomIndex(100, 30));
        assertEquals(15, LegacyCameraModule.toZoomIndex(50, 30));

        // Out-of-range positions clamp instead of reaching the camera.
        assertEquals(0, LegacyCameraModule.toZoomIndex(-50, 30));
        assertEquals(30, LegacyCameraModule.toZoomIndex(Integer.MAX_VALUE, 30));

        // A camera that cannot zoom has no table to index into.
        assertEquals(0, LegacyCameraModule.toZoomIndex(100, 0));
    }

    @Test
    public void zoomIndexesMapBackOntoTheLevel() {
        assertEquals(0, LegacyCameraModule.toZoomLevel(0, 30));
        assertEquals(100, LegacyCameraModule.toZoomLevel(30, 30));
        assertEquals(50, LegacyCameraModule.toZoomLevel(15, 30));
        assertEquals(0, LegacyCameraModule.toZoomLevel(5, 0));
    }

    /** Querying zoom before opening must answer rather than throw. */
    @Test
    public void zoomQueriesBeforeOpeningAreSafe() {
        assertEquals(0, module.getZoomLevel());
        assertEquals(100, module.getMaxZoomLevel());
        assertFalse(module.isZoomSupported());
        module.setZoomLevel(50);
    }

    /**
     * A focus the camera took is reported on the view, where an app can draw it.
     *
     * <p>Only the request: the shadowed camera records the autofocus callback rather than
     * answering it, which is the same shape as a camera that has not finished moving its lens.
     * </p>
     */
    @Test
    public void focusingReportsTheRequestOnTheView() {
        module.open();
        assertTrue("the shadowed camera supports auto focus", module.isFocusSupported());
        List<RectF> requested = new ArrayList<>();
        cameraView.setFocusListener(new CameraView.FocusListener() {
            @Override public void onFocusRequested(RectF area) { requested.add(area); }
            @Override public void onFocusCompleted(RectF area, boolean locked) {}
        });

        module.focus(new Rect(-100, -100, 100, 100), new Rect(-200, -200, 200, 200));

        assertEquals(1, requested.size());
        assertTrue("somewhere on the view: " + requested.get(0),
                requested.get(0).centerX() > 0 && requested.get(0).centerX() < 1080);
    }

    /** A camera that was never opened cannot focus, and says so rather than guessing. */
    @Test
    public void aClosedCameraCannotFocus() {
        assertFalse(module.isFocusSupported());
    }

    @Test
    public void nothingIsRecordingUntilAskedTo() {
        module.open();

        assertFalse(module.isRecording());
    }

    /** Stopping a recording that never started reports failure rather than throwing. */
    @Test
    public void stoppingWithoutRecordingIsHandled() {
        module.open();

        module.stopRecording();

        assertFalse(module.isRecording());
    }

    @Test
    public void aLayoutChangeBeforeOpeningIsHarmless() {
        module.onLayoutChanged();
    }

    @Test
    public void closingTwiceIsHarmless() {
        module.open();
        module.close();
        module.close();
    }
}
