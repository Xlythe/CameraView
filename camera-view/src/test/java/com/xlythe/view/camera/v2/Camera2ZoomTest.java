package com.xlythe.view.camera.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;
import android.util.Size;

import com.xlythe.view.camera.CameraView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.StreamConfigurationMapBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Zoom on the Camera2 backend: the ratio a session carries, the range it is held to, and the
 * presets worked out from the lenses.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 37})
public class Camera2ZoomTest {
    /** JPEG's internal HAL format, as {@link FakeCamera2} explains. */
    private static final int HAL_PIXEL_FORMAT_BLOB = 0x21;

    private final Context context = RuntimeEnvironment.getApplication();
    private FakeCamera2.TestCameraView cameraView;
    private Camera2Module module;

    @After
    public void tearDown() {
        if (module == null) return;
        module.close();
        FakeCamera2.idleMainLooper();
        cameraView.release();
    }

    private void openWith(boolean logicalBackCamera) throws Exception {
        if (logicalBackCamera) {
            FakeCamera2.addLogicalBackCamera(context);
        } else {
            FakeCamera2.addBackAndFrontCameras(context);
        }
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);
        module.open();
        FakeCamera2.idleMainLooper();
    }

    /**
     * Below API 30, and on a camera without the zoom ratio control, the ratio is how much of the
     * sensor is cropped away, up to the camera's maximum digital zoom.
     */
    @Test
    public void aCroppingCameraCarriesTheRatioAsACrop() throws Exception {
        openWith(false /* logicalBackCamera */);
        assertEquals(1f, module.getMinZoomRatio(), 0f);
        assertEquals("the fake camera's maximum digital zoom", 4f, module.getMaxZoomRatio(), 0f);

        module.setZoomRatio(2.5f);

        assertEquals(2.5f, module.getZoomRatio(), 0f);
        Rect activeArray = new Rect(0, 0, 4000, 3000);
        assertEquals(Camera2Module.calculateCropRegion(activeArray, 2.5f),
                activeSession().getCropRegion());
        assertNull(activeSession().getZoomRatio());

        module.setZoomRatio(40f);
        assertEquals("held to the top of the range", 4f, module.getZoomRatio(), 0f);
        module.setZoomRatio(0.1f);
        assertEquals("and to the bottom", 1f, module.getZoomRatio(), 0f);
        assertEquals(activeArray, activeSession().getCropRegion());
    }

    /**
     * A camera with the zoom ratio control is handed the ratio itself, which lets it switch
     * lenses as the ratio crosses from one to the next.
     */
    @Test
    public void aCameraWithTheZoomRatioControlIsHandedTheRatio() throws Exception {
        openWith(true /* logicalBackCamera */);
        if (Build.VERSION.SDK_INT < 30) {
            // The control does not exist yet, so the same camera falls back to cropping.
            assertEquals(1f, module.getMinZoomRatio(), 0f);
            assertEquals(8f, module.getMaxZoomRatio(), 0f);
            return;
        }
        assertEquals(0.6f, module.getMinZoomRatio(), 0f);
        assertEquals(10f, module.getMaxZoomRatio(), 0f);

        module.setZoomRatio(0.6f);

        assertEquals(0.6f, module.getZoomRatio(), 0f);
        assertEquals(Float.valueOf(0.6f), activeSession().getZoomRatio());
        assertNull("the ratio, rather than a crop on top of it", activeSession().getCropRegion());
    }

    /** A zoom asked for before the camera opens is the one its first session starts at. */
    @Test
    public void aZoomSetWhileClosedIsAppliedOnOpening() throws Exception {
        FakeCamera2.addBackAndFrontCameras(context);
        cameraView = FakeCamera2.laidOutCameraView(context, 1080, 1920);
        module = new Camera2Module(cameraView);

        module.setZoomRatio(3f);
        module.open();
        FakeCamera2.idleMainLooper();

        assertEquals(3f, module.getZoomRatio(), 0f);
        assertEquals(Camera2Module.calculateCropRegion(new Rect(0, 0, 4000, 3000), 3f),
                activeSession().getCropRegion());
    }

    /** A lens that cannot reach the zoom holds it to the end of its own range. */
    @Test
    public void aLensSwitchHoldsTheZoomToTheNewLens() throws Exception {
        openWith(true /* logicalBackCamera */);
        module.setZoomRatio(module.getMaxZoomRatio());

        module.setLensFacing(CameraView.LensFacing.FRONT);
        FakeCamera2.idleMainLooper();

        assertEquals("the front camera crops to 4x at most", 4f, module.getZoomRatio(), 0f);
        assertEquals(Camera2Module.calculateCropRegion(new Rect(0, 0, 4000, 3000), 4f),
                activeSession().getCropRegion());
    }

    /** The S25 Ultra's lenses, against its own logical camera, give its stops. */
    @Test
    public void presetsComeFromThePhysicalLenses() {
        CameraCharacteristics logical = camera(6.3f, 9.792f, new Size(4080, 3060), null);

        float[] presets = Camera2Module.zoomPresets(logical, FakeCamera2.s25Lenses(), 0.6f, 10f);

        assertArrayEquals(new float[] {0.6f, 1f, 2.741f, 5.061f}, presets, 0.001f);
    }

    /** A single lens has 1x, and the wide end of the range if it has one. */
    @Test
    public void aSingleLensHasOneStop() {
        CameraCharacteristics camera = camera(4.3f, 5.76f, new Size(4000, 3000), null);

        assertArrayEquals(new float[] {1f},
                Camera2Module.zoomPresets(camera, Collections.emptyList(), 1f, 8f), 0f);
    }

    /** 2x is a stop where the main sensor has twice the pixels of the largest picture. */
    @Test
    public void twoTimesIsAStopWhereTheSensorCanCropToIt() {
        CameraCharacteristics binned = camera(6.3f, 9.792f, new Size(8160, 6120), null);
        assertArrayEquals(new float[] {1f, 2f},
                Camera2Module.zoomPresets(binned, Collections.emptyList(), 1f, 8f), 0f);

        // A sensor that publishes its full readout separately, as one that bins does from API 31.
        CameraCharacteristics fullReadout =
                camera(6.3f, 9.792f, new Size(4080, 3060), new Size(16320, 12240));
        float[] expected = Build.VERSION.SDK_INT >= 31 ? new float[] {1f, 2f} : new float[] {1f};
        assertArrayEquals(expected,
                Camera2Module.zoomPresets(fullReadout, Collections.emptyList(), 1f, 8f), 0f);
    }

    /** A lens that does not publish its optics costs its own stop and nothing else. */
    @Test
    public void aLensWithoutOpticsIsLeftOut() {
        CameraCharacteristics logical = camera(6.3f, 9.792f, new Size(4080, 3060), null);
        List<CameraCharacteristics> lenses = new ArrayList<>(FakeCamera2.s25Lenses());
        lenses.add(ShadowCameraCharacteristics.newCameraCharacteristics());

        assertArrayEquals(new float[] {0.6f, 1f, 2.741f, 5.061f},
                Camera2Module.zoomPresets(logical, lenses, 0.6f, 10f), 0.001f);
    }

    /**
     * The backend finds the lenses behind the logical camera itself.
     *
     * <p>API 29 only: that is where getPhysicalCameraIds reads the camera's characteristics the
     * way Robolectric's shadow can answer, while later versions read native metadata the shadow
     * never fills in. Below API 30 the range also starts at 1x, so the ultra-wide joins it.</p>
     */
    @Test
    @Config(sdk = 29)
    public void theBackendFindsThePhysicalLenses() throws Exception {
        openWith(true /* logicalBackCamera */);

        assertArrayEquals(new float[] {1f, 2.741f, 5.061f}, module.getZoomPresets(), 0.001f);
    }

    /** Builds a camera's characteristics, with the JPEG sizes a typical 12MP camera offers. */
    private static CameraCharacteristics camera(float focalLength, float sensorWidth,
            Size pixelArray, Size fullReadout) {
        CameraCharacteristics characteristics = FakeCamera2.lens(focalLength, sensorWidth);
        ShadowCameraCharacteristics shadow = Shadow.extract(characteristics);
        shadow.set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, pixelArray);
        if (fullReadout != null && Build.VERSION.SDK_INT >= 31) {
            shadow.set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION,
                    fullReadout);
        }
        StreamConfigurationMapBuilder map = StreamConfigurationMapBuilder.newBuilder();
        for (Size size : new Size[] {new Size(4080, 3060), new Size(1920, 1080)}) {
            map.addOutputSize(HAL_PIXEL_FORMAT_BLOB, size);
            map.addOutputSize(ImageFormat.YUV_420_888, size);
        }
        shadow.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, map.build());
        return characteristics;
    }

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
