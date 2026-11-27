package com.xlythe.view.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.FakeImage;
import android.media.ImageReader;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlythe.view.camera.pano.PanoramaGuideView;
import com.xlythe.view.camera.shadow.ShadowAvailableTextureView;
import com.xlythe.view.camera.shadow.ShadowInjectableImageReader;
import com.xlythe.view.camera.shadow.ShadowListingCamcorderProfile;
import com.xlythe.view.camera.v2.FakeCamera2;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowCamera;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Sweeping a panorama through CameraView, on a backend that hands out a surface and on the one
 * that hands over preview bytes.
 *
 * <p>The stitching itself is tested in the pano package without a camera. What is tested here is
 * the wiring: that the frames of each backend reach the sweep, that the guide is shown and taken
 * down, and that a sweep and the other users of the camera's frames keep out of each other's
 * way.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {37}, shadows = {
        ShadowAvailableTextureView.class,
        ShadowInjectableImageReader.class,
        ShadowListingCamcorderProfile.class,
})
public class PanoramaCaptureTest {
    private static final long TIMEOUT_MILLIS = 10_000;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private Context context;
    @Nullable private FakeCamera2.TestCameraView cameraView;
    private Recorder recorder;
    private File file;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((android.app.Application) context).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        recorder = new Recorder();
        file = new File(folder.getRoot(), "sweep.jpg");
    }

    @After
    public void tearDown() {
        if (cameraView != null) {
            cameraView.close();
            idle();
            cameraView.release();
        }
        CameraView.setDefaultBackend(CameraView.Backend.AUTO);
        ShadowCamera.clearCameraInfo();
    }

    @Test
    public void aClosedCamera_cannotSweep() {
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        assertThrows(IllegalStateException.class, () -> cameraView.startPanorama(
                file, CameraView.PanoramaMode.PANORAMA, recorder));
        assertFalse(cameraView.isCapturingPanorama());
        // Stopping and cancelling a sweep that never started do nothing.
        cameraView.stopPanorama();
        cameraView.cancelPanorama();
    }

    @Test
    public void withoutARotationSensor_aSweepDoesNotStart() {
        openCamera2();
        assertFalse(cameraView.startPanorama(file, CameraView.PanoramaMode.PANORAMA, recorder));
        assertFalse(cameraView.isCapturingPanorama());
        assertEquals(View.GONE, guide().getVisibility());
    }

    @Test
    public void aSweep_showsTheGuide_andKeepsTheFramesToItself() {
        Sensor rotation = addRotationSensor();
        openCamera2();
        assertTrue(cameraView.startPanorama(file, CameraView.PanoramaMode.PHOTOSPHERE, recorder));
        assertTrue(cameraView.isCapturingPanorama());
        assertEquals(View.VISIBLE, guide().getVisibility());
        assertTrue(shadowSensors().hasListener(shadowSensors().getListeners().get(0), rotation));

        assertThrows(IllegalStateException.class, () -> cameraView.startPanorama(
                file, CameraView.PanoramaMode.PANORAMA, recorder));
        assertThrows(IllegalStateException.class,
                () -> cameraView.startFrameAnalysis(frame -> {}));
        assertThrows(IllegalStateException.class, () -> cameraView.enterBarcodeScanner(
                barcodes -> {}, Barcode.Format.QR_CODE));

        cameraView.cancelPanorama();
        idle();
        assertFalse(cameraView.isCapturingPanorama());
        assertEquals(View.GONE, guide().getVisibility());
        assertTrue("the sensor is let go", shadowSensors().getListeners().isEmpty());
        assertNull(recorder.saved);
        assertFalse(recorder.failed);
    }

    @Test
    public void aSweep_waitsForOtherUsersOfTheFrames() {
        addRotationSensor();
        openCamera2();
        assertTrue(cameraView.startFrameAnalysis(frame -> {}));
        assertThrows(IllegalStateException.class, () -> cameraView.startPanorama(
                file, CameraView.PanoramaMode.PANORAMA, recorder));
        cameraView.stopFrameAnalysis();
    }

    @Test
    public void camera2Frames_reachTheSweep() throws Exception {
        addRotationSensor();
        openCamera2();
        assertTrue(cameraView.startPanorama(file, CameraView.PanoramaMode.PANORAMA, recorder));
        ImageReader reader = awaitReader();
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        // Frames until one has been painted, each with the rotation it was captured at.
        while (!hasPainted() && System.currentTimeMillis() < deadline) {
            holdStill();
            ShadowInjectableImageReader.offerImage(reader, FakeImage.yuv420(64, 48, 0, 2));
            Thread.sleep(50);
            idle();
        }
        assertTrue("no frame was painted", hasPainted());
        cameraView.stopPanorama();
        awaitResult();
        assertEquals(file, recorder.saved);
        assertFalse(cameraView.isCapturingPanorama());
        assertEquals(View.GONE, guide().getVisibility());
    }

    @Test
    public void aSweepWithoutAFile_isFiledInTheGallery() throws Exception {
        addRotationSensor();
        openCamera2();
        assertTrue(cameraView.startPanorama(CameraView.PanoramaMode.PANORAMA, recorder));
        ImageReader reader = awaitReader();
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!hasPainted() && System.currentTimeMillis() < deadline) {
            holdStill();
            ShadowInjectableImageReader.offerImage(reader, FakeImage.yuv420(64, 48, 0, 2));
            Thread.sleep(50);
            idle();
        }
        assertTrue("no frame was painted", hasPainted());
        cameraView.stopPanorama();
        awaitResult();
        assertNotNull("expected the sweep in the gallery", recorder.filed);
        assertNull("a filed sweep is not also reported as a file", recorder.saved);
    }

    @Test
    public void closingTheCamera_savesTheSweep() throws Exception {
        addRotationSensor();
        openCamera2();
        assertTrue(cameraView.startPanorama(file, CameraView.PanoramaMode.PANORAMA, recorder));
        cameraView.close();
        idle();
        awaitResult();
        // Nothing reached it before the camera closed, so there was nothing to save.
        assertTrue(recorder.failed);
        assertFalse(cameraView.isCapturingPanorama());
    }

    @Test
    public void legacyFrames_reachTheSweep() throws Exception {
        Camera.CameraInfo info = new Camera.CameraInfo();
        info.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
        info.orientation = 90;
        ShadowCamera.addCameraInfo(0, info);
        CameraView.setDefaultBackend(CameraView.Backend.LEGACY);
        addRotationSensor();
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.open();
        idle();
        assertTrue(cameraView.isOpen());
        assertEquals(CameraView.Backend.LEGACY, cameraView.getBackend());
        Camera camera = (Camera) field(field(cameraView, CameraView.class, "mCameraModule"),
                com.xlythe.view.camera.legacy.LegacyCameraModule.class, "mCamera");
        Camera.Parameters parameters = camera.getParameters();
        parameters.set("horizontal-view-angle", "60");
        parameters.set("vertical-view-angle", "46.8");
        camera.setParameters(parameters);

        assertTrue(cameraView.startPanorama(file, CameraView.PanoramaMode.PANORAMA, recorder));
        Camera.Size size = camera.getParameters().getPreviewSize();
        ShadowCamera shadowCamera = Shadow.extract(camera);
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!hasPainted() && System.currentTimeMillis() < deadline) {
            holdStill();
            shadowCamera.invokePreviewCallback(new byte[size.width * size.height * 3 / 2]);
            Thread.sleep(50);
            idle();
        }
        assertTrue("no frame was painted", hasPainted());
        cameraView.stopPanorama();
        awaitResult();
        assertEquals(file, recorder.saved);
    }

    private void openCamera2() {
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        cameraView.open();
        idle();
        assertTrue(cameraView.isOpen());
    }

    /** Reports the phone held upright, facing north, now and a little before. */
    private void holdStill() {
        ShadowSensorManager sensors = shadowSensors();
        if (sensors.getListeners().isEmpty()) {
            return;
        }
        // Upright facing north: device x east, y up, z south.
        float[] vector = {(float) Math.sqrt(0.5), 0f, 0f, (float) Math.sqrt(0.5)};
        long now = SystemClock.elapsedRealtimeNanos();
        for (long ago : new long[] {60_000_000L, 20_000_000L, 0L}) {
            sensors.sendSensorEventToListeners(SensorEventBuilder.newBuilder()
                    .setSensor(sensors.getSensorList(Sensor.TYPE_GAME_ROTATION_VECTOR).get(0))
                    .setTimestamp(now - ago).setValues(vector).build());
        }
    }

    /** Whether the guide has anything to show, which it has once a frame is in. */
    private boolean hasPainted() throws Exception {
        Object session = field(cameraView, CameraView.class, "mPanoramaSession");
        if (session == null) {
            return false;
        }
        return ((com.xlythe.view.camera.pano.PanoramaSession) session).getGuideState() != null;
    }

    private ImageReader awaitReader() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        ImageReader reader = null;
        while (System.currentTimeMillis() < deadline) {
            idle();
            reader = ShadowInjectableImageReader.latestReader();
            if (reader != null && reader.getImageFormat() == android.graphics.ImageFormat.YUV_420_888) {
                return reader;
            }
            Thread.sleep(20);
        }
        assertNotNull("the sweep never got a reader", reader);
        return reader;
    }

    private void awaitResult() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (recorder.saved == null && recorder.filed == null && !recorder.failed
                && System.currentTimeMillis() < deadline) {
            idle();
            Thread.sleep(20);
        }
        idle();
        assertTrue("the sweep never reported back",
                recorder.saved != null || recorder.filed != null || recorder.failed);
    }

    private PanoramaGuideView guide() {
        for (int i = 0; i < cameraView.getChildCount(); i++) {
            if (cameraView.getChildAt(i) instanceof PanoramaGuideView) {
                return (PanoramaGuideView) cameraView.getChildAt(i);
            }
        }
        throw new AssertionError("CameraView has no guide");
    }

    private Sensor addRotationSensor() {
        Sensor sensor = ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR);
        shadowSensors().addSensor(sensor);
        return sensor;
    }

    private ShadowSensorManager shadowSensors() {
        return Shadows.shadowOf(
                (SensorManager) context.getSystemService(Context.SENSOR_SERVICE));
    }

    private static Object field(Object owner, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    /** Remembers what the sweep said. */
    private static final class Recorder implements CameraView.PanoramaListener {
        @Nullable volatile File saved;
        @Nullable volatile Uri filed;
        volatile boolean failed;
        volatile float progress;

        @Override
        public void onPanoramaProgress(float value) {
            progress = value;
        }

        @Override
        public void onPanoramaCaptured(@NonNull File file) {
            saved = file;
        }

        @Override
        public void onPanoramaSaved(@NonNull Uri uri) {
            filed = uri;
        }

        @Override
        public void onPanoramaFailed() {
            failed = true;
        }
    }
}
