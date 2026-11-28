package com.xlythe.view.camera.legacy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;

import androidx.exifinterface.media.ExifInterface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;
import com.xlythe.view.camera.ExifCaptureParameters;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.util.ReflectionHelpers;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * MAX asks for the largest picture the camera lists. The shadowed camera starts at 1280x960
     * and lists nothing larger than 800x600, so the two are easy to tell apart.
     */
    @Test
    public void aMaxPictureIsTheLargestListed() {
        module.setQuality(CameraView.Quality.MAX);
        module.open();

        assertEquals("800x600", pictureSize());
    }

    /** A change of quality reaches a camera that is already open, both ways. */
    @Test
    public void changingQualityWhileOpenResizesThePicture() {
        module.open();

        module.setQuality(CameraView.Quality.MAX);
        assertEquals("800x600", pictureSize());

        module.setQuality(CameraView.Quality.LOW);
        assertEquals("640x480", pictureSize());
    }

    private String pictureSize() {
        Camera camera = ReflectionHelpers.getField(module, "mCamera");
        Camera.Size size = camera.getParameters().getPictureSize();
        return size.width + "x" + size.height;
    }

    /** The published scale matches the other backends, so one control drives all three. */
    @Test
    public void theZoomScaleIsTheSameOnEveryBackend() {
        assertEquals(100, module.getMaxZoomLevel());
    }

    /** This API takes an index into a table of ratios, so a ratio picks the nearest entry. */
    @Test
    public void aRatioPicksTheNearestEntryOfTheZoomTable() {
        List<Integer> table = Arrays.asList(100, 150, 200, 300, 400);

        assertEquals(0, LegacyCameraModule.nearestZoomIndex(table, 1f));
        assertEquals(2, LegacyCameraModule.nearestZoomIndex(table, 2f));
        assertEquals("2.4x is nearer 2x than 3x",
                2, LegacyCameraModule.nearestZoomIndex(table, 2.4f));
        assertEquals(3, LegacyCameraModule.nearestZoomIndex(table, 2.6f));

        // Beyond either end is that end.
        assertEquals(0, LegacyCameraModule.nearestZoomIndex(table, 0.5f));
        assertEquals(4, LegacyCameraModule.nearestZoomIndex(table, 10f));
    }

    /** A ratio set on an open camera lands on the table and reads back as the entry it chose. */
    @Test
    public void aRatioRoundTripsThroughTheZoomTable() throws Exception {
        module.open();
        giveTheCameraAZoomTable();
        assertTrue(module.isZoomSupported());
        assertEquals(1f, module.getMinZoomRatio(), 0f);
        assertEquals(4f, module.getMaxZoomRatio(), 0f);

        module.setZoomRatio(2f);
        assertEquals(2f, module.getZoomRatio(), 0f);
        assertEquals("the camera is on the 2x entry", 2, openCamera().getParameters().getZoom());

        module.setZoomRatio(2.9f);
        assertEquals("the nearest entry", 3f, module.getZoomRatio(), 0f);

        module.setZoomLevel(module.getMaxZoomLevel());
        assertEquals(4f, module.getZoomRatio(), 0f);
        assertEquals(module.getMaxZoomLevel(), module.getZoomLevel());
    }

    /** 1x, 2x and the far end of the table, since this API says nothing about lenses. */
    @Test
    public void presetsAreOneTwoAndTheMaximum() throws Exception {
        assertArrayEquals("a closed camera has nothing but 1x",
                new float[] {1f}, module.getZoomPresets(), 0f);

        module.open();
        giveTheCameraAZoomTable();

        assertArrayEquals(new float[] {1f, 2f, 4f}, module.getZoomPresets(), 0f);
    }

    /** A zoom asked for while closed waits for open, and a camera that cannot zoom drops it. */
    @Test
    public void aZoomSetWhileClosedWaitsForTheCamera() {
        module.setZoomRatio(3f);
        assertEquals("held until there is a camera to give it to", 3f, module.getZoomRatio(), 0f);

        module.open();

        assertEquals("the shadowed camera cannot zoom at all", 1f, module.getZoomRatio(), 0f);
    }

    /** Querying zoom before opening must answer rather than throw. */
    @Test
    public void zoomQueriesBeforeOpeningAreSafe() {
        assertEquals(0, module.getZoomLevel());
        assertEquals(100, module.getMaxZoomLevel());
        assertFalse(module.isZoomSupported());
        module.setZoomLevel(50);
    }

    /** The range is the open camera's; a closed one has none to give. */
    @Test
    public void exposureCompensationReportsTheCamerasRange() {
        assertFalse(module.isExposureCompensationSupported());
        assertEquals(0, module.getMinExposureCompensation());
        assertEquals(0, module.getMaxExposureCompensation());
        assertEquals(0f, module.getExposureCompensationStep(), 0f);

        module.open();

        // The shadowed camera's defaults: -6 to 6, in steps of 1/2.
        assertTrue(module.isExposureCompensationSupported());
        assertEquals(-6, module.getMinExposureCompensation());
        assertEquals(6, module.getMaxExposureCompensation());
        assertEquals(0.5f, module.getExposureCompensationStep(), 0f);
    }

    /**
     * Holding exposure for a sweep reaches the camera's parameters, for whichever of the two
     * locks the camera has, and so does letting go.
     */
    @Test
    public void exposureLockReachesTheCamera_whereItCanHold() throws Exception {
        module.open();
        Camera.Parameters parameters = openCamera().getParameters();
        parameters.set("auto-exposure-lock-supported", "true");
        parameters.set("auto-whitebalance-lock-supported", "false");
        openCamera().setParameters(parameters);

        module.setExposureLocked(true);
        assertTrue(module.isExposureLocked());
        assertTrue(openCamera().getParameters().getAutoExposureLock());
        assertNull("not asked of a camera that cannot",
                openCamera().getParameters().get("auto-whitebalance-lock"));

        module.setExposureLocked(false);
        assertFalse(openCamera().getParameters().getAutoExposureLock());
    }

    /** An index lands on the camera's parameters, held to its range. */
    @Test
    public void anExposureIndexReachesTheCameraClamped() throws Exception {
        module.open();

        module.setExposureCompensation(3);
        assertEquals(3, module.getExposureCompensation());
        assertEquals(3, openCamera().getParameters().getExposureCompensation());

        module.setExposureCompensation(40);
        assertEquals(6, module.getExposureCompensation());
        assertEquals(6, openCamera().getParameters().getExposureCompensation());

        module.setExposureCompensation(-40);
        assertEquals(-6, module.getExposureCompensation());
        assertEquals(-6, openCamera().getParameters().getExposureCompensation());
    }

    /** With no camera to ask, the index is kept as given, and clamped once there is one. */
    @Test
    public void anExposureIndexSetWhileClosedWaitsForTheCamera() throws Exception {
        module.setExposureCompensation(40);
        assertEquals(40, module.getExposureCompensation());

        module.open();

        assertEquals(6, module.getExposureCompensation());
        assertEquals(6, openCamera().getParameters().getExposureCompensation());
    }

    /**
     * A camera that cannot compensate refuses a request while it is open, and leaves one made
     * before it opened for the next camera that can.
     */
    @Test
    public void aCameraWithoutExposureCompensationDeclines() throws Exception {
        module.open();
        takeAwayExposureCompensation();
        assertFalse(module.isExposureCompensationSupported());
        assertEquals(0, module.getMinExposureCompensation());
        assertEquals(0, module.getMaxExposureCompensation());
        assertEquals("the step it publishes is not one it can use",
                0f, module.getExposureCompensationStep(), 0f);

        module.setExposureCompensation(3);
        assertEquals(0, module.getExposureCompensation());
        assertEquals(0, openCamera().getParameters().getExposureCompensation());

        module.close();
        module.setExposureCompensation(2);
        // The same camera, whose parameters the shadow keeps between openings.
        module.open();
        assertEquals("kept for a camera that can", 2, module.getExposureCompensation());
        assertEquals(0, openCamera().getParameters().getExposureCompensation());
    }

    /**
     * The torch, stabilization and low-light boost are refused while a camera that cannot do them
     * is open, as on the other backends, rather than reported on and never acted on. A request
     * made while closed is kept for the camera that opens.
     */
    @Test
    public void controlsTheCameraCannotDoAreRefusedWhileItIsOpen() {
        module.setTorchEnabled(true);
        assertTrue("kept until a camera opens", module.isTorchEnabled());
        module.setTorchEnabled(false);
        // A camera without a flash unit, which lists no flash modes.
        Camera camera = Camera.open(0);
        Camera.Parameters parameters = camera.getParameters();
        parameters.remove("flash-mode-values");
        camera.setParameters(parameters);
        camera.release();

        module.open();
        assertFalse(module.hasFlash());
        module.setTorchEnabled(true);
        assertFalse(module.isTorchEnabled());

        assertFalse(module.isStabilizationSupported());
        module.setStabilizationEnabled(true);
        assertFalse(module.isStabilizationEnabled());

        assertFalse(module.isLowLightBoostSupported());
        module.setLowLightBoostEnabled(true);
        assertFalse(module.isLowLightBoostEnabled());
    }

    /** A picture's Exif reports the index the camera took, times its step. */
    @Test
    public void aPictureRecordsTheExposureBias() throws Exception {
        module.open();
        module.setExposureCompensation(-3);

        ExifCaptureParameters parameters =
                ExifCaptureParameters.forLegacyCamera(openCamera().getParameters());
        Exif exif = new Exif(Mockito.mock(InputStream.class));
        parameters.applyTo(exif);

        String[] bias = exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE).split("/");
        assertEquals("-3 steps of 1/2 EV", -1.5,
                Double.parseDouble(bias[0]) / Double.parseDouble(bias[1]), 0.001);
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

    /**
     * Gives the open camera a zoom table of 1x, 1.5x, 2x, 3x and 4x.
     *
     * <p>The shadowed camera starts with none, so it reports that it cannot zoom. Its parameters
     * are held by the camera and handed out as the same object every time, which is what lets them
     * be changed here.</p>
     */
    private void giveTheCameraAZoomTable() throws Exception {
        Camera.Parameters parameters = openCamera().getParameters();
        parameters.set("zoom-supported", "true");
        parameters.set("max-zoom", 4);
        parameters.set("zoom-ratios", "100,150,200,300,400");
        parameters.set("zoom", 0);
    }

    /** Gives the open camera the 0-to-0 range this API reports for one that cannot compensate. */
    private void takeAwayExposureCompensation() throws Exception {
        Camera.Parameters parameters = openCamera().getParameters();
        parameters.set("min-exposure-compensation", 0);
        parameters.set("max-exposure-compensation", 0);
    }

    private Camera openCamera() throws Exception {
        java.lang.reflect.Field field = LegacyCameraModule.class.getDeclaredField("mCamera");
        field.setAccessible(true);
        return (Camera) field.get(module);
    }
}
